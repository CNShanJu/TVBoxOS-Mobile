package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 统一的开关组件(跟随主题):
 * 开 = 高亮蓝(color_highlight,两主题一致),关 = 主题灰(switch_track_off,浅色浅灰/深色深灰),
 * 圆点固定白色。开关状态通过轨道颜色 + 圆点位置区分。
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
        int onColor = ContextCompat.getColor(getContext(), R.color.color_highlight);
        int offColor = ContextCompat.getColor(getContext(), R.color.switch_track_off);
        setTrackTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{onColor, offColor}));
        setThumbTintList(ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.white)));
    }
}
