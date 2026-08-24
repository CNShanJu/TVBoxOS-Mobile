package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import com.github.catvod.crawler.PlayUrlResolver;
import com.github.catvod.crawler.SpiderApi;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.DownloadTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import okhttp3.Response;

/**
 * 任务调度器（Task-Scheduler）：队列 / 并发调度 / 启停 / 任务生命周期。
 * 5.1 从 DownloadManager 按职责拆分，行为零变化。
 */
public class DownloadScheduler {

    private final DownloadManager dm;
    private Thread worker;

    /** 网络状态监听:网络恢复后唤醒调度并自动续传因网络失败的任务 */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            Log.i("TVBox-Download", "网络恢复:自动续传因网络失败的任务");
            // Bug1: 仅WiFi开启且当前是蜂窝时,网络恢复也不续传(维持 NETWORK_PAUSED 语义)
            if (dm.policy.isWifiOnly() && DownloadPolicy.isMobileNetwork()) {
                return;
            }
            boolean changed = false;
            synchronized (dm.tasks) {
                for (DownloadTask t : dm.tasks) {
                    if (t.networkFailed && t.state == DownloadTask.STATE_FAILED) {
                        t.state = DownloadTask.STATE_WAITING;
                        t.networkFailed = false;
                        t.needReResolve = true; // 断网期间代理签名可能过期,继续前重新解析
                        t.message = "";
                        changed = true;
                    }
                }
            }
            if (changed) {
                dm.persist();
                dm.notifyChanged();
            }
            wakeWorker();
        }
    };

    DownloadScheduler(DownloadManager dm) {
        this.dm = dm;
    }

    /** 注册网络状态监听(断网/切网后网络恢复时自动续传) */
    void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) App.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    void startWorker() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workLoop();
            }
        }, "tvbox-download");
        worker.setDaemon(true);
        worker.start();
    }

    void wakeWorker() {
        synchronized (dm.lock) {
            dm.lock.notifyAll();
        }
    }

    private void workLoop() {
        while (true) {
            try {
                if (!schedule()) {
                    synchronized (dm.lock) {
                        dm.lock.wait();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    /**
     * 并发调度(任务按加入时间排序):
     * 1) 正在下载数超过并发上限 → 按加入时间从后往前把最晚加入的多余任务置为"调度暂停"(等待调度,有空位自动恢复)
     * 2) 正在下载数不足 → 按加入时间从前往后启动等待/调度暂停任务
     *
     * @return 是否有调度动作(避免空转忙等)
     */
    private boolean schedule() {
        synchronized (dm.tasks) {
            List<DownloadTask> sorted = new ArrayList<>(dm.tasks);
            sorted.sort(Comparator.comparingLong(t -> t.createTime));
            int running = 0;
            for (DownloadTask t : sorted) {
                if (t.state == DownloadTask.STATE_DOWNLOADING) running++;
            }
            if (running > dm.policy.getMaxConcurrent()) {
                // 并发调低:最晚加入的转为"调度暂停"(与用户手动暂停区分)
                int toPause = running - dm.policy.getMaxConcurrent();
                for (int i = sorted.size() - 1; i >= 0 && toPause > 0; i--) {
                    DownloadTask t = sorted.get(i);
                    if (t.state == DownloadTask.STATE_DOWNLOADING) {
                        t.state = DownloadTask.STATE_SYSTEM_PAUSED;
                        Response r = dm.activeResponses.remove(t.id);
                        if (r != null) {
                            try {
                                r.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        toPause--;
                    }
                }
                dm.persist();
                dm.notifyChanged();
                return true;
            }
            if (running < dm.policy.getMaxConcurrent()) {
                // 并发调高:按加入顺序补足(等待中优先,其次自动恢复调度暂停的)
                int toStart = dm.policy.getMaxConcurrent() - running;
                for (DownloadTask t : sorted) {
                    if (toStart <= 0) break;
                    if (t.state == DownloadTask.STATE_WAITING || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                        // 同目标(savePath)已有任务在下载时,不重复启动,避免并发下载同一文件互相踩踏
                        if (isSameTargetDownloading(t, sorted)) continue;
                        if (t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                            t.state = DownloadTask.STATE_WAITING; // 调度暂停自动恢复
                        }
                        startTask(t);
                        toStart--;
                    }
                }
                return true;
            }
            return false;
        }
    }

    /** 是否有其他任务正在下载同一 savePath(防同目标并发下载互相踩踏) */
    private boolean isSameTargetDownloading(DownloadTask t, List<DownloadTask> sorted) {
        if (t.savePath == null) return false;
        for (DownloadTask other : sorted) {
            if (other == t) continue;
            if (other.state == DownloadTask.STATE_DOWNLOADING
                    && other.savePath != null && other.savePath.equals(t.savePath)) {
                return true;
            }
        }
        return false;
    }

    /** 启动一个任务(独立线程下载,支持并发;失败自动重试) */
    private void startTask(final DownloadTask t) {
        t.state = DownloadTask.STATE_DOWNLOADING;
        t.message = "";
        t.speed = 0;
        dm.persist();
        dm.notifyChanged();
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                // Bug4: 存储权限硬门槛,启动前检查(权限被撤销则拒绝启动)
                if (!FileCleaner.hasStoragePermission()) {
                    t.state = DownloadTask.STATE_FAILED;
                    t.message = "未授权存储权限,无法下载";
                    Log.i("TVBox-Download", "启动失败:无存储权限 " + t.fileName);
                    dm.persist();
                    dm.notifyChanged();
                    wakeWorker();
                    return;
                }
                // 下载前磁盘空间预检:不足则直接失败并提示需清理量级(避免下载中空间耗尽损坏设备)
                String spaceErr = dm.policy.checkDiskSpace(t);
                if (spaceErr != null) {
                    t.state = DownloadTask.STATE_FAILED;
                    t.message = spaceErr;
                    Log.i("TVBox-Download", "磁盘空间预检失败: " + t.fileName + " " + spaceErr);
                    dm.persist();
                    dm.notifyChanged();
                    wakeWorker();
                    return;
                }
                int retries = 0;
                // 进程重启后首次启动:代理签名URL通常已过期,先重新解析一次(与下载中过期重解析共用逻辑)
                if (t.needReResolve) {
                    t.needReResolve = false;
                    reResolveUrl(t);
                }
                while (true) {
                    try {
                        dm.executor.processTask(t);
                        return; // 成功
                    } catch (Throwable th) {
                        th.printStackTrace();
                        if (isTaskStopped(t)) return; // 暂停(用户/调度/网络)或已取消,不再重试
                        // 断网/切网等网络错误:允许更多次重试 + 指数退避(最长约2分钟),并标记网络失败待恢复后自动续传
                        boolean netErr = isNetworkError(th);
                        int maxRetry = netErr ? DownloadManager.MAX_NETWORK_RETRY : DownloadManager.MAX_RETRY;
                        // 地址可能过期(HTTP 403/404/410 等或 HTML 防盗链响应):重新解析地址后继续,
                        // 重置下载重试计数;解析次数有限制(MAX_RE_RESOLVE),避免无限重解析
                        if (!netErr && shouldReResolve(t, th) && t.reResolveCount < DownloadManager.MAX_RE_RESOLVE
                                && t.sourceKey != null && t.playFlag != null && t.episodeRawUrl != null) {
                            t.reResolveCount++;
                            retries = 0;
                            Log.i("TVBox-Download", "地址可能过期,重新解析(" + t.reResolveCount + "/" + DownloadManager.MAX_RE_RESOLVE + "): "
                                    + t.fileName + " " + t.message);
                            t.message = "地址更新中(" + t.reResolveCount + "/" + DownloadManager.MAX_RE_RESOLVE + ")";
                            dm.persist();
                            dm.notifyChanged();
                            try {
                                Thread.sleep(3000L);
                            } catch (InterruptedException ie) {
                                return;
                            }
                            if (reResolveUrl(t)) {
                                continue; // 用新地址继续下载
                            }
                            // 解析失败:继续走普通重试逻辑
                        }
                        if (retries < maxRetry) {
                            retries++;
                            long delay = netErr ? (3000L + retries * 3000L) : 3000L;
                            Log.i("TVBox-Download", "任务重试 " + retries + "/" + maxRetry + (netErr ? "(网络)" : "")
                                    + ": " + t.fileName + " " + t.message);
                            t.message = "重试中(" + retries + "/" + maxRetry + ")";
                            dm.persist();
                            dm.notifyChanged();
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException ie) {
                                return;
                            }
                            continue;
                        }
                        t.state = DownloadTask.STATE_FAILED;
                        t.message = th.getMessage() == null ? th.toString() : th.getMessage();
                        if (netErr) {
                            t.networkFailed = true; // 网络恢复后自动续传
                            t.message = t.message + "(网络恢复后自动继续)";
                        }
                        dm.persist();
                        dm.notifyChanged();
                        return;
                    } finally {
                        wakeWorker(); // 任务结束,重新调度下一个
                    }
                }
            }
        }, "tvbox-dl-" + (t.id != null && t.id.length() > 6 ? t.id.substring(0, 6) : "task"));
        th.setDaemon(true);
        th.start();
    }

    /** 判断异常是否为网络类错误(断网/超时/无法连接等) */
    private boolean isNetworkError(Throwable th) {
        Throwable c = th;
        while (c != null) {
            if (c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.ConnectException
                    || c instanceof java.net.UnknownHostException
                    || c instanceof java.net.SocketException
                    || c instanceof javax.net.ssl.SSLException) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    /**
     * 判断下载失败是否可能因"地址过期"(而非网络/源本身问题):
     * - 非网络类错误
     * - 异常信息含 HTTP 4xx(尤其 403 禁止/404 不存在/410 已失效)或 HTML 防盗链提示
     * 满足条件时尝试重新解析地址。
     */
    private boolean shouldReResolve(DownloadTask t, Throwable th) {
        String msg = th.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase();
        if (m.contains("html") || m.contains("防盗链") || m.contains("网页")) return true;
        // HTTP 状态码
        java.util.regex.Matcher mat = java.util.regex.Pattern.compile("http\\s*(\\d{3})").matcher(m);
        if (mat.find()) {
            int code = Integer.parseInt(mat.group(1));
            return code == 401 || code == 403 || code == 404 || code == 410 || code == 451;
        }
        return false;
    }

    /**
     * 重新解析播放地址与请求头（经 SpiderApi 串行执行，避免 quickjs 并发卡死）。
     * 只要解析成功就同步请求头（旧任务缺头时靠 403/404 触发重解析补头，即使地址未变也要继续重试）。
     *
     * @return true=地址或请求头已更新(调用方应继续重试下载)
     */
    private boolean reResolveUrl(DownloadTask t) {
        if (t.sourceKey == null || t.playFlag == null || t.episodeRawUrl == null) return false;
        try {
            PlayUrlResolver.ResolveResult rr = SpiderApi.resolvePlayUrl(t.sourceKey, t.playFlag, t.episodeRawUrl);
            if (rr != null && rr.url != null && !rr.url.isEmpty()) {
                boolean urlChanged = !rr.url.equals(t.url);
                t.headers = rr.headers; // 无论地址是否变化都同步请求头(防盗链源分片校验)
                if (urlChanged) {
                    Log.i("TVBox-Download", "重新解析地址成功: " + t.fileName);
                    t.url = rr.url;
                    // 地址已更新:直链进度作废(URL变了,原Range续传可能无效),分片/已下字节保留由下载逻辑按需处理
                    if (t.downloadedBytes > 0 && !t.isHls()) {
                        t.downloadedBytes = 0;
                    }
                } else {
                    Log.i("TVBox-Download", "重新解析地址无变化,已同步请求头: " + t.fileName);
                }
                return true;
            }
            Log.i("TVBox-Download", "重新解析地址无变化/失败,用原地址: " + t.fileName);
        } catch (Throwable th4) {
            Log.i("TVBox-Download", "重新解析地址异常,用原地址: " + t.fileName);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 入队 / 启停
    // ------------------------------------------------------------------

    /**
     * 新增下载任务（含 EpisodeId、封面图与解析请求头）
     *
     * @param url         播放地址(直链或 m3u8)
     * @param sourceKey   来源 key(存任务,重启后重新解析地址用,可空)
     * @param playFlag    线路名(可空)
     * @param episodeRawUrl 源站原始集地址(可空)
     * @param episodeId   统一剧集标识(可空)
     * @param pic         封面图 URL(可空)
     * @param headers     解析请求头(可空)
     * @param sourceName  来源名称(如 饭太硬),一级目录
     * @param vodName     剧名,二级目录与显示分组
     * @param episodeName 选集名称(播放页选集列表的名称,如 第1集)
     * @return true=已加入任务;false=该集已下载,不能重复下载
     */
    boolean enqueueInternal(String url, String sourceKey, String playFlag, String episodeRawUrl,
                            String episodeId, String pic, java.util.Map<String, String> headers,
                            String sourceName, String vodName, String episodeName) {
        String src = dm.sanitize(sourceName);
        if (src.isEmpty()) src = "未分类";
        String vn = dm.sanitize(vodName);
        if (vn.isEmpty()) vn = "未命名";

        // 文件扩展名:按 URL 后缀快速判定;m3u8 统一后续合并 mp4,其余直链保留原格式
        String ext = ".mp4";
        String lower = url == null ? "" : url.toLowerCase();
        if (!lower.contains(".m3u8")) {
            if (lower.contains(".mkv")) ext = ".mkv";
            else if (lower.contains(".flv")) ext = ".flv";
            else if (lower.contains(".avi")) ext = ".avi";
            else if (lower.contains(".mov")) ext = ".mov";
            else if (lower.contains(".webm")) ext = ".webm";
            else if (lower.contains(".wmv")) ext = ".wmv";
            else if (lower.contains(".m4v")) ext = ".m4v";
            else if (lower.contains(".3gp")) ext = ".3gp";
            else if (lower.contains(".mpg") || lower.contains(".mpeg")) ext = ".mpg";
            else if (lower.contains(".ts")) ext = ".ts";
            else if (lower.contains(".mp4")) ext = ".mp4";
        }

        String ep = episodeName == null ? "" : episodeName.trim();
        String fileName;
        if (ep.isEmpty() || ep.equals(vn)) {
            fileName = vn + ext;
        } else {
            fileName = vn + "_" + dm.sanitize(ep) + ext;
        }

        // Bug4: 存储权限是硬门槛,无权限不入队、不触发调度
        if (!FileCleaner.hasStoragePermission()) {
            Log.i("TVBox-Download", "enqueue 拒绝:无存储权限 " + fileName);
            return false;
        }

        File dir = new File(dm.getSaveDir(), src + File.separator + vn);
        if (!dir.exists()) dir.mkdirs();
        File finalFile = new File(dir, fileName);
        if (finalFile.exists()) {
            Log.i("TVBox-Download", "enqueue 拒绝:文件已存在 " + finalFile.getAbsolutePath());
            return false; // 已下载
        }
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (episodeId != null && !episodeId.isEmpty() && episodeId.equals(t.episodeId)) {
                    Log.i("TVBox-Download", "enqueue 拒绝:任务已存在(episodeId) " + finalFile.getAbsolutePath());
                    return false; // 任务已存在(任意状态),按统一剧集标识精确去重
                }
                if (t.savePath != null && t.savePath.equals(finalFile.getAbsolutePath())) {
                    Log.i("TVBox-Download", "enqueue 拒绝:任务已存在 " + finalFile.getAbsolutePath());
                    return false; // 任务已存在(任意状态)
                }
            }
        }

        DownloadTask t = new DownloadTask();
        // 任务唯一ID:由 时间+文件名 计算(紧凑hex,确定可推导;同名任务由入队查重保证唯一)
        t.createTime = System.currentTimeMillis();
        t.id = Integer.toHexString((int) (t.createTime & 0xFFFFFFFFL))
                + Integer.toHexString(fileName.hashCode());
        t.url = url;
        t.sourceKey = sourceKey;
        t.playFlag = playFlag;
        t.episodeRawUrl = episodeRawUrl;
        t.episodeId = episodeId;
        t.episodeName = episodeName;
        t.pic = pic;
        t.headers = headers;
        t.sourceName = src;
        t.vodName = vn;
        t.groupName = vn;
        t.fileName = fileName;
        t.savePath = finalFile.getAbsolutePath();
        t.partPath = t.savePath + ".part";
        // 复用残留的 .part(上次任务丢失/进程被杀后遗留):直链按已有大小断点续传,避免从头下载
        if (!lower.contains(".m3u8")) {
            File partFile = new File(t.partPath);
            if (partFile.exists() && partFile.length() > 0) {
                t.downloadedBytes = partFile.length();
            }
        }
        if (lower.contains(".m3u8")) {
            // Bug2: tmpDir 由 episodeId 派生(稳定可复用)——重入队同 episodeId 复用旧碎片续传,
            // 不再因 taskId 变化导致全部重下;无 episodeId 的旧任务回退 taskId
            String dirKey = (episodeId != null && !episodeId.isEmpty())
                    ? Integer.toHexString(episodeId.hashCode()) : t.id;
            t.tmpDir = new File(dir, "tmp" + File.separator + dirKey).getAbsolutePath();
        }
        t.state = DownloadTask.STATE_WAITING;
        synchronized (dm.tasks) {
            dm.tasks.add(t);
        }
        Log.i("TVBox-Download", "enqueue 加入任务: " + episodeName + " -> " + t.savePath + " url=" + url);
        dm.persist();
        dm.notifyChanged();
        wakeWorker();
        // 触发时顺带把剧集海报下载到本地(按剧集分文件夹,私有目录),下载页组级/条目级展示用
        dm.store.ensurePosterAsync(t.pic, t.vodName);
        return true;
    }

    /** 暂停(用户手动):下载中/等待中/排队中的任务都可手动暂停,暂停后不再参与自动调度 */
    void pause(DownloadTask t) {
        if (t.state == DownloadTask.STATE_DOWNLOADING
                || t.state == DownloadTask.STATE_WAITING
                || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
            pauseInternal(t);
        }
    }

    /** 暂停指定任务为"用户暂停"(下载中则关闭其连接) */
    private void pauseInternal(DownloadTask t) {
        t.state = DownloadTask.STATE_PAUSED;
        t.speed = 0;
        dm.persist();
        dm.notifyChanged();
        Response r = dm.activeResponses.remove(t.id);
        if (r != null) {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 继续:恢复单个暂停/失败任务。
     * 若并发已满,把最早开始下载的任务置为"调度暂停"给被恢复的任务让位,保证严格按并发上限执行。
     */
    void resume(DownloadTask t) {
        if (t.state != DownloadTask.STATE_PAUSED && t.state != DownloadTask.STATE_FAILED) {
            return;
        }
        t.state = DownloadTask.STATE_WAITING;
        t.message = "";
        synchronized (dm.tasks) {
            int running = 0;
            DownloadTask oldestRunning = null;
            for (DownloadTask tt : dm.tasks) {
                if (tt.state == DownloadTask.STATE_DOWNLOADING) {
                    running++;
                    if (oldestRunning == null || tt.createTime < oldestRunning.createTime) {
                        oldestRunning = tt;
                    }
                }
            }
            if (running >= dm.policy.getMaxConcurrent() && oldestRunning != null) {
                // 让位:最早开始下载的任务转为"调度暂停"(有空位自动恢复,关闭连接线程即退出)
                oldestRunning.state = DownloadTask.STATE_SYSTEM_PAUSED;
                Response r = dm.activeResponses.remove(oldestRunning.id);
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        dm.persist();
        dm.notifyChanged();
        wakeWorker();
    }

    /** 全部暂停:暂停所有下载中/等待中的任务(一次性持久化与通知) */
    void pauseAll() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING || t.state == DownloadTask.STATE_WAITING) {
                    t.state = DownloadTask.STATE_PAUSED;
                    changed = true;
                    Response r = dm.activeResponses.remove(t.id);
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
        }
    }

    /** 全部开始:继续所有已暂停/失败/调度暂停的任务(失败等同重试),一次性持久化与通知 */
    void startAll() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_PAUSED
                        || t.state == DownloadTask.STATE_FAILED
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    t.state = DownloadTask.STATE_WAITING;
                    t.message = "";
                    changed = true;
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
            wakeWorker();
        }
    }

    /** 任务是否已停止(暂停/调度暂停/网络暂停/取消) */
    private static boolean isTaskStopped(DownloadTask t) {
        return t.state == DownloadTask.STATE_PAUSED
                || t.state == DownloadTask.STATE_SYSTEM_PAUSED
                || t.state == DownloadTask.STATE_NETWORK_PAUSED
                || t.state == DownloadTask.STATE_CANCELLED;
    }

    /**
     * 删除任务
     *
     * @param t           任务
     * @param deleteFiles true=连本地文件(.part/成品/临时分片)一起删;false=只删记录保留文件
     */
    void remove(DownloadTask t, boolean deleteFiles) {
        // Bug2: 先置 CANCELLED 再关连接——下载线程每步检查 CANCELLED 立即中止,不落最终文件
        if (t.state == DownloadTask.STATE_DOWNLOADING) {
            t.state = DownloadTask.STATE_CANCELLED;
            Response r = dm.activeResponses.remove(t.id);
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (dm.tasks) {
            dm.tasks.remove(t);
        }
        if (deleteFiles) {
            FileCleaner.deleteQuietly(new File(t.partPath));
            FileCleaner.deleteQuietly(new File(t.savePath));
            // 分段目录递归删除,父级 tmp 仅当为空才删
            dm.executor.deleteSegmentsDir(t);
        }
        dm.persist();
        dm.notifyChanged();
        wakeWorker(); // 删除后重新调度
    }

    /**
     * Bug1: 仅WiFi开启且网络变为蜂窝/断开 → 全部任务置 NETWORK_PAUSED(关闭连接),
     * WiFi 恢复后自动恢复(见 {@link #resumeAllNetwork})。
     */
    void pauseAllNetwork() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_WAITING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    t.state = DownloadTask.STATE_NETWORK_PAUSED;
                    changed = true;
                    Response r = dm.activeResponses.remove(t.id);
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
        }
    }

    /** Bug1: WiFi 恢复 → 自动恢复 NETWORK_PAUSED 任务(用户手动 PAUSED 不自动恢复) */
    void resumeAllNetwork() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_NETWORK_PAUSED) {
                    t.state = DownloadTask.STATE_WAITING;
                    changed = true;
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
            wakeWorker();
        }
    }
}
