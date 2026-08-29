package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.CenterPopupView;

/**
 * 统一的居中弹窗基类:
 * <ul>
 *   <li>背景统一 {@link R.drawable#bg_large_round_popup}(全圆角 + bg_popup 主题色)——由布局根设置,
 *       基类不强设避免与 XPopup 默认容器叠加产生四角异常;</li>
 *   <li>最大宽度统一 {@link DialogStyle#CENTER_MAX_WIDTH_DP},窄屏自适应;</li>
 *   <li>最大高度统一 屏幕 70%,内容超限自动包 ScrollView 内部滚动,不撑满全屏;</li>
 * </ul>
 * 调主题背景/宽度/高度只改基类/常量,一处生效全部居中弹窗。
 * 子类实现 {@link #getImplLayoutId()} 与 {@link #onCreate()};
 * 标题+内容+按钮结构时,中间内容区用 weight=1 + 内部滚动,避免挤压上下标题/按钮。
 */
public abstract class AppCenterPopupView extends CenterPopupView {

    public AppCenterPopupView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected int getMaxWidth() {
        return Math.round(DialogStyle.CENTER_MAX_WIDTH_DP
                * getContext().getResources().getDisplayMetrics().density);
    }

    /** 统一最大高度:屏幕 70%(内容多时内部滚动,不撑满全屏) */
    @Override
    protected int getMaxHeight() {
        return Math.round(ScreenUtils.getScreenHeight() * 0.7f);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 内容超高时自动包一层 ScrollView:防止被 maxHeight 裁剪(标题/按钮结构内容区滚动)
        try {
            final View content = getPopupImplView();
            if (content != null) {
                content.post(() -> {
                    try {
                        int maxH = getMaxHeight();
                        Rect r = new Rect();
                        content.getDrawingRect(r);
                        if (content.getHeight() > maxH) {
                            wrapInScrollView(content);
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把弹窗内容根包进 ScrollView(仅超高时;内部按钮/列表不受影响) */
    private void wrapInScrollView(View content) {
        android.view.ViewGroup parent = (android.view.ViewGroup) content.getParent();
        if (parent == null || content.getParent() instanceof ScrollView) return;
        int idx = parent.indexOfChild(content);
        parent.removeView(content);
        ScrollView sv = new ScrollView(getContext());
        sv.setFillViewport(true);
        sv.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        android.view.ViewGroup.LayoutParams lp = content.getLayoutParams();
        sv.addView(content, lp != null ? lp
                : new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(sv, idx, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
