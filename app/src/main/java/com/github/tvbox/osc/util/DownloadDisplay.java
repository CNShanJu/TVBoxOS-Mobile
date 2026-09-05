package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.download.ArchiveItem;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 下载完成列表展示的纯静态工具(自 DownloadFragment 抽取,等价搬移):
 * 剧集名/清晰度解析、尺寸/速度/进度文本、列表指纹、递归删除。
 * 无 View/Context/Android 依赖,可 JVM 单测。
 */
public final class DownloadDisplay {

    private static final Pattern RESOLUTION = Pattern.compile("(?i)(\\d{3,4}p|4k|2k|8k|sd|hd|fhd|uhd)");

    private DownloadDisplay() {
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** episodeId 最后一段(playIndex) */
    public static String lastSegment(String episodeId) {
        if (episodeId == null) return null;
        int i = episodeId.lastIndexOf('|');
        return i >= 0 ? episodeId.substring(i + 1) : null;
    }

    /** 集数名:优先档案 episodeName(去清晰度后缀),空则按索引推导 第N集,再空用文件名(去扩展名) */
    public static String episodeTitleOf(ArchiveItem it, String fileName) {
        String label = it.episodeName;
        if (isEmpty(label) && it.episodeId != null) {
            String idx = lastSegment(it.episodeId);
            if (idx != null && idx.matches("\\d+")) label = "第" + idx + "集";
        }
        if (isEmpty(label)) {
            label = fileName;
            int dot = label.lastIndexOf('.');
            if (dot > 0) label = label.substring(0, dot);
        }
        return label.replaceAll("(?i)_?(\\d{3,4}p|4k|2k|8k|sd|hd|fhd|uhd)$", "").trim();
    }

    /** 清晰度:从集数名/文件名解析最后一个 480P/720P/1080P/4K... 段;无则 null */
    public static String resolutionOf(ArchiveItem it, String fileName) {
        String s = isEmpty(it.episodeName) ? fileName : it.episodeName;
        if (isEmpty(s)) return null;
        Matcher m = RESOLUTION.matcher(s);
        String last = null;
        while (m.find()) last = m.group(1);
        return last == null ? null : last.toUpperCase();
    }

    /** 下载完成列表指纹:文件路径 + 大小 */
    public static String doneSignature(List<VideoInfo> files) {
        StringBuilder sb = new StringBuilder();
        for (VideoInfo v : files) {
            sb.append(v.getPath()).append('|').append(v.getSize()).append(';');
        }
        return sb.toString();
    }

    /** 任务行"大小 · 进度"文本:分段 N/M + 已下/总量(有字节)+ 百分比;失败附原因 */
    public static String buildPercentText(DownloadTask t) {
        StringBuilder sb = new StringBuilder();
        if (t.isHls()) {
            sb.append("分段 ").append(t.doneSegments).append("/").append(t.totalSegments);
        }
        if (t.totalBytes > 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(formatSize(t.downloadedBytes)).append("/").append(formatSize(t.totalBytes));
        }
        sb.append(" (").append(t.getProgressPercent()).append("%)");
        if (t.state == DownloadTask.STATE_FAILED && t.message != null && !t.message.isEmpty()) {
            sb.append(" 失败:").append(t.message);
        }
        return sb.toString();
    }

    /** 实时网速文本 */
    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.1fMB/s", bytesPerSec / 1024.0 / 1024.0);
        }
        if (bytesPerSec >= 1024) {
            return String.format("%.0fKB/s", bytesPerSec / 1024.0);
        }
        return bytesPerSec + "B/s";
    }

    /** 文件大小文本 */
    public static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + "MB";
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 递归删除文件/目录 */
    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteRecursive(c);
            }
        }
        f.delete();
    }
}
