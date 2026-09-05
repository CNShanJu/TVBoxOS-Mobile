package com.github.tvbox.osc.download.internal;

/**
 * 下载任务变更事件（结构性变更：新增/删除/状态机切换/批量变更，UI 据此全量刷新）。
 * <p>
 * 自 common/event 迁入 download 模块（事件归业务模块，改进.txt §2.8）；
 * 进度级高频事件见 {@link DownloadProgressEvent}。
 */
public class DownloadEvent {

    public static final int TYPE_CHANGE = 0;

    public final int type;

    public DownloadEvent(int type) {
        this.type = type;
    }
}
