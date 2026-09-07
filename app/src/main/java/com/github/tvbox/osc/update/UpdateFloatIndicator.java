package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.github.tvbox.osc.R;

/**
 * 全局更新悬浮圈:任何下载中的时候,无论当前停留在哪个页面,都显示一个圆形进度圈
 * (与 FAB 同观感);点击弹出 {@link UpdateIndicatorDialog} 查看/控制下载。
 * <p>长按拖拽可移动到屏幕任意位置,松手自动吸附到左右边缘;位置在 Activity 切换后保留。
 * 附着到当前 Activity 的窗口(decorView 顶部末位),Activity 切换时由 {@code BaseActivity}
 * 的 attach/detach 驱动重新挂载;下载结束/取消即自动移除。
 */
public final class UpdateFloatIndicator implements UpdateManager.Listener {

    private static volatile UpdateFloatIndicator instance;

    private final Context appContext;
    private View floatView;
    private ViewGroup attachedParent;
    private Activity currentActivity;
    private UpdateIndicatorDialog dialog;

    /** 悬浮圈当前位置(left/top margin,px;初始 null=默认右下角) */
    private Integer posX = null;
    private Integer posY = null;

    // ---- 拖拽状态 ----
    private boolean dragTracking = false;
    private float downX, downY;
    private int dragStartLeft, dragStartTop;
    private final float touchSlop;

    private UpdateFloatIndicator(Context context) {
        this.appContext = context.getApplicationContext();
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
                || s == UpdateManager.State.COMPLETED
                || s == UpdateManager.State.FAILED);
        Activity a = currentActivity;
        if (!show || a == null || a.isFinishing() || a.isDestroyed()) {
            hide();
            return;
        }
        if (floatView == null) {
            floatView = LayoutInflater.from(a).inflate(R.layout.float_update_indicator, null);
            floatView.setOnClickListener(v -> showDialog());
            floatView.setOnTouchListener(new DragTouchListener());
        }
        if (floatView.getParent() != a.getWindow().getDecorView()) {
            if (floatView.getParent() instanceof ViewGroup) {
                ((ViewGroup) floatView.getParent()).removeView(floatView);
            }
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.NO_GRAVITY);
            placeInitial(lp, a, floatView);
            decor.addView(floatView, lp);
            attachedParent = decor;
        }
        updateBubble();
    }

    /** 首次挂载/无记录位置:默认贴右下(距边 18dp、距底 76dp 避底栏);有记录位置则恢复 */
    private void placeInitial(FrameLayout.LayoutParams lp, Activity a, View view) {
        float density = a.getResources().getDisplayMetrics().density;
        int defEdge = (int) (18 * density);
        int defBottom = (int) (76 * density);
        int viewW = Math.round(54 * density);
        int viewH = Math.round(54 * density);
        if (posX == null || posY == null) {
            lp.leftMargin = defEdge;
            lp.topMargin = defBottom;
        } else {
            lp.leftMargin = Math.max(0, posX);
            lp.topMargin = Math.max(0, posY);
        }
        // NO_GRAVITY 时 left/top 为距左上,right/bottom 为距右下;用距左上即可
        // 默认右下 → left = 屏宽 - edge - viewW,top = 屏高 - bottom - viewH
        if (posX == null || posY == null) {
            android.graphics.Point size = new android.graphics.Point();
            a.getWindowManager().getDefaultDisplay().getSize(size);
            lp.leftMargin = Math.max(0, size.x - defEdge - viewW);
            lp.topMargin = Math.max(0, size.y - defBottom - viewH);
        }
        // 记录相对窗口的实际位置(切 Activity 后窗口尺寸近似,恢复后仍可再拖)
        posX = lp.leftMargin;
        posY = lp.topMargin;
    }

    private void hide() {
        // floatView 对象常驻(listener 在首次创建时已绑定),仅从父容器摘除并暂停动画
        if (floatView != null) {
            UpdateBubbleView b = floatView.findViewById(R.id.update_bubble);
            if (b != null) b.pauseAnimations();
            if (floatView.getParent() instanceof ViewGroup) {
                ((ViewGroup) floatView.getParent()).removeView(floatView);
            }
        }
        attachedParent = null;
    }

    /** 用 UpdateBubbleView 映射 UpdateManager 状态与真实进度(进度环/中心图标/动画) */
    private void updateBubble() {
        if (floatView == null) return;
        UpdateBubbleView b = floatView.findViewById(R.id.update_bubble);
        if (b == null) return;
        UpdateManager m = UpdateManager.get();
        UpdateManager.State s = m.getState();
        long downloaded = m.getDownloaded();
        long total = m.getTotal();
        float progress = total > 0 ? (float) downloaded / (float) total : 0f;
        UpdateBubbleView.BubbleState bs;
        switch (s) {
            case PAUSED:
                bs = UpdateBubbleView.BubbleState.PAUSED;
                break;
            case COMPLETED:
                bs = UpdateBubbleView.BubbleState.COMPLETED;
                break;
            case FAILED:
                bs = UpdateBubbleView.BubbleState.FAILED;
                break;
            case DOWNLOADING:
            default:
                bs = UpdateBubbleView.BubbleState.DOWNLOADING;
                break;
        }
        b.setState(bs, progress);
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

    /** 拖拽处理:超过 slop 判定拖动,拖动中拦截点击,松手吸附左右边缘 */
    private final class DragTouchListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragTracking = false;
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                        dragStartLeft = lp.leftMargin;
                        dragStartTop = lp.topMargin;
                    }
                    return false; // 先不消费,由点击逻辑处理(若拖动开始再消费)
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (!dragTracking && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragTracking = true;
                        return true;
                    }
                    if (dragTracking) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                        ViewGroup parent = (ViewGroup) v.getParent();
                        int maxLeft = parent.getWidth() - v.getWidth();
                        int maxTop = parent.getHeight() - v.getHeight();
                        int nx = dragStartLeft + Math.round(dx);
                        int ny = dragStartTop + Math.round(dy);
                        lp.leftMargin = Math.max(0, Math.min(maxLeft, nx));
                        lp.topMargin = Math.max(0, Math.min(maxTop, ny));
                        v.requestLayout();
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragTracking) {
                        dragTracking = false;
                        snapToEdge(v);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        /** 松手吸附到最近左右边缘,垂直位置保持;记录当前坐标供切页后恢复 */
        private void snapToEdge(View v) {
            if (!(v.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            ViewGroup parent = (ViewGroup) v.getParent();
            if (parent == null) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            int edge = Math.round(v.getResources().getDisplayMetrics().density * 8);
            int centerX = lp.leftMargin + v.getWidth() / 2;
            boolean toLeft = centerX < parent.getWidth() / 2;
            lp.leftMargin = toLeft ? edge : Math.max(edge, parent.getWidth() - v.getWidth() - edge);
            v.requestLayout();
            posX = lp.leftMargin;
            posY = lp.topMargin;
        }
    }
}
