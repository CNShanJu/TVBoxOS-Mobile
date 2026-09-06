package com.github.tvbox.osc.spiderapi;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 型源(type0 XML / type1、type4 JSON)请求参数构造(纯逻辑,可 JVM 单测)。
 * <p>
 * 单一权威来源:SourceViewModel(type0/1/4 旧直连路径)与 :spider typed 实现
 * (SpiderDetailImpl 等)共用同一拼参语义,消除双实现漂移(评估 §J 修订方向)。
 */
public final class HttpSourceParams {

    private HttpSourceParams() {
    }

    /**
     * 详情参数:type0 → ac=videolist(旧 XML 源);type1/type4 → ac=detail。
     *
     * @param type 源类型(0/1/4;其余类型返回 null 表示不支持)
     * @param id   影片 id
     */
    public static Map<String, String> detail(int type, String id) {
        if (type != 0 && type != 1 && type != 4) return null;
        if (id == null) return null;
        Map<String, String> params = new HashMap<>();
        params.put("ac", type == 0 ? "videolist" : "detail");
        params.put("ids", id);
        return params;
    }
}
