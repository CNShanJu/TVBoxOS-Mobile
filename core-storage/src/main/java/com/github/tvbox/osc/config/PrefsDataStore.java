package com.github.tvbox.osc.config;

import android.content.Context;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.rxjava3.RxDataStore;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 现代化偏好存储(Preferences DataStore)的同步门面(迁移试点:SystemConfig 纯标量域)。
 * <p>
 * 语义:启动一次性把磁盘读入内存,get 内存直读;put 同步落盘(串行锁),与旧 Hawk 的
 * "写后立即可读/同步持久化"体验一致。仅支持标量(int/boolean/string/float/long),
 * 对象键仍走 {@link KeyValueStore}(DataStore 化逐域推进)。
 */
public final class PrefsDataStore {

    private static final String FILE_NAME = "prefs.pb";

    private static volatile RxDataStore<Preferences> store;
    private static volatile ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    private static final Object WRITE_LOCK = new Object();

    private PrefsDataStore() {
    }

    /** App 启动调用一次(在 KeyValueStore.init 之后,存量迁移经 legacy 键) */
    public static void init(Context context) {
        if (store != null) return;
        synchronized (PrefsDataStore.class) {
            if (store != null) return;
            RxDataStore<Preferences> ds = new RxPreferenceDataStoreBuilder(
                    context == null ? null : context.getApplicationContext(), FILE_NAME).build();
            store = ds;
            try {
                Preferences prefs = ds.data().blockingFirst();
                if (prefs != null) {
                    ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();
                    for (Preferences.Key<?> k : prefs.asMap().keySet()) {
                        Object v = prefs.asMap().get(k);
                        if (v != null) map.put(k.getName(), v);
                    }
                    cache = map;
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    // ── 读(内存)──

    public static String getString(String key, String defValue) {
        Object v = cache.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    public static boolean getBoolean(String key, boolean defValue) {
        Object v = cache.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    public static int getInt(String key, int defValue) {
        Object v = cache.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defValue;
    }

    public static float getFloat(String key, float defValue) {
        Object v = cache.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : defValue;
    }

    public static long getLong(String key, long defValue) {
        Object v = cache.get(key);
        return v instanceof Number ? ((Number) v).longValue() : defValue;
    }

    public static boolean contains(String key) {
        return cache.containsKey(key);
    }

    // ── 写(同步落盘,串行)──

    public static void put(String key, Object value) {
        if (value instanceof String) {
            putString(key, (String) value);
        } else if (value instanceof Boolean) {
            putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            putInt(key, (Integer) value);
        } else if (value instanceof Float) {
            putFloat(key, (Float) value);
        } else if (value instanceof Long) {
            putLong(key, (Long) value);
        } else {
            throw new IllegalArgumentException("PrefsDataStore 仅支持标量:" + (value == null ? "null" : value.getClass().getName()));
        }
    }

    private static void putString(String key, String v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.stringKey(key), v);
            return m;
        });
    }

    private static void putBoolean(String key, boolean v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.booleanKey(key), v);
            return m;
        });
    }

    private static void putInt(String key, int v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.intKey(key), v);
            return m;
        });
    }

    private static void putFloat(String key, float v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.floatKey(key), v);
            return m;
        });
    }

    private static void putLong(String key, long v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.longKey(key), v);
            return m;
        });
    }

    /** 删除键(无论原存储类型;整表扫描移除同名校验值) */
    public static void delete(String key) {
        cache.remove(key);
        RxDataStore<Preferences> s = store;
        if (s == null) return;
        synchronized (WRITE_LOCK) {
            try {
                s.updateDataAsync(prefs -> {
                    MutablePreferences mutable = prefs.toMutablePreferences();
                    for (Preferences.Key<?> k : new ArrayList<>(prefs.asMap().keySet())) {
                        if (key.equals(k.getName())) {
                            mutable.remove(k);
                        }
                    }
                    return io.reactivex.rxjava3.core.Single.just(mutable);
                }).blockingGet();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void write(java.util.function.Function<Preferences, MutablePreferences> fn) {
        RxDataStore<Preferences> s = store;
        if (s == null) return; // init 前 put 丢弃(装配先 init)
        synchronized (WRITE_LOCK) {
            try {
                s.updateDataAsync(prefs -> io.reactivex.rxjava3.core.Single.just(fn.apply(prefs)))
                        .blockingGet();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }
}
