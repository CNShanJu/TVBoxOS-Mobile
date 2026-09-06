package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.AbsXmlParser;
import com.github.tvbox.osc.spiderapi.SpiderDetailApi;

import java.util.Collections;

/**
 * 强类型详情实现(type=3 JS/JAR):内容串由 spider-api 契约取得后,在此解析为 AbsXml。
 * 说明:解析与 VM 共用同一权威实现 AbsXmlParser.parseJson(与 SourceViewModel.json() 同源,
 * 消除双实现漂移);app 只做 enrichment+post。
 */
public final class SpiderDetailImpl implements SpiderDetailApi {

    private static final SpiderDetailImpl INSTANCE = new SpiderDetailImpl();

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
            AbsXml xml = AbsXmlParser.parseJson(content, sourceKey);
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
