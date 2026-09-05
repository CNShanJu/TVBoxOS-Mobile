package com.github.tvbox.osc.spiderapi;

/**
 * 源/订阅加载器持有者（App 组合根注入 :spider 实现）。
 * 默认安全降级：loadConfig/loadJar 立即回调 error("-1")（与 ApiConfig 未配置订阅语义一致），
 * getSpider 返回空串——注入前被调用时页面按"未配置"处理，不阻塞。
 */
public final class SourceLoaderProviders {

    private static volatile SourceLoaderApi impl = new SourceLoaderApi() {
        @Override
        public void loadConfig(boolean useCache, Callback callback, android.app.Activity activity) {
            if (callback != null) {
                callback.error("-1");
            }
        }

        @Override
        public void loadJar(boolean useCache, String spider, Callback callback) {
            if (callback != null) {
                callback.error("-1");
            }
        }

        @Override
        public String getSpider() {
            return "";
        }
    };

    private SourceLoaderProviders() {
    }

    public static void set(SourceLoaderApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static SourceLoaderApi get() {
        return impl;
    }
}
