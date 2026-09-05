package com.github.tvbox.osc.util;

import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.config.KeyValueStore;
import com.github.tvbox.osc.config.PrefsDataStore;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;

/**
 * 直播偏好类型安全配置门面（改进.txt §五"配置：类型安全 Config"；DataStore 化）。
 * <p>
 * 收敛 LiveActivity/LiveSettingDialog/LiveSettingRightDialog/历史源弹窗对
 * {@link HawkConfig} 直播键的裸 Hawk 读写：键名沿用旧 key（兼容历史数据），
 * 读写封装为强类型方法并统一默认值；变更落日志便于排查。
 * <p>
 * 频道播放配置按"频道名"键存 JSON 文本；旧 Hawk 存量标量键类加载一次性迁移,
 * 频道键按访问惰性迁移(读取时迁一次并删旧键)。
 */
public final class LiveConfig {

    private static final Type STRING_LIST_TYPE = new TypeToken<ArrayList<String>>() {
    }.getType();

    private LiveConfig() {
    }

    static {
        migrateLegacy();
    }

    private static volatile boolean migratedLegacy = false;

    private static synchronized void migrateLegacy() {
        if (migratedLegacy) return;
        migratedLegacy = true;
        try {
            moveInt(HawkConfig.LIVE_CONNECT_TIMEOUT, 1);
            moveBool(HawkConfig.LIVE_SHOW_TIME, false);
            moveBool(HawkConfig.LIVE_SHOW_NET_SPEED, false);
            moveBool(HawkConfig.LIVE_CHANNEL_REVERSE, false);
            moveBool(HawkConfig.LIVE_CROSS_GROUP, false);
            moveString(HawkConfig.LIVE_CHANNEL, "");
            moveString(HawkConfig.EPG_URL, "");
            if (KeyValueStore.contains(HawkConfig.LIVE_HISTORY)) {
                ArrayList<String> list = KeyValueStore.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
                PrefsDataStore.putJson(HawkConfig.LIVE_HISTORY, list == null ? new ArrayList<String>() : list);
                KeyValueStore.delete(HawkConfig.LIVE_HISTORY);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void moveInt(String key, int def) {
        if (KeyValueStore.contains(key)) {
            PrefsDataStore.put(key, KeyValueStore.getInt(key, def));
            KeyValueStore.delete(key);
        }
    }

    private static void moveBool(String key, boolean def) {
        if (KeyValueStore.contains(key)) {
            PrefsDataStore.put(key, KeyValueStore.getBoolean(key, def));
            KeyValueStore.delete(key);
        }
    }

    private static void moveString(String key, String def) {
        if (KeyValueStore.contains(key)) {
            PrefsDataStore.put(key, KeyValueStore.getString(key, def));
            KeyValueStore.delete(key);
        }
    }

    // ── 超时换源（0-5 档，对应 5s~30s；默认 1 = 10s）──

    /** 超时换源档位索引(0-5)，默认 1 */
    public static int connectTimeout() {
        return PrefsDataStore.getInt(HawkConfig.LIVE_CONNECT_TIMEOUT, 1);
    }

    public static void setConnectTimeout(int index) {
        int v = Math.max(0, Math.min(5, index));
        if (v == connectTimeout()) return;
        PrefsDataStore.put(HawkConfig.LIVE_CONNECT_TIMEOUT, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 超时换源=" + v);
    }

    // ── 显示偏好 ──

    /** 显示时间(默认关) */
    public static boolean showTime() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_SHOW_TIME, false);
    }

    public static void setShowTime(boolean on) {
        if (showTime() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_SHOW_TIME, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 显示时间=" + on);
    }

    /** 显示网速(默认关) */
    public static boolean showNetSpeed() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_SHOW_NET_SPEED, false);
    }

    public static void setShowNetSpeed(boolean on) {
        if (showNetSpeed() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_SHOW_NET_SPEED, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 显示网速=" + on);
    }

    /** 换台方向反转(默认关:上=上一台) */
    public static boolean channelReverse() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_CHANNEL_REVERSE, false);
    }

    public static void setChannelReverse(boolean on) {
        if (channelReverse() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_CHANNEL_REVERSE, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 换台反转=" + on);
    }

    /** 上下键换台跨分组(默认关) */
    public static boolean crossGroup() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_CROSS_GROUP, false);
    }

    public static void setCrossGroup(boolean on) {
        if (crossGroup() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_CROSS_GROUP, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 跨选分组=" + on);
    }

    // ── 频道记忆 / 历史源 ──

    /** 最后播放频道名(默认空串) */
    public static String lastChannel() {
        return PrefsDataStore.getString(HawkConfig.LIVE_CHANNEL, "");
    }

    public static void setLastChannel(String name) {
        if (name == null) return;
        PrefsDataStore.put(HawkConfig.LIVE_CHANNEL, name);
    }

    /** 历史直播源列表(默认空;最多 20 条在调用方维护) */
    public static ArrayList<String> liveHistory() {
        ArrayList<String> list = PrefsDataStore.getJson(HawkConfig.LIVE_HISTORY, STRING_LIST_TYPE, new ArrayList<String>());
        return list == null ? new ArrayList<String>() : list;
    }

    /** 整体写回历史源列表 */
    public static void setLiveHistory(ArrayList<String> history) {
        PrefsDataStore.putJson(HawkConfig.LIVE_HISTORY, history == null ? new ArrayList<String>() : history);
    }

    // ── EPG / 频道播放配置 ──

    /** EPG 地址(由 :spider ApiConfig 加载订阅时写入,此处只读;未配置返回空串,调用方给默认值) */
    public static String epgUrl() {
        return PrefsDataStore.getString(HawkConfig.EPG_URL, "");
    }

    /** 某频道的播放配置覆写(未覆写返回 null,走默认配置;旧 Hawk 键按访问惰性迁移) */
    public static JSONObject channelPlayerConfig(String channelName) {
        if (channelName == null || channelName.isEmpty()) return null;
        String s = PrefsDataStore.getString(channelName, null);
        if (s == null) {
            Object v = KeyValueStore.get(channelName, null);
            if (v instanceof JSONObject) {
                s = v.toString();
                PrefsDataStore.put(channelName, s);
                KeyValueStore.delete(channelName);
            }
        }
        if (s == null || s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 覆写/更新某频道播放配置(存 JSON 文本) */
    public static void setChannelPlayerConfig(String channelName, JSONObject cfg) {
        if (channelName == null || channelName.isEmpty()) return;
        if (cfg == null) {
            PrefsDataStore.delete(channelName);
        } else {
            PrefsDataStore.put(channelName, cfg.toString());
        }
    }

    /** 清除某频道覆写(回到默认配置;同时清理旧 Hawk 残留键) */
    public static void deleteChannelPlayerConfig(String channelName) {
        if (channelName == null || channelName.isEmpty()) return;
        PrefsDataStore.delete(channelName);
        KeyValueStore.delete(channelName);
    }
}
