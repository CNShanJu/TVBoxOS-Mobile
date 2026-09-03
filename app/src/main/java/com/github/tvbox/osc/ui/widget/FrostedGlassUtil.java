package com.github.tvbox.osc.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.github.tvbox.osc.util.StackBlurBlur;

import eightbitlab.com.blurview.BlurView;

/**
 * 弹层/抽屉毛玻璃工具(外层方案, 不修改弹层自身布局):
 * 在弹层(右侧抽屉/底部弹窗等 viewMode 弹层)显示时, 往其所在 Activity 的内容根上、
 * 弹层容器正下方插一层全屏 BlurView 模糊底层 UI; 弹层自身的半透明主题色 bg_popup
 * (透明度由主题文件 bg_popup_alpha 控制)叠在模糊之上, 即形成毛玻璃。
 * <p>
 * 关闭弹层(容器被移除)时自动移除模糊层, 不影响页面其它内容, 也不改变弹层内部布局与内容。
 * 无 Activity 上下文/非 viewMode 弹层时静默跳过(那些弹层保持纯半透明)。
 */
public final class FrostedGlassUtil {

    private static final String BLUR_TAG = "popup_glass_blur";
    /** 模糊半径(参考悬浮钮/底栏), 觉得不够明显可加大 */
    private static final float BLUR_RADIUS = 20f;

    private FrostedGlassUtil() {
    }

    /**
     * 为弹层附加外层毛玻璃(幂等、失败静默)。要求 context 是 Activity。
     */
    public static void attach(View popupRoot, Context context) {
        try {
            if (popupRoot == null || !(context instanceof Activity)) return;
            final Activity activity = (Activity) context;
            final ViewGroup content = activity.findViewById(android.R.id.content);
            final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (content == null || decor == null) return;
            // 等弹层挂到窗口并完成布局后再插入模糊层(需要确切层级与尺寸)
            popupRoot.post(() -> {
                try {
                    if (!popupRoot.isAttachedToWindow()) return;
                    // 找弹层真正的宿主: 沿父链向上, 停在直接挂在 decor 或 content 下的容器
                    View child = popupRoot;
                    ViewGroup host = null;
                    while (child.getParent() instanceof ViewGroup) {
                        ViewGroup p = (ViewGroup) child.getParent();
                        if (p == content || p == decor) {
                            host = p;
                            break;
                        }
                        child = (View) p;
                    }
                    if (host == null) return; // 独立窗口弹层, 不处理
                    final ViewGroup h = host;
                    removeExistingBlur(h);
                    final BlurView blur = new BlurView(activity);
                    blur.setTag(BLUR_TAG);
                    blur.setupWith(h)
                            .setFrameClearDrawable(activity.getWindow().getDecorView().getBackground())
                            .setBlurAlgorithm(new StackBlurBlur())
                            .setBlurRadius(BLUR_RADIUS)
                            .setBlurAutoUpdate(true);
                    int idx = h.indexOfChild(child);
                    if (idx < 0) idx = 0;
                    h.addView(blur, idx,
                            new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                    // 弹层容器被移除(关闭)时, 顺带移除模糊层
                    View.OnAttachStateChangeListener cleanup = new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {
                            try {
                                removeExistingBlur(h);
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                    child.addOnAttachStateChangeListener(cleanup);
                    popupRoot.addOnAttachStateChangeListener(cleanup);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable th) {
            Log.d("FrostedGlassUtil", "毛玻璃附加失败: " + th.getMessage());
        }
    }

    private static void removeExistingBlur(ViewGroup content) {
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View v = content.getChildAt(i);
            if (BLUR_TAG.equals(v.getTag())) {
                content.removeViewAt(i);
            }
        }
    }
}
