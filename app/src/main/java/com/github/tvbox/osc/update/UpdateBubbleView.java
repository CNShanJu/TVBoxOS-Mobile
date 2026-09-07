package com.github.tvbox.osc.update;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/**
 * 更新悬浮气泡:自绘圆底 + 外圈环形进度 + 状态中心图标,并驱动对应动画。
 * 状态(仅下载后显示):下载中 / 暂停 / 失败 / 完成。
 * <ul>
 *   <li>下载中:进度环跟随真实进度;中心下载箭头 Y 轴 0→-4dp→0(600ms)+ 透明度闪烁,不旋转;</li>
 *   <li>暂停:停止所有动画,中心白色暂停双竖线,进度环停在当前;</li>
 *   <li>失败:中心白色错误叉,进度环变红;首次失败可短震(haptic);无循环;</li>
 *   <li>完成:进度环 100%,中心白色对勾,一次性 scale 1.0→1.15→1.0,点按触发安装。</li>
 * </ul>
 * 状态切换必须终止旧动画再启动新动画,避免叠加错乱。
 */
public class UpdateBubbleView extends View {

    public enum BubbleState { IDLE, DOWNLOADING, PAUSED, FAILED, COMPLETED }

    private static final int BG_COLOR = 0xFF4C6EF5;      // 品牌蓝圆底
    private static final int PROGRESS_COLOR = 0xFF4C6EF5; // 进度环蓝色
    private static final int PROGRESS_OK_COLOR = 0xFF37C871;// 完成绿(可选,完成用蓝即可)
    private static final int PROGRESS_FAIL_COLOR = 0xFFF25555;// 失败红
    private static final int TRACK_COLOR = 0xFFE6E8F0;   // 进度底环浅灰
    private static final int ICON_COLOR = 0xFFFFFFFF;    // 中心图标白

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArcRect = new RectF();

    private final Drawable mDownloadIcon;   // 下载箭头
    private final Drawable mCheckIcon;      // 对勾

    private BubbleState mState = BubbleState.IDLE;
    private float mProgress = 0f;           // 0..1
    private float mRingWidthDp;

    // 动画
    private ValueAnimator mArrowAnim;       // 上下位移
    private ValueAnimator mBounceAnim;      // 完成缩放
    private float mArrowOffset = 0f;        // px,0..-4dp
    private float mBounceScale = 1f;
    private long mDownMs;                   // 长按/点击持续时间,用于区分点击
    private boolean mFailHapticDone = false;

    public UpdateBubbleView(@NonNull Context context) {
        super(context);
        mRingWidthDp = 4 * getResources().getDisplayMetrics().density;
        mDownloadIcon = ContextCompat.getDrawable(context, R.drawable.ic_arrow_down);
        mCheckIcon = ContextCompat.getDrawable(context, R.drawable.ic_check_circle);
        if (mDownloadIcon != null) mDownloadIcon.setTint(ICON_COLOR);
        if (mCheckIcon != null) mCheckIcon.setTint(ICON_COLOR);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    /** 更新状态与进度(0..1);状态切换会终止旧动画再启动新动画 */
    public void setState(BubbleState state, float progress) {
        if (mState != state) {
            stopAll();
            mState = state;
            if (state == BubbleState.FAILED) {
                if (!mFailHapticDone) {
                    mFailHapticDone = true;
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            } else {
                mFailHapticDone = false;
            }
        }
        mProgress = Math.max(0f, Math.min(1f, progress));
        if (mState == BubbleState.DOWNLOADING) {
            startArrowAnim();
        } else if (mState == BubbleState.COMPLETED) {
            mProgress = 1f;
            startBounceOnce();
        }
        invalidate();
    }

    private void stopAll() {
        if (mArrowAnim != null) { mArrowAnim.cancel(); mArrowAnim = null; }
        if (mBounceAnim != null) { mBounceAnim.cancel(); mBounceAnim = null; }
        mArrowOffset = 0f;
        mBounceScale = 1f;
    }

    /** 下载中:箭头上下 0→-4dp→0 循环,配合透明度闪烁 */
    private void startArrowAnim() {
        if (mArrowAnim != null && mArrowAnim.isRunning()) return;
        float amp = -4 * getResources().getDisplayMetrics().density;
        mArrowAnim = ValueAnimator.ofFloat(0f, amp, 0f);
        mArrowAnim.setDuration(600);
        mArrowAnim.setRepeatCount(ValueAnimator.INFINITE);
        mArrowAnim.setInterpolator(new DecelerateInterpolator(2f));
        mArrowAnim.addUpdateListener(a -> {
            mArrowOffset = (float) a.getAnimatedValue();
            invalidate();
        });
        mArrowAnim.start();
    }

    /** 完成:一次性 scale 1.0→1.15→1.0 */
    private void startBounceOnce() {
        if (mBounceAnim != null && mBounceAnim.isRunning()) return;
        mBounceAnim = ValueAnimator.ofFloat(1f, 1.15f, 1f);
        mBounceAnim.setDuration(440);
        mBounceAnim.setInterpolator(new DecelerateInterpolator());
        mBounceAnim.addUpdateListener(a -> {
            mBounceScale = (float) a.getAnimatedValue();
            invalidate();
        });
        mBounceAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { mBounceScale = 1f; invalidate(); }
        });
        mBounceAnim.start();
    }

