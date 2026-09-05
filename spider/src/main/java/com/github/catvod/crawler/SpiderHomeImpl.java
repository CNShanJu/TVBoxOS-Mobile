package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SortParser;
import com.github.tvbox.osc.spiderapi.SpiderHomeApi;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.HashMap;
import java.util.Map;

/**
 * 强类型首页/分类实现(type=3 JS/JAR):内容经 spider-api 契约取得后解析为 AbsSortXml/AbsXml。
 * 解析规则与 app 的 sortJson()/json() 一致(SortParser + Gson → AbsJson → AbsXml)。
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
    public AbsSortXml homeContent(String sourceKey, boolean filter) {
        if (sourceKey == null) {
            android.util.Log.w("SpiderBridge", "home(typed): 入参缺失 key=" + sourceKey);
            return null;
        }
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null || sb.getType() != 3) {
                return null; // 仅 type=3 走强类型
            }
            String content = SpiderContentImpl.get().homeContent(sourceKey, filter);
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "home(typed): 内容为空 key=" + sourceKey + " filter=" + filter);
                return null;
            }
            // 解析与 app sortJson() 一致;若响应内嵌 list(首页视频),一并解析到 sort.videoList
            AbsSortXml data = SortParser.parseSortJson(content);
            if (data == null || data.classes == null || data.classes.sortList == null
                    || data.classes.sortList.isEmpty()) {
                android.util.Log.w("SpiderBridge", "home(typed): 无分类/解析为空 key=" + sourceKey);
                return null;
            }
            // 同响应内嵌首页视频(list 字段,与旧 json() 语义一致):带上供 VM 直接发布
            if (data.list != null && data.list.videoList != null && !data.list.videoList.isEmpty()) {
                data.videoList = data.list.videoList;
            }
            android.util.Log.d("SpiderBridge", "home(typed) 成功: key=" + sourceKey
                    + " filter=" + filter + " classes=" + data.classes.sortList.size()
                    + " embedded=" + (data.videoList == null ? 0 : data.videoList.size()));
            return data;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "home(typed) 异常: key=" + sourceKey, th);
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
