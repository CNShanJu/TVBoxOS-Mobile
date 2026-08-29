package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.event.DownloadEvent;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * 下载管理器（门面）——5.1 已按职责拆分为五个内部组件，行为零变化：
 * <ul>
 *   <li>{@link DownloadStore}    任务记录器：任务列表 / 查询 / 对账 / 海报</li>
 *   <li>{@link DownloadScheduler} 任务调度器：队列 / 并发 / 启停 / 生命周期</li>
 *   <li>{@link DownloadExecutor}  下载执行单元：直链 / HLS 分片 / 校验 / 合并</li>
 *   <li>{@link DownloadPolicy}    决策器：并发 / 仅WiFi / 磁盘水位</li>
 *   <li>{@link FileCleaner}       文件清理器：删除 / 复制 / 保存目录</li>
 * </ul>
 * 对外 API（enqueue/pause/resume/remove/查询等）保持不变，调用方（详情页/下载页/配置门面）无感。
 */
public class DownloadManager {

    static final String HAWK_KEY = "download_tasks_v1";
    static final String HAWK_MAX_CONCURRENT = "download_max_concurrent";
    static final String HAWK_WIFI_ONLY = "download_wifi_only";
    static final int BUFFER = 64 * 1024;

    private static DownloadManager instance;

    /** 注入的 application context（独立模块 :download，App 启动时 init 注入） */
    static volatile android.content.Context appContext;

    final List<DownloadTask> tasks = new ArrayList<>();
    final Object lock = new Object();

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
    final Map<String, Response> activeResponses = new ConcurrentHashMap<>();
    /** 下载专用客户端:超时比播放请求长(慢速源/本地代理链不易超时) */
    static final OkHttpClient downloadClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(new HttpClient.UserAgentInterceptor())
            .build();

    /** 失败自动重试次数(不含首次) */
    static final int MAX_RETRY = 2;
    /** 网络错误(断网/切网)重试次数上限(不含首次),配合退避最长约2分钟 */
    static final int MAX_NETWORK_RETRY = 8;
    /** 地址可能过期时重新解析地址的次数上限(每次解析后重置下载重试计数) */
    static final int MAX_RE_RESOLVE = 3;
    /** 碎片校验后自动补下缺失分片的最大轮数 */
    static final int MAX_SEGMENT_REPAIR = 3;

    /** 分段信息 TXT 文件名 */
    public static final String SEGMENTS_INFO = "segments.txt";
    /** 合并阶段状态文案(下载中任务的 message 标记) */
    public static final String MSG_VERIFYING = "文件校验中";
    public static final String MSG_MERGING = "文件合并中";
    public static final String MSG_REMUX = "文件封装中";

    /** 变更事件去抖:进度高频刷新合并为至多每 500ms 广播一次 */
    private final Handler notifyHandler = new Handler(Looper.getMainLooper());
    private final Runnable notifyRunnable = () ->
            EventBus.getDefault().post(new DownloadEvent(DownloadEvent.TYPE_CHANGE));

    // ------------------------------------------------------------------
    // 内部组件（5.1 拆分）
    // ------------------------------------------------------------------

    final DownloadStore store;
    final DownloadScheduler scheduler;
    final DownloadExecutor executor;
    final DownloadPolicy policy;
    /** 已下载档案（长期保留，5.3） */
    final com.github.tvbox.osc.download.DownloadArchive archive;

    private DownloadManager() {
        store = new DownloadStore(this);
        scheduler = new DownloadScheduler(this);
        executor = new DownloadExecutor(this);
        policy = new DownloadPolicy(this);
        archive = com.github.tvbox.osc.download.DownloadArchive.get();
        store.load();
        // Bug5: 孤儿 tmpDir 回收(启动时任务已加载,无写入中,安全)
        FileCleaner.cleanupOrphanTmpDirs(tasks);
        scheduler.startWorker();
        scheduler.registerNetworkCallback();
    }

    public static synchronized DownloadManager get() {
        if (instance == null) {
            instance = new DownloadManager();
        }
        return instance;
    }

    /** App 启动时调用一次：注入 context（保存目录/海报/网络监听等用，独立模块不依赖 app 类） */
    public static void init(android.content.Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        FileCleaner.setAppContext(appContext);
        DownloadStore.setAppContext(appContext);
        com.github.tvbox.osc.download.DownloadNotifier.init(context);
    }

    // ------------------------------------------------------------------
    // 基础设施（组件共用）
    // ------------------------------------------------------------------

