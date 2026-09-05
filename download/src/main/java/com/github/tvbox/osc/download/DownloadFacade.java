package com.github.tvbox.osc.download;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.log.LogEntry;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.util.DownloadManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 下载对外门面（Download-Facade）：详情页/下载页等外部程序唯一的下载 API 入口。
 * <ul>
 *   <li>查询快照：剧集级展示态 / 视频级聚合（对外 5 态，内部状态不外泄）；</li>
 *   <li>事件订阅：状态/进度变化（DownloadManager 已去抖 500ms 广播）；</li>
 *   <li>任务日志：委托 LogStore.queryByTask（7 天）；</li>
 *   <li>档案管理：已下载档案长期保留，删文件联动删档案。</li>
 * </ul>
 * 展示态：{@link #ST_NOT_DOWNLOADED} 未下载 / {@link #ST_DOWNLOADED} 已下载 /
 * {@link #ST_DOWNLOADING} 下载中（含排队/校验/合并/网络暂停/调度暂停）/
 * {@link #ST_PAUSED} 已暂停（手动）/ {@link #ST_FAILED} 失败可重试。
 */
public final class DownloadFacade {

    public static final int ST_NOT_DOWNLOADED = 0;
    public static final int ST_DOWNLOADED = 1;
    public static final int ST_DOWNLOADING = 2;
    public static final int ST_PAUSED = 3;
    public static final int ST_FAILED = 4;

    public interface DownloadStatusListener {
        /** 下载状态/进度变化（去抖 500ms 合并后回调，主线程） */
        void onChanged();
    }

    private static final DownloadFacade instance = new DownloadFacade();
    private final List<DownloadStatusListener> listeners = new CopyOnWriteArrayList<>();

    private DownloadFacade() {
        EventBus.getDefault().register(this);
    }

    public static DownloadFacade get() {
        return instance;
    }

    // ------------------------------------------------------------------
    // 查询快照
    // ------------------------------------------------------------------

    /**
     * 剧集级展示态（一次快照）：
     *
     * @param videoId      sourceKey|vodId
     * @param playFlag     线路名（episodeId 组成部分）
     * @param episodeCount 集数
     */
    public int[] queryEpisodes(String videoId, String playFlag, int episodeCount) {
        int[] states = new int[Math.max(0, episodeCount)];
        List<DownloadTask> tasks = DownloadManager.get().getTasks();
        for (int i = 0; i < states.length; i++) {
            String episodeId = buildEpisodeId(videoId, playFlag, i);
            DownloadTask t = findTask(tasks, episodeId);
            if (t != null) {
                states[i] = mapTaskState(t);
                continue;
            }
            states[i] = DownloadArchive.get().isDownloaded(episodeId) ? ST_DOWNLOADED : ST_NOT_DOWNLOADED;
        }
        return states;
    }

    /** 视频级聚合（已下载/下载中/已暂停/失败/未下载） */
    public VideoSummary queryVideo(String videoId, String playFlag, int episodeCount) {
        VideoSummary sum = new VideoSummary();
        sum.total = Math.max(0, episodeCount);
        int[] states = queryEpisodes(videoId, playFlag, episodeCount);
        for (int s : states) {
            switch (s) {
                case ST_DOWNLOADED:
                    sum.downloaded++;
                    break;
                case ST_DOWNLOADING:
                    sum.downloading++;
                    break;
                case ST_PAUSED:
                    sum.paused++;
                    break;
                case ST_FAILED:
                    sum.failed++;
                    break;
                default:
                    sum.notDownloaded++;
            }
        }
        return sum;
    }

    /** 单任务详情（进度等） */
    public DownloadTask getTask(String episodeId) {
        if (episodeId == null) return null;
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (episodeId.equals(t.episodeId)) return t;
        }
        return null;
    }

    public List<DownloadTask> getTasks() {
        return DownloadManager.get().getTasks();
    }

    // ------------------------------------------------------------------
    // 入队 / 队列控制（改进.txt 第一阶段:UI 只走 Facade,不再直接调 DownloadManager）
    // ------------------------------------------------------------------

    /** 入队(与详情页原 DownloadManager.enqueue 全参数一致;headers 可为 null) */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, java.util.Map<String, String> headers,
                           String sourceName, String vodName, String episodeName) {
        return DownloadManager.get().enqueue(url, sourceKey, playFlag, episodeRawUrl,
                episodeId, pic, headers, sourceName, vodName, episodeName);
    }

    /** 按任务对象暂停 */
    public void pause(DownloadTask t) {
        if (t != null) DownloadManager.get().pause(t);
    }

    /** 按任务对象恢复 */
    public void resume(DownloadTask t) {
        if (t != null) DownloadManager.get().resume(t);
    }

    /** 暂停全部 */
    public void pauseAll() {
        DownloadManager.get().pauseAll();
    }

    /** 恢复全部 */
    public void startAll() {
        DownloadManager.get().startAll();
    }

    /** 删除任务(不删文件) */
    public void remove(DownloadTask t) {
        if (t != null) DownloadManager.get().remove(t);
    }

    /** 删除任务(deleteFiles=true 连文件一起删) */
    public void remove(DownloadTask t, boolean deleteFiles) {
        if (t != null) DownloadManager.get().remove(t, deleteFiles);
    }

    /** 按本地路径清理下载任务(本地文件删除联动),返回清理条数 */
    public int removeTasksByPath(String savePath) {
        return DownloadManager.get().removeTasksByPath(savePath);
    }

    /** 按本地路径删除档案(与 removeTasksByPath 配套,一次调用完成文件联动) */
    public boolean removeArchiveByPath(String savePath) {
        return DownloadArchive.get().removeByPath(savePath);
    }

    /** 海报本地文件(不存在返回 null) */
    public java.io.File getPosterFile(String vodName) {
        return DownloadManager.getPosterFile(vodName);
    }

    /** 异步拉取并缓存海报文件 */
    public void ensurePosterAsync(String pic, String vodName) {
        DownloadManager.get().ensurePosterAsync(pic, vodName);
    }

    // ------------------------------------------------------------------
    // 排队 / 插队（4.4，按 episodeId 操作）
    // ------------------------------------------------------------------

    /** 排队插队(温和): 提到队首, 不打断运行中任务 */
    public void moveToFront(String episodeId) {
        DownloadTask t = getTask(episodeId);
        if (t != null) DownloadManager.get().moveToFront(t);
    }

    /** 设置优先级(HIGH/NORMAL/LOW); 置 HIGH 且并发满时抢占让位(被抢占者排最前) */
    public void setPriority(String episodeId, int level) {
        DownloadTask t = getTask(episodeId);
        if (t != null) DownloadManager.get().setPriority(t, level);
    }

    // ------------------------------------------------------------------
    // 任务日志（委托 LogStore，7 天）
    // ------------------------------------------------------------------

    public List<LogEntry> getTaskLog(String episodeId, int limit, int offset) {
        if (LogStore.get() == null) return null;
        return LogStore.get().queryByTask(episodeId, limit, offset);
    }

    // ------------------------------------------------------------------
    // 事件订阅
    // ------------------------------------------------------------------

    public void register(DownloadStatusListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void unregister(DownloadStatusListener l) {
        listeners.remove(l);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent e) {
        if (e == null || e.type != DownloadEvent.TYPE_CHANGE) return;
        for (DownloadStatusListener l : listeners) {
            try {
                l.onChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 档案管理（长期保留）
    // ------------------------------------------------------------------

    /** 某视频的已下载列表（下载完成 tab 数据源） */
    public List<ArchiveItem> queryArchive(String videoId) {
        return DownloadArchive.get().queryByVideo(videoId);
    }

    public ArchiveItem getArchive(String episodeId) {
        return DownloadArchive.get().get(episodeId);
    }

    /** 删除档案（deleteFile=true 连文件一起删） */
    public boolean deleteArchive(String episodeId, boolean deleteFile) {
        return DownloadArchive.get().remove(episodeId, deleteFile);
    }

    /** 重命名成品文件（同步更新档案） */
    public boolean renameArchive(String episodeId, String newName) {
        return DownloadArchive.get().rename(episodeId, newName);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static String buildEpisodeId(String videoId, String playFlag, int playIndex) {
        return (videoId == null ? "" : videoId) + "|"
                + (playFlag == null ? "" : playFlag) + "|" + playIndex;
    }

    private static DownloadTask findTask(List<DownloadTask> tasks, String episodeId) {
        if (episodeId == null || episodeId.isEmpty()) return null;
        for (DownloadTask t : tasks) {
            if (episodeId.equals(t.episodeId)) return t;
        }
        return null;
    }

    private static int mapTaskState(DownloadTask t) {
        switch (t.state) {
            case DownloadTask.STATE_COMPLETED:
                return t.savePath != null && new File(t.savePath).exists() ? ST_DOWNLOADED : ST_NOT_DOWNLOADED;
            case DownloadTask.STATE_PAUSED:
                return ST_PAUSED;
            case DownloadTask.STATE_FAILED:
                return ST_FAILED;
            default:
                // WAITING / DOWNLOADING / SYSTEM_PAUSED / NETWORK_PAUSED
                return ST_DOWNLOADING;
        }
    }
}
