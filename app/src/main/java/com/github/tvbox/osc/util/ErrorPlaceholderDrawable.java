package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/**
 * 封面"加载失败 / 无封面"统一占位:在加载态占位 {@code placeholder_poster}(灰底 + 居中猫)
 * 基础上,底部叠加一行"图片加载失败"提示文字。
 *
 * <p>为什么用代码画而非 XML:layer-list / vector 无法渲染中文文字,故在此把统一占位当底图,
 * 用 {@link Canvas#drawText} 叠字。文字大小随占位尺寸等比并夹取,始终清晰不拉伸模糊。
 * 全 App 失败占位只此一处(AGENTS §七:禁止各页面自造第二套占位图)。
 */
public class ErrorPlaceholderDrawable extends Drawable {

    private static final String TEXT = "图片加载失败";

    private final Drawable base;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float textSizePx;
    private final float minTextSizePx;
    private final float bottomInsetPx;

    /** 每个 ImageView 需各自持有实例(Drawable 有 bounds/callback 状态),故按次新建;底图共享 ConstantState,开销很小 */
    public static Drawable get(Context context) {
        return new ErrorPlaceholderDrawable(context);
    }

    private ErrorPlaceholderDrawable(Context context) {
        base = ContextCompat.getDrawable(context, R.drawable.placeholder_poster);
        float density = context.getResources().getDisplayMetrics().density;
        textSizePx = 12f * density;
        minTextSizePx = 9f * density;
        bottomInsetPx = 10f * density;
        textPaint.setColor(ContextCompat.getColor(context, R.color.text_sub_foreground));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        if (base != null) base.setBounds(bounds);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (base != null) base.draw(canvas);
        Rect b = getBounds();
        if (b.isEmpty()) return;
        // 文字随宽度等比,夹在 [minTextSizePx, textSizePx],避免极小/极大卡片下失真或溢出
        float size = Math.max(minTextSizePx, Math.min(textSizePx, b.width() * 0.14f));
        textPaint.setTextSize(size);
        float cx = b.exactCenterX();
        float baseline = b.bottom - bottomInsetPx;
        canvas.drawText(TEXT, cx, baseline, textPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        textPaint.setAlpha(alpha);
        if (base != null) base.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        textPaint.setColorFilter(colorFilter);
        if (base != null) base.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return base != null ? base.getIntrinsicWidth() : -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return base != null ? base.getIntrinsicHeight() : -1;
    }
}
