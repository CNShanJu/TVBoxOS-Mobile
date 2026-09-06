package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.github.tvbox.osc.R;

/**
 * 全局更新悬浮圈:任何下载中的时候,无论当前停留在哪个页面,都显示一个圆形进度圈
 * (仿首页"直播"悬浮钮);点击弹出 {@link UpdateIndicatorDialog} 查看/控制下载。
 * <p>附着到当前 Activity 的窗口(decorView 顶部末位),Activity 切换时由 {@code BaseActivity}
 * 的 attach/detach 驱动重新挂载;下载结束/取消即自动移除。
 */
public final class UpdateFloatIndicator implements UpdateManager.Listener {

    private static volatile UpdateFloatIndicator instance;

    private final Context appContext;
    private View floatView;
    private ViewGroup attachedParent;
    private Activity currentActivity;
    private UpdateIndicatorDialog dialog;

    private UpdateFloatIndicator(Context context) {
        this.appContext = context.getApplicationContext();
        UpdateManager.get().addListener(this);
    }

    public static synchronized UpdateFloatIndicator get(Context context) {
        if (instance == null) {
            instance = new UpdateFloatIndicator(context);
        }
        return instance;
    }

    /** BaseActivity.onResume 调用:记录当前 Activity 并(如需)挂载悬浮圈 */
    public void attach(Activity activity) {
        if (activity == null) return;
        this.currentActivity = activity;
        syncView();
    }

    /** BaseActivity.onPause/onDestroy 调用:当前 Activity 离开前台即卸载悬浮圈(安装/切页时隐藏) */
    public void detach(Activity activity) {
        if (activity != null && activity == currentActivity) {
            hide();
        }
    }

    // ── 状态回调 ──

    @Override
    public void onUpdate(UpdateManager.State state, long downloaded, long total, UpdateInfo info) {
        syncView();
    }

    private void syncView() {
        UpdateManager.State s = UpdateManager.get().getState();
        boolean show = (s == UpdateManager.State.DOWNLOADING
                || s == UpdateManager.State.PAUSED
                || s == UpdateManager.State.COMPLETED);
        Activity a = currentActivity;
        if (!show || a == null || a.isFinishing() || a.isDestroyed()) {
            hide();
            return;
        }
        if (floatView == null) {
            floatView = LayoutInflater.from(a).inflate(R.layout.float_update_indicator, null);
            floatView.setOnClickListener(v -> showDialog());
        }
        if (floatView.getParent() != a.getWindow().getDecorView()) {
            if (floatView.getParent() instanceof ViewGroup) {
                ((ViewGroup) floatView.getParent()).removeView(floatView);
            }
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.BOTTOM);
            int edge = (int) (a.getResources().getDisplayMetrics().density * 18);
            int bottom = (int) (a.getResources().getDisplayMetrics().density * 76);
            lp.setMargins(edge, 0, edge, bottom);
            decor.addView(floatView, lp);
            attachedParent = decor;
        }
        updatePercent();
    }

    private void hide() {
        if (floatView != null && floatView.getParent() instanceof ViewGroup) {
            ((ViewGroup) floatView.getParent()).removeView(floatView);
        }
        attachedParent = null;
    }

    private void updatePercent() {
        if (floatView == null) return;
        android.widget.TextView tv = floatView.findViewById(R.id.float_percent);
        if (tv == null) return;
        UpdateManager m = UpdateManager.get();
        UpdateManager.State s = m.getState();
        long downloaded = m.getDownloaded();
        long total = m.getTotal();
        int percent = total > 0 ? (int) (downloaded * 100 / total) : 0;
        switch (s) {
            case DOWNLOADING:
                tv.setText(percent + "%");
                break;
            case PAUSED:
                tv.setText("⏸");
                break;
            case COMPLETED:
                tv.setText("✓");
                break;
            default:
                tv.setText("");
                break;
        }
    }

    private void showDialog() {
        if (dialog != null) {
            dialog.dismiss();
        }
        Activity a = currentActivity;
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        dialog = new UpdateIndicatorDialog(a);
        dialog.show();
    }
}
