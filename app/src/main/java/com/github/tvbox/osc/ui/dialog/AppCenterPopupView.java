package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.CenterPopupView;

/**
 * 统一的居中弹窗基类:
 * 背景统一使用 {@link R.drawable#bg_large_round_popup}(全圆角 + bg_popup 主题色),
 * 内容宽度统一限制为 {@link DialogStyle#CENTER_MAX_WIDTH_DP}(窄屏自适应)。
 * 调主题背景/宽度只改基类/常量,一处生效全部居中弹窗。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()}。
 */
public abstract class AppCenterPopupView extends CenterPopupView {

    public AppCenterPopupView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 统一居中弹窗背景:全圆角 + 主题背景色(bg_popup 浅色白 / 暗色深)
        View root = getPopupImplView();
        if (root != null) {
            root.setBackgroundResource(R.drawable.bg_large_round_popup);
            // 统一宽度:内容 ≤ CENTER_MAX_WIDTH_DP(布局 match_parent 时不再撑满全屏)
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp != null) {
                int maxPx = Math.round(DialogStyle.CENTER_MAX_WIDTH_DP
                        * getContext().getResources().getDisplayMetrics().density);
                if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        || lp.width > maxPx) {
                    lp.width = maxPx;
                    root.setLayoutParams(lp);
                }
            }
        }
    }
}
