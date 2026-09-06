package com.github.tvbox.osc.util.player;

import com.github.tvbox.osc.bean.VodInfo;

/**
 * 一次播放请求的上下文值对象(自 PlayFragment.play 抽取,等价搬移):
 * 把"来源/线路/集索引/集名/播放地址 + 进度/字幕缓存键"聚成不可变对象,
 * 供 VM.getPlay 调用与起播流程引用;键由 {@link PlaySessionKeys} 统一生成,
 * 与历史逐字一致。后续 PlayViewModel 化的请求载体。
 */
public final class PlayRequest {

    private final VodInfo vodInfo;
    private final String seriesName;
    private final String url;
    private final String sourceKey;
    private final String progressKey;
    private final String subtitleCacheKey;

    private PlayRequest(VodInfo vodInfo, String seriesName, String url) {
        this.vodInfo = vodInfo;
        this.seriesName = seriesName;
        this.url = url;
        this.sourceKey = vodInfo.sourceKey;
        this.progressKey = PlaySessionKeys.progressKey(vodInfo, seriesName);
        this.subtitleCacheKey = PlaySessionKeys.subtitleCacheKey(vodInfo, seriesName);
    }

    /** 由当前剧集与选集构造请求上下文 */
    public static PlayRequest of(VodInfo vodInfo, VodInfo.VodSeries series) {
        return new PlayRequest(vodInfo, series.name, series.url);
    }

    public VodInfo vodInfo() {
        return vodInfo;
    }

    public String seriesName() {
        return seriesName;
    }

    public String url() {
        return url;
    }

    public String sourceKey() {
        return sourceKey;
    }

    public String progressKey() {
        return progressKey;
    }

    public String subtitleCacheKey() {
        return subtitleCacheKey;
    }
}
