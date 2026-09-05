package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SpiderHomeApi;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.HashMap;
import java.util.Map;

/**
 * 强类型分类/首页视频实现(type=3 JS/JAR):内容经 spider-api 契约取得后解析为 AbsXml。
 * 解析规则与 app json() 一致(Gson → AbsJson → AbsXml)。
 */
public final class SpiderHomeImpl implements SpiderHomeApi {

    private static final SpiderHomeImpl INSTANCE = new SpiderHomeImpl();
    private final Gson gson = new Gson();

    public static SpiderHomeImpl get() {
        return INSTANCE;
    }

    private AbsXml parse(String sourceKey, String content, String tag) {
        if (content == null || content.isEmpty()) {
            android.util.Log.w("SpiderBridge", tag + " 内容为空 key=" + sourceKey);
            return null;
        }
        try {
            AbsJson absJson = gson.fromJson(content, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml xml = absJson == null ? null : absJson.toAbsXml();
            if (xml == null || xml.movie == null) {
                android.util.Log.w("SpiderBridge", tag + " 解析为空 key=" + sourceKey);
                return null;
            }
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", tag + " 异常 key=" + sourceKey, th);
            return null;
        }
    }

    @Override
    public AbsXml category(String sourceKey, String tid, String pg, boolean filter, Map<String, String> extend) {
        SourceBean sb = ApiConfig.get().getSource(sourceKey);
        if (sb == null || sb.getType() != 3) return null;
        Map<String, String> ext = extend == null ? new HashMap<>() : new HashMap<>(extend);
        String content = SpiderContentImpl.get().categoryContent(sourceKey, tid, pg, filter, ext);
        AbsXml xml = parse(sourceKey, content, "category(typed)");
        if (xml != null) {
            android.util.Log.d("SpiderBridge", "category(typed) 成功: key=" + sourceKey + " tid=" + tid + " pg=" + pg);
        }
        return xml;
    }

    @Override
    public AbsXml homeVideoContent(String sourceKey) {
        SourceBean sb = ApiConfig.get().getSource(sourceKey);
        if (sb == null || sb.getType() != 3) return null;
        String content = SpiderContentImpl.get().homeVideoContent(sourceKey);
        AbsXml xml = parse(sourceKey, content, "homeVideo(typed)");
        if (xml != null) {
            android.util.Log.d("SpiderBridge", "homeVideo(typed) 成功: key=" + sourceKey);
        }
        return xml;
    }
}
