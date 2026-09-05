package com.github.tvbox.osc.config;

import com.orhanobut.hawk.Hawk;

/**
 * 键值存储类型安全封装（core-storage 配置收口：Hawk 的类型安全门面）。
 * <p>
 * 键沿用旧应用 key（历史数据兼容）；只暴露强类型 get 与 put/delete/contains，
 * UI/业务不直接触碰 Hawk 实现与原始值转换。后续配置门面（SystemConfig 等）与
 * 各业务 Config 统一经本类读写。
 */
public final class KeyValueStore {

    private KeyValueStore() {
    }

    // ── 读（强类型 + 默认值兜底）──

    public static String getString(String key, String defValue) {
        Object v = Hawk.get(key, null);
        return v instanceof String ? (String) v : defValue;
    }

    public static boolean getBoolean(String key, boolean defValue) {
        Object v = Hawk.get(key, null);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    public static int getInt(String key, int defValue) {
        Object v = Hawk.get(key, null);
        if (v instanceof Number) return ((Number) v).intValue();
        return defValue;
    }

    /** 任意类型读取（内部存储结构由调用方保证;null 时返回 defValue） */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, T defValue) {
        T v = (T) Hawk.get(key, null);
        return v == null ? defValue : v;
    }

    // ── 写 / 删 / 存在 ──

    public static void put(String key, Object value) {
        Hawk.put(key, value);
    }

    public static void delete(String key) {
        Hawk.delete(key);
    }

    public static boolean contains(String key) {
        return Hawk.contains(key);
    }
}
