package com.github.tvbox.osc.util.player;

/**
 * "已播放剧集"持久化键纯逻辑(自 PlayFragment/DownloadFragment 抽取,等价搬移):
 * 播放记录(SP CacheConst.VIDEO_PLAYED_SP)以 sourceKey|vodId 为键、值为已播放集索引集合。
 * <ul>
 *   <li>{@link #of} 由 sourceKey/vodId 构造(PlayFragment.recordPlayedEpisode 用)</li>
 *   <li>{@link #fromEpisodeId} 由统一剧集标识 episodeId(sourceKey|vodId|flag|index)
 *       截取前两段(DownloadFragment.playedIndicesOf 用)</li>
 * </ul>
 */
public final class PlayedVodKey {

    private PlayedVodKey() {
    }

    /** sourceKey|vodId(null 侧归一为空串) */
    public static String of(String sourceKey, String vodId) {
        return (sourceKey == null ? "" : sourceKey) + "|" + (vodId == null ? "" : vodId);
    }

    /** 由 episodeId 取前两段;格式不足时返回 null */
    public static String fromEpisodeId(String episodeId) {
        if (episodeId == null) return null;
        int first = episodeId.indexOf('|');
        if (first < 0) return null;
        int second = episodeId.indexOf('|', first + 1);
        if (second < 0) return null;
        return episodeId.substring(0, second);
    }
}
