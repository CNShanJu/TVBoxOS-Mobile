package com.github.tvbox.osc.util;
import com.github.tvbox.osc.config.SystemConfig;

import android.content.Context;
import android.view.View;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.github.tvbox.osc.base.App;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载动画统一配置:通过设置页"加载动画"选项切换全局加载动画。
 * <p>
 * 目录结构(每个动画一个文件夹,统一配置文件 config.json):
 * <pre>
 * assets/loading/
 *   anim_loading/           默认动画
 *     anim_loading.json     Lottie 动画文件
 *     config.json           配置:{ "mbox_tipsname": "默认", "size": 72 }
 *   glowing_fish_loader/    Glowing Fish
 *     glowing_fish_loader.json
 *     config.json           { "mbox_tipsname": "鱼", "size": 84 }
 * </pre>
 * 展示名(mbox_tipsname)与页面显示尺寸(size,dp)统一从 config.json 读取,不再读 lottie 文件。
 * 选择值(HawkConfig.LOADING_ANIM)存动画文件夹名;旧版存的文件名/数字自动兼容。
 */
public class LoadingAnim {

    /** 可选动画根目录(assets 下) */
    public static final String DIR_NAME = "loading";
    /** 统一配置文件名称 */
    public static final String CONFIG_FILE = "config.json";
    /** 默认动画文件夹名(loading 下) */
    public static final String DEFAULT_NAME = "anim_loading";
    /** 配置键:视频播放里的尺寸(dp) */
    private static final String KEY_PLAYER = "size_player";
    /** 配置键:其他地方的尺寸(dp) */
    private static final String KEY_OTHER = "size_other";

    /** 兼容旧版:Glowing Fish 的旧选择值 1 映射到文件夹名 */
    private static final String LEGACY_GLOWING_FISH_NAME = "glowing_fish_loader";

    /** 默认动画显示尺寸(dp),配置文件缺失/异常时兜底 */
    private static final int DEFAULT_SIZE_DP = 72;

    /** 配置读取缓存:文件夹名 -> 配置 JSON */
    private static final Map<String, JSONObject> configCache = new HashMap<>();

