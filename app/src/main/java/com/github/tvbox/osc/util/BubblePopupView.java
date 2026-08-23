package com.github.tvbox.osc.util;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.CenterPopupView;

/**
 * 气泡弹窗(供 AppBubble 使用):居中弹窗但内容布局底部对齐,
 * 无遮罩、不拦截触摸,展示后自动消失。样式完全由 view_bubble.xml 控制(跟随主题)。
 */
public class BubblePopupView extends CenterPopupView {

    private final View mContentView;

    public BubblePopupView(@NonNull Context context, View contentView) {
        super(context);
        mContentView = contentView;
    }

    @Override
    protected int getImplLayoutId() {
        // 用透明根布局,内容由 mContentView 提供
        return R.layout.view_bubble_container;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        FrameLayout container = findViewById(R.id.bubble_container);
        if (container != null && mContentView != null) {
            // 移除根布局自带的孩子(仅占位),挂载真实气泡
            container.removeAllViews();
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            container.addView(mContentView, lp);
            // 内容底部对齐容器(底部留出距屏幕底部间距)
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) mContentView.getLayoutParams();
            flp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            flp.bottomMargin = dp2px(120);
            mContentView.setLayoutParams(flp);
        }
    }

    private int dp2px(float dp) {
        return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
    }
}
