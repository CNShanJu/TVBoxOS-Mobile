package com.github.tvbox.osc.util.player;

import com.github.tvbox.osc.bean.VodInfo;

/**
 * 播放会话键纯构造(自 PlayFragment 抽取,等价搬移):
 * - progressKey:进度持久化用(与 PlayHistoryRepository 内部再 MD5)
 * - subtitleCacheKey:字幕缓存/清理用
 * 无 View/Android 依赖,可 JVM 单测;作为后续 PlayViewModel 化的数据键来源。
 */
public final class PlaySessionKeys {

    private PlaySessionKeys() {
    }

    /** 播放进度 key = 来源 + 剧id + 线路 + 索引 + 集名(与历史实现拼接一致) */
    public static String progressKey(VodInfo vodInfo, String seriesName) {
        return vodInfo.sourceKey + vodInfo.id + vodInfo.playFlag + vodInfo.playIndex + seriesName;
    }

    /** 字幕缓存 key = 来源-剧id-线路-索引-集名-subt(与历史实现拼接一致) */
    public static String subtitleCacheKey(VodInfo vodInfo, String seriesName) {
        return vodInfo.sourceKey + "-" + vodInfo.id + "-" + vodInfo.playFlag
                + "-" + vodInfo.playIndex + "-" + seriesName + "-subt";
    }
}
