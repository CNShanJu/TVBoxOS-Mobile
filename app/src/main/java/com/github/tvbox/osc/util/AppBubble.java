package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.github.tvbox.osc.base.App;

/**
 * 统一提醒组件（系统 Toast 样式）：
 * 直接使用系统 Toast（Toast.makeText），与 jar/三方库弹出的 toast 视觉统一。
 * <p>
 * 说明：早期版本为自定义圆角气泡（跟随 app 主题配色），但 jar 爬虫内部直接弹系统 Toast，
 * 两种样式并存观感不一；现统一为系统 Toast 样式（Android 13+ 自动为系统圆角胶囊样式）。
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
                    Toast toast = Toast.makeText(ctx, msg,
                            longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                    toast.show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
