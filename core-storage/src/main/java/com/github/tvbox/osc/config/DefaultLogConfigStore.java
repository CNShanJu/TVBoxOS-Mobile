package com.github.tvbox.osc.config;

import com.github.tvbox.osc.log.LogConfigStore;

/**
 * {@link LogConfigStore} 的 KeyValueStore 实现（:log 依赖倒置端；App 启动注入,
 * 与全应用配置同一底层,避免同一键两侧分叉）。
 */
public final class DefaultLogConfigStore implements LogConfigStore {

    private static final DefaultLogConfigStore INSTANCE = new DefaultLogConfigStore();

    private DefaultLogConfigStore() {
    }

    public static LogConfigStore get() {
        return INSTANCE;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return KeyValueStore.getBoolean(key, defValue);
    }

    @Override
    public int getInt(String key, int defValue) {
        return KeyValueStore.getInt(key, defValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        KeyValueStore.put(key, value);
    }

    @Override
    public void putInt(String key, int value) {
        KeyValueStore.put(key, value);
    }
}
