package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsXml;

import java.util.Map;

/** 强类型分类/首页视频契约(type3 JS/JAR):返回解析后的 {@link AbsXml} */
public interface SpiderHomeApi {

    /** 分类列表(type3) */
    AbsXml category(String sourceKey, String tid, String pg, boolean filter, Map<String, String> extend);

    /** 首页推荐视频(type3,homeVideoContent) */
    AbsXml homeVideoContent(String sourceKey);
}
