package com.github.tvbox.osc.log;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * logcat 原始流捕获（只含本应用日志）：`logcat --uid=<本应用uid>`(老系统退回 --pid=<本进程>)
 * 只抓当前应用，绝不抓其他应用/系统日志；默认只保留 INFO 及以上级别(避免本应用自身的 V/D 刷屏全量落盘)，
 * 日志级别调为 DEBUG 时才会保留 V/D 全量。
 * <p>
 * 按天写入 filesDir/app_logs/logcat-yyyy-MM-dd.log（与 AppLog 共用目录，过渡期
 * 现有 LogActivity 仍可展示），单文件超 8MB 滚动分段、目录总大小上限 48MB 自动清理、7 天前文件删除。
 * <p>
 * 与业务日志（Room）互补：业务日志结构化可筛选，这里保留原始 logcat 流（"全部日志"）。
 * 开关由 {@link LogStore#setEnabled(boolean)} 联动（默认关）。
 * Context 由 {@link LogStore#init(Context)} 注入（独立模块，不依赖 app 类）。
 */
public final class LogcatCapture {

    /** 与 AppLog 共用目录（app_logs），前缀 logcat- 区分 */
    private static final String DIR = "app_logs";
    private static final String PREFIX = "logcat-";
    private static final String SUFFIX = ".log";
    private static final int RETENTION_DAYS = 7;
    private static final int BATCH_LINES = 100;
    private static final long FLUSH_MS = 1500;
    /**
     * 单个 logcat 文件大小上限(字节)。超过后滚动成 logcat-yyyy-MM-dd.1.log 等分段文件,
     * 避免某一天日志量爆炸时单个文件无限增长(曾导致应用占用存储激增)。
     */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    /** logcat 目录总大小上限(字节):所有分段/日期文件合计超限后删除最旧文件 */
    private static final long MAX_TOTAL_BYTES = 48L * 1024 * 1024;
    private static final long DAY_MS = 24L * 3600 * 1000;

    private static final Object LOCK = new Object();
    private static volatile Process process;
    private static volatile Thread thread;
    private static volatile Context appContext;

    private LogcatCapture() {
    }

    /** 由 LogStore.init 注入 application context（独立模块不依赖 app 类） */
    static void setAppContext(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    /** 包内可见: LogStore.export 需要 cacheDir */
    static Context appContext() {
        return appContext;
    }

    /** 启动捕获（幂等）；未注入 context（模块未初始化/降级模式）时静默不启动 */
    public static void start() {
        if (appContext() == null) return;
        if (thread != null && thread.isAlive()) return;
        synchronized (LOCK) {
            if (thread != null && thread.isAlive()) return;
            try {
                File dir = logDir();
                if (!dir.exists()) dir.mkdirs();
                // 启动即先做一次清理:把上次遗留的超限/超期文件收掉,避免一开启就占满存储
                cleanupFiles();
                Process p;
                try {
                    // 只抓本应用:优先 logcat --uid=<本应用uid>(Android 8+)
                    p = Runtime.getRuntime().exec(buildCommand(true));
                } catch (Throwable th) {
                    p = null;
                }
                if (p == null) {
                    // uid 参数缺失等极端情况:退回 --pid=<本进程> 仍只含本应用
                    p = Runtime.getRuntime().exec(buildCommand(false));
                }
                final Process first = p;
                process = p;
                Thread t = new Thread(() -> readLoop(first), "tvbox-logcat");
                t.setDaemon(true);
                thread = t;
                t.start();
            } catch (Throwable th) {
                Log.e("LogcatCapture", "启动 logcat 捕获失败", th);
                process = null;
                thread = null;
            }
        }
    }

    /**
     * 组装 logcat 命令:
     * ① --uid=本应用uid(Android 8+);老系统不支持时退回 --pid=本进程pid——两者都只会拿到本应用日志,
     *    绝不用无过滤的全量 logcat(那会把整机日志写进应用目录导致存储激增)。
     * ② 记录级别:默认附加 "*:I"(INFO 及以上)。应用自身的 V/D 刷屏(Exo/okhttp/WebView/JS 等)不再全量落盘;
     *    只有把日志级别设为 DEBUG(log_level=0)时才全量保留 V/D,便于深挖问题。
     */
    private static String[] buildCommand(boolean useUid) {
        List<String> cmd = new ArrayList<>();
        cmd.add("logcat");
        cmd.add("-v");
        cmd.add("time");
        if (useUid) {
            cmd.add("--uid=" + android.os.Process.myUid());
        } else {
            cmd.add("--pid=" + android.os.Process.myPid());
        }
        cmd.add("-T");
        cmd.add("1");
        if (LogConfig.getLevel() > LogStore.LEVEL_DEBUG) {
            cmd.add("*:I");
        }
        return cmd.toArray(new String[0]);
    }

    /** 读取 logcat 输出循环;--uid 不被支持(老系统)导致进程立即退出时,用 --pid 重启一次继续读 */
    private static void readLoop(Process firstProc) {
        Process current = firstProc;
        boolean canPidFallback = true;
        try {
            while (current != null) {
                if (process != current) break; // 已停止
                BufferedReader reader = null;
                boolean eof = false;
                try {
                    reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8));
                    List<String> batch = new ArrayList<>();
                    long lastFlush = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (process != current) break; // 已停止
                        batch.add(line);
                        long now = System.currentTimeMillis();
                        if (batch.size() >= BATCH_LINES || now - lastFlush > FLUSH_MS) {
                            appendLines(batch);
                            lastFlush = now;
                        }
                    }
                    appendLines(batch);
                    eof = true;
                } catch (Throwable th) {
                    // 某些设备无权限读取 logcat:回退为直接忽略(业务日志仍正常)
                    Log.d("LogcatCapture", "logcat 读取终止: " + th.getMessage());
                    eof = true;
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (!eof || !canPidFallback || process != current) {
                    break;
                }
                // 流结束且不是我们主动停止:大概率是 --uid 参数不被当前系统支持而立即退出,
                // 换 --pid 再抓一次(仍是本应用日志);仍未成功则放弃,不做全量抓取
                canPidFallback = false;
                boolean badExit = false;
                try {
                    if (!current.isAlive()) {
                        badExit = current.exitValue() != 0;
                    }
                } catch (Throwable ignored) {
                }
                if (!badExit) break;
                try {
                    Process pidProc = Runtime.getRuntime().exec(buildCommand(false));
                    if (pidProc != null && pidProc.isAlive()) {
                        process = pidProc;
                        current = pidProc;
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                break;
            }
        } catch (Throwable th) {
            Log.d("LogcatCapture", "logcat 读取循环终止: " + th.getMessage());
        }
    }

    /** 停止捕获 */
    public static void stop() {
        Process p = process;
        process = null;
        thread = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 按天文件
    // ------------------------------------------------------------------

    private static void appendLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        synchronized (LOCK) {
            try {
                File dir = logDir();
                if (!dir.exists()) dir.mkdirs();
                File file = todayFile();
                StringBuilder sb = new StringBuilder(lines.size() * 96);
                for (String l : lines) sb.append(l).append('\n');
                FileOutputStream fos = new FileOutputStream(file, true);
                try {
                    fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                } finally {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                }
                rotateIfNeeded(file);
                cleanupFiles();
            } catch (Throwable th) {
                Log.e("LogcatCapture", "写 logcat 文件失败", th);
            }
        }
    }

    private static File logDir() {
        return new File(appContext().getFilesDir(), DIR);
    }

    private static File todayFile() {
        return new File(logDir(), PREFIX + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()) + SUFFIX);
    }

    /** 当日文件超过单文件上限时滚动为 logcat-yyyy-MM-dd.N.log(与活跃文件同前缀,便于按天查看) */
    private static void rotateIfNeeded(File file) {
        if (file == null || !file.exists() || file.length() <= MAX_FILE_BYTES) return;
        try {
            String path = file.getAbsolutePath();
            String stem = path.substring(0, path.length() - SUFFIX.length());
            int i = 1;
            File seg;
            do {
                seg = new File(stem + "." + i + SUFFIX);
                i++;
            } while (seg.exists());
            if (!file.renameTo(seg)) {
                // 重命名失败(极少见)则忽略,下一次追加时再试
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清理策略:① 删除超过保留天数的文件;② 总大小超过上限时删除最旧的滚动文件(不删当前活跃文件) */
    private static void cleanupFiles() {
        try {
            File dir = logDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            List<File> keep = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (!f.isFile() || !f.getName().startsWith(PREFIX) || !f.getName().endsWith(SUFFIX)) {
                    continue;
                }
                if (now - f.lastModified() > RETENTION_DAYS * DAY_MS) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } else {
                    keep.add(f);
                }
            }
            if (keep.isEmpty()) return;
            // 按最后修改时间升序(最旧的在前),总大小仍超限时优先删除旧文件
            java.util.Collections.sort(keep, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            String activeName = todayFile().getName();
            long total = 0;
            for (File f : keep) total += f.length();
            for (File f : keep) {
                if (total <= MAX_TOTAL_BYTES) break;
                if (f.getName().equals(activeName)) continue; // 保留当前正在写的文件
                long len = f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                total -= len;
            }
        } catch (Throwable ignored) {
        }
    }
}