    /** 持久化(异步+合并):后台单线程写 Hawk,且始终写最新快照 */
    void persist() {
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

    void notifyChanged() {
        // 去抖:高频进度事件(每任务每800ms一次)合并,避免下载页被刷屏
        notifyHandler.removeCallbacks(notifyRunnable);
        notifyHandler.postDelayed(notifyRunnable, 500);
        // 前台服务保活:调度状态变化后同步(有下载中→启动/刷新, 无→停止)
        updateForegroundService();
    }

    /** 前台服务保活(可选增强):存在下载中/等待任务时拉起,全部结束停止;进度通知去抖由 persist 频控 */
    private void updateForegroundService() {
        if (appContext == null) return;
        try {
            List<DownloadTask> snapshot;
            synchronized (tasks) {
                snapshot = new ArrayList<>(tasks);
            }
            com.github.tvbox.osc.download.DownloadForegroundService.startIfNeeded(appContext, snapshot);
            com.github.tvbox.osc.download.DownloadForegroundService.stopIfIdle(appContext, snapshot);
        } catch (Throwable ignored) {
        }
    }

    void wakeWorker() {
        scheduler.wakeWorker();
    }

    String sanitize(String name) {
        return sanitizeName(name);
    }

    /** 去除文件名的非法字符;null/空白返回空串,由调用方决定兜底名 */
    public static String sanitizeName(String name) {
        if (name == null) return "";
        String n = name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n;
    }

    // ------------------------------------------------------------------
    // 对外接口（委托组件）
    // ------------------------------------------------------------------

    /** 任务快照(安全遍历) */
    public List<DownloadTask> getTasks() {
        return store.getTasks();
    }

    /** 新增下载任务(简化入口,不含重新解析信息) */
    public boolean enqueue(String url, String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, null, null, null, null, null, null, sourceName, vodName, episodeName);
    }

    /** 新增下载任务(含 EpisodeId 统一剧集标识) */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, null, null, sourceName, vodName, episodeName);
    }

    /** 带 EpisodeId 与封面图 URL 的入队 */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, null, sourceName, vodName, episodeName);
    }

    /** 带 EpisodeId、封面图 URL 与解析请求头(防盗链源下载必须携带)的入队 */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, Map<String, String> headers,
                           String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, headers, sourceName, vodName, episodeName);
    }

    /** 新增下载任务(不含 EpisodeId) */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, null, null, null, sourceName, vodName, episodeName);
    }

    /** 暂停(用户手动) */
    public void pause(DownloadTask t) {
        scheduler.pause(t);
    }

    /** 继续:恢复单个暂停/失败任务 */
    public void resume(DownloadTask t) {
        scheduler.resume(t);
    }

    /** 排队插队(温和): 提到队首(该优先级最前), 不打断运行中任务 */
    public void moveToFront(DownloadTask t) {
        scheduler.moveToFront(t);
    }

    /** 设置优先级(HIGH/NORMAL/LOW); 置 HIGH 且并发满时抢占让位(被抢占者排最前) */
    public void setPriority(DownloadTask t, int level) {
        scheduler.setPriority(t, level);
    }

    /** 全部暂停 */
    public void pauseAll() {
        scheduler.pauseAll();
    }

    /** 全部开始 */
    public void startAll() {
        scheduler.startAll();
    }

    /** 删除任务(默认只删记录保留碎片——Bug2: 重入队同 episodeId 复用碎片续传,不白下) */
    public void remove(DownloadTask t) {
        scheduler.remove(t, false);
    }

    /** 删除任务(deleteFiles=true 连本地文件一起删;false 只删记录保留文件) */
    public void remove(DownloadTask t, boolean deleteFiles) {
        scheduler.remove(t, deleteFiles);
    }

    /** 查询某集下载状态(0=无记录,1=已下载且文件存在,2=已有任务) */
    public int getEpisodeDownloadState(String sourceName, String vodName, String episodeName) {
        return store.getEpisodeDownloadState(sourceName, vodName, episodeName);
    }

    /** 清理"已完成但文件已不存在"的失效记录 */
    public int pruneMissingCompleted() {
        return store.pruneMissingCompleted();
    }

    // ------------------------------------------------------------------
    // 配置（委托决策器）
    // ------------------------------------------------------------------

    public int getMaxConcurrent() {
        return policy.getMaxConcurrent();
    }

    /** 设置最大并发数(1-5),触发重新调度 */
    public void setMaxConcurrent(int n) {
        policy.setMaxConcurrent(n);
    }

    /** 每任务限速(字节/秒;0=不限速)。5.4 增强,仅内存生效(重启需重新设置) */
    public void setSpeedLimit(DownloadTask t, long bytesPerSecond) {
        if (t != null) {
            t.speedLimit = Math.max(0, bytesPerSecond);
        }
    }

    /** 是否仅 WiFi 下载(默认开启) */
    public boolean isWifiOnly() {
        return policy.isWifiOnly();
    }

    public void setWifiOnly(boolean wifiOnly) {
        policy.setWifiOnly(wifiOnly);
    }

    /** 当前网络是否为移动网络(蜂窝) */
    public static boolean isMobileNetwork() {
        return DownloadPolicy.isMobileNetwork();
    }

    // ------------------------------------------------------------------
    // 存储 / 海报（委托组件）
    // ------------------------------------------------------------------

    /** 下载保存根目录 */
    public static File getSaveDir() {
        return FileCleaner.getSaveDir();
    }

    /** 剧集海报目录:应用私有目录 poster/<剧名> */
    public static File getPosterDir(String vodName) {
        return DownloadStore.getPosterDir(vodName);
    }

    /** 剧集海报本地文件:已存在返回 File,否则返回 null */
    public static File getPosterFile(String vodName) {
        return DownloadStore.getPosterFile(vodName);
    }

    /** 确保剧集海报已下载到本地 */
    public void ensurePosterAsync(String pic, String vodName) {
        store.ensurePosterAsync(pic, vodName);
    }
}
