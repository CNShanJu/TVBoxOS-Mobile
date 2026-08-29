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
 * 实现:系统 Toast + 自定义 view(根 ViewGroup 规避 Android 13+ text toast 系统样式),
 * 颜色由代码按 app 主题设置直接指定(不依赖 values-night 资源限定符,后者只跟随系统深色)。
 * 系统 Toast 由系统队列管理,轻量不卡顿,setGravity 底部位置准确。
 * 用法:AppBubble.toast("xxx") / AppBubble.toastLong("xxx")
 * <p>
 * 调试辅助(仅 debug 构建):toast 触发时经 {@link ToastTracer} 打印调用栈到 logcat(tag=ToastTrace),
 * 定位"不知道哪里冒出来的 toast";release 构建 ToastTracer 为空实现,产物零残留。
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
        ToastTracer.log(msg); // debug:打印触发调用栈; release:no-op
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = App.getInstance();
                    // 主题上下文(深色切换,保证 inflate 的默认资源跟随 app 主题)
                    Context themedCtx = ctx;
                    try {
                        Configuration config = new Configuration(ctx.getResources().getConfiguration());
                        int night = Utils.isAppDarkTheme() ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
                        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                        themedCtx = ctx.createConfigurationContext(config);
                    } catch (Throwable ignored) {
                    }
                    View view = LayoutInflater.from(themedCtx).inflate(R.layout.view_bubble, null);
                    TextView tv = view.findViewById(R.id.tv_bubble_text);
                    tv.setText(msg);
                    // 气泡颜色托管给 app 主题设置:直接读 THEME_TAG,不依赖资源限定符/AppCompatDelegate
                    boolean dark = Utils.isAppDarkTheme();
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(dark ? 0xFF2E2C36 : 0xFFF2F3F7);
                    bg.setCornerRadius(ctx.getResources().getDimension(R.dimen.radius_dialog));
                    tv.setBackground(bg);
                    tv.setTextColor(dark ? 0xFFFFFFFF : 0xFF1F2937);

                    Toast toast = new Toast(ctx);
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
