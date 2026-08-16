package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.hjq.bar.TitleBar;

/**
 * 统一的二级页面标题栏组件:
 * 背景 / 标题文字 / 分割线统一跟随主题配置(bg_body / text_main),
 * 各页面只需设置 title 与可选的 rightIcon、子 view(如收藏页的清除图标)。
 */
public class AppTitleBar extends TitleBar {

    public AppTitleBar(Context context) {
        this(context, null);
    }

    public AppTitleBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDefault();
    }

    private void initDefault() {
        int textColor = ContextCompat.getColor(getContext(), R.color.text_main);
        setBackgroundColor(ContextCompat.getColor(getContext(), R.color.bg_body));
        setTitleColor(textColor);
        setLeftIconTint(textColor);
        setRightIconTint(textColor);
        setLineVisible(false);
        // 统一右侧图标尺寸,避免自带矢量图标显示过大
        int iconSize = Math.round(20 * getResources().getDisplayMetrics().density);
        setRightIconSize(iconSize, iconSize);
    }
}
