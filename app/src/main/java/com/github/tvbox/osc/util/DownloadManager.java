package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
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
    private static final String HAWK_WIFI_ONLY = "download_wifi_only";
    private static final int BUFFER = 64 * 1024;

    private static DownloadManager instance;

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final Object lock = new Object();
    private Thread worker;
    /** 持久化:后台单线程执行,避免主线程批量入队时被 Hawk.put(加密+磁盘IO)卡死 */
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-persist");
        t.setDaemon(true);
        return t;
    });
    /** 持久化合并锁:始终只写最新快照,批量入队不会重复写几十次 */
    private final Object persistLock = new Object();
    private List<DownloadTask> pendingSnapshot = null;
    private boolean writeScheduled = false;
    /** 每个任务当前活动的 HTTP 响应,用于暂停/删除时关闭对应连接 */
    private final Map<String, Response> activeResponses = new ConcurrentHashMap<>();
    /** 下载专用客户端:超时比播放请求长(慢速源/本地代理链不易超时) */
    private static final OkHttpClient downloadClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(new HttpClient.UserAgentInterceptor())
            .build();
    /** 失败自动重试次数(不含首次) */
    private static final int MAX_RETRY = 2;
    /** 网络错误(断网/切网)重试次数上限(不含首次),配合退避最长约2分钟 */
    private static final int MAX_NETWORK_RETRY = 8;
    /** 地址可能过期时重新解析地址的次数上限(每次解析后重置下载重试计数) */
    private static final int MAX_RE_RESOLVE = 3;
    /** 磁盘空间安全余量:下载完成后至少保留的可用空间(避免手机因空间耗尽卡死/无法开机) */
    private static final long MIN_FREE_SPACE = 1536L * 1024 * 1024; // 1.5GB
    /** 磁盘空间不足时的兜底检查:可用空间低于该值直接拒绝(防止极端情况) */
    private static final long MIN_ABSOLUTE_FREE = 512L * 1024 * 1024; // 512MB
    /** 碎片校验后自动补下缺失分片的最大轮数 */
    private static final int MAX_SEGMENT_REPAIR = 3;
    /** 分段信息 TXT 文件名 */
    public static final String SEGMENTS_INFO = "segments.txt";
    /** 合并阶段状态文案(下载中任务的 message 标记) */
    public static final String MSG_VERIFYING = "文件校验中";
    public static final String MSG_MERGING = "文件合并中";
    /** 最大并发下载数(1-5) */
    private volatile int maxConcurrent = 3;
    /** 变更事件去抖:进度高频刷新合并为至多每 500ms 广播一次 */
    private final Handler notifyHandler = new Handler(Looper.getMainLooper());
    private final Runnable notifyRunnable = () ->
            EventBus.getDefault().post(new DownloadEvent(DownloadEvent.TYPE_CHANGE));
    /** 网络状态监听:网络恢复后唤醒调度并自动续传因网络失败的任务 */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            Log.i("TVBox-Download", "网络恢复:自动续传因网络失败的任务");
            boolean changed = false;
            synchronized (tasks) {
                for (DownloadTask t : tasks) {
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
                persist();
                notifyChanged();
            }
            wakeWorker();
        }
    };

    public static synchronized DownloadManager get() {
        if (instance == null) {
            instance = new DownloadManager();
        }
        return instance;
    }

    private DownloadManager() {
        try {
            List<DownloadTask> saved = Hawk.get(HAWK_KEY, new ArrayList<DownloadTask>());
            if (saved != null) tasks.addAll(saved);
        } catch (Throwable th) {
            th.printStackTrace(); // 存储损坏时兜底为空列表,不阻塞下载器启动
        }
        // 进程重启:上次"下载中/排队中/等待中"的任务统一置为暂停,不自动恢复下载,
        // 由用户手动"继续/全部开始";继续时若持有源信息则自动重新解析地址(代理签名重启后通常已过期)。
        // 手动暂停过的任务同样保持暂停。
        boolean needPersist = false;
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED
                        || t.state == DownloadTask.STATE_WAITING) {
                    t.state = DownloadTask.STATE_PAUSED;
                    t.needReResolve = true;
                    needPersist = true;
                }
                t.speed = 0;
            }
        }
        if (needPersist) {
            persist();
            Log.i("TVBox-Download", "进程重启:未完成任务置为暂停,等待用户手动开始(继续时自动重新解析地址)");
        }
        int savedConcurrent = 3;
        try {
            savedConcurrent = Hawk.get(HAWK_MAX_CONCURRENT, 3);
        } catch (Throwable ignored) {
        }
        maxConcurrent = Math.max(1, Math.min(5, savedConcurrent));
        startWorker();
        registerNetworkCallback();
    }

    /** 注册网络状态监听(断网/切网后网络恢复时自动续传) */
    private void registerNetworkCallback() {
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

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    /** 是否仅 WiFi 下载(默认开启,移动网络下载前需强提醒确认) */
    public boolean isWifiOnly() {
        try {
            return Hawk.get(HAWK_WIFI_ONLY, true);
        } catch (Throwable th) {
            return true;
        }
    }

    public void setWifiOnly(boolean wifiOnly) {
        try {
            Hawk.put(HAWK_WIFI_ONLY, wifiOnly);
        } catch (Throwable ignored) {
        }
    }

    /** 当前网络是否为移动网络(蜂窝) */
    public static boolean isMobileNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) App.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable th) {
            return false;
        }
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

    /** 任务快照(安全遍历,避免外部迭代与任务增删并发冲突) */
    public List<DownloadTask> getTasks() {
        synchronized (tasks) {
            return new ArrayList<>(tasks);
        }
    }

    /**
     * 新增下载任务(简化入口,不含重新解析信息)
     */
    public boolean enqueue(String url, String sourceName, String vodName, String episodeName) {
        return enqueue(url, null, null, null, null, sourceName, vodName, episodeName);
    }

    /**
     * 新增下载任务(含 EpisodeId 统一剧集标识)
     */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String sourceName, String vodName, String episodeName) {
        return enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, null, sourceName, vodName, episodeName);
    }

    /** 带 EpisodeId 与封面图 URL 的入队 */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, String sourceName, String vodName, String episodeName) {
        return enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, sourceName, vodName, episodeName);
    }

    /**
     * 新增下载任务
     *
     * @param url         播放地址(直链或 m3u8)
     * @param sourceKey   来源 key(存任务,重启后重新解析地址用,可空)
     * @param playFlag    线路名(可空)
     * @param episodeRawUrl 源站原始集地址(可空)
     * @param sourceName  来源名称(如 饭太硬),一级目录
     * @param vodName     剧名,二级目录与显示分组
     * @param episodeName 选集名称(播放页选集列表的名称,如 第1集)
     * @return true=已加入任务;false=该集已下载,不能重复下载
     */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String sourceName, String vodName, String episodeName) {
        return enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, null, null, sourceName, vodName, episodeName);
    }

    private boolean enqueueInternal(String url, String sourceKey, String playFlag, String episodeRawUrl,
                                    String episodeId, String pic, String sourceName, String vodName, String episodeName) {
        String src = sanitize(sourceName);
        if (src.isEmpty()) src = "未分类";
        String vn = sanitize(vodName);
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
            fileName = vn + "_" + sanitize(ep) + ext;
        }

        File dir = new File(getSaveDir(), src + File.separator + vn);
        if (!dir.exists()) dir.mkdirs();
        File finalFile = new File(dir, fileName);
        if (finalFile.exists()) {
            Log.i("TVBox-Download", "enqueue 拒绝:文件已存在 " + finalFile.getAbsolutePath());
            return false; // 已下载
        }
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
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
            // 分段目录:对应剧集目录下的 tmp/<任务id>,删除/完成时整体清理
            t.tmpDir = new File(dir, "tmp" + File.separator + t.id).getAbsolutePath();
        }
        t.state = DownloadTask.STATE_WAITING;
        synchronized (tasks) {
            tasks.add(t);
        }
        Log.i("TVBox-Download", "enqueue 加入任务: " + episodeName + " -> " + t.savePath + " url=" + url);
        persist();
        notifyChanged();
        wakeWorker();
        return true;
    }

    /** 暂停(用户手动):下载中/等待中/排队中的任务都可手动暂停,暂停后不再参与自动调度 */
    public void pause(DownloadTask t) {
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

    /**
     * 继续:恢复单个暂停/失败任务。
     * 若并发已满,把最早开始下载的任务置为"调度暂停"给被恢复的任务让位,保证严格按并发上限执行。
     */
    public void resume(DownloadTask t) {
        if (t.state != DownloadTask.STATE_PAUSED && t.state != DownloadTask.STATE_FAILED) {
            return;
        }
        t.state = DownloadTask.STATE_WAITING;
        t.message = "";
        synchronized (tasks) {
            int running = 0;
            DownloadTask oldestRunning = null;
            for (DownloadTask tt : tasks) {
                if (tt.state == DownloadTask.STATE_DOWNLOADING) {
                    running++;
                    if (oldestRunning == null || tt.createTime < oldestRunning.createTime) {
                        oldestRunning = tt;
                    }
                }
            }
            if (running >= maxConcurrent && oldestRunning != null) {
                // 让位:最早开始下载的任务转为"调度暂停"(有空位自动恢复,关闭连接线程即退出)
                oldestRunning.state = DownloadTask.STATE_SYSTEM_PAUSED;
                Response r = activeResponses.remove(oldestRunning.id);
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        persist();
        notifyChanged();
        wakeWorker();
    }

    /** 全部暂停:暂停所有下载中/等待中的任务(一次性持久化与通知) */
    public void pauseAll() {
        boolean changed = false;
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING || t.state == DownloadTask.STATE_WAITING) {
                    t.state = DownloadTask.STATE_PAUSED;
                    changed = true;
                    Response r = activeResponses.remove(t.id);
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
            persist();
            notifyChanged();
        }
    }

    /** 全部开始:继续所有已暂停/失败/调度暂停的任务(失败等同重试),一次性持久化与通知 */
    public void startAll() {
        boolean changed = false;
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
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
            persist();
            notifyChanged();
            wakeWorker();
        }
    }

    /** 删除任务与文件 */
    public void remove(DownloadTask t) {
        remove(t, true);
    }

    /**
     * 删除任务
     *
     * @param t           任务
     * @param deleteFiles true=连本地文件(.part/成品/临时分片)一起删;false=只删记录保留文件
     */
    public void remove(DownloadTask t, boolean deleteFiles) {
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
        if (deleteFiles) {
            deleteQuietly(new File(t.partPath));
            deleteQuietly(new File(t.savePath));
            // 分段目录递归删除,父级 tmp 仅当为空才删
            deleteSegmentsDir(t);
        }
        persist();
        notifyChanged();
        wakeWorker(); // 删除后重新调度
    }

    /**
     * 清理"已完成但文件已不存在"的失效记录(下载完成列表基于记录展示前的对账)。
     * 不广播事件(调用方正处于刷新流程,避免刷新循环)。
     *
     * @return 移除的记录数
     */
    public int pruneMissingCompleted() {
        List<DownloadTask> toRemove = new ArrayList<>();
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (t.state == DownloadTask.STATE_COMPLETED && t.savePath != null) {
                    if (!new File(t.savePath).exists()) {
                        toRemove.add(t);
                    }
                }
            }
            if (!toRemove.isEmpty()) {
                tasks.removeAll(toRemove);
            }
        }
        if (!toRemove.isEmpty()) {
            persist();
            Log.i("TVBox-Download", "清理失效下载完成记录 " + toRemove.size() + " 条");
        }
        return toRemove.size();
    }

    /**
     * 查询某集(来源+剧名+剧集名)的下载状态,用于下载选择弹窗去重:
     * 0=无记录,可下载;1=已下载完成且文件存在;2=已有任务(下载中/排队/暂停/失败等,未完成)
     */
    public int getEpisodeDownloadState(String sourceName, String vodName, String episodeName) {
        String src = sanitize(sourceName);
        if (src.isEmpty()) src = "未分类";
        String vn = sanitize(vodName);
        if (vn.isEmpty()) vn = "未命名";
        String ep = episodeName == null ? "" : episodeName.trim();
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (t.vodName == null || !vn.equals(t.vodName)) continue;
                if (t.sourceName != null && !src.equals(t.sourceName)) continue;
                if (t.fileName == null || t.savePath == null) continue;
                // 文件名匹配:剧名_第X集.ext 或 剧名_第X集_720P.ext;单集为 剧名.ext
                boolean match;
                String base = vn + "_" + sanitize(ep);
                if (ep.isEmpty() || ep.equals(vn)) {
                    match = t.fileName.startsWith(vn + ".");
                } else {
                    match = t.fileName.startsWith(base + ".") || t.fileName.startsWith(base + "_");
                }
                if (!match) continue;
                if (t.state == DownloadTask.STATE_COMPLETED) {
                    // 完成但文件已丢:视为无记录(下载完成列表对账时会清理该记录)
                    if (new File(t.savePath).exists()) return 1;
                    continue;
                }
                return 2;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 持久化 / 通知
    // ------------------------------------------------------------------

    /**
     * 持久化(异步+合并):后台单线程写 Hawk,且始终写最新快照。
     * 批量入队(如一次选几十集下载)时主线程不再被 Hawk.put 阻塞,也不会重复写几十次。
     */
    private void persist() {
        List<DownloadTask> snapshot;
        synchronized (tasks) {
            snapshot = new ArrayList<>(tasks);
        }
        synchronized (persistLock) {
            pendingSnapshot = snapshot;
            if (writeScheduled) return; // 已有写任务在跑,跑完会再写最新快照
            writeScheduled = true;
        }
        persistExecutor.execute(() -> {
            while (true) {
                List<DownloadTask> toWrite;
                synchronized (persistLock) {
                    toWrite = pendingSnapshot;
                    pendingSnapshot = null;
                }
                try {
                    Hawk.put(HAWK_KEY, toWrite);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                synchronized (persistLock) {
                    if (pendingSnapshot == null) {
                        writeScheduled = false;
                        return;
                    }
                }
            }
        });
    }

    private void notifyChanged() {
        // 去抖:高频进度事件(每任务每800ms一次)合并,避免下载页被刷屏
        notifyHandler.removeCallbacks(notifyRunnable);
        notifyHandler.postDelayed(notifyRunnable, 500);
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
     * 1) 正在下载数超过并发上限 → 按加入时间从后往前把最晚加入的多余任务置为"调度暂停"(等待调度,有空位自动恢复)
     * 2) 正在下载数不足 → 按加入时间从前往后启动等待/调度暂停任务
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
                // 并发调低:最晚加入的转为"调度暂停"(与用户手动暂停区分)
                int toPause = running - maxConcurrent;
                for (int i = sorted.size() - 1; i >= 0 && toPause > 0; i--) {
                    DownloadTask t = sorted.get(i);
                    if (t.state == DownloadTask.STATE_DOWNLOADING) {
                        t.state = DownloadTask.STATE_SYSTEM_PAUSED;
                        Response r = activeResponses.remove(t.id);
                        if (r != null) {
                            try {
                                r.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        toPause--;
                    }
                }
                persist();
                notifyChanged();
                return true;
            }
            if (running < maxConcurrent) {
                // 并发调高:按加入顺序补足(等待中优先,其次自动恢复调度暂停的)
                int toStart = maxConcurrent - running;
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
    /**
     * 下载前磁盘空间预检:估算文件大小,检查保存目录所在磁盘剩余空间。
     * 要求:下载完成后可用空间仍 ≥ MIN_FREE_SPACE(1.5GB),不足则拒绝启动并提示需清理的量级。
     *
     * @return null=空间充足;否则返回错误提示文案
     */
    private String checkDiskSpace(DownloadTask t) {
        try {
            long size = estimateFileSize(t);
            if (size <= 0) return null; // 无法估算(如服务器不返回大小),不阻塞
            File dir = new File(t.savePath).getParentFile();
            if (dir == null || !dir.exists()) return null;
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long free = stat.getAvailableBytes();
            long needAfter = free - size; // 下载完后的剩余
            if (needAfter < MIN_FREE_SPACE) {
                long needClean = (MIN_FREE_SPACE - needAfter + 1024 * 1024 - 1) / (1024 * 1024);
                return "磁盘空间不足:文件约 " + formatSize(size) + ",完成后可用仅 "
                        + formatSize(Math.max(0, needAfter)) + ",需清理约 " + needClean + "MB";
            }
            return null;
        } catch (Throwable th) {
            return null; // 预检异常不阻塞下载
        }
    }

    /**
     * 估算文件大小:直链用 HEAD/首字节响应 Content-Length;
     * m3u8 用播放列表分片数 × 每片估算(无法精确时返回 0 表示不阻塞)。
     */
    private long estimateFileSize(DownloadTask t) {
        try {
            // 优先用已知大小(断点续传时已记录)
            if (t.totalBytes > 0) return t.totalBytes;
            if (t.url != null && t.url.toLowerCase().contains(".m3u8")) {
                // m3u8:尝试取播放列表,分片数量 × 单片估算(2MB/片,仅粗略)
                try {
                    Response resp = getDownloadResponse(t.url, baseHeaders());
                    activeResponses.put(t.id, resp);
                    try {
                        if (resp.isSuccessful()) {
                            String text = resp.body().string();
                            int segs = 0;
                            for (String line : text.split("\n")) {
                                String l = line.trim();
                                if (!l.isEmpty() && !l.startsWith("#")) segs++;
                            }
                            if (segs > 0) return segs * 2L * 1024 * 1024; // 粗略 2MB/片
                        }
                    } finally {
                        activeResponses.remove(t.id);
                        resp.close();
                    }
                } catch (Throwable ignored) {
                }
                return 0;
            }
            // 直链:HEAD 请求拿 Content-Length
            Request head = new Request.Builder().url(t.url)
                    .header("User-Agent", "okhttp/3.12.11")
                    .header("Range", "bytes=0-0") // 部分服务器不支持 HEAD,用首字节 Range 探测
                    .build();
            Response resp = downloadClient.newCall(head).execute();
            try {
                if (resp.isSuccessful()) {
                    String cl = resp.header("Content-Length");
                    if (cl != null) return Long.parseLong(cl.trim());
                }
            } finally {
                resp.close();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 格式化大小(供磁盘空间提示) */
    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private void startTask(final DownloadTask t) {
        t.state = DownloadTask.STATE_DOWNLOADING;
        t.message = "";
        t.speed = 0;
        persist();
        notifyChanged();
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                // 下载前磁盘空间预检:不足则直接失败并提示需清理量级(避免下载中空间耗尽损坏设备)
                String spaceErr = checkDiskSpace(t);
                if (spaceErr != null) {
                    t.state = DownloadTask.STATE_FAILED;
                    t.message = spaceErr;
                    Log.i("TVBox-Download", "磁盘空间预检失败: " + t.fileName + " " + spaceErr);
                    persist();
                    notifyChanged();
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
                        processTask(t);
                        return; // 成功
                    } catch (Throwable th) {
                        th.printStackTrace();
                        if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_SYSTEM_PAUSED) return; // 暂停(用户/调度),不再重试
                        // 断网/切网等网络错误:允许更多次重试 + 指数退避(最长约2分钟),并标记网络失败待恢复后自动续传
                        boolean netErr = isNetworkError(th);
                        int maxRetry = netErr ? MAX_NETWORK_RETRY : MAX_RETRY;
                        // 地址可能过期(HTTP 403/404/410 等或 HTML 防盗链响应):重新解析地址后继续,
                        // 重置下载重试计数;解析次数有限制(MAX_RE_RESOLVE),避免无限重解析
                        if (!netErr && shouldReResolve(t, th) && t.reResolveCount < MAX_RE_RESOLVE
                                && t.sourceKey != null && t.playFlag != null && t.episodeRawUrl != null) {
                            t.reResolveCount++;
                            retries = 0;
                            Log.i("TVBox-Download", "地址可能过期,重新解析(" + t.reResolveCount + "/" + MAX_RE_RESOLVE + "): "
                                    + t.fileName + " " + t.message);
                            t.message = "地址更新中(" + t.reResolveCount + "/" + MAX_RE_RESOLVE + ")";
                            persist();
                            notifyChanged();
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
                            persist();
                            notifyChanged();
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
                        persist();
                        notifyChanged();
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
     * 重新解析播放地址(经 SourceViewModel.spThreadPool 串行,避免 quickjs 并发卡死)。
     *
     * @return true=解析成功且地址已更新
     */
    private boolean reResolveUrl(DownloadTask t) {
        if (t.sourceKey == null || t.playFlag == null || t.episodeRawUrl == null) return false;
        try {
            java.util.concurrent.Future<String> future = SourceViewModel.spThreadPool.submit(() ->
                    PlayUrlResolver.resolve(t.sourceKey, t.playFlag, t.episodeRawUrl));
            String newUrl = future.get(20, TimeUnit.SECONDS);
            if (newUrl != null && !newUrl.isEmpty() && !newUrl.equals(t.url)) {
                Log.i("TVBox-Download", "重新解析地址成功: " + t.fileName);
                t.url = newUrl;
                // 地址已更新:直链进度作废(URL变了,原Range续传可能无效),分片/已下字节保留由下载逻辑按需处理
                if (t.downloadedBytes > 0 && !t.isHls()) {
                    t.downloadedBytes = 0;
                }
                return true;
            }
            Log.i("TVBox-Download", "重新解析地址无变化/失败,用原地址: " + t.fileName);
        } catch (Throwable th4) {
            Log.i("TVBox-Download", "重新解析地址异常,用原地址: " + t.fileName);
        }
        return false;
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
        Response resp = getDownloadResponse(t.url, headers);
        activeResponses.put(t.id, resp);
        try {
            // 内容级 HLS 识别:代理/伪装 URL 不含 .m3u8,但实际返回的是 m3u8 播放列表
            if (isM3u8Response(resp)) {
                Log.i("TVBox-Download", "内容识别为 m3u8,转 HLS 下载: " + t.fileName);
                resp.close();
                activeResponses.remove(t.id);
                if (t.downloadedBytes > 0) {
                    t.downloadedBytes = 0;
                    deleteQuietly(new File(t.partPath));
                }
                downloadHls(t);
                return;
            }
            int code = resp.code();
            if (code == 200 && t.downloadedBytes > 0) {
                // 服务器不支持断点,从头开始
                t.downloadedBytes = 0;
                deleteQuietly(new File(t.partPath));
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
                        synchronized (tasks) {
                            for (DownloadTask tt : tasks) {
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
                        persist();
                    }
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
            long lastSpeedTime = System.currentTimeMillis();
            long lastSpeedBytes = t.downloadedBytes;
            while ((n = is.read(buf)) > 0) {
                if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    os.flush();
                    os.close();
                    t.speed = 0;
                    persist();
                    notifyChanged();
                    return;
                }
                os.write(buf, 0, n);
                t.downloadedBytes += n;
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
                    persist();
                    notifyChanged();
                }
            }
            os.flush();
            os.close();
            t.speed = 0;
            if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
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
        // 续传起点以磁盘实况为准(不信任 TXT/内存计数):用户可能删过部分分片文件,
        // 若仍用 t.doneSegments 会跳过缺失分片直接合并导致失败。
        File tmpDir = segmentsDirOf(t);
        if (!tmpDir.exists()) tmpDir.mkdirs();
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
            if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                t.speed = 0;
                persist();
                notifyChanged();
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
            writeSegmentsInfo(t, tmpDir, segments, t.doneSegments); // 每片完成即写入TXT进度
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
            persist();
            notifyChanged();
        }
        t.speed = 0;

        // 碎片下载完,进入"文件校验中"。校验以磁盘实况为准:每轮全盘扫描缺失分片并补下,
        // 不信任 TXT 计数(用户可能删过分片文件,计数会失真导致跳过补下直接合并失败)。
        t.message = MSG_VERIFYING;
        persist();
        notifyChanged();
        int repair = 0;
        while (true) {
            // 缺失分片列表:优先读 TXT 逐片状态(精确到片,支持非连续缺失);
            // TXT 无状态行(旧格式)时全盘扫描兜底。
            List<Integer> missing = readMissingSegments(tmpDir, segments.size());
            if (missing == null) {
                missing = new ArrayList<>();
                for (int i = 0; i < segments.size(); i++) {
                    File segFile = new File(tmpDir, String.format("%05d.ts", i));
                    if (!segFile.exists() || segFile.length() <= 0) missing.add(i);
                }
            }
            if (missing.isEmpty()) break; // 全部分片存在,校验通过
            if (repair >= MAX_SEGMENT_REPAIR) {
                throw new IOException("碎片校验不一致,自动补下" + MAX_SEGMENT_REPAIR + "轮后仍缺失(缺 " + missing.size() + " 片,如第"
                        + missing.get(0) + "片)");
            }
            repair++;
            Log.i("TVBox-Download", "碎片校验缺失 " + missing.size() + " 片,第" + repair + "/" + MAX_SEGMENT_REPAIR
                    + "轮补下: " + t.fileName + " 缺失首片=" + missing.get(0));
            boolean allOk = true;
            for (int idx : missing) {
                File segFile = new File(tmpDir, String.format("%05d.ts", idx));
                if (!segFile.exists() || segFile.length() <= 0) {
                    downloadSegment(segments.get(idx), segFile, 0, t); // 失败抛异常由外层重试
                }
                if (segFile.exists() && segFile.length() > 0) {
                    if (t.doneSegments <= idx) t.doneSegments = idx + 1;
                } else {
                    allOk = false; // 仍有缺失,留到下一轮
                }
            }
            writeSegmentsInfo(t, tmpDir, segments, t.doneSegments); // 补下进度+逐片状态写回TXT
            if (allOk) break;
        }
        t.doneSegments = segments.size(); // 全部就绪,进度=已下载分片数
        t.segmentBytes = 0;
        writeSegmentsInfo(t, tmpDir, segments, t.doneSegments);

        // 校验通过,进入"文件合并"
        t.message = MSG_MERGING;
        persist();
        notifyChanged();

        // 合并前空间检查:合并需额外写入约一个最终文件大小的 merged.tmp(分片已占空间),
        // 不足则失败并提示,避免合并中空间耗尽损坏
        long mergeSize = 0;
        for (int i = 0; i < segments.size(); i++) {
            mergeSize += new File(tmpDir, String.format("%05d.ts", i)).length();
        }
        if (mergeSize > 0) {
            try {
                File dir = new File(t.savePath).getParentFile();
                if (dir != null && dir.exists()) {
                    StatFs stat = new StatFs(dir.getAbsolutePath());
                    long free = stat.getAvailableBytes();
                    if (free - mergeSize < MIN_FREE_SPACE) {
                        throw new IOException("磁盘空间不足,无法合并(完成后可用仅 "
                                + formatSize(Math.max(0, free - mergeSize)) + ",需清理约 "
                                + ((MIN_FREE_SPACE - (free - mergeSize) + 1024 * 1024 - 1) / (1024 * 1024)) + "MB)");
                    }
                }
            } catch (IOException e) {
                throw e; // 空间不足直接失败,保留分片现场待清理后重试
            } catch (Throwable ignored) {
            }
        }

        // 合并分片 -> mp4(双阶段原子合并):
        // 1) 先合并到分段目录内的 merged.tmp(过程文件,与成果隔离,崩溃最多损坏它)
        // 2) 完整后 rename 到最终文件(rename 为原子操作,要么成功要么未发生,杜绝半成品最终文件)
        File finalFile = new File(t.savePath);
        if (finalFile.getParentFile() != null && !finalFile.getParentFile().exists()) {
            finalFile.getParentFile().mkdirs();
        }
        File mergeTmp = new File(tmpDir, "merged.tmp");
        OutputStream out = new FileOutputStream(mergeTmp);
        try {
            for (int i = 0; i < segments.size(); i++) {
                File segFile = new File(tmpDir, String.format("%05d.ts", i));
                copyFile(segFile, out);
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
            copyFile(mergeTmp, finalFile);
            deleteQuietly(mergeTmp);
        }
        // 合成完成后清理:删本任务分片目录(父级 tmp 保留)
        deleteSegmentsDir(t);
        t.tmpDir = null;
        t.message = "";
        t.state = DownloadTask.STATE_COMPLETED;
        persist();
        notifyChanged();
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
            File f = new File(tmpDir, SEGMENTS_INFO);
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
    private int readSegmentsInfo(File tmpDir) {
        try {
            File info = new File(tmpDir, SEGMENTS_INFO);
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
            File info = new File(tmpDir, SEGMENTS_INFO);
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
        Map<String, String> headers = baseHeaders();
        if (segDone > 0) {
            headers.put("Range", "bytes=" + segDone + "-");
        }
        Response resp = getDownloadResponse(segUrl, headers);
        activeResponses.put(t.id, resp);
        try {
            int code = resp.code();
            if (code == 416) {
                // Range 超出文件末尾:该分段实际已完整(上次写入完成但进度未更新)。
                // 关闭本次响应,删除残片,不带 Range 从头整段重下,避免重试死循环
                Log.i("TVBox-Download", "分段416(Range超界),整段重下: " + segFile.getName());
                resp.close();
                activeResponses.remove(t.id);
                deleteQuietly(segFile);
                segDone = 0;
                headers.remove("Range");
                resp = getDownloadResponse(segUrl, headers);
                activeResponses.put(t.id, resp);
                code = resp.code();
            }
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
                if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
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
        Response resp = getDownloadResponse(url, baseHeaders());
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
                    Response resp2 = getDownloadResponse(variant, baseHeaders());
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

    /** 任务的分段下载目录:优先任务自己的 tmpDir,否则按 文件目录/tmp/<任务id> 兜底(保证唯一,多任务不共用) */
    private File segmentsDirOf(DownloadTask t) {
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
    private void deleteSegmentsDir(DownloadTask t) {
        deleteRecursive(segmentsDirOf(t));
    }

    /** 用下载专用客户端(更长超时)发起同步请求,调用方负责关闭 Response */
    private Response getDownloadResponse(String url, Map<String, String> headers) throws IOException {
        try {
            Request.Builder builder = new Request.Builder().url(HttpClient.normalizeUrl(url));
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            return downloadClient.newCall(builder.build()).execute();
        } catch (IOException e) {
            throw e;
        } catch (Throwable th) {
            throw new IOException("request build failed: " + th.getMessage(), th);
        }
    }

    /** 判断响应是否为 m3u8 播放列表(按 Content-Type 或内容开头),不消费响应体 */
    private boolean isM3u8Response(Response resp) {
        try {
            String ct = resp.header("Content-Type");
            if (ct != null && (ct.contains("mpegurl") || ct.contains("mpeg-url") || ct.contains("apple"))) {
                return true;
            }
            okio.BufferedSource source = resp.body().source();
            source.request(10);
            okio.Buffer buf = source.getBuffer().clone();
            String head = buf.readUtf8(Math.min(10, buf.size()));
            return head.startsWith("#EXTM3U");
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

    /**
     * 从响应头识别真实文件扩展名:Content-Disposition 的 filename 最可靠,其次按 Content-Type 映射。
     * 识别不出或为音频/未知类型返回 null(保持 URL 判定的扩展名)。
     */
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
