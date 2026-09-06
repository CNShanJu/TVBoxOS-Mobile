package com.github.tvbox.osc.config;

import android.content.Context;

/**
 * 键值存储类型安全封装——Hawk 一次性迁移通道已下线(版 N:无 hawk 化灰度)。
 * <p>
 * 历史:各域(系统偏好/播放设置/下载任务档案与策略/订阅/直播/日志/遥控/爬虫缓存)已迁到
 * DataStore({@link PrefsDataStore})或应用私有文件,旧 Hawk 键均按访问"读一次即删"。本类原为
 * Hawk 类型安全门面;现已摘除 Hawk 后端与依赖:
 * <ul>
 *   <li>{@link #init} 为空占位实现(保留以便 App 装配调用点不改);</li>
 *   <li>旧键读取(contains/get*)一律返回"不存在/默认值"——已跑过迁移的存量设备无感,
 *       各域 legacy 分支自然失效不再触发;</li>
 *   <li>put/delete 为空操作:当前全仓无活跃 legacy 写入,禁止再经本类写旧键。</li>
 * </ul>
 * 版 N+1(下一发布窗口,旧版升级数据回归通过后):删除本类与各域 legacy 分支调用点。
 */
public final class KeyValueStore {

    private KeyValueStore() {
    }

    /** 存储初始化占位(原 Hawk.init;已无后端) */
    public static void init(Context context) {
    }

    // ── legacy 迁移通道读取:已无后端,一律默认值(见类注释)──

    public static String getString(String key, String defValue) {
        return defValue;
    }

    public static boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    public static int getInt(String key, int defValue) {
        return defValue;
    }

    /** 任意类型读取:存量迁移通道已下线,恒返回 defValue */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, T defValue) {
        return defValue;
    }

    // ── 写 / 删 / 存在:禁止再经 legacy 键读写(当前全仓无活跃调用)──

    public static void put(String key, Object value) {
    }

    public static void delete(String key) {
    }

    public static boolean contains(String key) {
        return false;
    }
}
