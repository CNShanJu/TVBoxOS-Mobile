package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.CheckBox;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 删除下载确认弹窗:勾选"同时删除本地文件"。
 * 不勾 = 只删除记录,保留本地文件;勾选 = 记录与本地文件一起删除。
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
        // 点整行切换勾选
        findViewById(R.id.ll_check).setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
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
