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

    private static volatile com.github.tvbox.osc.net.NetworkProvider networkProvider;

    private AppCompositionRoot() {
    }

    /** App.onCreate 网络/下载组件初始化后调用 */
    public static void init() {
        android.util.Log.i("AppCompositionRoot", "init: 注入 UrlResolver/ManualCheck/Content/NetworkProvider");
        // 下载侧播放地址解析(:spider 提供实现,download 不依赖 :spider 实现)
        com.github.tvbox.osc.util.DownloadManager.setUrlResolverApi(
                com.github.catvod.crawler.SpiderUrlResolverImpl.get());
        // 嗅探型源“手动视频判定”
        com.github.tvbox.osc.spiderapi.SpiderManualCheckProviders.set(
                com.github.catvod.crawler.SpiderManualCheckImpl.get());
        // 首页/分类/详情/搜索/播放解析内容(SourceViewModel 不取具体 Spider)
        com.github.tvbox.osc.spiderapi.SpiderContentProviders.set(
                com.github.catvod.crawler.SpiderContentImpl.get());
        // 强类型详情(type3,解析下沉 :spider;失败自动回退字符串通道)
        com.github.tvbox.osc.spiderapi.SpiderDetailProviders.set(
                com.github.catvod.crawler.SpiderDetailImpl.get());
        // 强类型搜索(type3:quick/聚合;失败自动回退字符串通道)
        com.github.tvbox.osc.spiderapi.SpiderSearchProviders.set(
                com.github.catvod.crawler.SpiderSearchImpl.get());
        // 网络客户端提供者:general/noRedirect 来自 OkGoHelper;playback 复用 Exo 已建实例
        networkProvider = new com.github.tvbox.osc.net.NetworkProvider() {
            @Override
            public okhttp3.OkHttpClient general() {
                return com.github.tvbox.osc.util.OkGoHelper.getDefaultClient();
            }

            @Override
            public okhttp3.OkHttpClient noRedirect() {
                return com.github.tvbox.osc.util.OkGoHelper.getNoRedirectClient();
            }

            @Override
            public okhttp3.OkHttpClient playback() {
                okhttp3.OkHttpClient c = com.github.tvbox.osc.base.App.playbackHttpClient;
                return c != null ? c : com.github.tvbox.osc.net.NetworkProvider.DEFAULT.playback();
            }
        };
    }

    /** 网络客户端提供者(供后续新代码经接口取客户端;缺省为按需构建) */
    public static com.github.tvbox.osc.net.NetworkProvider network() {
        com.github.tvbox.osc.net.NetworkProvider p = networkProvider;
        return p != null ? p : com.github.tvbox.osc.net.NetworkProvider.DEFAULT;
    }
}
