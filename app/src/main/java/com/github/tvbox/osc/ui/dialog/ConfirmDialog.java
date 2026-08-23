package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 主题化确认弹窗(标题 + 消息 + 取消/确定):
 * 弹窗背景按 app 主题取色(浅色白 / 深色深),圆角统一走主题圆角档 radius_dialog,
 * 与 DeleteDownloadDialog 等下载相关弹窗视觉一致(替代 XPopup 默认 asConfirm 的库内固定圆角)。
 */
public class ConfirmDialog extends CenterPopupView {

    private final String mTitle;
    private final String mMessage;
    private final String mConfirmText;
    private final Runnable mOnConfirm;

    public ConfirmDialog(@NonNull @NotNull Context context, String title, String message,
                         String confirmText, Runnable onConfirm) {
        super(context);
        mTitle = title;
        mMessage = message;
        mConfirmText = confirmText;
        mOnConfirm = onConfirm;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_confirm;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 弹窗背景:按 app 主题取色(浅色白 / 深色深),圆角统一 radius_dialog
        boolean dark = Utils.isAppDarkTheme();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF2A2D32 : 0xFFFFFFFF);
        bg.setCornerRadius(getContext().getResources().getDimension(R.dimen.radius_dialog));
        getPopupImplView().setBackground(bg);

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvMessage = findViewById(R.id.tv_message);
        if (mTitle != null) tvTitle.setText(mTitle);
        tvMessage.setText(mMessage == null ? "" : mMessage);

        TextView tvOk = findViewById(R.id.tv_ok);
        if (mConfirmText != null && !mConfirmText.isEmpty()) tvOk.setText(mConfirmText);
        findViewById(R.id.tv_cancel).setOnClickListener(v -> dismiss());
        tvOk.setOnClickListener(v -> {
            dismiss();
            if (mOnConfirm != null) {
                mOnConfirm.run();
            }
        });
    }
}
