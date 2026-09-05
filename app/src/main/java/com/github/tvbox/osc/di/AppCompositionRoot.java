package com.github.tvbox.osc.di;

import android.content.Context;

/**
 * App 组合根(改进.txt 第四节):集中完成跨模块服务的依赖注入。
 * 现阶段接入:
 * - 播放地址解析契约(PlayUrlResolverApi)→ :spider 实现(下载/播放解析用)
 * - 手动视频判定(SpiderManualCheckApi)→ :spider 实现(嗅探 WebView 用)
 * - 爬虫内容服务(SpiderContentApi)→ :spider 实现(首页/分类/详情/搜索/播放用)
 * - PlayerFactory adapter 注册(type=1 IJK / type=2 Exo,roadmap 2.1 原型)
 * 后续扩展位:NetworkProvider / StorageFacade 等。
 */
public final class AppCompositionRoot {

    private static volatile com.github.tvbox.osc.net.NetworkProvider networkProvider;
    private static volatile boolean playerAdaptersRegistered = false;

    private AppCompositionRoot() {
    }

    /** App.onCreate 网络/下载组件初始化后调用 */
    public static void init() {
        android.util.Log.i("AppCompositionRoot", "init: 注入 UrlResolver/ManualCheck/Content/NetworkProvider");
        // 下载侧播放地址解析(:spider 提供实现,download 不依赖 :spider 实现)
        com.github.tvbox.osc.download.DownloadFacade.setUrlResolverApi(
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
        // 强类型分类/首页视频(type3;失败自动回退字符串通道)
        com.github.tvbox.osc.spiderapi.SpiderHomeProviders.set(
                com.github.catvod.crawler.SpiderHomeImpl.get());
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
        registerPlayerAdapters();
    }

    /**
     * PlayerFactory 内核适配器注册(roadmap 2.1 会话原型):
     * type=1(IJK) / type=2(Exo) 注册引擎无关的 PlayerApi 适配器,
     * 供 PlaybackSessions 会话层/后续 PlayFragment 薄层化按 playType 取用。
     * 适配器仅作协议收敛:真机回归前 UI 仍由 mVideoView 直接驱动,此处不改变现有播放行为。
     * 内核/渲染工厂语义与 PlayerHelper.updateCfg 一致(IJK=type1 / Exo=type2)。
     */
    private static synchronized void registerPlayerAdapters() {
        if (playerAdaptersRegistered) return;
        try {
            // type=1 IJK:复用 PlayerHelper 的 IJK 内核工厂语义(new IjkMediaPlayer(context, codec))
            com.github.tvbox.osc.player.api.PlayerFactory.register(1, (context, options, listener) -> {
                android.util.Log.i("AppCompositionRoot", "PlayerFactory: 创建 IJK(type=1) 适配器");
                xyz.doikki.videoplayer.player.VideoView vv = newVideoView(context, 1);
                com.github.tvbox.osc.player.api.PlayerApi api =
                        new com.github.tvbox.osc.player.VideoViewPlayerApi(vv, true);
                api.subscribe(listener);
                return api;
            });
            // type=2 Exo:复用 EXOmPlayer 内核工厂语义
            com.github.tvbox.osc.player.api.PlayerFactory.register(2, (context, options, listener) -> {
                android.util.Log.i("AppCompositionRoot", "PlayerFactory: 创建 Exo(type=2) 适配器");
                xyz.doikki.videoplayer.player.VideoView vv = newVideoView(context, 2);
                com.github.tvbox.osc.player.api.PlayerApi api =
                        new com.github.tvbox.osc.player.VideoViewPlayerApi(vv, true);
                api.subscribe(listener);
                return api;
            });
            playerAdaptersRegistered = true;
            android.util.Log.i("AppCompositionRoot", "PlayerFactory: IJK(1)/Exo(2) 适配器已注册");
        } catch (Throwable th) {
            android.util.Log.w("AppCompositionRoot", "PlayerFactory 适配器注册失败(不影响主链路)", th);
        }
    }

    /** 按内核类型创建独立 VideoView(工厂语义与 PlayerHelper.updateCfg 对齐;渲染默认 texture) */
    private static xyz.doikki.videoplayer.player.VideoView newVideoView(Context context, int playType) {
        xyz.doikki.videoplayer.player.VideoView vv = new xyz.doikki.videoplayer.player.VideoView(context);
        if (playType == 1) {
            // IJK 内核工厂(与 PlayerHelper.updateCfg 相同)
            vv.setPlayerFactory(new xyz.doikki.videoplayer.player.PlayerFactory<com.github.tvbox.osc.player.IjkMediaPlayer>() {
                @Override
                public com.github.tvbox.osc.player.IjkMediaPlayer createPlayer(Context ctx) {
                    return new com.github.tvbox.osc.player.IjkMediaPlayer(ctx, null);
                }
            });
        } else {
            vv.setPlayerFactory(new xyz.doikki.videoplayer.player.PlayerFactory<com.github.tvbox.osc.player.EXOmPlayer>() {
                @Override
                public com.github.tvbox.osc.player.EXOmPlayer createPlayer(Context ctx) {
                    return new com.github.tvbox.osc.player.EXOmPlayer(ctx);
                }
            });
        }
        try {
            int renderType = com.github.tvbox.osc.player.api.PlayConfig.getRenderType();
            if (renderType == 1) {
                vv.setRenderViewFactory(com.github.tvbox.osc.player.render.SurfaceRenderViewFactory.create());
            } else {
                vv.setRenderViewFactory(xyz.doikki.videoplayer.render.TextureRenderViewFactory.create());
            }
        } catch (Throwable th) {
            android.util.Log.w("AppCompositionRoot", "VideoView 渲染工厂设置失败(原型)", th);
        }
        return vv;
    }

    /** 网络客户端提供者(供后续新代码经接口取客户端;缺省为按需构建) */
    public static com.github.tvbox.osc.net.NetworkProvider network() {
        com.github.tvbox.osc.net.NetworkProvider p = networkProvider;
        return p != null ? p : com.github.tvbox.osc.net.NetworkProvider.DEFAULT;
    }
}
