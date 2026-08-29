package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.BottomPopupView;

/**
 * 统一的底部弹窗基类:
 * 背景统一使用 {@link R.drawable#bg_bottom_dialog}(顶部圆角 + bg_popup 主题色),
 * 最大高度统一 短边 80%(横竖屏一致); 横屏时宽度限制为屏幕 55% 居中, 防全宽挤压。
 * 调主题背景/尺寸只改基类/常量,一处生效全部底部弹窗。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()};
 * 标题+内容+按钮结构时,中间内容区用 weight=1 + 内部滚动,避免挤压上下标题/按钮。
 */
public abstract class AppBottomPopupView extends BottomPopupView {

    public AppBottomPopupView(@NonNull Context context) {
        super(context);
    }

    /** 统一最大高度:短边 80%(横竖屏一致——横屏时短边=竖屏高,防横屏挤压) */
    @Override
    protected int getMaxHeight() {
        int shortSide = Math.min(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight());
        return Math.round(shortSide * 0.8f);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 统一底部弹窗背景:顶部圆角 + 主题背景色(bg_popup 浅色白 / 暗色深)
        View root = getPopupImplView();
        if (root != null) {
            root.setBackgroundResource(R.drawable.bg_bottom_dialog);
            // 横屏适配:内容宽度限 屏幕55% 并居中(竖屏保持全宽), 防横屏被拉全宽挤压
            if (ScreenUtils.isLandscape()) {
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                int w = Math.round(ScreenUtils.getScreenWidth() * 0.55f);
                if (lp == null) {
                    lp = new ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
                } else {
                    lp.width = w;
                }
                root.setLayoutParams(lp);
                // 横屏时父容器水平居中
                View parent = (View) root.getParent();
                if (parent instanceof FrameLayout) {
                    FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) root.getLayoutParams();
                    fl.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                    root.setLayoutParams(fl);
                }
            }
        }
    }
}
