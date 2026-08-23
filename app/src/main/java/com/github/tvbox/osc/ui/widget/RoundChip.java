package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/**
 * 统一的"圆角 + 文字"组件(选集格子/设置选项等):
 * 选中 = 蓝色文字(#1890FF)无背景;未选中 = 主色文字。
 * 背景/描边由外部通过 background 提供,组件只统一文字与选中态。
 */
public class RoundChip extends FrameLayout {

    private final TextView mTextView;
    /** 禁用态(已下载/下载中不可选),文字置灰 */
    private boolean mDisabled = false;

    public RoundChip(Context context) {
        this(context, null);
    }

    public RoundChip(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTextView = new TextView(context);
        mTextView.setGravity(Gravity.CENTER);
        mTextView.setSingleLine(true);
        mTextView.setEllipsize(TextUtils.TruncateAt.END);
        mTextView.setTextSize(12);
        // 内部文字 wrap_content + 居中:让 icon+文字 作为整体居中(icon贴着文字),
        // 避免 fill 宽度 + gravity=center 时复合drawable被钉在组件最左边、文字单独居中
        addView(mTextView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        updateColor(false);
    }

    public void setTitle(String title) {
        mTextView.setText(title);
    }

    public String getTitle() {
        return mTextView.getText().toString();
    }

    public void setChipTextSize(float sp) {
        mTextView.setTextSize(sp);
    }

    /**
     * 状态图标(复合 drawable 贴在文字左侧,如 下载中蓝↓ / 已下载绿✓):
     *
     * @param resId    图标资源;0 表示清除图标
     * @param colorRes 图标着色(主题色);0 表示不着色
     */
    public void setStateIcon(int resId, int colorRes) {
        if (resId == 0) {
            mTextView.setCompoundDrawables(null, null, null, null);
            return;
        }
        Drawable d = ContextCompat.getDrawable(getContext(), resId);
        if (d != null) {
            int size = Math.round(16 * getContext().getResources().getDisplayMetrics().density);
            d.setBounds(0, 0, size, size);
            if (colorRes != 0) {
                d.setColorFilter(ContextCompat.getColor(getContext(), colorRes), PorterDuff.Mode.SRC_IN);
            }
            mTextView.setCompoundDrawables(d, null, null, null);
            mTextView.setCompoundDrawablePadding(Math.round(3 * getContext().getResources().getDisplayMetrics().density));
        }
    }

    /** 禁用态:文字置灰(用于已下载/下载中不可重复选择的剧集) */
    public void setDisabled(boolean disabled) {
        mDisabled = disabled;
        updateColor(isSelected());
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        updateColor(selected);
    }

    private void updateColor(boolean selected) {
        if (mDisabled) {
            mTextView.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
        } else {
            mTextView.setTextColor(ContextCompat.getColor(getContext(),
                    selected ? R.color.color_highlight : R.color.colorPrimary));
        }
    }
}
