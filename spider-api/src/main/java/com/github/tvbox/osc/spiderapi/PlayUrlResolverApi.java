package com.github.tvbox.osc.spiderapi;

/** 播放地址解析契约:download 等消费方只依赖本接口,不依赖 :spider 实现 */
public interface PlayUrlResolverApi {

    /**
     * 解析真实播放地址与请求头(串行执行,避免 quickjs 并发卡死)。
     *
     * @param sourceKey   源 key
     * @param playFlag    线路/解析方式
     * @param episodeRawUrl 原始剧集页/地址
     * @return 解析结果;失败/不可解析返回 null
     */
    ResolveResult resolvePlayUrl(String sourceKey, String playFlag, String episodeRawUrl);

    /** 未注入时的默认实现(返回 null,由调用方按“解析失败”处理) */
    PlayUrlResolverApi NONE = (sourceKey, playFlag, rawUrl) -> null;
}
