package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 统一的开关组件:
 * 轨道固定为文字主色(text_main,不随开/关变色),圆点固定白色。
 * 开关状态通过圆点位置区分。
 */
public class AppSwitch extends SwitchMaterial {

    public AppSwitch(Context context) {
        this(context, null);
    }

    public AppSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDefault();
    }

    private void initDefault() {
        setTrackTintList(ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.text_main)));
        setThumbTintList(ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.white)));
    }
}
