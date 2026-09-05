package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 直播偏好类型安全配置门面（改进.txt §五"配置：类型安全 Config"）。
 * <p>
 * 收敛 LiveActivity/LiveSettingDialog/LiveSettingRightDialog/历史源弹窗对
 * {@link HawkConfig} 直播键的裸 Hawk 读写：键名沿用旧 key（兼容历史数据），
 * 读写封装为强类型方法并统一默认值；变更落日志便于排查。
 * <p>
 * 说明：EPG_URL 由 :spider ApiConfig 加载订阅时写入，跨模块共享，不归本类；
 * 本类只覆盖"直播偏好/频道/历史源"这一类 UI 配置。
 */
public final class LiveConfig {

    private LiveConfig() {
    }

    // ── 超时换源（0-5 档，对应 5s~30s；默认 1 = 10s）──

    /** 超时换源档位索引(0-5)，默认 1 */
    public static int connectTimeout() {
        return Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1);
    }

    public static void setConnectTimeout(int index) {
        int v = Math.max(0, Math.min(5, index));
        if (v == connectTimeout()) return;
        Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 超时换源=" + v);
    }

    // ── 显示偏好 ──

    /** 显示时间(默认关) */
    public static boolean showTime() {
        return Hawk.get(HawkConfig.LIVE_SHOW_TIME, false);
    }

    public static void setShowTime(boolean on) {
        if (showTime() == on) return;
        Hawk.put(HawkConfig.LIVE_SHOW_TIME, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 显示时间=" + on);
    }

    /** 显示网速(默认关) */
    public static boolean showNetSpeed() {
        return Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false);
    }

    public static void setShowNetSpeed(boolean on) {
        if (showNetSpeed() == on) return;
        Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 显示网速=" + on);
    }

    /** 换台方向反转(默认关:上=上一台) */
    public static boolean channelReverse() {
        return Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false);
    }

    public static void setChannelReverse(boolean on) {
        if (channelReverse() == on) return;
        Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 换台反转=" + on);
    }

    /** 上下键换台跨分组(默认关) */
    public static boolean crossGroup() {
        return Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false);
    }

    public static void setCrossGroup(boolean on) {
        if (crossGroup() == on) return;
        Hawk.put(HawkConfig.LIVE_CROSS_GROUP, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "直播设置: 跨选分组=" + on);
    }

    // ── 频道记忆 / 历史源 ──

    /** 最后播放频道名(默认空串) */
    public static String lastChannel() {
        return Hawk.get(HawkConfig.LIVE_CHANNEL, "");
    }

    public static void setLastChannel(String name) {
        if (name == null) return;
        Hawk.put(HawkConfig.LIVE_CHANNEL, name);
    }

    /** 历史直播源列表(默认空;最多 20 条在调用方维护) */
    public static ArrayList<String> liveHistory() {
        ArrayList<String> list = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
        return list == null ? new ArrayList<String>() : list;
    }

    /** 整体写回历史源列表 */
    public static void setLiveHistory(ArrayList<String> history) {
        Hawk.put(HawkConfig.LIVE_HISTORY, history == null ? new ArrayList<String>() : history);
    }

    // ── EPG / 频道播放配置 ──

    /** EPG 地址(由 :spider ApiConfig 加载订阅时写入,此处只读;未配置返回空串,调用方给默认值) */
    public static String epgUrl() {
        return Hawk.get(HawkConfig.EPG_URL, "");
    }

    /** 某频道的播放配置覆写(未覆写返回 null,走默认配置) */
    public static JSONObject channelPlayerConfig(String channelName) {
        if (channelName == null || channelName.isEmpty()) return null;
        Object v = Hawk.get(channelName, null);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    /** 覆写/更新某频道播放配置 */
    public static void setChannelPlayerConfig(String channelName, JSONObject cfg) {
        if (channelName == null || channelName.isEmpty()) return;
        Hawk.put(channelName, cfg);
    }

    /** 清除某频道覆写(回到默认配置) */
    public static void deleteChannelPlayerConfig(String channelName) {
        if (channelName == null || channelName.isEmpty()) return;
        Hawk.delete(channelName);
    }
}
