package com.github.tvbox.osc.download.internal;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.StatFs;


import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.state.SystemEvent;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.config.KeyValueStore;
import com.github.tvbox.osc.config.PrefsDataStore;

import java.io.File;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 决策器（Policy-Decider）：并发数 / 仅WiFi / 磁盘水位等策略规则。
 * 只输出"允许/拒绝/限值"，不直接操作任务状态（调度由 DownloadScheduler 执行）。
 */
public class DownloadPolicy {

    /** 磁盘空间安全余量:下载完成后至少保留的可用空间(避免手机因空间耗尽卡死/无法开机) */
    static final long MIN_FREE_SPACE = 1536L * 1024 * 1024; // 1.5GB
    /** 磁盘空间不足时的兜底检查:可用空间低于该值直接拒绝(防止极端情况) */
    static final long MIN_ABSOLUTE_FREE = 512L * 1024 * 1024; // 512MB

    private final DownloadManager dm;

    /** 最大并发下载数(1-5) */
    private volatile int maxConcurrent = 3;

    /** 旧 Hawk 存量一次性迁移(下载并发/仅WiFi → PrefsDataStore;Policy 类加载时执行一次) */
    private static volatile boolean migrated = false;

    private static synchronized void migrateLegacyOnce() {
        if (migrated) return;
        migrated = true;
        try {
            if (KeyValueStore.contains(DownloadManager.HAWK_MAX_CONCURRENT)) {
                int v = KeyValueStore.get(DownloadManager.HAWK_MAX_CONCURRENT, 3);
                PrefsDataStore.put(DownloadManager.HAWK_MAX_CONCURRENT, v);
                KeyValueStore.delete(DownloadManager.HAWK_MAX_CONCURRENT);
            }
            if (KeyValueStore.contains(DownloadManager.HAWK_WIFI_ONLY)) {
                boolean v = KeyValueStore.get(DownloadManager.HAWK_WIFI_ONLY, true);
                PrefsDataStore.put(DownloadManager.HAWK_WIFI_ONLY, v);
                KeyValueStore.delete(DownloadManager.HAWK_WIFI_ONLY);
            }
        } catch (Throwable ignored) {
        }
    }

    DownloadPolicy(DownloadManager dm) {
        this.dm = dm;
        migrateLegacyOnce();
        int savedConcurrent = 3;
        try {
            savedConcurrent = PrefsDataStore.getInt(DownloadManager.HAWK_MAX_CONCURRENT, 3);
        } catch (Throwable ignored) {
        }
        maxConcurrent = Math.max(1, Math.min(5, savedConcurrent));
        // Bug1: 订阅全局状态监控(②)的网络事件——仅WiFi开启时切蜂窝/断网 → 暂停全部;
        // WiFi 恢复 → 自动恢复。决策器只下发指令,执行在 Scheduler。
        SystemStateMonitor monitor = SystemStateMonitor.get();
        if (monitor != null) {
            monitor.register((SystemEvent e) -> {
                if (!SystemStateMonitor.TYPE_NETWORK.equals(e.type)) return;
                if (!isWifiOnly()) return;
                if (SystemStateMonitor.VAL_CELLULAR.equals(e.value)
                        || SystemStateMonitor.VAL_NONE.equals(e.value)) {
                    dm.scheduler.pauseAllNetwork();
                } else if (SystemStateMonitor.VAL_WIFI.equals(e.value)) {
                    dm.scheduler.resumeAllNetwork();
                }
            }, SystemStateMonitor.TYPE_NETWORK);
            // Bug4: 存储权限被撤销 → 暂停全部(避免半截文件)
            monitor.register((SystemEvent e) -> {
                if (!SystemStateMonitor.TYPE_PERMISSION.equals(e.type)) return;
                if (SystemStateMonitor.VAL_PERMISSION_REVOKED.equals(e.value)) {
                    dm.scheduler.pauseAllPermission();
                }
            }, SystemStateMonitor.TYPE_PERMISSION);
        }
    }

    int getMaxConcurrent() {
        return maxConcurrent;
    }

    /** 设置最大并发数(1-5),触发重新调度 */
    void setMaxConcurrent(int n) {
        int v = Math.max(1, Math.min(5, n));
        maxConcurrent = v;
        try {
            PrefsDataStore.put(DownloadManager.HAWK_MAX_CONCURRENT, v);
        } catch (Throwable ignored) {
        }
        dm.notifyChanged();
        dm.wakeWorker();
    }

    /** 是否仅 WiFi 下载(默认开启,移动网络下载前需强提醒确认) */
    boolean isWifiOnly() {
        try {
            return PrefsDataStore.getBoolean(DownloadManager.HAWK_WIFI_ONLY, true);
        } catch (Throwable th) {
            return true;
        }
    }

    void setWifiOnly(boolean wifiOnly) {
        try {
            PrefsDataStore.put(DownloadManager.HAWK_WIFI_ONLY, wifiOnly);
        } catch (Throwable ignored) {
        }
    }

    /** 当前网络是否为移动网络(蜂窝) */
    static boolean isMobileNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) DownloadManager.appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 下载前磁盘空间预检:估算文件大小,检查保存目录所在磁盘剩余空间。
     * 要求:下载完成后可用空间仍 ≥ MIN_FREE_SPACE(1.5GB),不足则拒绝启动并提示需清理的量级。
     *
     * @return null=空间充足;否则返回错误提示文案
     */
    String checkDiskSpace(DownloadTask t) {
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
                    Response resp = dm.executor.getDownloadResponse(t.url, dm.executor.baseHeaders(t));
                    dm.activeResponses.put(t.id, resp);
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
                        dm.activeResponses.remove(t.id);
                        resp.close();
                    }
                } catch (Throwable ignored) {
                }
                return 0;
            }
            // 直链:HEAD 请求拿 Content-Length(带任务请求头,防盗链源 HEAD 也可能校验)
            Request.Builder headBuilder = new Request.Builder().url(t.url)
                    .header("Range", "bytes=0-0"); // 部分服务器不支持 HEAD,用首字节 Range 探测
            Map<String, String> hdrs = dm.executor.baseHeaders(t);
            for (Map.Entry<String, String> e : hdrs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headBuilder.header(e.getKey(), e.getValue());
                }
            }
            Response resp = dm.downloadClient.newCall(headBuilder.build()).execute();
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
}