    /** 页面不可见时暂停全部动画(由宿主在 detach 时调用) */
    public void pauseAnimations() {
        stopAll();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - mRingWidthDp;

        // 外圈进度环:先画浅灰底环
        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeWidth(mRingWidthDp);
        mTrackPaint.setColor(TRACK_COLOR);
        mArcRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(mArcRect, -90f, 360f, false, mTrackPaint);

        // 进度环:按状态取色
        if (mProgress > 0f) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(mRingWidthDp);
            mPaint.setColor(mState == BubbleState.FAILED ? PROGRESS_FAIL_COLOR
                    : (mState == BubbleState.COMPLETED ? PROGRESS_OK_COLOR : PROGRESS_COLOR));
            canvas.drawArc(mArcRect, -90f, mProgress * 360f, false, mPaint);
        }

        // 中心圆底(主色调)
        float iconR = r - mRingWidthDp / 2f - 1;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(BG_COLOR);
        canvas.drawCircle(cx, cy, iconR, mPaint);

        // 中心图标(带状态缩放/位移)
        canvas.save();
        canvas.scale(mBounceScale, mBounceScale, cx, cy);
        canvas.translate(0f, mArrowOffset);
        drawCenterIcon(canvas, cx, cy, iconR);
        canvas.restore();
    }

    private void drawCenterIcon(Canvas canvas, float cx, float cy, float r) {
        float icon = r * 1.05f; // 图标目标尺寸
        switch (mState) {
            case FAILED: {
                // 白色错误叉号(两交叉线)
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(r * 0.16f);
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setColor(ICON_COLOR);
                float l = r * 0.30f;
                canvas.drawLine(cx - l, cy - l, cx + l, cy + l, mPaint);
                canvas.drawLine(cx + l, cy - l, cx - l, cy + l, mPaint);
                break;
            }
            case PAUSED: {
                // 白色暂停双竖线
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(ICON_COLOR);
                float barW = r * 0.17f;
                float barH = r * 0.55f;
                float gap = r * 0.16f;
                canvas.drawRoundRect(cx - gap - barW, cy - barH, cx - gap, cy + barH, barW, barW, mPaint);
                canvas.drawRoundRect(cx + gap, cy - barH, cx + gap + barW, cy + barH, barW, barW, mPaint);
                break;
            }
            case COMPLETED: {
                if (mCheckIcon != null) {
                    drawIconBound(canvas, mCheckIcon, cx, cy, icon);
                }
                break;
            }
            case DOWNLOADING:
            case IDLE:
            default: {
                if (mDownloadIcon != null) {
                    drawIconBound(canvas, mDownloadIcon, cx, cy, icon + mArrowOffset);
                }
                break;
            }
        }
    }

    private void drawIconBound(Canvas canvas, Drawable d, float cx, float cy, float size) {
        float half = size / 2f;
        d.setBounds((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
        d.draw(canvas);
    }
}
