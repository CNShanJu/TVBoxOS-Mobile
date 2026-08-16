package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.DrawerPopupView;

/**
 * 统一的右侧抽屉基类:
 * 背景统一使用圆角 + bg_popup(跟随主题深浅),内部组件由各页面自行决定。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()}。
 */
public abstract class AppDrawerPopupView extends DrawerPopupView {

    public AppDrawerPopupView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 统一抽屉背景:圆角 + 主题背景色(bg_popup 浅色白 / 暗色深)
        View root = getPopupImplView();
        if (root != null) {
            root.setBackgroundResource(R.drawable.bg_drawer);
        }
    }
}
