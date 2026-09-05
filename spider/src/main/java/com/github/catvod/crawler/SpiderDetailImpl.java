package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SpiderDetailApi;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.Collections;

/**
 * 强类型详情实现(type=3 JS/JAR):内容串由 spider-api 契约取得后,在此解析为 AbsXml。
 * 说明:解析规则与 app 侧 json() 一致(Gson → AbsJson → AbsXml);app 只做 enrichment+post。
 */
public final class SpiderDetailImpl implements SpiderDetailApi {

    private static final SpiderDetailImpl INSTANCE = new SpiderDetailImpl();
    private final Gson gson = new Gson();

    public static SpiderDetailImpl get() {
        return INSTANCE;
    }

    @Override
    public AbsXml detail(String sourceKey, String vodId) {
        if (sourceKey == null || vodId == null) {
            android.util.Log.w("SpiderBridge", "detail(typed): 入参缺失 key=" + sourceKey + " id=" + vodId);
            return null;
        }
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null || sb.getType() != 3) {
                return null; // 仅 type=3 走强类型;其余类型仍走原字符串通道
            }
            String content = SpiderContentImpl.get().detailContent(sourceKey, Collections.singletonList(vodId));
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "detail(typed): 内容为空 key=" + sourceKey + " id=" + vodId);
                return null;
            }
            AbsJson absJson = gson.fromJson(content, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml xml = absJson == null ? null : absJson.toAbsXml();
            if (xml == null || xml.movie == null) {
                android.util.Log.w("SpiderBridge", "detail(typed): 解析为空 key=" + sourceKey + " id=" + vodId);
                return null;
            }
            android.util.Log.d("SpiderBridge", "detail(typed) 成功: key=" + sourceKey + " id=" + vodId);
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "detail(typed) 异常: key=" + sourceKey + " id=" + vodId, th);
            return null;
        }
    }
}
