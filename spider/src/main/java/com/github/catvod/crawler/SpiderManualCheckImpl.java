package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SpiderManualCheckApi;

/** :spider 侧手动视频判定实现:桥接 ApiConfig.getCSP(UI/下载侧不再直接拿具体 Spider) */
public final class SpiderManualCheckImpl implements SpiderManualCheckApi {

    private static final SpiderManualCheckImpl INSTANCE = new SpiderManualCheckImpl();

    public static SpiderManualCheckImpl get() {
        return INSTANCE;
    }

    @Override
    public Boolean manualVideoCheck(String sourceKey, String url) {
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            Spider sp = sb == null ? null : ApiConfig.get().getCSP(sb);
            if (sp == null || !sp.manualVideoCheck()) return null;
            return sp.isVideoFormat(url);
        } catch (Throwable th) {
            return null;
        }
    }
}
