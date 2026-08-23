package com.github.tvbox.osc.util;

import android.view.View;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.github.tvbox.osc.R;
import com.orhanobut.hawk.Hawk;

/**
 * 加载动画统一配置:通过设置页"加载动画"选项切换全局加载动画。
 * <p>
 * 0=默认(anim_loading.json),1=Glowing Fish(glowing_fish_loader.json)。
 * 只作用于全局 LoadSir 加载回调(搜索/列表/详情等页面加载中),播放器与下载功能不受影响。
 */
public class LoadingAnim {

    public static final int ANIM_DEFAULT = 0;
    public static final int ANIM_GLOWING_FISH = 1;

    /** 当前配置的动画文件(assets 下的 lottie json 文件名) */
    public static String getAnimFileName() {
        try {
            int sel = Hawk.get(HawkConfig.LOADING_ANIM, ANIM_DEFAULT);
            if (sel == ANIM_GLOWING_FISH) return "glowing_fish_loader.json";
        } catch (Throwable ignored) {
        }
        return "anim_loading.json";
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
                lav.playAnimation();
            } catch (Throwable th) {
                // 动画文件异常时静默回退,不阻塞加载页展示
                th.printStackTrace();
            }
        }
    }
}
