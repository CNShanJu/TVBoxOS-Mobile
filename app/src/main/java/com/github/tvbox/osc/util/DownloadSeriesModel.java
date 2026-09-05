package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * 下载选择弹窗的选集数据模型纯逻辑(自 DetailActivity 抽取,等价搬移):
 * - 从弹窗当前列表收集勾选集名(排序/刷新后按集名保留勾选)
 * - 按详情页"全集正表"重建选集副本(写统一剧集标识 episodeId)
 * - 批量查询所需的 episodeIds/episodeNames 数组拆分
 * 无 View/Context 依赖,episodeId 生成经 {@link #rebuildCopy} 注入的工厂,可 JVM 单测。
 */
public final class DownloadSeriesModel {

    private DownloadSeriesModel() {
    }

    /** 收集弹窗当前勾选的集名(selected 且 name 非空) */
    public static Set<String> collectSelectedNames(List<VodInfo.VodSeries> shown) {
        Set<String> selectedNames = new HashSet<>();
        if (shown == null) {
            return selectedNames;
        }
        for (VodInfo.VodSeries s : shown) {
            if (s.selected && s.name != null) {
                selectedNames.add(s.name);
            }
        }
        return selectedNames;
    }

    /**
     * 按全集正表重建弹窗选集副本:每集写统一剧集标识并保留勾选(按集名匹配)。
     *
     * @param master        详情页全集(seriesMap.get(playFlag)),决定顺序与子集
     * @param selectedNames 需保留勾选的集名集合
     * @param episodeIdOf   第 i 集(0 起)的剧集标识生成器(episodeId = 来源|剧id|线路|索引)
     */
    public static List<VodInfo.VodSeries> rebuildCopy(List<VodInfo.VodSeries> master,
                                                      Set<String> selectedNames,
                                                      IntFunction<String> episodeIdOf) {
        List<VodInfo.VodSeries> copy = new ArrayList<>();
        if (master == null) {
            return copy;
        }
        int copyIdx = 0;
        for (VodInfo.VodSeries s : master) {
            VodInfo.VodSeries c = new VodInfo.VodSeries();
            c.name = s.name;
            c.url = s.url;
            c.selected = s.name != null && selectedNames.contains(s.name);
            c.episodeId = episodeIdOf.apply(copyIdx);
            copyIdx++;
            copy.add(c);
        }
        return copy;
    }

    /** 批量查询用:episodeId 数组(与 copy 顺序一致) */
    public static String[] episodeIdsOf(List<VodInfo.VodSeries> copy) {
        String[] ids = new String[copy.size()];
        for (int i = 0; i < copy.size(); i++) {
            ids[i] = copy.get(i).episodeId;
        }
        return ids;
    }

    /** 批量查询用:集名数组(与 copy 顺序一致) */
    public static String[] episodeNamesOf(List<VodInfo.VodSeries> copy) {
        String[] names = new String[copy.size()];
        for (int i = 0; i < copy.size(); i++) {
            names[i] = copy.get(i).name;
        }
        return names;
    }
}
