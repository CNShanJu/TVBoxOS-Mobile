package com.github.tvbox.osc.download;

/**
 * 下载进度事件(轻量、高频):只携带发生进度的任务 id,供 UI(下载页)对该任务做
 * 增量局部刷新(notifyItemChanged),不触发全量重聚合/重建。
 * <p>
 * 与 {@link DownloadEvent}(结构性变更:新增/删除/状态机切换/批量变更,UI 全量刷新)区分:
 * 进度类变更(直链窗口 / HLS 每分片 / 合并进度)由 DownloadManager.flushProgress 节流后发本事件。
 */
public class DownloadProgressEvent {

    /** 发生进度的任务 id(与 DownloadTask.id 一致;可为 null,UI 侧直接忽略) */
    public final String taskId;

    public DownloadProgressEvent(String taskId) {
        this.taskId = taskId;
    }
}
