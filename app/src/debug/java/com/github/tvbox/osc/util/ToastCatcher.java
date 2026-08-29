package com.github.tvbox.osc.util;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 全局 Toast 捕获（仅 debug 构建编译）：拦截系统 Toast 公共出口，打印所有 toast 的触发调用栈。
 * <p>
 * 背景：AppBubble 只是本项目 toast 的一条路径——**jar 爬虫/第三方库**持有 app Context 后可直接
 * {@code Toast.makeText(...).show()}（系统默认小气泡），不经 AppBubble，AppBubble 内的溯源钩不到。
 * 所有 Toast 最终都调用 {@code Toast.sService}（{@code android.app.INotificationManager}）入队，
 * 故反射将 {@code Toast.sService} 替换为动态代理：入队时打印调用栈 + 消息文本（API 30+ 可取到文本），
 * 再透传原对象正常显示。
 * <p>
 * release 构建用空实现替换（{@code app/src/release/.../ToastCatcher.java}），产物零残留。
 */
public final class ToastCatcher {

    private static final String TAG = "ToastTrace";

    private ToastCatcher() {
    }

    /** 反射替换 Toast.sService 为代理；失败静默降级（不影响 toast 显示） */
    public static void install() {
        try {
            Field field = Toast_sService();
            if (field == null) return;
            field.setAccessible(true);
            final Object original = field.get(null); // INotificationManager
            if (original == null) return;

            Class<?> iface = original.getClass();
            Object proxy = Proxy.newProxyInstance(
                    iface.getClassLoader(),
                    new Class<?>[]{iface},
                    (Object p, Method method, Object[] args) -> {
                        String name = method.getName();
                        if ("enqueueToast".equals(name) || "enqueueTextToast".equals(name)) {
                            logToast(args);
                        }
                        return method.invoke(original, args);
                    });
            field.set(null, proxy);
            Log.d(TAG, "全局 Toast 捕获已安装(debug)");
        } catch (Throwable th) {
            // hook 失败不阻塞业务:toast 正常显示,仅丢失溯源
            Log.d(TAG, "全局 Toast 捕获安装失败: " + th.getMessage());
        }
    }

    /** Toast.sService 字段（各 Android 版本名稳定为 sService；找不到返回 null） */
    private static Field Toast_sService() {
        try {
            return android.widget.Toast.class.getDeclaredField("sService");
        } catch (Throwable th) {
            return null;
        }
    }

    /** 打印 toast 消息（API 30+ enqueueTextToast 的 args[1] 是文本；enqueueToast 取不到文本只打栈） */
    private static void logToast(Object[] args) {
        StringBuilder sb = new StringBuilder("[toast] ");
        if (args != null && args.length > 1 && args[1] instanceof CharSequence) {
            sb.append(args[1]); // enqueueTextToast(String pkg, CharSequence text, int duration)
        } else {
            sb.append("(消息文本不可见, 系统 enqueueToast)");
        }
        sb.append("\n触发位置:\n").append(buildTrace());
        Log.w(TAG, sb.toString());
    }

    /** 当前调用栈（跳过本类/反射代理帧），最多 12 帧 */
    private static String buildTrace() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement e : st) {
            String cls = e.getClassName();
            if (cls.startsWith("com.github.tvbox.osc.util.Toast")) continue; // 跳过本类/AppBubble
            if (cls.startsWith("com.github.tvbox.osc.util.AppBubble")) continue;
            if (cls.startsWith("java.lang.reflect.")) continue;
            if (cls.startsWith("java.lang.Thread")) continue;
            if (cls.startsWith("android.widget.Toast")) continue; // show() 帧
            sb.append("    at ").append(cls).append('.').append(e.getMethodName())
                    .append('(').append(e.getFileName()).append(':').append(e.getLineNumber()).append(")\n");
            if (++kept >= 12) break;
        }
        return sb.toString();
    }
}
