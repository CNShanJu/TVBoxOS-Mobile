package com.github.tvbox.osc.util;

import android.os.Build;
import android.os.Environment;

import android.content.Context;
import com.github.tvbox.osc.bean.DownloadTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件清理器（File-Cleaner）：临时碎片 / 产物落盘 / 目录回收 / 存储权限 等文件操作。
 * Bug5 增加孤儿 tmpDir 回收；孤儿 tmpDir 增强在后续阶段落地。
 */
public class FileCleaner {

    /** 注入的 application context(独立模块 :download) */
    private static volatile Context appContext;

    static void setAppContext(Context c) {
        appContext = c == null ? null : c.getApplicationContext();
    }

    private FileCleaner() {
    }

    /** Bug4: 存储权限硬门槛(Android 10+ 需要 MANAGE_EXTERNAL_STORAGE 才能写公共目录) */
    static boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    /**
     * Bug5: 孤儿 tmpDir 回收——扫描保存根目录下所有 tmp/<任务目录>,
     * 无对应存活任务(含正在写入的任务按快照比对)即递归删除。
     * 调用时机:App 启动 / 下载页打开(任务已加载,安全)。
     */
    static void cleanupOrphanTmpDirs(List<DownloadTask> liveTasks) {
        try {
            Set<String> liveDirs = new HashSet<>();
            if (liveTasks != null) {
                for (DownloadTask t : liveTasks) {
                    if (t.tmpDir != null && !t.tmpDir.isEmpty()) {
                        liveDirs.add(new File(t.tmpDir).getAbsolutePath());
                    }
                }
            }
            scanAndClean(getSaveDir(), liveDirs);
        } catch (Throwable ignored) {
        }
    }

    private static void scanAndClean(File dir, Set<String> liveDirs) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            if ("tmp".equals(f.getName())) {
                File[] sub = f.listFiles();
                if (sub != null) {
                    for (File s : sub) {
                        if (s.isDirectory() && !liveDirs.contains(s.getAbsolutePath())) {
                            deleteRecursive(s);
                        }
                    }
                }
            } else {
                scanAndClean(f, liveDirs);
            }
        }
    }

    /** 下载保存根目录:有存储管理权限用公共 Download,否则用应用私有目录 */
    static File getSaveDir() {
        File base;
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TVBox");
        } else {
            File ext = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            base = ext == null ? new File(appContext.getFilesDir(), "downloads") : new File(ext, "TVBox");
        }
        if (!base.exists()) base.mkdirs();
        return base;
    }

    static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteRecursive(c);
            }
        }
        f.delete();
    }

    static void copyFile(File src, File dst) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[DownloadManager.BUFFER];
        int n;
        while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
        fis.close();
        fos.close();
    }

    static void copyFile(File src, OutputStream out) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        byte[] buf = new byte[DownloadManager.BUFFER];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        fis.close();
    }
}
