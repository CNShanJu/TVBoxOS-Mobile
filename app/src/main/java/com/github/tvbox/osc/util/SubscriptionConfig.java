package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.config.KeyValueStore;
import com.github.tvbox.osc.config.PrefsDataStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 订阅/搜索域配置门面(UI 摘 Hawk;DataStore 化:标量直存,对象/容器经 gson typed JSON)。
 * <p>
 * 键沿用 {@link HawkConfig} 旧应用 key(历史设置兼容);旧 Hawk 存量类加载时一次性导入并删旧键。
 * 订阅**加载/落库**(ApiConfig)仍在 :spider 侧处理,本门面只向 UI 提供类型安全的读写。
 */
public final class SubscriptionConfig {

    private static final Type SUBSCRIPTION_LIST_TYPE = new TypeToken<List<Subscription>>() {
    }.getType();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();
    private static final Type SOURCES_MAP_TYPE =
            new TypeToken<HashMap<String, HashMap<String, String>>>() {
            }.getType();

    private static final String KEY_IMPORT_DIR = "before_selected_path";

    private SubscriptionConfig() {
    }

    static {
        migrateLegacy();
    }

    /** 旧 Hawk 存量一次性迁移到 PrefsDataStore(DataStore 化;类加载时执行,PrefsDataStore 已由 App 启动早期 init) */
    private static volatile boolean migratedLegacy = false;

    private static synchronized void migrateLegacy() {
        if (migratedLegacy) return;
        migratedLegacy = true;
        try {
            if (KeyValueStore.contains(HawkConfig.API_URL)) {
                PrefsDataStore.put(HawkConfig.API_URL, KeyValueStore.getString(HawkConfig.API_URL, ""));
                KeyValueStore.delete(HawkConfig.API_URL);
            }
            if (KeyValueStore.contains(HawkConfig.SUBSCRIPTIONS)) {
                PrefsDataStore.putJson(HawkConfig.SUBSCRIPTIONS,
                        KeyValueStore.get(HawkConfig.SUBSCRIPTIONS, new ArrayList<Subscription>()));
                KeyValueStore.delete(HawkConfig.SUBSCRIPTIONS);
            }
            if (KeyValueStore.contains(HawkConfig.DEFAULT_SUBS)) {
                PrefsDataStore.putJson(HawkConfig.DEFAULT_SUBS,
                        KeyValueStore.get(HawkConfig.DEFAULT_SUBS, new ArrayList<Subscription>()));
                KeyValueStore.delete(HawkConfig.DEFAULT_SUBS);
            }
            if (KeyValueStore.contains(HawkConfig.HISTORY_SEARCH)) {
                PrefsDataStore.putJson(HawkConfig.HISTORY_SEARCH,
                        KeyValueStore.get(HawkConfig.HISTORY_SEARCH, new ArrayList<String>()));
                KeyValueStore.delete(HawkConfig.HISTORY_SEARCH);
            }
            if (KeyValueStore.contains(HawkConfig.SOURCES_FOR_SEARCH)) {
                PrefsDataStore.putJson(HawkConfig.SOURCES_FOR_SEARCH,
                        KeyValueStore.get(HawkConfig.SOURCES_FOR_SEARCH,
                                new HashMap<String, HashMap<String, String>>()));
                KeyValueStore.delete(HawkConfig.SOURCES_FOR_SEARCH);
            }
            if (KeyValueStore.contains(KEY_IMPORT_DIR)) {
                PrefsDataStore.put(KEY_IMPORT_DIR, KeyValueStore.getString(KEY_IMPORT_DIR, ""));
                KeyValueStore.delete(KEY_IMPORT_DIR);
            }
        } catch (Throwable ignored) {
        }
    }

    // ── 订阅地址(当前启用接口地址)──

    /** 当前启用订阅接口地址(未设置返回空串) */
    public static String getApiUrl() {
        return PrefsDataStore.getString(HawkConfig.API_URL, "");
    }

    public static void setApiUrl(String url) {
        PrefsDataStore.put(HawkConfig.API_URL, url == null ? "" : url);
    }

    // ── 订阅列表 ──

    /** 订阅列表(空表兜底) */
    public static List<Subscription> getSubscriptions() {
        return PrefsDataStore.getJson(HawkConfig.SUBSCRIPTIONS, SUBSCRIPTION_LIST_TYPE, new ArrayList<Subscription>());
    }

    public static void setSubscriptions(List<Subscription> subs) {
        PrefsDataStore.putJson(HawkConfig.SUBSCRIPTIONS, subs == null ? new ArrayList<>() : subs);
    }

    // ── 本地默认订阅(打包 assets 注入;App 启动与文件同步)──

    /** 上次记录的本地默认订阅集(未记录返回空表) */
    public static List<Subscription> getDefaultSubs() {
        return PrefsDataStore.getJson(HawkConfig.DEFAULT_SUBS, SUBSCRIPTION_LIST_TYPE, new ArrayList<Subscription>());
    }

    public static void setDefaultSubs(List<Subscription> subs) {
        PrefsDataStore.putJson(HawkConfig.DEFAULT_SUBS, subs == null ? new ArrayList<>() : subs);
    }

    /** 是否记录过本地默认订阅集(迁移兼容判定用) */
    public static boolean containsDefaultSubs() {
        return PrefsDataStore.contains(HawkConfig.DEFAULT_SUBS);
    }

    // ── 搜索历史(快速搜索页记录)──

    /** 搜索历史词(空表兜底) */
    public static List<String> getSearchHistory() {
        return PrefsDataStore.getJson(HawkConfig.HISTORY_SEARCH, STRING_LIST_TYPE, new ArrayList<String>());
    }

    public static void setSearchHistory(List<String> history) {
        PrefsDataStore.putJson(HawkConfig.HISTORY_SEARCH, history == null ? new ArrayList<>() : history);
    }

    public static void clearSearchHistory() {
        PrefsDataStore.putJson(HawkConfig.HISTORY_SEARCH, new ArrayList<String>());
    }

    // ── 搜索源勾选记忆(按订阅接口地址分组)──

    /** 全部按源勾选记忆(key=接口地址;搜索页/搜索助手按 api 取用) */
    public static HashMap<String, HashMap<String, String>> getCheckedSources() {
        return PrefsDataStore.getJson(HawkConfig.SOURCES_FOR_SEARCH, SOURCES_MAP_TYPE,
                new HashMap<String, HashMap<String, String>>());
    }

    public static void setCheckedSources(HashMap<String, HashMap<String, String>> sources) {
        PrefsDataStore.putJson(HawkConfig.SOURCES_FOR_SEARCH,
                sources == null ? new HashMap<>() : sources);
    }

    // ── 订阅管理页导入目录记忆(文件选择器起始目录)──

    /** 上次导入选择的目录(未记忆返回空串,调用方用默认下载目录兜底) */
    public static String getLastImportDir() {
        return PrefsDataStore.getString(KEY_IMPORT_DIR, "");
    }

    public static void setLastImportDir(String dir) {
        PrefsDataStore.put(KEY_IMPORT_DIR, dir == null ? "" : dir);
    }
}
