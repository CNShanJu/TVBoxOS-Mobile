package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.ArchiveItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载页"聚合分组"纯逻辑(自 DownloadFragment 抽取,等价搬移):
 * 把下载任务(运行态)与已下载档案(长期)按"来源+剧名"聚合成展示卡,
 * 按每组最早创建时间排序。无 View/Context 依赖,输入输出纯数据,可 JVM 单测。
 */
public final class DownloadGrouping {

    /** 聚合分组:key = 来源 + 剧名(同剧不同源各自成卡) */
    public static final class Group {
        public String key;
        public String name;        // 剧名
        public String sourceName;
        public List<DownloadTask> tasks = new ArrayList<>();
        /** 已完成集(档案表长期数据源) */
        public List<ArchiveItem> doneItems = new ArrayList<>();
    }

    private DownloadGrouping() {
    }

    /** 剧名(兼容旧字段 groupName) */
    public static String vodNameOf(DownloadTask t) {
        return t.vodName == null ? t.groupName : t.vodName;
    }

    /** 任务是否属于该剧(剧名+来源)分组 */
    public static boolean inGroup(DownloadTask t, String name, String source) {
        if (!name.equals(vodNameOf(t))) return false;
        String src = source == null ? "" : source;
        return src.equals(t.sourceName == null ? "" : t.sourceName);
    }

    /**
     * 聚合分组:运行态任务(非 COMPLETED,COMPLETED 走档案) + 已完成且文件存在的档案记录,
     * 按组内最早 createTime/downloadTime 升序。
     */
    public static List<Group> group(List<DownloadTask> tasks, List<ArchiveItem> archive) {
        Map<String, Group> map = new LinkedHashMap<>();
        Map<String, Long> firstTime = new LinkedHashMap<>();
        if (tasks != null) {
            for (DownloadTask t : tasks) {
                if (t.state == DownloadTask.STATE_COMPLETED) continue; // 已完成走档案
                String src = t.sourceName == null ? "" : t.sourceName;
                String name = vodNameOf(t);
                String key = src + "\u0001" + name;
                Group g = map.get(key);
                if (g == null) {
                    g = new Group();
                    g.key = key;
                    g.name = name;
                    g.sourceName = src;
                    map.put(key, g);
                    firstTime.put(key, t.createTime);
                }
                g.tasks.add(t);
            }
        }
        if (archive != null) {
            for (ArchiveItem it : archive) {
                if (it.savePath == null || !new File(it.savePath).exists()) continue;
                String src = it.sourceName == null ? "" : it.sourceName;
                String name = it.vodName == null ? "" : it.vodName;
                String key = src + "\u0001" + name;
                Group g = map.get(key);
                if (g == null) {
                    g = new Group();
                    g.key = key;
                    g.name = name;
                    g.sourceName = src;
                    map.put(key, g);
                    firstTime.put(key, it.downloadTime);
                }
                g.doneItems.add(it);
            }
        }
        List<Group> groups = new ArrayList<>(map.values());
        groups.sort(Comparator.comparingLong(g -> firstTime.get(g.key)));
        return groups;
    }

    /** 该剧(剧名+来源)是否仍存在于聚合:有下载中任务或已下载档案(文件存在) */
    public static boolean isGroupPresent(List<DownloadTask> tasks, List<ArchiveItem> archive,
                                         String name, String source) {
        String wantSrc = source == null ? "" : source;
        if (tasks != null) {
            for (DownloadTask t : tasks) {
                if (!name.equals(vodNameOf(t))) continue;
                if (!wantSrc.equals(t.sourceName == null ? "" : t.sourceName)) continue;
                if (t.state != DownloadTask.STATE_COMPLETED) return true;
            }
        }
        if (archive != null) {
            for (ArchiveItem it : archive) {
                if (!name.equals(it.vodName)) continue;
                if (!wantSrc.equals(it.sourceName == null ? "" : it.sourceName)) continue;
                if (it.savePath != null && new File(it.savePath).exists()) return true;
            }
        }
        return false;
    }

    /** 该剧未完成的任务列表(按加入时间排序) */
    public static List<DownloadTask> tasksInGroup(List<DownloadTask> tasks, String vodName, String sourceName) {
        List<DownloadTask> list = new ArrayList<>();
        if (tasks != null) {
            for (DownloadTask t : tasks) {
                if (t.state != DownloadTask.STATE_COMPLETED && inGroup(t, vodName, sourceName)) {
                    list.add(t);
                }
            }
        }
        list.sort(Comparator.comparingLong(t -> t.createTime));
        return list;
    }

    /** 聚合卡副标题:未完成任务数 + 已完成且文件存在的集数(如 "3 个任务 · 已完成 5 集") */
    public static String aggregateNote(List<DownloadTask> tasks, List<ArchiveItem> doneItems) {
        int running = 0;
        if (tasks != null) {
            for (DownloadTask t : tasks) {
                if (t.state != DownloadTask.STATE_COMPLETED) running++;
            }
        }
        int done = 0;
        if (doneItems != null) {
            for (ArchiveItem it : doneItems) {
                if (it.savePath != null && new File(it.savePath).exists()) done++;
            }
        }
        String note = running > 0 ? running + " 个任务" : "";
        if (done > 0) {
            note = (note.isEmpty() ? "" : note + " · ") + "已完成 " + done + " 集";
        }
        return note;
    }
}