    /**
     * 当前配置的动画文件夹名(如 anim_loading / glowing_fish_loader)。
     * 兼容旧值:文件名(glowing_fish_loader.json)、带 loading/ 前缀、旧数字(0/1)。
     */
    public static String getAnimName() {
        try {
            Object sel = SystemConfig.getLoadingAnimRaw();
            if (sel instanceof String) {
                String name = (String) sel;
                if (name != null && !name.isEmpty()) {
                    String bare = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                    if (bare.endsWith(".json")) bare = bare.substring(0, bare.length() - 5);
                    if (exists(bare)) return bare;
                    return DEFAULT_NAME;
                }
                return DEFAULT_NAME;
            }
            if (sel instanceof Number) {
                int v = ((Number) sel).intValue();
                if (v == 1 && exists(LEGACY_GLOWING_FISH_NAME)) return LEGACY_GLOWING_FISH_NAME;
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_NAME;
    }

    /** 默认动画的 lottie 文件路径 */
    public static String getDefaultFileName() {
        return DIR_NAME + "/" + DEFAULT_NAME + "/" + DEFAULT_NAME + ".json";
    }

    /** 当前配置动画的 lottie 文件路径 */
    public static String getAnimFileName() {
        String name = getAnimName();
        return DIR_NAME + "/" + name + "/" + name + ".json";
    }

    /** 当前配置动画在视频播放里的显示尺寸(dp),来自 config.json 的 size_player */
    public static int getPlayerSizeDp() {
        return getSizeDp(getAnimName(), KEY_PLAYER);
    }

    /** 当前配置动画在其他地方的显示尺寸(dp),来自 config.json 的 size_other */
    public static int getOtherSizeDp() {
        return getSizeDp(getAnimName(), KEY_OTHER);
    }

    /** 可用加载动画列表:loading/ 下的子目录(每个目录 = 一个动画),按目录名排序 */
    public static List<String> getAvailableAnimFiles() {
        List<String> list = new ArrayList<>();
        try {
            Context ctx = App.getInstance();
            if (ctx == null || ctx.getAssets() == null) return list;
            String[] dirs = ctx.getAssets().list(DIR_NAME);
            if (dirs != null) {
                for (String d : dirs) {
                    if (d != null && !d.startsWith(".") && !d.contains(".") && exists(d)) {
                        list.add(d);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        Collections.sort(list);
        return list;
    }

    /**
     * 展示名:优先读取该动画 config.json 的 mbox_tipsname(如 "鱼"),
     * 缺失/读取失败时兜底用文件夹名。
     */
    public static String displayName(String animName) {
        if (animName == null) return "";
        String bare = animName.contains("/") ? animName.substring(animName.lastIndexOf('/') + 1) : animName;
        JSONObject cfg = readConfig(bare);
        if (cfg != null) {
            String tips = cfg.optString("mbox_tipsname", "");
            if (!tips.isEmpty()) return tips;
        }
        return bare;
    }

    /** 动画显示尺寸(dp):config.json 的对应键,缺失/异常返回默认 72 */
    private static int getSizeDp(String animName, String key) {
        JSONObject cfg = readConfig(animName);
        int size = cfg != null ? cfg.optInt(key, DEFAULT_SIZE_DP) : DEFAULT_SIZE_DP;
        return size > 0 ? size : DEFAULT_SIZE_DP;
    }

    /** 读取动画文件夹的 config.json(带缓存) */
    private static JSONObject readConfig(String animName) {
        synchronized (configCache) {
            JSONObject cached = configCache.get(animName);
            if (cached != null) return cached;
        }
        JSONObject cfg = null;
        try {
            String assetPath = DIR_NAME + "/" + animName + "/" + CONFIG_FILE;
            try (InputStream is = App.getInstance().getAssets().open(assetPath)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                cfg = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        synchronized (configCache) {
            configCache.put(animName, cfg);
        }
        return cfg;
    }

    /** 动画目录是否存在 */
    private static boolean exists(String animName) {
        if (animName == null || animName.isEmpty()) return false;
        try {
            String[] list = App.getInstance().getAssets().list(DIR_NAME + "/" + animName);
            return list != null && list.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 把全局加载动画应用到指定 Lottie 视图(动态切换动画文件) */
    public static void apply(View view) {
        if (view instanceof LottieAnimationView) {
            LottieAnimationView lav = (LottieAnimationView) view;
            try {
                String animName = getAnimName();
                // 尺寸按配置区分:视频播放里(tag=vod_control_loading)用 size_player,其他地方用 size_other;
                // 先设动画再改尺寸,且尺寸未变化不触发重排,避免初始化/加载期间被干扰
                boolean player = "vod_control_loading".equals(view.getTag());
                int size = getSizeDp(animName, player ? KEY_PLAYER : KEY_OTHER);
                lav.setAnimation(DIR_NAME + "/" + animName + "/" + animName + ".json");
                lav.setRepeatMode(LottieDrawable.RESTART); // 从头循环,不是往返播放(reverse)
                lav.setRepeatCount(LottieDrawable.INFINITE);
                lav.setSpeed(1f); // 原速播放(代码设置动画时 XML 的 lottie_speed 不生效,需显式指定)
                // 动画含高斯模糊等超出画布内容时关闭按画布裁剪,避免光晕被边界切掉
                lav.setClipToCompositionBounds(false);
                android.view.ViewGroup.LayoutParams lp = lav.getLayoutParams();
                if (lp != null) {
                    int px = Math.round(size * view.getResources().getDisplayMetrics().density);
                    if (lp.width != px || lp.height != px) {
                        lp.width = px;
                        lp.height = px;
                        lav.setLayoutParams(lp);
                    }
                }
                lav.playAnimation();
            } catch (Throwable th) {
                // 动画文件异常时静默回退,不阻塞加载页展示
                th.printStackTrace();
            }
        }
    }
}
