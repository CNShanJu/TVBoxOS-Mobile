package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.BgImageTransform;

/**
 * 背景图调整手势层(全屏透明,只负责"拖动位置 / 双指等比缩放"):
 * <ul>
 *   <li>单指拖动 = 平移,位移按视图尺寸归一化;</li>
 *   <li>双指捏合 = 等比缩放,并<b>保持两指中点下的图像点不动</b>(缩哪停哪),
 *       双指同时移动等于顺带平移;</li>
 *   <li>手势过程中回调 {@link Callback#onTune} 实时预览,抬手回调
 *       {@link Callback#onTuneCommitted} 交给宿主持久化。</li>
 * </ul>
 * 计算全部复用 {@link BgImageTransform}(与背景层渲染同一套模型,所见即所得);
 * 图片尺寸由宿主动态提供(未加载完时 {@link Callback#onTuneBegin} 返回 null,手势直接忽略)。
 */
public class BackgroundTuneView extends View {

    /** 手势开始时的背景状态(由宿主从背景层读取) */
    public static final class State {
        public final int imageW;
        public final int imageH;
        public final float zoom;
        public final float offsetX;
        public final float offsetY;

        public State(int imageW, int imageH, float zoom, float offsetX, float offsetY) {
            this.imageW = imageW;
            this.imageH = imageH;
            this.zoom = zoom;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    public interface Callback {
        /** 手势开始时取当前背景状态;返回 null 表示不可调整(如图片未加载完) */
        @Nullable
        State onTuneBegin();

        /** 实时预览(手势过程中高频调用,不要在这里写配置) */
        void onTune(float zoom, float offsetX, float offsetY);

        /** 手势结束:可持久化 */
        void onTuneCommitted(float zoom, float offsetX, float offsetY);
    }

    private Callback callback;

    /** 本次手势的起点状态 */
    private State start;
    private float lastX, lastY;
    /** 捏合阶段的固定基准:起始指距 + 起始缩放 + 起始焦点下的图像点(每次双指按下重新捕获) */
    private float startSpan;
    private float pinchStartZoom;
    private boolean pinching;
    /** 手势开始时焦点下的图像点(图像坐标) */
    private float focusImageX, focusImageY;

    public BackgroundTuneView(Context context) {
        this(context, null);
    }

    public BackgroundTuneView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BackgroundTuneView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (callback == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return beginGesture(event);
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    pinching = true;
                    startSpan = span(event);
                    captureFocusImagePoint(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (start == null) return false;
                if (pinching && event.getPointerCount() >= 2) {
                    applyPinch(event);
                } else {
                    applyDrag(event);
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // 抬掉一根手指:以剩下手指当前位置为新起点,继续拖动不跳变
                pinching = false;
                captureResumePoint(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                commit();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean beginGesture(MotionEvent event) {
        State s = callback.onTuneBegin();
        if (s == null || s.imageW <= 0 || s.imageH <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            start = null;
            return false;
        }
        start = s;
        pinching = false;
        lastX = event.getX();
        lastY = event.getY();
        return true;
    }

    /** 单指拖动:图片跟着手指走(位移 = 手指位移 / 视图尺寸) */
    private void applyDrag(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float dx = x - lastX;
        float dy = y - lastY;
        lastX = x;
        lastY = y;
        float ox = BgImageTransform.clampOffset(start.offsetX + dx / getWidth());
        float oy = BgImageTransform.clampOffset(start.offsetY + dy / getHeight());
        start = new State(start.imageW, start.imageH, start.zoom, ox, oy);
        callback.onTune(start.zoom, ox, oy);
    }

    /** 双指捏合:等比缩放 + 焦点锚定(焦点下的图像点在缩放前后不动) */
    private void applyPinch(MotionEvent event) {
        float span = span(event);
        if (startSpan <= 0f || span <= 0f) return;
        float factor = span / startSpan;
        // 注意:倍率始终相对"双指按下那一刻"的缩放,不能用上一次结果累乘(会指数放大)
        float zoom = BgImageTransform.clampZoom(pinchStartZoom * factor);
        float scale = BgImageTransform.coverScale(start.imageW, start.imageH, getWidth(), getHeight()) * zoom;
        if (scale <= 0f) return;
        float focusX = (event.getX(0) + event.getX(1)) / 2f;
        float focusY = (event.getY(0) + event.getY(1)) / 2f;
        // 让手势开始时焦点下的图像点在缩放后仍落在当前焦点位置(顺带处理双指平移)
        float left = focusX - focusImageX * scale;
        float top = focusY - focusImageY * scale;
        float ox = BgImageTransform.offsetFromLeft(left, start.imageW, getWidth(), scale);
        float oy = BgImageTransform.offsetFromTop(top, start.imageH, getHeight(), scale);
        start = new State(start.imageW, start.imageH, zoom, ox, oy);
        callback.onTune(zoom, ox, oy);
    }

    /** 记录当前焦点下的图像点(图像坐标系)+ 起始缩放,供捏合锚定使用 */
    private void captureFocusImagePoint(MotionEvent event) {
        float focusX = (event.getX(0) + event.getX(1)) / 2f;
        float focusY = (event.getY(0) + event.getY(1)) / 2f;
        float[] r = BgImageTransform.resolve(start.imageW, start.imageH, getWidth(), getHeight(),
                start.zoom, start.offsetX, start.offsetY);
        pinchStartZoom = start.zoom;
        if (r[0] <= 0f) {
            focusImageX = 0f;
            focusImageY = 0f;
            return;
        }
        focusImageX = (focusX - r[1]) / r[0];
        focusImageY = (focusY - r[2]) / r[0];
    }

    /** 从双指变单指:把"剩下那根手指"当作新起点,避免位置跳变 */
    private void captureResumePoint(MotionEvent event) {
        int keep = event.getActionIndex() == 0 ? 1 : 0;
        if (keep >= event.getPointerCount()) return;
        lastX = event.getX(keep);
        lastY = event.getY(keep);
    }

    private void commit() {
        State s = start;
        start = null;
        pinching = false;
        if (s != null && callback != null) {
            callback.onTuneCommitted(s.zoom, s.offsetX, s.offsetY);
        }
    }

    private static float span(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
