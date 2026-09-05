package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SpiderSearchApi;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * 强类型搜索实现(type=3 JS/JAR):内容经 spider-api 契约取得后解析为 AbsXml。
 * 解析规则与 app 侧 json() 一致(Gson → AbsJson → AbsXml)。
 */
public final class SpiderSearchImpl implements SpiderSearchApi {

    private static final SpiderSearchImpl INSTANCE = new SpiderSearchImpl();
    private final Gson gson = new Gson();

    public static SpiderSearchImpl get() {
        return INSTANCE;
    }

    @Override
    public AbsXml search(String sourceKey, String word, boolean quick) {
        if (sourceKey == null || word == null) {
            android.util.Log.w("SpiderBridge", "search(typed): 入参缺失 key=" + sourceKey + " word=" + word);
            return null;
        }
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null || sb.getType() != 3) {
                return null; // 仅 type=3 走强类型
            }
            String content = SpiderContentImpl.get().searchContent(sourceKey, word, quick);
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "search(typed): 内容为空 key=" + sourceKey
                        + " word=" + word + " quick=" + quick);
                return null;
            }
            AbsJson absJson = gson.fromJson(content, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml xml = absJson == null ? null : absJson.toAbsXml();
            if (xml == null || xml.movie == null || xml.movie.videoList == null || xml.movie.videoList.isEmpty()) {
                android.util.Log.w("SpiderBridge", "search(typed): 解析为空/无列表 key=" + sourceKey
                        + " word=" + word + " quick=" + quick);
                return null;
            }
            android.util.Log.d("SpiderBridge", "search(typed) 成功: key=" + sourceKey + " word=" + word
                    + " quick=" + quick + " hits=" + xml.movie.videoList.size());
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "search(typed) 异常: key=" + sourceKey + " word=" + word, th);
            return null;
        }
    }
}
