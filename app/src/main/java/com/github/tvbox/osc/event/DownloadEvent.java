package com.github.tvbox.osc.event;

/**
 * 下载任务变更事件(新增/进度/暂停/完成/删除),用于刷新下载页
 */
public class DownloadEvent {

    public static final int TYPE_CHANGE = 0;

    public final int type;

    public DownloadEvent(int type) {
        this.type = type;
    }
}
