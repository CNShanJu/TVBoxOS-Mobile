package com.github.tvbox.osc.util;

import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.event.DownloadEvent;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Response;

/**
 * 内置下载管理器
 * <ul>
 *     <li>支持直链文件下载与 HLS(m3u8)分段下载合并为 mp4</li>
 *     <li>支持暂停/继续,断点续传(Range + 分段进度)</li>
 *     <li>任务持久化到 Hawk,重启自动恢复(下载中任务自动续传)</li>
 *     <li>目录结构:Download/TVBox/来源名/剧名/剧名_第几集.mp4(m3u8 分片先入 tmp,合并后清理)</li>
 *     <li>同一来源同一剧名同一集已存在时拒绝重复下载</li>
 * </ul>
 */
public class DownloadManager {

    private static final String HAWK_KEY = "download_tasks_v1";
    private static final String HAWK_MAX_CONCURRENT = "download_max_concurrent";
    private static final int BUFFER = 64 * 1024;

    private static DownloadManager instance;

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final Object lock = new Object();
    private Thread worker;
    /** 每个任务当前活动的 HTTP 响应,用于暂停/删除时关闭对应连接 */
    private final Map<String, Response> activeResponses = new ConcurrentHashMap<>();
    /** 最大并发下载数(1-5) */
    private volatile int maxConcurrent = 3;

    public static synchronized DownloadManager get() {
        if (instance == null) {
            instance = new DownloadManager();
        }
        return instance;
    }

