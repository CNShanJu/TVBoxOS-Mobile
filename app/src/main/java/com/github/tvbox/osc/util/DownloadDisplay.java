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

    // ── 任务行状态展示(纯映射;颜色由 UI 按色调键取) ──

    /** 状态行色调:错误(红)/次要(灰)/下载中高亮 */
    public enum StatusTone { ERROR, MUTED, ACTIVE }

    private static boolean msgIs(String msg, String stage) {
        return msg != null && msg.startsWith(stage);
    }

    /** 收尾阶段(合并x%/剩K片)message 自带进度,不再追加整体百分比 */
    public static boolean stageMessageOwnsProgress(String msg) {
        return msgIs(msg, com.github.tvbox.osc.download.DownloadFacade.MSG_MERGING)
                || msgIs(msg, com.github.tvbox.osc.download.DownloadFacade.MSG_REPAIRING);
    }

    /** 状态行文本:失败/已暂停/网络中断/排队中/等待中/已取消/收尾 message/下载中 */
    public static String statusTextOf(DownloadTask t) {
        switch (t.state) {
            case DownloadTask.STATE_FAILED:
                return "失败";
            case DownloadTask.STATE_PAUSED:
                return "已暂停";
            case DownloadTask.STATE_NETWORK_PAUSED:
                return "网络中断";
            case DownloadTask.STATE_SYSTEM_PAUSED:
                return "排队中";
            case DownloadTask.STATE_WAITING:
                return "等待中";
            case DownloadTask.STATE_CANCELLED:
                return "已取消";
            default:
                break;
        }
        String msg = t.message;
        if (msgIs(msg, com.github.tvbox.osc.download.DownloadFacade.MSG_REPAIRING)
                || msgIs(msg, com.github.tvbox.osc.download.DownloadFacade.MSG_VERIFYING)
                || msgIs(msg, com.github.tvbox.osc.download.DownloadFacade.MSG_MERGING)
                || msgIs(msg, com.github.tvbox.osc.download.DownloadFacade.MSG_REMUX)) {
            return msg;
        }
        return "下载中";
    }

    /** 状态行色调键:失败=ERROR;各暂停/取消=次要;下载中/收尾=ACTIVE */
    public static StatusTone statusToneOf(DownloadTask t) {
        if (t.state == DownloadTask.STATE_FAILED) return StatusTone.ERROR;
        switch (t.state) {
            case DownloadTask.STATE_PAUSED:
            case DownloadTask.STATE_NETWORK_PAUSED:
            case DownloadTask.STATE_SYSTEM_PAUSED:
            case DownloadTask.STATE_WAITING:
            case DownloadTask.STATE_CANCELLED:
                return StatusTone.MUTED;
            default:
                return StatusTone.ACTIVE;
        }
    }

    /** 是否显示实时网速:真正下载中且非收尾阶段(message 非 校验/合并/封装/补片) */
    public static boolean shouldShowSpeed(DownloadTask t) {
        if (t.state != DownloadTask.STATE_DOWNLOADING || t.message == null) return false;
        return !stageMessageOwnsProgress(t.message)
                && !msgIs(t.message, com.github.tvbox.osc.download.DownloadFacade.MSG_VERIFYING)
                && !msgIs(t.message, com.github.tvbox.osc.download.DownloadFacade.MSG_REMUX);
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

    // ── 任务行文本拼装(纯;convert 内只做 setText) ──

    /** 行1:剧名 · 集名(集名与剧名相同或为空时不追加) */
    public static String titleText(DownloadTask t) {
        String vodName = t.vodName == null ? "" : t.vodName;
        String ep = t.episodeName;
        if (ep != null && !ep.isEmpty() && !ep.equals(t.vodName)) {
            return vodName + " · " + ep;
        }
        return vodName;
    }

    /** 状态行完整文本:状态(+整体百分比,收尾阶段省略)+ 实时网速(仅下载中非收尾) */
    public static String statusLine(DownloadTask t) {
        String status = statusTextOf(t);
        boolean stageOwns = stageMessageOwnsProgress(t.message);
        String line = stageOwns
                ? status
                : status + " · " + t.getProgressPercent() + "%";
        if (shouldShowSpeed(t) && t.speed > 0) {
            line += " · " + formatSpeed(t.speed);
        }
        return line;
    }

    /** 行4:来源文本(空来源显示"未知") */
    public static String sourceText(String sourceName) {
        String src = sourceName == null ? "" : sourceName;
        return "来源 " + (src.isEmpty() ? "未知" : src);
    }

    /** 左滑暂停/继续按钮文案:暂停中=继续,失败=重试,其余=暂停 */
    public static String swipeActionText(DownloadTask t) {
        if (t.state == DownloadTask.STATE_PAUSED) return "继续";
        if (t.state == DownloadTask.STATE_FAILED) return "重试";
        return "暂停";
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
