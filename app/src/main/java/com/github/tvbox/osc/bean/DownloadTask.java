package com.github.tvbox.osc.bean;

/**
 * 下载任务(支持直链与 HLS m3u8,断点续传)
 */
public class DownloadTask {

    public static final int STATE_WAITING = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_COMPLETED = 3;
    public static final int STATE_FAILED = 4;

    public String id;
    public String url;
    /** 来源名称(如 饭太硬),作为一级目录 */
    public String sourceName;
    /** 剧名,作为二级目录与显示分组 */
    public String vodName;
    /** 名称分组(兼容旧字段,现等同剧名) */
    public String groupName;
    /** 保存文件名(剧名_第几集.mp4) */
    public String fileName;
    /** 最终文件路径 */
    public String savePath;
    /** 临时文件(.part) */
    public String partPath;
    /** m3u8 临时分片目录(下载完成后删除) */
    public String tmpDir;

    // 直链下载进度(字节)
    public long totalBytes;
    public long downloadedBytes;

    // HLS 下载进度(分段)
    public int totalSegments;
    public int doneSegments;
    public long segmentBytes; // 当前分段已下载字节(用于段内断点续传)

    public int state = STATE_WAITING;
    public String message = "";
    public long createTime = System.currentTimeMillis();

    public boolean isHls() {
        return totalSegments > 0;
    }

    public int getProgressPercent() {
        if (isHls()) {
            if (totalSegments <= 0) return 0;
            return (int) (100L * doneSegments / totalSegments);
        }
        if (totalBytes <= 0) return 0;
        return (int) (100L * downloadedBytes / totalBytes);
    }
}
