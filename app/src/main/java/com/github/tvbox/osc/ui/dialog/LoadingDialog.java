package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LoadingAnim;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 全局加载框(居中):内容为全局加载态 Lottie({@link LoadingAnim#apply} 按设置切换动画/尺寸),
 * 替代 XPopup {@code asLoading()} 的默认转圈,保证订阅导入等所有 {@code showLoadingDialog}
 * 调用与全局加载态一致。无卡片背景,由 XPopup 暗色遮罩垫底;BACK 不关闭(加载框为阻塞态)。
 */
public class LoadingDialog extends CenterPopupView {

    public LoadingDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_loading;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        LoadingAnim.apply(findViewById(R.id.lottie_loading));
    }

    /** 阻塞态:BACK 不关闭加载框,避免导入/请求中途被误关 */
    @Override
    protected boolean onBackPressed() {
        return true;
    }
}