    private DownloadManager() {
        List<DownloadTask> saved = Hawk.get(HAWK_KEY, new ArrayList<DownloadTask>());
        if (saved != null) tasks.addAll(saved);
        int savedConcurrent = Hawk.get(HAWK_MAX_CONCURRENT, 3);
        maxConcurrent = Math.max(1, Math.min(5, savedConcurrent));
        startWorker();
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    /** 设置最大并发数(1-5),触发重新调度 */
    public void setMaxConcurrent(int n) {
        int v = Math.max(1, Math.min(5, n));
        maxConcurrent = v;
        try {
            Hawk.put(HAWK_MAX_CONCURRENT, v);
        } catch (Throwable ignored) {
        }
        notifyChanged();
        wakeWorker();
    }

    private void startWorker() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workLoop();
            }
        }, "tvbox-download");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    public List<DownloadTask> getTasks() {
        return tasks;
    }

    /**
     * 新增下载任务
     *
     * @param url         播放地址(直链或 m3u8)
     * @param sourceName  来源名称(如 饭太硬),一级目录
     * @param vodName     剧名,二级目录与显示分组
     * @param episodeName 选集名称(播放页选集列表的名称,如 第1集)
     * @return true=已加入任务;false=该集已下载,不能重复下载
     */
    public boolean enqueue(String url, String sourceName, String vodName, String episodeName) {
        String src = sanitize(sourceName);
        if (src.isEmpty()) src = "未分类";
        String vn = sanitize(vodName);
        if (vn.isEmpty()) vn = "未命名";

        // 文件扩展名:m3u8 统一合成 mp4,直链保留原格式
        String ext = ".mp4";
        String lower = url == null ? "" : url.toLowerCase();
        if (!lower.contains(".m3u8")) {
            if (lower.contains(".mkv")) ext = ".mkv";
            else if (lower.contains(".flv")) ext = ".flv";
            else if (lower.contains(".avi")) ext = ".avi";
            else if (lower.contains(".mov")) ext = ".mov";
            else if (lower.contains(".webm")) ext = ".webm";
            else if (lower.contains(".ts")) ext = ".ts";
            else if (lower.contains(".mp4")) ext = ".mp4";
        }

        String ep = episodeName == null ? "" : episodeName.trim();
        String fileName;
        if (ep.isEmpty() || ep.equals(vn)) {
            fileName = vn + ext;
        } else {
            fileName = vn + "_" + sanitize(ep) + ext;
        }

        File dir = new File(getSaveDir(), src + File.separator + vn);
        if (!dir.exists()) dir.mkdirs();
        File finalFile = new File(dir, fileName);
        if (finalFile.exists()) return false; // 已下载
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (t.savePath != null && t.savePath.equals(finalFile.getAbsolutePath())) {
                    return false; // 任务已存在(任意状态)
                }
            }
        }

        DownloadTask t = new DownloadTask();
        t.id = UUID.randomUUID().toString();
        t.url = url;
        t.sourceName = src;
        t.vodName = vn;
        t.groupName = vn;
        t.fileName = fileName;
        t.savePath = finalFile.getAbsolutePath();
        t.partPath = t.savePath + ".part";
        if (lower.contains(".m3u8")) {
            t.tmpDir = new File(dir, "tmp").getAbsolutePath();
        }
        t.state = DownloadTask.STATE_WAITING;
        synchronized (tasks) {
            tasks.add(t);
        }
        persist();
        notifyChanged();
        wakeWorker();
        return true;
    }

    /** 暂停 */
    public void pause(DownloadTask t) {
        if (t.state == DownloadTask.STATE_DOWNLOADING || t.state == DownloadTask.STATE_WAITING) {
            pauseInternal(t);
        }
    }

    /** 暂停指定任务(下载中则关闭其连接) */
    private void pauseInternal(DownloadTask t) {
        t.state = DownloadTask.STATE_PAUSED;
        persist();
        notifyChanged();
        Response r = activeResponses.remove(t.id);
        if (r != null) {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 继续 */
    public void resume(DownloadTask t) {
        if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
            t.state = DownloadTask.STATE_WAITING;
            persist();
            notifyChanged();
            wakeWorker();
        }
    }

    /** 删除任务与文件 */
    public void remove(DownloadTask t) {
        if (t.state == DownloadTask.STATE_DOWNLOADING) {
            Response r = activeResponses.remove(t.id);
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (tasks) {
            tasks.remove(t);
        }
        deleteQuietly(new File(t.partPath));
        deleteQuietly(new File(t.savePath));
        deleteQuietly(new File(t.partPath + "_segments"));
        if (t.tmpDir != null && !t.tmpDir.isEmpty()) {
            deleteRecursive(new File(t.tmpDir));
        }
        persist();
        notifyChanged();
        wakeWorker(); // 删除后重新调度
    }

    // ------------------------------------------------------------------
    // 持久化 / 通知
    // ------------------------------------------------------------------

    private void persist() {
        try {
            synchronized (tasks) {
                Hawk.put(HAWK_KEY, new ArrayList<DownloadTask>(tasks));
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void notifyChanged() {
        EventBus.getDefault().post(new DownloadEvent(DownloadEvent.TYPE_CHANGE));
    }

    private void wakeWorker() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------
    // 工作线程
    // ------------------------------------------------------------------

    private void workLoop() {
        while (true) {
            try {
                if (!schedule()) {
                    synchronized (lock) {
                        lock.wait();
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
     * 1) 正在下载数超过并发上限 → 按加入时间从后往前暂停最晚加入的多余任务
     * 2) 正在下载数不足 → 按加入时间从前往后启动等待任务
     *
     * @return 是否有调度动作(避免空转忙等)
     */
    private boolean schedule() {
        synchronized (tasks) {
            List<DownloadTask> sorted = new ArrayList<>(tasks);
            sorted.sort(Comparator.comparingLong(t -> t.createTime));
            int running = 0;
            for (DownloadTask t : sorted) {
                if (t.state == DownloadTask.STATE_DOWNLOADING) running++;
            }
            if (running > maxConcurrent) {
                // 并发调低:暂停最晚加入的
                int toPause = running - maxConcurrent;
                for (int i = sorted.size() - 1; i >= 0 && toPause > 0; i--) {
                    DownloadTask t = sorted.get(i);
                    if (t.state == DownloadTask.STATE_DOWNLOADING) {
                        pauseInternal(t);
                        toPause--;
                    }
                }
                return true;
            }
            if (running < maxConcurrent) {
                // 并发调高:按加入顺序补足
                int toStart = maxConcurrent - running;
                for (DownloadTask t : sorted) {
                    if (toStart <= 0) break;
                    if (t.state == DownloadTask.STATE_WAITING) {
                        startTask(t);
                        toStart--;
                    }
                }
                return true;
            }
            return false;
        }
    }

    /** 启动一个任务(独立线程下载,支持并发) */
    private void startTask(final DownloadTask t) {
        t.state = DownloadTask.STATE_DOWNLOADING;
        t.message = "";
        persist();
        notifyChanged();
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    processTask(t);
                } catch (Throwable th) {
                    th.printStackTrace();
                    if (t.state != DownloadTask.STATE_PAUSED) {
                        t.state = DownloadTask.STATE_FAILED;
                        t.message = th.getMessage() == null ? th.toString() : th.getMessage();
                        persist();
                        notifyChanged();
                    }
                } finally {
                    wakeWorker(); // 任务结束,重新调度下一个
                }
            }
        }, "tvbox-dl-" + (t.id != null && t.id.length() > 6 ? t.id.substring(0, 6) : "task"));
        th.setDaemon(true);
        th.start();
    }

    private void processTask(DownloadTask t) throws IOException {
        if (t.url != null && t.url.toLowerCase().contains(".m3u8")) {
            downloadHls(t);
        } else {
            downloadDirect(t);
        }
    }

    // ------------------------------------------------------------------
    // 直链下载(断点续传)
    // ------------------------------------------------------------------

    private void downloadDirect(DownloadTask t) throws IOException {
        Map<String, String> headers = baseHeaders();
        if (t.downloadedBytes > 0) {
            headers.put("Range", "bytes=" + t.downloadedBytes + "-");
        }
        Response resp = HttpClient.getResponseSync(t.url, headers);
        activeResponses.put(t.id, resp);
        try {
            int code = resp.code();
            if (code == 200 && t.downloadedBytes > 0) {
                // 服务器不支持断点,从头开始
                t.downloadedBytes = 0;
                deleteQuietly(new File(t.partPath));
            } else if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            if (t.totalBytes <= 0) {
                String cl = resp.header("Content-Length");
                if (cl != null) {
                    t.totalBytes = t.downloadedBytes + Long.parseLong(cl);
                }
            }
            File part = new File(t.partPath);
            File parent = part.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            OutputStream os = new FileOutputStream(part, t.downloadedBytes > 0);
            InputStream is = resp.body().byteStream();
            byte[] buf = new byte[BUFFER];
            int n;
            long lastPersist = 0;
            while ((n = is.read(buf)) > 0) {
                if (t.state == DownloadTask.STATE_PAUSED) {
                    os.flush();
                    os.close();
                    persist();
                    notifyChanged();
                    return;
                }
                os.write(buf, 0, n);
                t.downloadedBytes += n;
                long now = System.currentTimeMillis();
                if (now - lastPersist > 800) {
                    lastPersist = now;
                    persist();
                    notifyChanged();
                }
            }
            os.flush();
            os.close();
            if (t.state == DownloadTask.STATE_PAUSED) {
                persist();
                notifyChanged();
                return;
            }
            finishDirect(t);
        } finally {
            activeResponses.remove(t.id);
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
            copyFile(part, finalFile);
            deleteQuietly(part);
        }
        t.state = DownloadTask.STATE_COMPLETED;
        t.downloadedBytes = t.totalBytes;
        persist();
        notifyChanged();
    }

    // ------------------------------------------------------------------
    // HLS(m3u8)分段下载 + 合并
    // ------------------------------------------------------------------

    private void downloadHls(DownloadTask t) throws IOException {
        String playlistUrl = t.url;
        String playlist = fetchPlaylist(playlistUrl, t);
        // fetchPlaylist 遇到主播放列表时会切换到具体变体(t.url 已更新),分片需按实际播放列表解析
        List<String> segments = parseSegments(t.url, playlist);
        if (segments.isEmpty()) {
            throw new IOException("m3u8 无有效分片");
        }
        t.totalSegments = segments.size();
        if (t.doneSegments > segments.size()) t.doneSegments = 0;
        // 分片先下到 tmp 目录,全部完成后合成 mp4 再删除 tmp
        File tmpDir = t.tmpDir != null && !t.tmpDir.isEmpty()
                ? new File(t.tmpDir) : new File(t.partPath + "_segments");
        if (!tmpDir.exists()) tmpDir.mkdirs();

        for (int i = t.doneSegments; i < segments.size(); i++) {
            if (t.state == DownloadTask.STATE_PAUSED) {
                persist();
                notifyChanged();
                return;
            }
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            long segDone = (i == t.doneSegments) ? t.segmentBytes : 0;
            downloadSegment(segments.get(i), segFile, segDone, t);
            t.doneSegments = i + 1;
            t.segmentBytes = 0;
            persist();
            notifyChanged();
        }

        // 合并分片 -> mp4
        File finalFile = new File(t.savePath);
        if (finalFile.getParentFile() != null && !finalFile.getParentFile().exists()) {
            finalFile.getParentFile().mkdirs();
        }
        if (finalFile.exists()) finalFile.delete();
        OutputStream out = new FileOutputStream(finalFile);
        for (int i = 0; i < segments.size(); i++) {
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            copyFile(segFile, out);
        }
        out.flush();
        out.close();
        // 合成完成后清理 tmp(删除分片;tmp 空了就删除 tmp 目录)
        deleteRecursive(tmpDir);
        t.tmpDir = null;
        t.state = DownloadTask.STATE_COMPLETED;
        persist();
        notifyChanged();
    }

    private void downloadSegment(String segUrl, File segFile, long segDone, DownloadTask t) throws IOException {
        Map<String, String> headers = baseHeaders();
        if (segDone > 0) {
            headers.put("Range", "bytes=" + segDone + "-");
        }
        Response resp = HttpClient.getResponseSync(segUrl, headers);
        activeResponses.put(t.id, resp);
        try {
            int code = resp.code();
            if (code == 200 && segDone > 0) {
                segDone = 0;
                deleteQuietly(segFile);
            } else if (code != 200 && code != 206) {
                throw new IOException("segment HTTP " + code);
            }
            File parent = segFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            OutputStream os = new FileOutputStream(segFile, segDone > 0);
            InputStream is = resp.body().byteStream();
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (t.state == DownloadTask.STATE_PAUSED) {
                    os.flush();
                    os.close();
                    t.segmentBytes = segDone;
                    persist();
                    return;
                }
                os.write(buf, 0, n);
                segDone += n;
                t.segmentBytes = segDone;
            }
            os.flush();
            os.close();
        } finally {
            activeResponses.remove(t.id);
            resp.close();
        }
    }

    private String fetchPlaylist(String url, DownloadTask t) throws IOException {
        Response resp = HttpClient.getResponseSync(url, baseHeaders());
        activeResponses.put(t.id, resp);
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
                    Response resp2 = HttpClient.getResponseSync(variant, baseHeaders());
                    activeResponses.put(t.id, resp2);
                    try {
                        if (!resp2.isSuccessful()) throw new IOException("variant HTTP " + resp2.code());
                        t.url = variant;
                        return resp2.body().string();
                    } finally {
                        activeResponses.remove(t.id);
                        resp2.close();
                    }
                }
                throw new IOException("主播放列表无变体");
            }
            return text;
        } finally {
            activeResponses.remove(t.id);
            resp.close();
        }
    }

    private List<String> parseSegments(String playlistUrl, String playlist) {
        List<String> segs = new ArrayList<>();
        String base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1);
        for (String line : playlist.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
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

    private Map<String, String> baseHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "okhttp/3.12.11");
        return headers;
    }

    /** 下载保存根目录:有存储管理权限用公共 Download,否则用应用私有目录 */
    public static File getSaveDir() {
        File base;
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TVBox");
        } else {
            File ext = App.getInstance().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            base = ext == null ? new File(App.getInstance().getFilesDir(), "downloads") : new File(ext, "TVBox");
        }
        if (!base.exists()) base.mkdirs();
        return base;
    }

    /** 去除文件名的非法字符;null/空白返回空串,由调用方决定兜底名 */
    private String sanitize(String name) {
        if (name == null) return "";
        String n = name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n;
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
        fis.close();
        fos.close();
    }

    private static void copyFile(File src, OutputStream out) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        fis.close();
    }
}
