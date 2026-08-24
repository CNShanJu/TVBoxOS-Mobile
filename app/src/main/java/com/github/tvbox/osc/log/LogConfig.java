package com.github.tvbox.osc.log;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 日志配置门面（配置门面模式：数据自持 + 模块内持久化 + 变更订阅）。
 * <p>
 * 数据维护在 log 模块内部（Hawk 键），对外只暴露 查询 / 操作 / 订阅：
 * <ul>
 *   <li>查询：{@link #isEnabled()} / {@link #getLevel()} / {@link #getRetentionDays()}</li>
 *   <li>操作：{@link #setEnabled(boolean)}（联动 LogStore + logcat 捕获）/ {@link #setLevel(int)} /
 *       {@link #setRetentionDays(int)}</li>
 *   <li>订阅：{@link #subscribe(Listener)}——设置页等关注方刷新 UI</li>
 * </ul>
 * 持久化 key：开关沿用 {@link HawkConfig#APP_LOG}（与旧设置兼容），级别/保留天数新增独立 key。
 */
public final class LogConfig {

    /** 开关（沿用旧 key，兼容历史设置） */
    private static final String KEY_ENABLED = HawkConfig.APP_LOG;
    private static final String KEY_LEVEL = "log_level";
    private static final String KEY_RETENTION = "log_retention";

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private LogConfig() {
    }

    public interface Listener {
        void onConfigChanged();
    }

    // ── 查询 ──

    public static boolean isEnabled() {
        return Hawk.get(KEY_ENABLED, false);
    }

    /** 0=DEBUG 1=INFO 2=WARN 3=ERROR，默认 INFO */
    public static int getLevel() {
        return Math.max(LogStore.LEVEL_DEBUG, Math.min(LogStore.LEVEL_ERROR, Hawk.get(KEY_LEVEL, LogStore.LEVEL_INFO)));
    }

    /** 默认 7 天 */
    public static int getRetentionDays() {
        int v = Hawk.get(KEY_RETENTION, 7);
        return Math.max(1, Math.min(30, v));
    }

    // ── 操作（内部校验 + 持久化 + 广播变更）──

    public static void setEnabled(boolean on) {
        if (isEnabled() == on) return;
        Hawk.put(KEY_ENABLED, on);
        LogStore.get().setEnabled(on);
        fireChanged();
    }

    public static void setLevel(int level) {
        int v = Math.max(LogStore.LEVEL_DEBUG, Math.min(LogStore.LEVEL_ERROR, level));
        if (getLevel() == v) return;
        Hawk.put(KEY_LEVEL, v);
        LogStore.get().setLevel(v);
        fireChanged();
    }

    public static void setRetentionDays(int days) {
        int v = Math.max(1, Math.min(30, days));
        if (getRetentionDays() == v) return;
        Hawk.put(KEY_RETENTION, v);
        fireChanged();
    }

    // ── 订阅 ──

    public static void subscribe(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    private static void fireChanged() {
        for (Listener l : listeners) {
            try {
                l.onConfigChanged();
            } catch (Throwable ignored) {
            }
        }
    }
}
