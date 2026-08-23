package com.github.tvbox.osc.util;

import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;

/**
 * 统一提醒气泡组件:
 * 圆角气泡 + 跟随主题配色(浅色柔和灰白底深字 / 深色深底白字),底部居中弹出。
 * <p>
 * 实现:XPopup 自绘气泡(无遮罩、淡入淡出、自动消失),完全脱离系统 Toast 渲染,
 * 规避 Android 13+ 及魅族 Flyme ROM 把自定义 Toast view 判为 "text toast"
 * 显示系统默认样式(白底黑字小圆角、setGravity 失效)的问题。
 * 用法:AppBubble.toast("xxx") / AppBubble.toastLong("xxx")
 */
public class AppBubble {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 当前显示的气泡,新气泡弹出前先关闭旧的 */
    private static BasePopupView currentPopup;

    private AppBubble() {
    }

    public static void toast(final CharSequence msg) {
        show(msg, false);
    }

    public static void toast(final int resId) {
        toast(App.getInstance().getString(resId));
    }

    public static void toastLong(final CharSequence msg) {
        show(msg, true);
    }

    public static void toastLong(final int resId) {
        toastLong(App.getInstance().getString(resId));
    }

    private static void show(final CharSequence msg, final boolean longDuration) {
        if (msg == null || msg.length() == 0) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 关闭上一个气泡(避免叠加)
                    dismiss();
                    // 需要 Activity 上下文:取当前栈顶 Activity;无前台 Activity 时静默丢弃
                    final android.app.Activity activity = AppManager.getInstance().currentActivity();
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    // 主题上下文(深色切换)
                    android.content.Context themedCtx = activity;
                    try {
                        Configuration config = new Configuration(activity.getResources().getConfiguration());
                        int night = Utils.isDarkTheme() ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
                        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                        themedCtx = activity.createConfigurationContext(config);
                    } catch (Throwable ignored) {
                    }
                    View view = LayoutInflater.from(themedCtx).inflate(R.layout.view_bubble, null);
                    ((TextView) view.findViewById(R.id.tv_bubble_text)).setText(msg);
                    currentPopup = new XPopup.Builder(activity)
                            .isViewMode(true)          // view 模式:不拦截触摸
                            .dismissOnTouchOutside(true)
                            .dismissOnBackPressed(false)
                            .shadowBgColor(android.graphics.Color.TRANSPARENT) // 无遮罩
                            .asCustom(new BubblePopupView(activity, view));
                    // 自动消失(短/长),关闭后清引用
                    currentPopup.delayDismissWith(longDuration ? 3500 : 2000, () -> dismiss());
                    currentPopup.show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 关闭当前气泡(幂等) */
    private static void dismiss() {
        if (currentPopup != null) {
            try {
                currentPopup.dismiss();
            } catch (Throwable ignored) {
            }
            currentPopup = null;
        }
    }
}
