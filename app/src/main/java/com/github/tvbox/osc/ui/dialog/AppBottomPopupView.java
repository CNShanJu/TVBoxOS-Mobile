package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.BottomPopupView;

/**
 * 统一的底部弹窗基类:
 * 背景统一使用 {@link R.drawable#bg_bottom_dialog}(顶部圆角 + bg_popup 主题色),
 * 内部组件由各页面自行决定。调主题背景只改基类/公共 drawable,一处生效全部底部弹窗。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()}。
 */
public abstract class AppBottomPopupView extends BottomPopupView {

    public AppBottomPopupView(@NonNull Context context) {
        super(context);
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
