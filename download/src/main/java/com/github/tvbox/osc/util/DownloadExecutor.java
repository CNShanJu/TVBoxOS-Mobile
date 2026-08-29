package com.github.tvbox.osc.util;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.DownloadLog;
import com.github.tvbox.osc.download.DownloadSubType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载执行单元（BaseDownloadTask 的框架侧执行器）：直链下载与 HLS(m3u8) 分段下载/校验/合并。
 * 5.1 从 DownloadManager 按职责拆分，行为零变化；后续阶段将收敛为任务对象（4.6/4.7）。
 */
public class DownloadExecutor {

    private final DownloadManager dm;

    DownloadExecutor(DownloadManager dm) {
        this.dm = dm;
    }

    void processTask(DownloadTask t) throws IOException {
        if (t.url != null && t.url.toLowerCase().contains(".m3u8")) {
            downloadHls(t);
        } else {
            downloadDirect(t);
        }
    }

    // ------------------------------------------------------------------
    // 直链下载(断点续传)
    // ------------------------------------------------------------------

    /** 直链下载算法入口（4.6 任务对象化: 由 NormalFileDownloadTask.doRun 委托） */
    public void downloadDirect(DownloadTask t) throws IOException {
        Map<String, String> headers = baseHeaders(t);
        if (t.downloadedBytes > 0) {
            headers.put("Range", "bytes=" + t.downloadedBytes + "-");
        }
        Response resp = getDownloadResponse(t.url, headers);
        dm.activeResponses.put(t.id, resp);
        try {
            // 内容级 HLS 识别:代理/伪装 URL 不含 .m3u8,但实际返回的是 m3u8 播放列表
            if (isM3u8Response(resp)) {
                Log.i("TVBox-Download", "内容识别为 m3u8,转 HLS 下载: " + t.fileName);
                resp.close();
                dm.activeResponses.remove(t.id);
                if (t.downloadedBytes > 0) {
                    t.downloadedBytes = 0;
                    FileCleaner.deleteQuietly(new File(t.partPath));
                }
                downloadHls(t);
                return;
            }
            int code = resp.code();
            if (code == 200 && t.downloadedBytes > 0) {
                // 服务器不支持断点,从头开始
                t.downloadedBytes = 0;
                FileCleaner.deleteQuietly(new File(t.partPath));
            } else if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            // 内容校验:返回的是 HTML 网页/防盗链页而非视频,直接判失败,不保存垃圾文件
            if (isHtmlResponse(resp)) {
                throw new IOException("响应不是视频内容(可能为网页或防盗链页)");
            }
            if (t.totalBytes <= 0) {
                String cl = resp.header("Content-Length");
                if (cl != null) {
                    t.totalBytes = t.downloadedBytes + Long.parseLong(cl);
                }
            }
            // 响应头识别真实扩展名(代理/无后缀 URL 会隐藏格式):首次下载时在写 .part 前修正文件名
            if (t.downloadedBytes == 0) {
                String realExt = detectExtensionFromResponse(resp);
                if (realExt != null && !t.fileName.endsWith(realExt)) {
                    int dot = t.savePath.lastIndexOf('.');
                    String newPath = dot >= 0 ? t.savePath.substring(0, dot) + realExt : t.savePath + realExt;
                    // 改名查重:新路径若已有任务/文件则放弃改名,避免"同集两个任务"或覆盖已下载文件
                    boolean conflict = new File(newPath).exists();
                    if (!conflict) {
                        synchronized (dm.tasks) {
                            for (DownloadTask tt : dm.tasks) {
                                if (tt != t && tt.savePath != null && tt.savePath.equals(newPath)) {
                                    conflict = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!conflict) {
                        t.savePath = newPath;
                        t.partPath = t.savePath + ".part";
                        t.fileName = new File(t.savePath).getName();
                        Log.i("TVBox-Download", "响应头识别扩展名修正: " + t.fileName);
                        dm.persist();
                    }
                }
            }
            File part = new File(t.partPath);
            File parent = part.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // 内容魔数校验(防盗链占位/错误页拦截): peek 响应体前 16 字节不消费流,
            // 扩展名视频但文件头完全不符 → 判失败, 杜绝 3KB 之类的假"完成"文件
            if (t.downloadedBytes <= 0) {
                try {
                    okio.BufferedSource src = resp.body().source();
                    src.request(16);
                    okio.Buffer pb = src.getBuffer().clone();
                    int hn = (int) Math.min(16, pb.size());
                    byte[] head = new byte[hn];
                    if (hn > 0) pb.readFully(head);
                    if (!isPlausibleVideo(head, t.fileName)) {
                        throw new IOException("响应内容与视频格式不符(可能为防盗链占位页或错误响应)");
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Throwable ignored) {
                }
            }
            OutputStream os = new FileOutputStream(part, t.downloadedBytes > 0);
            InputStream is = resp.body().byteStream();
            byte[] buf = new byte[DownloadManager.BUFFER];
            int n;
            long lastPersist = 0;
            long lastSpeedTime = System.currentTimeMillis();
            long lastSpeedBytes = t.downloadedBytes;
            while ((n = is.read(buf)) > 0) {
                if (isInterrupted(t)) {
                    os.flush();
                    os.close();
                    t.speed = 0;
                    dm.persist();
                    dm.notifyChanged();
                    return;
                }
                os.write(buf, 0, n);
                t.downloadedBytes += n;
                throttle(t, n); // 5.4 增强: 每任务限速
                long now = System.currentTimeMillis();
                if (now - lastPersist > 800) {
                    // 实时网速:按时间窗口内的字节增量计算
                    long delta = now - lastSpeedTime;
                    if (delta > 0) {
                        t.speed = (long) ((t.downloadedBytes - lastSpeedBytes) * 1000.0 / delta);
                    }
                    lastSpeedTime = now;
                    lastSpeedBytes = t.downloadedBytes;
                    lastPersist = now;
                    dm.persist();
                    dm.notifyChanged();
                }
            }
            os.flush();
            os.close();
            t.speed = 0;
            if (isInterrupted(t)) {
                dm.persist();
                dm.notifyChanged();
                return;
            }
            // 完整性校验:服务器声明了 Content-Length 但实际字节不足 → 提前断开,
            // 判失败(保留 .part 可重试),绝不产出 3KB 之类的残缺"完成"文件
            if (t.totalBytes > 0 && t.downloadedBytes < t.totalBytes) {
                throw new IOException("下载不完整: 期望 " + t.totalBytes + " B,实际 " + t.downloadedBytes
                        + " B,服务器提前断开");
            }
            finishDirect(t);
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    private void finishDirect(DownloadTask t) throws IOException {
        File part = new File(t.partPath);
        File finalFile = new File(t.savePath);
        if (finalFile.getParentFile() != null && !finalFile.getParentFile().exists()) {
            finalFile.getParentFile().mkdirs();
        }
        if (finalFile.exists()) finalFile.delete();
        if (!part.renameTo(finalFile)) {
            FileCleaner.copyFile(part, finalFile);
            FileCleaner.deleteQuietly(part);
        }
        t.state = DownloadTask.STATE_COMPLETED;
        t.downloadedBytes = t.totalBytes;
        DownloadLog.LOG.success(DownloadSubType.SAVE, "下载完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        dm.archive.add(t); // 5.3: 完成写已下载档案(长期,先于清理)
        com.github.tvbox.osc.download.DownloadNotifier.notifyCompleted(t); // 可选增强: 完成通知
        dm.persist();
        dm.notifyChanged();
    }

    // ------------------------------------------------------------------
    // HLS(m3u8)分段下载 + 合并
    // ------------------------------------------------------------------

    /** HLS 分段下载算法入口（4.6 任务对象化: 由 M3u8DownloadTask.doRun 委托） */
    public void downloadHls(DownloadTask t) throws IOException {
        String playlistUrl = t.url;
        String playlist = fetchPlaylist(playlistUrl, t);
        // fetchPlaylist 遇到主播放列表时会切换到具体变体(t.url 已更新),分片需按实际播放列表解析
        List<String> segments = parseSegments(t.url, playlist);
        if (segments.isEmpty()) {
            throw new IOException("m3u8 无有效分片");
        }
        Log.i("TVBox-Download", "播放列表 " + segments.size() + " 片, 播放列表url=" + t.url
                + " 首片=" + segments.get(0));
        t.totalSegments = segments.size();
        // 续传起点以磁盘实况为准(不信任 TXT/内存计数):用户可能删过部分分片文件,
        // 若仍用 t.doneSegments 会跳过缺失分片直接合并导致失败。
        File tmpDir = segmentsDirOf(t);
        if (!tmpDir.exists()) tmpDir.mkdirs();
        ensureNoMedia(tmpDir); // Bug5: 碎片目录放 .nomedia,防止 TS 碎片进系统相册
        int existing = countExistingSegments(tmpDir, segments.size());
        if (existing < t.doneSegments) {
            Log.i("TVBox-Download", "分片缺失(磁盘" + existing + "/" + segments.size() + ",记录" + t.doneSegments
                    + "),从缺失处续传: " + t.fileName);
        }
        t.doneSegments = existing;
        t.segmentBytes = 0;
        // 下载前记录分段信息 TXT:来源/剧名/集数/碎片数/解析地址/分片列表/已完成(断点续传同步进度)
        writeSegmentsInfo(t, tmpDir, segments, t.doneSegments);

        long speedWindowStart = System.currentTimeMillis();
        long speedWindowBytes = 0;
        // 只下载缺失的分片(跳过已存在且非空的分片),支持非连续缺失续传(如第3、7片被删)
        for (int i = 0; i < segments.size(); i++) {
            if (isInterrupted(t)) {
                t.speed = 0;
                dm.persist();
                dm.notifyChanged();
                return;
            }
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            if (segFile.exists() && segFile.length() > 0) {
                if (t.doneSegments <= i) t.doneSegments = i + 1;
                continue; // 已存在,跳过
            }
            long segDone = 0; // 缺失分片从头下(无残留字节)
            downloadSegment(segments.get(i), segFile, segDone, t);
            if (t.doneSegments <= i) t.doneSegments = i + 1;
            t.segmentBytes = 0;
            // 注意: 不逐片写 segments.txt(每片全扫太浪费)——TXT 在校验/补片阶段统一写
            // 实时网速:按已完成分片的字节增量估算
            speedWindowBytes += segFile.length();
            long now = System.currentTimeMillis();
            if (now - speedWindowStart >= 500) {
                long delta = now - speedWindowStart;
                if (delta > 0) {
                    t.speed = (long) (speedWindowBytes * 1000.0 / delta);
                }
                speedWindowStart = now;
                speedWindowBytes = 0;
            }
            dm.persist();
            dm.notifyChanged();
        }
        t.speed = 0;

        // 碎片下载完,进入"文件校验中"。缺失清单驱动(4.7③): 首次全盘比对生成缺失清单(仅此一次全扫),
        // 每轮只补缺失清单项 + 只复检清单项(不反复全盘扫), 3 轮上限。
        t.message = DownloadManager.MSG_VERIFYING;
        dm.persist();
        dm.notifyChanged();
        // 首次全盘比对, 生成缺失清单(仅此一次 O(total))
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            if (!segFile.exists() || segFile.length() <= 0) missing.add(i);
        }
        // 校验/开始 日志: 清单N片, 缺失M项:[序号](缺失清单全量落日志, 事后可核对)
        DownloadLog.LOG.info(DownloadSubType.VERIFY, "校验开始: 清单 " + segments.size() + " 片, 缺失 " + missing.size()
                + " 项" + missingList(missing), DownloadLog.extras(t.episodeId));
        int repair = 0;
        while (!missing.isEmpty()) {
            if (repair >= DownloadManager.MAX_SEGMENT_REPAIR) {
                // 补片 FAILED: 完整缺失清单落日志(不截断), 供事后核对; 保留碎片现场
                DownloadLog.LOG.fail(DownloadSubType.REPAIR, "补片 FAILED: 第 " + DownloadManager.MAX_SEGMENT_REPAIR
                        + " 轮仍缺失 " + missing.size() + " 片:" + missingList(missing), DownloadLog.extras(t.episodeId));
                throw new IOException("碎片校验不一致,自动补下" + DownloadManager.MAX_SEGMENT_REPAIR + "轮后仍缺失(缺 " + missing.size() + " 片,如第"
                        + missing.get(0) + "片)");
            }
            repair++;
            Log.i("TVBox-Download", "碎片校验缺失 " + missing.size() + " 片,第" + repair + "/" + DownloadManager.MAX_SEGMENT_REPAIR
                    + "轮补下: " + t.fileName + " 缺失首片=" + missing.get(0));
            // 每轮补片开始: 目标[序号], 轮次 k/3
            DownloadLog.LOG.info(DownloadSubType.REPAIR, "补片第 " + repair + "/" + DownloadManager.MAX_SEGMENT_REPAIR
                    + " 轮开始: 目标 " + missing.size() + " 片" + missingList(missing), DownloadLog.extras(t.episodeId));
            // 只补缺失清单项; 单片失败不整体抛(留待下一轮, 3 轮内仍缺才失败)
            List<Integer> stillMissing = new ArrayList<>();
            int attempt = 0, okCount = 0, failCount = 0;
            for (int idx : missing) {
                File segFile = new File(tmpDir, String.format("%05d.ts", idx));
                if (!segFile.exists() || segFile.length() <= 0) {
                    attempt++;
                    try {
                        downloadSegment(segments.get(idx), segFile, 0, t);
                        // 单项补下成功: 片i 成功 bytes
                        DownloadLog.LOG.success(DownloadSubType.REPAIR, "补片/片 " + idx + " 成功 " + segFile.length() + "B",
                                DownloadLog.extras(t.episodeId));
                        okCount++;
                    } catch (IOException e) {
                        // 单项补下失败: 原因+HTTP码(留待下轮)
                        failCount++;
                        Log.i("TVBox-Download", "补片失败(留待下轮): 片" + idx + " " + e.getMessage());
                        DownloadLog.LOG.fail(DownloadSubType.REPAIR, "补片/片 " + idx + " 失败 " + e.getMessage(),
                                DownloadLog.extras(t.episodeId));
                    }
                }
                // 只复检缺失清单项(不扫全目录)
                if (segFile.exists() && segFile.length() > 0) {
                    if (t.doneSegments <= idx) t.doneSegments = idx + 1;
                } else {
                    stillMissing.add(idx);
                }
            }
            missing = stillMissing;
            writeSegmentsInfo(t, tmpDir, segments, t.doneSegments); // 每轮结束写一次 TXT(非每片)
            // 每轮补片结束: 补K, 成功K1, 失败K2, 剩余J:[序号]
            DownloadLog.LOG.info(DownloadSubType.REPAIR, "补片第 " + repair + "/" + DownloadManager.MAX_SEGMENT_REPAIR
                    + " 轮结束: 补" + attempt + " 成功" + okCount + " 失败" + failCount + " 剩余" + missing.size()
                    + " 片" + missingList(missing), DownloadLog.extras(t.episodeId));
        }
        t.doneSegments = segments.size(); // 全部就绪,进度=已下载分片数
        t.segmentBytes = 0;
        writeSegmentsInfo(t, tmpDir, segments, t.doneSegments);

        // 校验通过,进入"文件合并"
        t.message = DownloadManager.MSG_MERGING;
        dm.persist();
        dm.notifyChanged();
        // 合并尝试计数(第几次): >1 即重试,先记"合并/重试"(含上次失败原因还原上下文)
        t.mergeCount++;
        if (t.mergeCount > 1) {
            DownloadLog.LOG.warn(DownloadSubType.MERGE, "合并/重试 第 " + t.mergeCount + " 次开始, 上次失败原因: "
                    + (t.mergeFailReason == null || t.mergeFailReason.isEmpty() ? "未知" : t.mergeFailReason),
                    DownloadLog.extras(t.episodeId));
        }
        long mergeStart = System.currentTimeMillis();
        // 合并分片 -> mp4(双阶段原子合并):
        // 1) 先合并到分段目录内的 merged.tmp(过程文件,与成果隔离,崩溃最多损坏它)
        // 2) 完整后 rename 到最终文件(rename 为原子操作,要么成功要么未发生,杜绝半成品最终文件)
        File finalFile = new File(t.savePath);
        if (finalFile.getParentFile() != null && !finalFile.getParentFile().exists()) {
            finalFile.getParentFile().mkdirs();
        }
        File mergeTmp = new File(tmpDir, "merged.tmp");
        try {
            // 合并前空间检查:合并需额外写入约一个最终文件大小的 merged.tmp(分片已占空间),
            // 不足则失败并提示,避免合并中空间耗尽损坏
            long mergeSize = 0;
            for (int i = 0; i < segments.size(); i++) {
                mergeSize += new File(tmpDir, String.format("%05d.ts", i)).length();
            }
            if (mergeSize > 0) {
                File dir = new File(t.savePath).getParentFile();
                if (dir != null && dir.exists()) {
                    android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
                    long free = stat.getAvailableBytes();
                    if (free - mergeSize < DownloadPolicy.MIN_FREE_SPACE) {
                        throw new IOException("磁盘空间不足,无法合并(完成后可用仅 "
                                + formatSize(Math.max(0, free - mergeSize)) + ",需清理约 "
                                + ((DownloadPolicy.MIN_FREE_SPACE - (free - mergeSize) + 1024 * 1024 - 1) / (1024 * 1024)) + "MB)");
                    }
                }
            }
            // 合并/开始 日志: 分片N, 缺失清单状态(此时补片循环已退出=已清空), 分片总size, 目标路径
            DownloadLog.LOG.info(DownloadSubType.MERGE, "合并开始 第 " + t.mergeCount + " 次: 分片 " + segments.size()
                            + ", 缺失清单=已清空, 分片总size=" + formatSize(mergeSize) + ", 目标 " + t.savePath,
                    DownloadLog.extras(t.episodeId));

            OutputStream out = new FileOutputStream(mergeTmp);
            try {
                for (int i = 0; i < segments.size(); i++) {
                    if (t.state == DownloadTask.STATE_CANCELLED) {
                        throw new IOException("cancelled"); // Bug2: 删除记录后合并立即中止,不落最终文件
                    }
                    File segFile = new File(tmpDir, String.format("%05d.ts", i));
                    FileCleaner.copyFile(segFile, out);
                }
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            // 原子替换:先删旧最终文件(若有),再 rename;rename 失败则复制兜底
            if (finalFile.exists()) finalFile.delete();
            if (!mergeTmp.renameTo(finalFile)) {
                FileCleaner.copyFile(mergeTmp, finalFile);
                FileCleaner.deleteQuietly(mergeTmp);
            }
            // 合并/完成 日志: 最终size, 耗时ms
            DownloadLog.LOG.success(DownloadSubType.MERGE, "合并完成: 最终 " + formatSize(finalFile.length()) + ", 耗时 "
                    + (System.currentTimeMillis() - mergeStart) + "ms", DownloadLog.extras(t.episodeId));
            t.mergeFailReason = ""; // 合并成功,清空失败原因
        } catch (IOException e) {
            // 合并/失败 日志: 第k次, 原因(IO/缺片/校验不符), 碎片保留(不删 .ts, 供重试)
            t.mergeFailReason = e.getMessage() == null ? e.toString() : e.getMessage();
            DownloadLog.LOG.fail(DownloadSubType.MERGE, "合并失败 第 " + t.mergeCount + " 次, 原因: " + t.mergeFailReason
                    + ", 碎片保留", DownloadLog.extras(t.episodeId));
            throw e;
        }
        // Bug3: 合并产物是 TS 字节流(rename 成 .mp4 只是换后缀,时间戳不连续 -> 相册显示 1 秒)。
        // 重封装为标准 MP4(MediaExtractor demux + MediaMuxer mux,时长/缩略图正确);
        // 失败回退 .ts 后缀(不伪装 mp4),日志记录回退原因。
        t.message = DownloadManager.MSG_REMUX;
        dm.persist();
        dm.notifyChanged();
        if (remuxTsToMp4(finalFile)) {
            DownloadLog.LOG.success(DownloadSubType.REMUX, "重封装完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        } else {
            if (t.savePath.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                String tsPath = t.savePath.substring(0, t.savePath.length() - 4) + ".ts";
                File tsFile = new File(tsPath);
                if (finalFile.renameTo(tsFile)) {
                    t.savePath = tsPath;
                    t.fileName = tsFile.getName();
                }
            }
            DownloadLog.LOG.warn(DownloadSubType.REMUX, "重封装失败,回退 .ts 后缀: " + t.fileName,
                    DownloadLog.extras(t.episodeId));
        }
        // 顺序铁律: 落盘 → 写档案 → 清理 → COMPLETED。
        // 清理(删碎片)是危险操作, 只有档案写成功后才允许; 档案写失败则保留碎片现场可重试。
        t.message = "";
        t.state = DownloadTask.STATE_COMPLETED;
        DownloadLog.LOG.success(DownloadSubType.SAVE, "下载完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        dm.archive.add(t); // 先写档案(长期)
        // 档案写成功后才清理分片目录(父级 tmp 保留)
        deleteSegmentsDir(t);
        t.tmpDir = null;
        com.github.tvbox.osc.download.DownloadNotifier.notifyCompleted(t); // 可选增强: 完成通知
        dm.persist();
        dm.notifyChanged();
    }

    /**
     * 在分段目录记录/更新分段信息 TXT:来源/剧名/集数/碎片数/解析地址/分片列表/已完成/分片状态。
     * 已完成 = 已下载完的连续分片数(断点续传起点);分片状态 = 逐片 1/0 标记(1=完成,0=缺失),
     * 支持非连续缺失(如用户删了第3、7片)时精确识别缺失分片。
     */
    private void writeSegmentsInfo(DownloadTask t, File tmpDir, List<String> segments, int doneCount) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("来源=").append(t.sourceName == null ? "" : t.sourceName).append('\n');
            sb.append("剧名=").append(t.vodName == null ? "" : t.vodName).append('\n');
            sb.append("集数=").append(t.episodeName == null ? "" : t.episodeName).append('\n');
            sb.append("碎片数=").append(segments.size()).append('\n');
            sb.append("解析地址=").append(t.url == null ? "" : t.url).append('\n');
            sb.append("分片列表=");
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format("%05d.ts", i));
            }
            sb.append('\n');
            sb.append("已完成=").append(Math.max(0, Math.min(doneCount, segments.size()))).append('\n');
            // 逐片状态:1=完成(存在且非空),0=缺失。全盘扫描磁盘实况,不依赖计数推断。
            sb.append("分片状态=");
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) sb.append(',');
                File segFile = new File(tmpDir, String.format("%05d.ts", i));
                sb.append(segFile.exists() && segFile.length() > 0 ? '1' : '0');
            }
            sb.append('\n');
            File f = new File(tmpDir, DownloadManager.SEGMENTS_INFO);
            java.io.FileWriter fw = new java.io.FileWriter(f);
            try {
                fw.write(sb.toString());
            } finally {
                fw.close();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 读取分段信息 TXT 记录的"已完成"分片数;TXT 缺失/损坏返回 0 */
    int readSegmentsInfo(File tmpDir) {
        try {
            File info = new File(tmpDir, DownloadManager.SEGMENTS_INFO);
            if (!info.exists()) return 0;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(info));
            String line;
            int done = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("已完成=")) {
                    try {
                        done = Integer.parseInt(line.substring(4).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            br.close();
            return Math.max(0, done);
        } catch (Throwable th) {
            return 0;
        }
    }

    /**
     * 读取分段信息 TXT 的"分片状态"(逐片 1/0),返回缺失分片索引列表。
     * 支持非连续缺失(如第3、7片被删);TXT 缺失/无状态行时返回 null(调用方回退全盘扫描)。
     */
    private List<Integer> readMissingSegments(File tmpDir, int total) {
        try {
            File info = new File(tmpDir, DownloadManager.SEGMENTS_INFO);
            if (!info.exists()) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(info));
            String line;
            String statusLine = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("分片状态=")) {
                    statusLine = line.substring("分片状态=".length());
                    break;
                }
            }
            br.close();
            if (statusLine == null || statusLine.isEmpty()) return null;
            String[] parts = statusLine.split(",");
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < parts.length && i < total; i++) {
                if (!"1".equals(parts[i].trim())) missing.add(i);
            }
            return missing;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 全盘扫描分段目录,统计"存在且非空"的分片数(续传/校验以磁盘实况为准,不信任TXT计数) */
    private int countExistingSegments(File tmpDir, int total) {
        int count = 0;
        for (int i = 0; i < total; i++) {
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            if (segFile.exists() && segFile.length() > 0) count++;
        }
        return count;
    }

    private void downloadSegment(String segUrl, File segFile, long segDone, DownloadTask t) throws IOException {
        Map<String, String> headers = baseHeaders(t);
        if (segDone > 0) {
            headers.put("Range", "bytes=" + segDone + "-");
        }
        Response resp = getDownloadResponse(segUrl, headers);
        dm.activeResponses.put(t.id, resp);
        try {
            int code = resp.code();
            if (code == 416) {
                // Range 超出文件末尾:该分段实际已完整(上次写入完成但进度未更新)。
                // 关闭本次响应,删除残片,不带 Range 从头整段重下,避免重试死循环
                Log.i("TVBox-Download", "分段416(Range超界),整段重下: " + segFile.getName());
                resp.close();
                dm.activeResponses.remove(t.id);
                FileCleaner.deleteQuietly(segFile);
                FileCleaner.deleteQuietly(new File(segFile.getAbsolutePath() + ".part"));
                segDone = 0;
                headers.remove("Range");
                resp = getDownloadResponse(segUrl, headers);
                dm.activeResponses.put(t.id, resp);
                code = resp.code();
            }
            if (code == 200 && segDone > 0) {
                segDone = 0;
                FileCleaner.deleteQuietly(segFile);
            } else if (code != 200 && code != 206) {
                Log.i("TVBox-Download", "分片 HTTP " + code + " url=" + segUrl);
                throw new IOException("segment HTTP " + code);
            }
            File parent = segFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // 分片内容魔数校验(整段重下场景): .ts 分片含 0x47 同步字节或 fMP4 以 ftyp 开头,
            // 防盗链错误响应(几KB 文本)不符 → 判失败(走补片/重试), 不产出假分片
            if (segDone <= 0) {
                try {
                    okio.BufferedSource src = resp.body().source();
                    src.request(8);
                    okio.Buffer pb = src.getBuffer().clone();
                    int hn = (int) Math.min(8, pb.size());
                    byte[] head = new byte[hn];
                    if (hn > 0) pb.readFully(head);
                    if (!containsByte(head, (byte) 0x47) && !containsAscii(head, "ftyp")) {
                        throw new IOException("分片内容非 TS/fMP4(可能防盗链错误响应)");
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Throwable ignored) {
                }
            }
            // Bug2: 分片先写 .part 再 rename 原子落盘——进程被杀不产生"残缺但非空"的 .ts,
            // 续传/校验只信任 rename 后的完整分片
            File partFile = new File(segFile.getAbsolutePath() + ".part");
            OutputStream os = new FileOutputStream(partFile, segDone > 0);
            InputStream is = resp.body().byteStream();
            byte[] buf = new byte[DownloadManager.BUFFER];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (isInterrupted(t)) {
                    os.flush();
                    os.close();
                    t.segmentBytes = segDone;
                    dm.persist();
                    return;
                }
                os.write(buf, 0, n);
                segDone += n;
                t.segmentBytes = segDone;
                throttle(t, n); // 5.4 增强: 每任务限速
            }
            os.flush();
            os.close();
            if (!partFile.renameTo(segFile)) {
                FileCleaner.copyFile(partFile, segFile);
                FileCleaner.deleteQuietly(partFile);
            }
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    private String fetchPlaylist(String url, DownloadTask t) throws IOException {
        Response resp = getDownloadResponse(url, baseHeaders(t));
        dm.activeResponses.put(t.id, resp);
        try {
            if (!resp.isSuccessful()) throw new IOException("m3u8 HTTP " + resp.code());
            String text = resp.body().string();
            // 主播放列表(多码率):取第一个变体
            if (text.contains("#EXT-X-STREAM-INF")) {
                String base = url.substring(0, url.lastIndexOf('/') + 1);
                for (String line : text.split("\n")) {
                    String l = line.trim();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    String variant = resolveUrl(url, base, l);
                    Response resp2 = getDownloadResponse(variant, baseHeaders(t));
                    dm.activeResponses.put(t.id, resp2);
                    try {
                        if (!resp2.isSuccessful()) throw new IOException("variant HTTP " + resp2.code());
                        t.url = variant;
                        return resp2.body().string();
                    } finally {
                        dm.activeResponses.remove(t.id);
                        resp2.close();
                    }
                }
                throw new IOException("主播放列表无变体");
            }
            return text;
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    private List<String> parseSegments(String playlistUrl, String playlist) {
        List<String> segs = new ArrayList<>();
        String base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1);
        for (String line : playlist.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            // 代理返回的 m3u8 可能被 HTML 包裹(如 <pre>...</pre>): 含标签的行不是分片, 跳过
            if (l.contains("<") || l.contains(">")) continue;
            segs.add(resolveUrl(playlistUrl, base, l));
        }
        return segs;
    }

    private String resolveUrl(String original, String base, String seg) {
        if (seg.startsWith("http://") || seg.startsWith("https://")) return seg;
        if (seg.startsWith("/")) {
            Uri uri = Uri.parse(original);
            return uri.getScheme() + "://" + uri.getHost() + seg;
        }
        return base + seg;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 请求头:默认 UA + 任务携带的解析请求头(UA/Referer 等,防盗链源分片/文件校验,必须带上) */
    Map<String, String> baseHeaders(DownloadTask t) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "okhttp/3.12.11");
        if (t != null && t.headers != null && !t.headers.isEmpty()) {
            for (Map.Entry<String, String> e : t.headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(e.getKey(), e.getValue());
                }
            }
        }
        return headers;
    }

    /** 任务的分段下载目录:优先任务自己的 tmpDir,否则按 文件目录/tmp/<任务id> 兜底(保证唯一,多任务不共用) */
    File segmentsDirOf(DownloadTask t) {
        if (t.tmpDir != null && !t.tmpDir.isEmpty()) {
            return new File(t.tmpDir);
        }
        File parent = t.savePath != null ? new File(t.savePath).getParentFile() : null;
        String id = t.id != null && !t.id.isEmpty() ? t.id : "x";
        return new File(parent, "tmp" + File.separator + id);
    }

    /**
     * 删除本任务的分段目录 tmp/<任务id>(只删本任务,不删父级 tmp,避免频繁新建/删除)
     */
    void deleteSegmentsDir(DownloadTask t) {
        FileCleaner.deleteRecursive(segmentsDirOf(t));
    }

    /** 用下载专用客户端(更长超时)发起同步请求,调用方负责关闭 Response */
    Response getDownloadResponse(String url, Map<String, String> headers) throws IOException {
        try {
            Request.Builder builder = new Request.Builder().url(HttpClient.normalizeUrl(url));
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            return dm.downloadClient.newCall(builder.build()).execute();
        } catch (IOException e) {
            throw e;
        } catch (Throwable th) {
            throw new IOException("request build failed: " + th.getMessage(), th);
        }
    }

    /** 判断响应是否为 m3u8 播放列表(按 Content-Type 或内容开头),不消费响应体;
        前 64 字节 trim 后匹配,防 BOM/空白/变体列表(EXT-X-)漏判导致 3KB 播放列表被当视频存盘 */
    private boolean isM3u8Response(Response resp) {
        try {
            String ct = resp.header("Content-Type");
            if (ct != null && (ct.contains("mpegurl") || ct.contains("mpeg-url") || ct.contains("apple"))) {
                return true;
            }
            okio.BufferedSource source = resp.body().source();
            source.request(64);
            okio.Buffer buf = source.getBuffer().clone();
            String head = buf.readUtf8(Math.min(64, buf.size())).trim();
            return head.startsWith("#EXTM3U") || head.contains("EXT-X-");
        } catch (Throwable th) {
            return false;
        }
    }

    /** 判断响应是否为 HTML/网页(防盗链页等),是则不应作为视频保存;不消费响应体 */
    private boolean isHtmlResponse(Response resp) {
        try {
            String ct = resp.header("Content-Type");
            if (ct != null) {
                String lct = ct.toLowerCase(Locale.ROOT);
                if (lct.contains("text/html")) return true;
            }
            okio.BufferedSource source = resp.body().source();
            source.request(32);
            okio.Buffer buf = source.getBuffer().clone();
            String head = buf.readUtf8(Math.min(32, buf.size())).toLowerCase(Locale.ROOT);
            return head.contains("<!doctype") || head.contains("<html") || head.contains("<script");
        } catch (Throwable th) {
            return false;
        }
    }

    /** 响应头识别真实文件扩展名:Content-Disposition 的 filename 最可靠,其次按 Content-Type 映射。
        识别不出或为音频/未知类型返回 null(保持 URL 判定的扩展名)。 */
    private String detectExtensionFromResponse(Response resp) {
        try {
            String cd = resp.header("Content-Disposition");
            if (cd != null) {
                Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").matcher(cd);
                if (m.find()) {
                    String fn = m.group(1).trim();
                    int dot = fn.lastIndexOf('.');
                    if (dot >= 0 && dot < fn.length() - 1) {
                        String e = fn.substring(dot).toLowerCase(Locale.ROOT);
                        if (e.length() <= 5 && e.matches("\\.[a-z0-9]+")) return e;
                    }
                }
            }
            String ct = resp.header("Content-Type");
            if (ct == null) return null;
            ct = ct.toLowerCase(Locale.ROOT);
            if (ct.contains("mpegurl") || ct.startsWith("audio/") || ct.contains("text/")) return null;
            if (ct.contains("matroska")) return ".mkv";
            if (ct.contains("webm")) return ".webm";
            if (ct.contains("quicktime")) return ".mov";
            if (ct.contains("mp2t") || ct.contains("mpegts") || ct.contains("mpeg-ts")) return ".ts";
            if (ct.contains("x-ms-wmv")) return ".wmv";
            if (ct.contains("3gpp")) return ".3gp";
            if (ct.contains("x-m4v")) return ".m4v";
            if (ct.contains("flv")) return ".flv";
            if (ct.contains("msvideo") || ct.contains("/avi")) return ".avi";
            if (ct.contains("mpeg")) return ".mpg";
            if (ct.contains("mp4") || ct.contains("mp4v")) return ".mp4";
        } catch (Throwable th) {
        }
        return null;
    }

    /**
     * 直链内容魔数校验:按扩展名检查响应体文件头, 防盗链占位页/错误响应(几KB文本或随机字节)
     * 与视频格式完全不符 → 返回 false 判失败; 非视频扩展名/无法判断一律放行(不误伤)。
     */
    private boolean isPlausibleVideo(byte[] head, String fileName) {
        if (head == null || head.length < 4 || fileName == null) return true;
        String fn = fileName.toLowerCase(Locale.ROOT);
        if (fn.endsWith(".mp4") || fn.endsWith(".m4v") || fn.endsWith(".3gp") || fn.endsWith(".mov")) {
            return containsAscii(head, "ftyp") || containsAscii(head, "moov") || containsAscii(head, "mdat")
                    || containsAscii(head, "free");
        }
        if (fn.endsWith(".mkv") || fn.endsWith(".webm")) {
            return (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        }
        if (fn.endsWith(".flv")) {
            return containsAscii(head, "FLV");
        }
        if (fn.endsWith(".ts") || fn.endsWith(".m2ts")) {
            // MPEG-TS 同步字节 0x47(可能在 0/188/376 偏移)
            return containsByte(head, (byte) 0x47);
        }
        return true;
    }

    private static boolean containsAscii(byte[] head, String s) {
        byte[] needle = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= head.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (head[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean containsByte(byte[] head, byte b) {
        for (byte v : head) {
            if (v == b) return true;
        }
        return false;
    }

    /**
     * 5.4 增强: 每任务限速（t.speedLimit 字节/秒, 0=不限速）。
     * 500ms 窗口滑动节流; 限速不会改变行为, 只是放慢写入。
     */
    private static void throttle(DownloadTask t, int written) {
        if (t.speedLimit <= 0) return;
        long windowBytes = written;
        long windowStart = System.currentTimeMillis();
        while (t.speedLimit > 0 && !isInterrupted(t)) {
            long now = System.currentTimeMillis();
            long elapsed = now - windowStart;
            if (elapsed < 500) return; // 窗口未满, 继续
            long expect = t.speedLimit * elapsed / 1000;
            if (windowBytes <= expect) return; // 未超速
            long over = windowBytes - expect;
            long delay = over * 1000 / Math.max(1, t.speedLimit);
            try {
                Thread.sleep(Math.min(delay, 2000));
            } catch (InterruptedException ignored) {
                return;
            }
            windowStart = now;
            windowBytes = 0;
        }
    }

    /** 任务被暂停(用户/调度/网络)或已取消(删除记录)时,下载循环应中止 */
    private static boolean isInterrupted(DownloadTask t) {
        return t.state == DownloadTask.STATE_PAUSED
                || t.state == DownloadTask.STATE_SYSTEM_PAUSED
                || t.state == DownloadTask.STATE_NETWORK_PAUSED
                || t.state == DownloadTask.STATE_CANCELLED;
    }

    /**
     * Bug3: 把 TS 拼接产物重封装为标准 MP4（MediaExtractor demux + MediaMuxer mux）。
     * Android MediaExtractor 支持 demux MPEG-TS，mux 出的 MP4 时长/缩略图正确（无需 ffmpeg）。
     *
     * @return true=重封装成功并已替换源文件;false=失败(调用方回退 .ts 后缀)
     */
    private static boolean remuxTsToMp4(File src) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(src.getAbsolutePath());
            int videoTrack = -1;
            int audioTrack = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i;
                    videoFormat = f;
                } else if (mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i;
                    audioFormat = f;
                }
            }
            if (videoTrack < 0 && audioTrack < 0) return false; // 无可用轨,无法重封装

            File outTmp = new File(src.getParentFile(), "remux_" + System.currentTimeMillis() + ".mp4");
            muxer = new MediaMuxer(outTmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideo = -1;
            int muxAudio = -1;
            if (videoTrack >= 0) {
                extractor.selectTrack(videoTrack);
                muxVideo = muxer.addTrack(videoFormat);
            }
            if (audioTrack >= 0) {
                extractor.selectTrack(audioTrack);
                muxAudio = muxer.addTrack(audioFormat);
            }
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;
                int size = extractor.readSampleData(buffer, 0);
                if (size <= 0) {
                    extractor.advance();
                    continue;
                }
                long pts = extractor.getSampleTime();
                int flags = extractor.getSampleFlags();
                buffer.position(0);
                buffer.limit(size);
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                info.flags = flags;
                if (track == videoTrack && muxVideo >= 0) {
                    muxer.writeSampleData(muxVideo, buffer, info);
                } else if (track == audioTrack && muxAudio >= 0) {
                    muxer.writeSampleData(muxAudio, buffer, info);
                }
                extractor.advance();
            }
            muxer.stop();
            muxer.release();
            muxer = null;
            extractor.release();
            extractor = null;
            // 用重封装产物替换原拼接文件
            if (!outTmp.renameTo(src)) {
                if (src.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    src.delete();
                }
                if (!outTmp.renameTo(src)) {
                    FileCleaner.copyFile(outTmp, src);
                    FileCleaner.deleteQuietly(outTmp);
                }
            }
            return true;
        } catch (Throwable th) {
            Log.i("TVBox-Download", "重封装失败: " + src.getName() + " -> " + th.getMessage());
            return false;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Bug5: 确保目录内写入 .nomedia(媒体扫描器忽略该目录,碎片不进相册) */
    private static void ensureNoMedia(File dir) {
        try {
            File nm = new File(dir, ".nomedia");
            if (!nm.exists()) {
                //noinspection ResultOfMethodCallIgnored
                nm.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 格式化大小(供磁盘空间提示) */
    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 缺失清单转可读串 "[1,3,7]"; 空清单返回 "[]" (日志全量落清单, 不截断) */
    private static String missingList(List<Integer> missing) {
        if (missing == null || missing.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(missing.get(i));
        }
        return sb.append(']').toString();
    }
}
