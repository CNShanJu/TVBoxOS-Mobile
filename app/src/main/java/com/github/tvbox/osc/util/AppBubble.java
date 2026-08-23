package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;

/**
 * 统一提醒气泡组件:
 * 圆角气泡 + 跟随主题配色(浅色柔和灰白底深字 / 深色深底白字),底部居中弹出。
 * <p>
 * 自定义 view 根必须是 ViewGroup(不能是 TextView):Android 13+ 会把"根=TextView"的
 * Toast 判定为 text toast,导致 setGravity 失效、自定义背景/文字色(含主题色)不生效,
 * 显示成系统默认样式(白底黑字小圆角)。见 view_bubble.xml。
 * 用法:AppBubble.toast("xxx") / AppBubble.toastLong("xxx")
 */
public class AppBubble {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
                    Context ctx = App.getInstance();
                    // AppCompat 的夜间模式只作用于 Activity 上下文,Application 上下文的资源配置不会跟着切换,
                    // 直接用 App 上下文 inflate 会让气泡永远解析成浅色主题(深色主题下仍是白底)。
                    // 这里按当前主题构造配置上下文,保证气泡背景/文字跟随主题。
                    Context themedCtx = ctx;
                    try {
                        Configuration config = new Configuration(ctx.getResources().getConfiguration());
                        int night = Utils.isDarkTheme() ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
                        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                        themedCtx = ctx.createConfigurationContext(config);
                    } catch (Throwable ignored) {
                    }
                    Toast toast = new Toast(ctx);
                    View view = LayoutInflater.from(themedCtx).inflate(R.layout.view_bubble, null);
                    ((TextView) view.findViewById(R.id.tv_bubble_text)).setText(msg);
                    toast.setView(view);
                    toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp2px(ctx, 120));
                    toast.setDuration(longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                    toast.show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static int dp2px(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
