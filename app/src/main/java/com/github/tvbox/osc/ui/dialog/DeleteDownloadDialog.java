package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.CheckBox;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 删除下载确认弹窗(下载完成列表用):勾选"同时删除本地文件"。
 * 不勾 = 只删除记录,保留本地文件;勾选 = 记录与本地文件一起删除。
 * <p>
 * 弹窗背景由代码按 app 主题设置(浅色/深色)直接指定,不依赖资源限定符
 * (后者只跟随系统深色模式,与 app 内主题设置可能不同步);勾选行无背景无涟漪,
 * 复选框用默认样式(与下载完成长按多选一致)。
 */
public class DeleteDownloadDialog extends CenterPopupView {

    public interface OnDeleteListener {
        void onDelete(boolean deleteFiles);
    }

    private final OnDeleteListener mListener;

    public DeleteDownloadDialog(@NonNull @NotNull Context context, OnDeleteListener listener) {
        super(context);
        mListener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_delete_download;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        CheckBox cb = findViewById(R.id.cb_delete_file);
        View llCheck = findViewById(R.id.ll_check);
        // 弹窗背景:按 app 主题取色(浅色白 / 深色深),圆角统一走主题圆角档(radius_dialog)
        boolean dark = Utils.isAppDarkTheme();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF2A2D32 : 0xFFFFFFFF);
        bg.setCornerRadius(getContext().getResources().getDimension(R.dimen.radius_dialog));
        getPopupImplView().setBackground(bg);
        // 点整行切换勾选(勾选行无背景/无涟漪,复选框用默认样式与下载完成多选一致)
        llCheck.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
        findViewById(R.id.tv_cancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.tv_ok).setOnClickListener(v -> {
            boolean deleteFiles = cb.isChecked();
            dismiss();
            if (mListener != null) {
                mListener.onDelete(deleteFiles);
            }
        });
    }
}
