package com.github.tvbox.osc.di;

/**
 * App 组合根(改进.txt 第四节):集中完成跨模块服务的依赖注入。
 * 现阶段接入:
 * - 播放地址解析契约(PlayUrlResolverApi)→ :spider 实现(下载/播放解析用)
 * - 手动视频判定(SpiderManualCheckApi)→ :spider 实现(嗅探 WebView 用)
 * - 爬虫内容服务(SpiderContentApi)→ :spider 实现(首页/分类/详情/搜索/播放用)
 * 后续扩展位:NetworkProvider / StorageFacade / PlayerFactory(adapter 注册)。
 */
public final class AppCompositionRoot {

    private AppCompositionRoot() {
    }

    /** 在 App.onCreate 网络/下载组件初始化后调用 */
    public static void init() {
        // 下载侧播放地址解析(:spider 提供实现,download 不依赖 :spider 实现)
        com.github.tvbox.osc.util.DownloadManager.setUrlResolverApi(
                com.github.catvod.crawler.SpiderUrlResolverImpl.get());
        // 嗅探型源“手动视频判定”
        com.github.tvbox.osc.spiderapi.SpiderManualCheckProviders.set(
                com.github.catvod.crawler.SpiderManualCheckImpl.get());
        // 首页/分类/详情/搜索/播放解析内容(SourceViewModel 不取具体 Spider)
        com.github.tvbox.osc.spiderapi.SpiderContentProviders.set(
                com.github.catvod.crawler.SpiderContentImpl.get());
    }
}
