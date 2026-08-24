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
 * logcat 全部日志捕获（package:mine 语义）：`logcat --uid=<本应用uid>` 只抓本应用，
 * 按天写入 filesDir/app_logs/logcat-yyyy-MM-dd.log（与 AppLog 共用目录，过渡期
 * 现有 LogActivity 仍可展示），自动清理 7 天前文件。
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

    /** 启动捕获（幂等）；release 上 --uid 受限时自动回退无过滤并提示 */
    public static void start() {
        if (thread != null && thread.isAlive()) return;
        synchronized (LOCK) {
            if (thread != null && thread.isAlive()) return;
            try {
                File dir = logDir();
                if (!dir.exists()) dir.mkdirs();
                final Process p = Runtime.getRuntime().exec(new String[]{
                        "logcat", "-v", "time", "--uid=" + android.os.Process.myUid(), "-T", "1"});
                process = p;
                Thread t = new Thread(() -> {
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                        List<String> batch = new ArrayList<>();
                        long lastFlush = 0;
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (process != p) break; // 已停止
                            batch.add(line);
                            long now = System.currentTimeMillis();
                            if (batch.size() >= BATCH_LINES || now - lastFlush > FLUSH_MS) {
                                appendLines(batch);
                                lastFlush = now;
                            }
                        }
                        appendLines(batch);
                    } catch (Throwable th) {
                        // 某些设备无权限读取 logcat：回退为直接忽略（业务日志仍正常）
                        Log.d("LogcatCapture", "logcat 读取终止: " + th.getMessage());
                    } finally {
                        if (reader != null) {
                            try {
                                reader.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }, "tvbox-logcat");
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
                cleanupOld();
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

    /** 删除 7 天前的 logcat 文件 */
    private static void cleanupOld() {
        try {
            File dir = logDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600 * 1000;
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith(PREFIX) && f.getName().endsWith(SUFFIX)) {
                    if (f.lastModified() < cutoff) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
