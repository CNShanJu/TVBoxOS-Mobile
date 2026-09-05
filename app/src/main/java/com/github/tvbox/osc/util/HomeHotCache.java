package com.github.tvbox.osc.util;

import com.github.tvbox.osc.config.KeyValueStore;

/**
 * 用户页"豆瓣热播"当日缓存（UI 摘 Hawk：UserFragment 的 home_hot/home_hot_day 键收口）。
 * <p>
 * 语义：按自然日缓存热播 JSON；当天命中直接展示,跨天/下拉刷新时清掉重拉。
 * 键沿用旧裸键（历史数据兼容）。
 */
public final class HomeHotCache {

    private static final String KEY_DAY = "home_hot_day";
    private static final String KEY_JSON = "home_hot";

    private HomeHotCache() {
    }

    /** 缓存写入日（未缓存返回空串） */
    public static String getDay() {
        return KeyValueStore.get(KEY_DAY, "");
    }

    /** 缓存的热播 JSON（未缓存返回空串） */
    public static String getData() {
        return KeyValueStore.get(KEY_JSON, "");
    }

    /** 写入当日缓存 */
    public static void save(String day, String json) {
        KeyValueStore.put(KEY_DAY, day == null ? "" : day);
        KeyValueStore.put(KEY_JSON, json == null ? "" : json);
    }

    /** 清空当日缓存（下拉刷新强制重拉） */
    public static void clear() {
        KeyValueStore.delete(KEY_DAY);
        KeyValueStore.delete(KEY_JSON);
    }
}
