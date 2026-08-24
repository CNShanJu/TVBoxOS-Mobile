package com.github.tvbox.osc.util;

import android.os.Build;
import android.os.Environment;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 文件清理器（File-Cleaner）：临时碎片 / 产物落盘 / 目录回收等文件操作。
 * 5.1 先收敛纯文件工具（delete/copy/saveDir）；孤儿 tmpDir 回收等增强在后续阶段落地。
 */
public class FileCleaner {

    private FileCleaner() {
    }

    /** 下载保存根目录:有存储管理权限用公共 Download,否则用应用私有目录 */
    static File getSaveDir() {
        File base;
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TVBox");
        } else {
            File ext = App.getInstance().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            base = ext == null ? new File(App.getInstance().getFilesDir(), "downloads") : new File(ext, "TVBox");
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
