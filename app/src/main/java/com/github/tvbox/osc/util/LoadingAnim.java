package com.github.tvbox.osc.util;

import android.content.Context;
import android.view.View;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.github.tvbox.osc.base.App;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 加载动画统一配置:通过设置页"加载动画"选项切换全局加载动画。
 * <p>
 * 动画文件存放:
 * <ul>
 *   <li>默认动画:assets/anim_loading.json(目录为空或选择项失效时的兜底)</li>
 *   <li>可选动画:assets/loading/*.json(打包时自动扫描该目录,按文件名注册到设置页选项)</li>
 * </ul>
 * 选择值(HawkConfig.LOADING_ANIM)存动画文件名;旧版存的 int(0=默认,1=Glowing Fish)自动兼容。
 */
public class LoadingAnim {

    /** 可选动画目录(assets 下) */
    public static final String DIR_NAME = "loading";
    /** 默认动画文件名(assets 根目录) */
    public static final String DEFAULT_FILE = "anim_loading.json";

    /** 兼容旧版:Glowing Fish 的旧选择值 1 映射到文件名 */
    private static final String LEGACY_GLOWING_FISH = "glowing_fish_loader.json";

    /**
     * 当前配置的动画文件名(assets 下相对路径,可能带 loading/ 前缀)。
     * 选择值无效/文件不存在时回退默认 anim_loading.json。
     */
    public static String getAnimFileName() {
        try {
            Object sel = Hawk.get(HawkConfig.LOADING_ANIM, null);
            // 新格式:存文件名
            if (sel instanceof String) {
                String name = (String) sel;
                if (name != null && !name.isEmpty()) {
                    return resolveFileName(name);
                }
                return DEFAULT_FILE;
            }
            // 旧格式:存 int(0=默认,1=Glowing Fish)
            if (sel instanceof Number) {
                int v = ((Number) sel).intValue();
                if (v == 1) return resolveFileName(LEGACY_GLOWING_FISH);
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_FILE;
    }

    /**
     * 解析用户选择的文件名:若在可选目录(assets/loading/)中存在则返回带目录前缀的路径,
     * 否则回退默认。兼容用户直接填 "glowing_fish_loader.json" 或带 "loading/" 前缀。
     */
    private static String resolveFileName(String name) {
        try {
            if (name == null || name.isEmpty()) return DEFAULT_FILE;
            String bare = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
            if (!bare.endsWith(".json")) bare = bare + ".json";
            // 尝试可选目录
            String[] list = App.getInstance().getAssets().list(DIR_NAME);
            if (list != null) {
                for (String f : list) {
                    if (f.equals(bare)) {
                        return DIR_NAME + "/" + bare;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_FILE;
    }

    /**
     * 可用加载动画列表:默认(anim_loading.json) + assets/loading/ 目录下所有 json。
     * 目录为空时只有默认。
     */
    public static List<String> getAvailableAnimFiles() {
        List<String> list = new ArrayList<>();
        list.add(DEFAULT_FILE); // 默认始终可选
        try {
            Context ctx = App.getInstance();
            if (ctx == null || ctx.getAssets() == null) return list;
            String[] files = ctx.getAssets().list(DIR_NAME);
            if (files != null) {
                for (String f : files) {
                    if (f != null && f.endsWith(".json")) {
                        list.add(f); // 只存文件名,展示时去 .json
                    }
                }
            }
        } catch (IOException ignored) {
        }
        Collections.sort(list.subList(1, list.size())); // 默认在最前,其余按文件名排序
        return list;
    }

    /** 展示名(去 .json 后缀,默认显示为"默认") */
    public static String displayName(String fileName) {
        if (fileName == null) return "";
        String bare = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName;
        if (DEFAULT_FILE.equals(bare)) return "默认";
        return bare.endsWith(".json") ? bare.substring(0, bare.length() - 5) : bare;
    }

    /** 把全局加载动画应用到指定 Lottie 视图(动态切换动画文件) */
    public static void apply(View view) {
        if (view instanceof LottieAnimationView) {
            LottieAnimationView lav = (LottieAnimationView) view;
            try {
                lav.setAnimation(getAnimFileName());
                lav.setRepeatMode(LottieDrawable.RESTART); // 从头循环,不是往返播放(reverse)
                lav.setRepeatCount(LottieDrawable.INFINITE);
                lav.setSpeed(1f); // 原速播放(代码设置动画时 XML 的 lottie_speed 不生效,需显式指定)
                // 动画含高斯模糊等超出画布内容时关闭按画布裁剪,避免光晕被边界切掉
                lav.setClipToCompositionBounds(false);
                lav.playAnimation();
            } catch (Throwable th) {
                // 动画文件异常时静默回退,不阻塞加载页展示
                th.printStackTrace();
            }
        }
    }
}
