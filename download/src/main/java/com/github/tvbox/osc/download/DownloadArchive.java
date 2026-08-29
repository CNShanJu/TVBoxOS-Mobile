package com.github.tvbox.osc.download;

import com.github.tvbox.osc.bean.DownloadTask;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 已下载档案（长期保留，独立于 7 天任务日志）：
 * 任务完成时写入；删文件联动删档案；重启对账清理失效项（文件丢失 → 删档案）。
 * 数据在下载模块内部维护（Hawk 键），对外经 {@link DownloadFacade} 访问。
 */
public final class DownloadArchive {

    private static final String HAWK_KEY = "download_archive_v1";

    private static volatile DownloadArchive instance;

    private final List<ArchiveItem> items = new ArrayList<>();

    private DownloadArchive() {
        try {
            List<ArchiveItem> saved = Hawk.get(HAWK_KEY, new ArrayList<ArchiveItem>());
            if (saved != null) {
                items.addAll(saved);
                // 对账：文件已丢失的档案视为失效,清理
                boolean changed = false;
                for (int i = items.size() - 1; i >= 0; i--) {
                    ArchiveItem it = items.get(i);
                    if (it.savePath != null && !new File(it.savePath).exists()) {
                        items.remove(i);
                        changed = true;
                    }
                }
                if (changed) persist();
            }
        } catch (Throwable ignored) {
        }
    }

    public static DownloadArchive get() {
        if (instance == null) {
            synchronized (DownloadArchive.class) {
                if (instance == null) {
                    instance = new DownloadArchive();
                }
            }
        }
        return instance;
    }

    /** 任务完成（落盘后）写档案；同 episodeId 覆盖旧档案 */
    public synchronized void add(DownloadTask t) {
        if (t == null || t.savePath == null) return;
        ArchiveItem it = new ArchiveItem();
        it.episodeId = t.episodeId;
        it.videoId = videoIdOf(t);
        it.sourceKey = t.sourceKey;
        it.sourceName = t.sourceName;
        it.vodName = t.vodName;
        it.episodeName = t.episodeName;
        it.savePath = t.savePath;
        it.size = new File(t.savePath).length();
        it.downloadTime = System.currentTimeMillis();
        it.pic = t.pic;
        for (int i = items.size() - 1; i >= 0; i--) {
            ArchiveItem old = items.get(i);
            if (old.episodeId != null && old.episodeId.equals(it.episodeId)) {
                items.remove(i);
            }
        }
        items.add(0, it);
        persist();
    }

    /** 某视频的已下载列表（新→旧） */
    public synchronized List<ArchiveItem> queryByVideo(String videoId) {
        List<ArchiveItem> out = new ArrayList<>();
        if (videoId == null) return out;
        for (ArchiveItem it : items) {
            if (videoId.equals(it.videoId)) out.add(it);
        }
        return out;
    }

    /** 全部档案（新→旧；下载管理页聚合根级用） */
    public synchronized List<ArchiveItem> getAll() {
        return new ArrayList<>(items);
    }

    /** 按 剧名(+来源) 查询已下载列表（下载管理页"下载完成"tab 数据源；旧档案无 sourceName 时放行） */
    public synchronized List<ArchiveItem> queryByVod(String vodName, String sourceName) {
        List<ArchiveItem> out = new ArrayList<>();
        if (vodName == null) return out;
        for (ArchiveItem it : items) {
            if (!vodName.equals(it.vodName)) continue;
            if (sourceName != null && !sourceName.isEmpty()
                    && it.sourceName != null && !sourceName.equals(it.sourceName)) {
                continue;
            }
            out.add(it);
        }
        return out;
    }

    /** 单集档案 */
    public synchronized ArchiveItem get(String episodeId) {
        if (episodeId == null) return null;
        for (ArchiveItem it : items) {
            if (episodeId.equals(it.episodeId)) return it;
        }
        return null;
    }

    /** 按文件路径查档案（下载管理页删除完成项用；完成项可能只剩档案无任务记录） */
    public synchronized ArchiveItem findByPath(String savePath) {
        if (savePath == null) return null;
        for (ArchiveItem it : items) {
            if (savePath.equals(it.savePath)) return it;
        }
        return null;
    }

    /** 按文件路径删除档案记录（本地视频页删除文件时联动; 文件已由调用方删除, 这里不重复删） */
    public synchronized boolean removeByPath(String savePath) {
        if (savePath == null) return false;
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (savePath.equals(items.get(i).savePath)) {
                items.remove(i);
                changed = true;
            }
        }
        if (changed) persist();
        return changed;
    }

    /** 删除档案（deleteFile=true 连文件一起删） */
    public synchronized boolean remove(String episodeId, boolean deleteFile) {
        for (int i = items.size() - 1; i >= 0; i--) {
            ArchiveItem it = items.get(i);
            if (episodeId != null && episodeId.equals(it.episodeId)) {
                if (deleteFile && it.savePath != null) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(it.savePath).delete();
                }
                items.remove(i);
                persist();
                return true;
            }
        }
        return false;
    }

    /** 重命名成品文件（同步更新档案路径） */
    public synchronized boolean rename(String episodeId, String newName) {
        if (episodeId == null || newName == null || newName.isEmpty()) return false;
        ArchiveItem it = get(episodeId);
        if (it == null || it.savePath == null) return false;
        File oldFile = new File(it.savePath);
        File parent = oldFile.getParentFile();
        if (parent == null) return false;
        File newFile = new File(parent, newName);
        if (!oldFile.renameTo(newFile)) return false;
        it.savePath = newFile.getAbsolutePath();
        persist();
        return true;
    }

    /** 某集是否已下载（档案 + 文件存在; 文件已不存在的孤儿档案惰性清理——本地/管理页删文件后, 查询即自动移除脏记录） */
    public synchronized boolean isDownloaded(String episodeId) {
        ArchiveItem it = get(episodeId);
        if (it == null) return false;
        if (it.savePath == null || !new File(it.savePath).exists()) {
            // 文件已不存在: 移除孤儿档案并持久化, 详情页/下载抽屉下次查询即恢复正常"未下载"
            items.remove(it);
            persist();
            return false;
        }
        return true;
    }

    private static String videoIdOf(DownloadTask t) {
        if (t.episodeId != null && !t.episodeId.isEmpty()) {
            int first = t.episodeId.indexOf('|');
            if (first > 0) {
                int second = t.episodeId.indexOf('|', first + 1);
                if (second > first) {
                    return t.episodeId.substring(0, second);
                }
            }
            return t.episodeId;
        }
        return (t.sourceKey == null ? "" : t.sourceKey) + "|" + (t.vodName == null ? "" : t.vodName);
    }

    private void persist() {
        try {
            Hawk.put(HAWK_KEY, new ArrayList<>(items));
        } catch (Throwable ignored) {
        }
    }
}
