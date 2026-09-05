package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogLiveSettingBinding;
import com.github.tvbox.osc.ui.activity.LiveActivity;

import org.jetbrains.annotations.NotNull;

/**
 * 直播设置弹窗（底部样式，竖屏/普通播放器设置入口）。
 * 内容编排全部委托 {@link LiveSettingPanel}；本类只保留 XPopup 底部壳差异。
 */
public class LiveSettingDialog extends AppBottomPopupView {

    @NonNull
    private final LiveActivity mActivity;
    private LiveSettingPanel mPanel;

    public LiveSettingDialog(@NonNull @NotNull Context context) {
        super(context);
        mActivity = (LiveActivity) context;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_live_setting;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogLiveSettingBinding binding = DialogLiveSettingBinding.bind(getPopupImplView());
        mPanel = new LiveSettingPanel(mActivity, binding.mSettingGroupView, binding.mSettingItemView);
        mPanel.init();
    }
}
