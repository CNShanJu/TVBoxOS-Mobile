package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsXml;

/** 强类型分类/首页视频服务持有者(App 组合根注入;默认不可用返回 null) */
public final class SpiderHomeProviders {

    private static volatile SpiderHomeApi impl = new SpiderHomeApi() {
        @Override
        public AbsXml category(String sourceKey, String tid, String pg, boolean filter, java.util.Map<String, String> extend) {
            return null;
        }

        @Override
        public AbsXml homeVideoContent(String sourceKey) {
            return null;
        }
    };

    private SpiderHomeProviders() {
    }

    public static void set(SpiderHomeApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static SpiderHomeApi get() {
        return impl;
    }
}
