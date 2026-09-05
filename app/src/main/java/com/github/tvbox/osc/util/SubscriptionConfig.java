package com.github.tvbox.osc.util;
import com.github.tvbox.osc.config.HawkConfig;

import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.config.KeyValueStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 订阅/搜索域配置门面（UI 摘 Hawk：订阅地址、订阅列表、搜索历史、按源勾选记忆、导入目录记忆）。
 * <p>
 * 键沿用 {@link HawkConfig} 旧应用 key（历史设置兼容）；订阅**加载/落库**（ApiConfig 与 App 装配）
 * 仍由 :spider/启动侧处理，本门面只向 UI 提供类型安全的读写，不再让页面直接触碰 Hawk。
 */
public final class SubscriptionConfig {

    private SubscriptionConfig() {
    }

    // ── 订阅地址（当前启用接口地址）──

    /** 当前启用订阅接口地址（未设置返回空串） */
    public static String getApiUrl() {
        return KeyValueStore.get(HawkConfig.API_URL, "");
    }

    public static void setApiUrl(String url) {
        KeyValueStore.put(HawkConfig.API_URL, url == null ? "" : url);
    }

    // ── 订阅列表 ──

    /** 订阅列表（空表兜底） */
    @SuppressWarnings("unchecked")
    public static List<Subscription> getSubscriptions() {
        Object v = KeyValueStore.get(HawkConfig.SUBSCRIPTIONS, new ArrayList<Subscription>());
        return v instanceof List ? (List<Subscription>) v : new ArrayList<>();
    }

    public static void setSubscriptions(List<Subscription> subs) {
        KeyValueStore.put(HawkConfig.SUBSCRIPTIONS, subs == null ? new ArrayList<>() : subs);
    }

    // ── 搜索历史（快速搜索页记录）──

    /** 搜索历史词（空表兜底） */
    @SuppressWarnings("unchecked")
    public static List<String> getSearchHistory() {
        Object v = KeyValueStore.get(HawkConfig.HISTORY_SEARCH, new ArrayList<String>());
        return v instanceof List ? (List<String>) v : new ArrayList<>();
    }

    public static void setSearchHistory(List<String> history) {
        KeyValueStore.put(HawkConfig.HISTORY_SEARCH, history == null ? new ArrayList<>() : history);
    }

    public static void clearSearchHistory() {
        KeyValueStore.put(HawkConfig.HISTORY_SEARCH, new ArrayList<String>());
    }

    // ── 搜索源勾选记忆（按订阅接口地址分组）──

    /** 全部按源勾选记忆（key=接口地址;搜索页/搜索助手按 api 取用） */
    @SuppressWarnings("unchecked")
    public static HashMap<String, HashMap<String, String>> getCheckedSources() {
        Object v = KeyValueStore.get(HawkConfig.SOURCES_FOR_SEARCH, new HashMap<String, HashMap<String, String>>());
        return v instanceof HashMap ? (HashMap<String, HashMap<String, String>>) v : new HashMap<>();
    }

    public static void setCheckedSources(HashMap<String, HashMap<String, String>> sources) {
        KeyValueStore.put(HawkConfig.SOURCES_FOR_SEARCH, sources == null ? new HashMap<>() : sources);
    }

    // ── 订阅管理页导入目录记忆（文件选择器起始目录）──

    /** 上次导入选择的目录（未记忆返回空串，调用方用默认下载目录兜底） */
    public static String getLastImportDir() {
        return KeyValueStore.get("before_selected_path", "");
    }

    public static void setLastImportDir(String dir) {
        KeyValueStore.put("before_selected_path", dir == null ? "" : dir);
    }
}
