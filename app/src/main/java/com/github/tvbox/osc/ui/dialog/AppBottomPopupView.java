package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.BottomPopupView;

/**
 * 统一的底部弹窗基类:
 * 背景统一使用 {@link R.drawable#bg_bottom_dialog}(顶部圆角 + bg_popup 主题色),
 * 最大高度统一 屏幕 80%(内容超高时 XPopup 自动内部滚动,不撑满全屏)。
 * 调主题背景/高度只改基类/常量,一处生效全部底部弹窗。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()};
 * 标题+内容+按钮结构时,中间内容区用 weight=1 + 内部滚动,避免挤压上下标题/按钮。
 */
public abstract class AppBottomPopupView extends BottomPopupView {

    public AppBottomPopupView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected int getMaxHeight() {
        return Math.round(ScreenUtils.getScreenHeight() * 0.8f);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 统一底部弹窗背景:顶部圆角 + 主题背景色(bg_popup 浅色白 / 暗色深)
        View root = getPopupImplView();
        if (root != null) {
            root.setBackgroundResource(R.drawable.bg_bottom_dialog);
        }
    }
}
