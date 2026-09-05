package com.github.tvbox.osc.config;

import com.github.tvbox.osc.log.LogConfigStore;

/**
 * {@link LogConfigStore} 的 Preferences DataStore 实现（:log 依赖倒置端；App 启动注入,
 * 与全应用现代化偏好同一底层;旧 Hawk 存量一次性迁移）。
 */
public final class DefaultLogConfigStore implements LogConfigStore {

    private static final String KEY_ENABLED = "app_log";
    private static final String KEY_LEVEL = "log_level";
    private static final String KEY_RETENTION = "log_retention";

    private static final DefaultLogConfigStore INSTANCE = new DefaultLogConfigStore();
    private static volatile boolean migrated = false;

    private DefaultLogConfigStore() {
    }

    public static LogConfigStore get() {
        migrateLegacy();
        return INSTANCE;
    }

    /** 旧 Hawk 存量一次性迁移到 PrefsDataStore(日志三键) */
    private static synchronized void migrateLegacy() {
        if (migrated) return;
        migrated = true;
        try {
            move(KEY_ENABLED, false);
            move(KEY_LEVEL, 1);
            move(KEY_RETENTION, 7);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void move(String key, T defValue) {
        if (KeyValueStore.contains(key)) {
            T v = (T) KeyValueStore.get(key, null);
            PrefsDataStore.put(key, v == null ? defValue : v);
            KeyValueStore.delete(key);
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return PrefsDataStore.getBoolean(key, defValue);
    }

    @Override
    public int getInt(String key, int defValue) {
        return PrefsDataStore.getInt(key, defValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        PrefsDataStore.put(key, value);
    }

    @Override
    public void putInt(String key, int value) {
        PrefsDataStore.put(key, value);
    }
}
