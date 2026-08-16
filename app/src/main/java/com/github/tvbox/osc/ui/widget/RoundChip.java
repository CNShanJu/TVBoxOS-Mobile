package com.github.tvbox.osc.ui.widget;

import android.content.Context;
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
        addView(mTextView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        updateColor(selected);
    }

    private void updateColor(boolean selected) {
        mTextView.setTextColor(ContextCompat.getColor(getContext(),
                selected ? R.color.color_highlight : R.color.colorPrimary));
    }
}
