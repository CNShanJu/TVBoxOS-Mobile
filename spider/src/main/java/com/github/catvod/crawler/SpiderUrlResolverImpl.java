package com.github.catvod.crawler;

import com.github.tvbox.osc.spiderapi.PlayUrlResolverApi;
import com.github.tvbox.osc.spiderapi.ResolveResult;

/**
 * :spider 侧对 spider-api 契约的实现:桥接 ApiConfig/SpiderApi 的既有解析链路。
 * App 组合根在启动时把它注入 DownloadManager(下载侧不再直接依赖 :spider 实现)。
 */
public final class SpiderUrlResolverImpl implements PlayUrlResolverApi {

    private static final SpiderUrlResolverImpl INSTANCE = new SpiderUrlResolverImpl();

    public static SpiderUrlResolverImpl get() {
        return INSTANCE;
    }

    @Override
    public ResolveResult resolvePlayUrl(String sourceKey, String playFlag, String episodeRawUrl) {
        if (sourceKey == null || playFlag == null || episodeRawUrl == null) return null;
        try {
            PlayUrlResolver.ResolveResult rr = SpiderApi.resolvePlayUrl(sourceKey, playFlag, episodeRawUrl);
            if (rr == null || rr.url == null || rr.url.isEmpty()) return null;
            return new ResolveResult(rr.url, rr.headers);
        } catch (Throwable th) {
            return null;
        }
    }
}
