package com.github.tvbox.osc.config;



import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 系统配置门面（配置门面模式 3.6：数据自持 + 模块内持久化 + 变更订阅）。
 * <p>
 * 数据维护在系统层（common 模块内，Hawk 键沿用旧应用 key，与历史设置兼容）；
 * 对外只暴露 查询 / 操作 / 订阅 / 备份：
 * <ul>
 *   <li>查询：getDohUrl / getTheme / getLoadingAnim / getHomeRec / getHistoryNum / getLiveUrl / isPrivateBrowsing</li>
 *   <li>操作：对应 setXxx（内部校验 + 持久化 + 广播变更）</li>
 *   <li>订阅：{@link #subscribe(Listener)}——设置页等关注方刷新 UI</li>
 *   <li>备份：{@link #exportConfig()} / {@link #importConfig(Map)}（BackupDialog 聚合）</li>
 * </ul>
 * 联动（如 DNS 切换后重建 DnsOverHttps、主题切换后重载 UI）由关注方订阅/调用方执行，
 * 门面只保证"数据 + 持久化 + 广播"。
 */
public final class SystemConfig {

    // 键沿用旧应用 key（兼容历史设置）
    private static final String KEY_DOH_URL = "doh_url";
    private static final String KEY_THEME = "theme_tag";
    private static final String KEY_LOADING_ANIM = "loading_anim";
    private static final String KEY_HOME_REC = "home_rec";
    private static final String KEY_HISTORY_NUM = "history_num";
    private static final String KEY_LIVE_URL = "live_url";
    private static final String KEY_PRIVATE_BROWSING = "private_browsing";
    // UI/功能偏好（同样沿用旧应用 key，历史设置兼容）
    private static final String KEY_SHOW_PREVIEW = "show_preview";
    private static final String KEY_FAST_SEARCH_MODE = "fast_search_mode";
    private static final String KEY_DEBUG_OPEN = "debug_open";
    private static final String KEY_IGNORE_SSL_ERROR = "ignore_ssl_error";
    private static final String KEY_LAN_SERVER_ENABLE = "lan_server_enable";

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();


    private SystemConfig() {
    }

    public interface Listener {
        void onConfigChanged();
    }

    // ── 查询 ──

    /** 安全 DNS 选项索引（0 关闭），默认 0 */
    public static int getDohUrl() {
        return PrefsDataStore.getInt(KEY_DOH_URL, 0);
    }

    /** 主题：0 跟随系统 1 浅色 2 深色，默认 0 */
    public static int getTheme() {
        return PrefsDataStore.getInt(KEY_THEME, 0);
    }

    /** 加载动画文件夹名（空串=默认），默认空 */
    public static String getLoadingAnim() {
        return PrefsDataStore.getString(KEY_LOADING_ANIM, "");
    }

    /** 加载动画原始存储值（兼容旧数字 0/1 等历史值，供 LoadingAnim 兼容解析；新代码用 {@link #getLoadingAnim()}） */
    public static Object getLoadingAnimRaw() {
        return PrefsDataStore.getString(KEY_LOADING_ANIM, null);
    }

    /** 主页内容显示：0 豆瓣热播 1 站点推荐 2 关闭，默认 0 */
    public static int getHomeRec() {
        return PrefsDataStore.getInt(KEY_HOME_REC, 0);
    }

    /** 保留历史记录数量选项，默认 0 */
    public static int getHistoryNum() {
        return PrefsDataStore.getInt(KEY_HISTORY_NUM, 0);
    }

    /** 直播源地址，默认空 */
    public static String getLiveUrl() {
        return PrefsDataStore.getString(KEY_LIVE_URL, "");
    }

    /** 无痕浏览（不存搜索/观看历史），默认关 */
    public static boolean isPrivateBrowsing() {
        return PrefsDataStore.getBoolean(KEY_PRIVATE_BROWSING, false);
    }

    /** 详情页缩略预览，默认开 */
    public static boolean isShowPreview() {
        return PrefsDataStore.getBoolean(KEY_SHOW_PREVIEW, true);
    }

    /** 快速搜索模式（列表页点击结果直接起快速搜索），默认关 */
    public static boolean isFastSearchMode() {
        return PrefsDataStore.getBoolean(KEY_FAST_SEARCH_MODE, false);
    }

    /** 调试叠加层/调试日志（播放页 debug 视图、网络日志等），默认关 */
    public static boolean isDebugOpen() {
        return PrefsDataStore.getBoolean(KEY_DEBUG_OPEN, false);
    }

    /** 忽略 HTTPS 证书错误（默认关：开启会降低 TLS 安全性，仅个别自签名站点用） */
    public static boolean isIgnoreSslError() {
        return PrefsDataStore.getBoolean(KEY_IGNORE_SSL_ERROR, false);
    }

    /** 局域网服务开关（默认关：关闭时 HTTP 服务仅监听 127.0.0.1） */
    public static boolean isLanServerEnabled() {
        return PrefsDataStore.getBoolean(KEY_LAN_SERVER_ENABLE, false);
    }

    // ── 操作（内部校验 + 持久化 + 广播变更）──

    public static void setDohUrl(int pos) {
        int v = Math.max(0, pos);
        if (getDohUrl() == v) return;
        PrefsDataStore.put(KEY_DOH_URL, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 安全DNS=" + v);
        fireChanged();
    }

    public static void setTheme(int tag) {
        int v = Math.max(0, Math.min(2, tag));
        if (getTheme() == v) return;
        PrefsDataStore.put(KEY_THEME, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 主题=" + v);
        fireChanged();
    }

    public static void setLoadingAnim(String name) {
        String v = name == null ? "" : name;
        if (v.equals(getLoadingAnim())) return;
        PrefsDataStore.put(KEY_LOADING_ANIM, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 加载动画=" + v);
        fireChanged();
    }

    public static void setHomeRec(int type) {
        int v = Math.max(0, Math.min(2, type));
        if (getHomeRec() == v) return;
        PrefsDataStore.put(KEY_HOME_REC, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 首页内容=" + v);
        fireChanged();
    }

    public static void setHistoryNum(int num) {
        int v = Math.max(0, num);
        if (getHistoryNum() == v) return;
        PrefsDataStore.put(KEY_HISTORY_NUM, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 历史记录数=" + v);
        fireChanged();
    }

    public static void setLiveUrl(String url) {
        String v = url == null ? "" : url;
        if (v.equals(getLiveUrl())) return;
        PrefsDataStore.put(KEY_LIVE_URL, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 直播源=" + v);
        fireChanged();
    }

    public static void setPrivateBrowsing(boolean on) {
        if (isPrivateBrowsing() == on) return;
        PrefsDataStore.put(KEY_PRIVATE_BROWSING, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 无痕浏览=" + on);
        fireChanged();
    }

    public static void setShowPreview(boolean on) {
        if (isShowPreview() == on) return;
        PrefsDataStore.put(KEY_SHOW_PREVIEW, on);
        fireChanged();
    }

    public static void setFastSearchMode(boolean on) {
        if (isFastSearchMode() == on) return;
        PrefsDataStore.put(KEY_FAST_SEARCH_MODE, on);
        fireChanged();
    }

    public static void setDebugOpen(boolean on) {
        if (isDebugOpen() == on) return;
        PrefsDataStore.put(KEY_DEBUG_OPEN, on);
        fireChanged();
    }

    public static void setIgnoreSslError(boolean on) {
        if (isIgnoreSslError() == on) return;
        PrefsDataStore.put(KEY_IGNORE_SSL_ERROR, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 忽略证书错误=" + on);
        fireChanged();
    }

    public static void setLanServerEnabled(boolean on) {
        if (isLanServerEnabled() == on) return;
        PrefsDataStore.put(KEY_LAN_SERVER_ENABLE, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 局域网服务=" + on);
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

    // ── 备份/恢复（BackupDialog 聚合各模块配置）──

    public static Map<String, Object> exportConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put(KEY_DOH_URL, getDohUrl());
        cfg.put(KEY_THEME, getTheme());
        cfg.put(KEY_LOADING_ANIM, getLoadingAnim());
        cfg.put(KEY_HOME_REC, getHomeRec());
        cfg.put(KEY_HISTORY_NUM, getHistoryNum());
        cfg.put(KEY_LIVE_URL, getLiveUrl());
        cfg.put(KEY_PRIVATE_BROWSING, isPrivateBrowsing());
        return cfg;
    }

    public static void importConfig(Map<String, Object> cfg) {
        if (cfg == null) return;
        Object v;
        if ((v = cfg.get(KEY_DOH_URL)) instanceof Number) setDohUrl(((Number) v).intValue());
        if ((v = cfg.get(KEY_THEME)) instanceof Number) setTheme(((Number) v).intValue());
        if ((v = cfg.get(KEY_LOADING_ANIM)) instanceof String) setLoadingAnim((String) v);
        if ((v = cfg.get(KEY_HOME_REC)) instanceof Number) setHomeRec(((Number) v).intValue());
        if ((v = cfg.get(KEY_HISTORY_NUM)) instanceof Number) setHistoryNum(((Number) v).intValue());
        if ((v = cfg.get(KEY_LIVE_URL)) instanceof String) setLiveUrl((String) v);
        if ((v = cfg.get(KEY_PRIVATE_BROWSING)) instanceof Boolean) setPrivateBrowsing((Boolean) v);
    }
}
