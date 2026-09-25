package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/**
 * 封面"加载失败 / 无封面"统一占位:灰底圆角 + 猫图标 + 图标下方一行"图片加载失败"提示文字,
 * "图标 + 文字"作为整体在卡片里**垂直居中**。
 * 色值/圆角/图标与加载态占位 {@code placeholder_poster} 同源(bg_component + radius_card + ic_placeholder_cat)。
 * 与加载态占位保持一致的重心,图片加载失败时不会出现"图标突然往上跳"的观感。
 *
 * <p>为什么用代码画而非 XML:layer-list / vector 渲染不了中文,且这里要按控件尺寸动态排版。
 *
 * <p><b>底部覆盖层的避让</b>:搜索结果卡、{@code item_grid}(首页/收藏/历史宫格)的海报底部有一条
 * 渐变黑底信息条(集数"更新至N集" + 剧名),它是海报卡的兄弟视图、绘制在图片之上;占位内容压到
 * 那条黑底上会被盖住。故绘制时**自动量出**这条覆盖层的高度(同一父容器里、排在图片之后、底边与
 * 图片齐平的可见兄弟视图),让整组排在它上方;宿主没有这种覆盖层(详情弹窗海报、没有集数的卡)
 * 时不避让,直接居中。
 *
 * <p><b>怎么做到"既居中又不被盖"</b>:整组高度 G,覆盖层高 R,卡片高 H。
 * ① 想真正居中,整组上下各留出 R,即要求 G ≤ H - 2R → 不满足就**等比缩小图标**(留 40dp 下限);
 * ② 缩小仍不够(卡片很扁)就把整组整体上移,最少让到 R 之上(H - R - G);
 * ③ 图标再放不下就只画文字 —— 保证"图片加载失败"在任何卡片形状下都露在信息条上方,
 * 同时尽量让视线重心落在卡片中间。
 *
 * <p>全 App 失败占位只此一处(AGENTS §七:禁止各页面自造第二套占位图)。
 */
public class ErrorPlaceholderDrawable extends Drawable {

    private static final String TEXT = "图片加载失败";

    /** 为了居中而缩图标的尺寸下限(dp):再小就宁可整体上移,也不把图标缩成一个点 */
    private static final float MIN_ICON_DP = 40f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final float density;
    private final float cornerRadiusPx;
    private final float textSizePx;
    private final float minTextSizePx;
    private final float iconTextGapPx;
    private final float minIconPx;

    /** 每个 ImageView 需各自持有实例(Drawable 有 bounds 状态),故按次新建;开销很小 */
    public static Drawable get(Context context) {
        return new ErrorPlaceholderDrawable(context);
    }

    private ErrorPlaceholderDrawable(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        bgPaint.setColor(ContextCompat.getColor(context, R.color.bg_component));
        cornerRadiusPx = context.getResources().getDimension(R.dimen.radius_card);
        icon = ContextCompat.getDrawable(context, R.drawable.ic_placeholder_cat);
        textSizePx = 12f * density;
        minTextSizePx = 9f * density;
        iconTextGapPx = 3f * density;
        minIconPx = MIN_ICON_DP * density;
        textPaint.setColor(ContextCompat.getColor(context, R.color.text_sub_foreground));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        // 1) 灰底圆角(与加载态占位同色同圆角;ImageView 若自身有更小圆角由控件 clipToOutline 处理)
        float radius = Math.min(cornerRadiusPx, Math.min(b.width(), b.height()) * 0.5f);
        canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, bgPaint);

        // 2) 文字尺寸:随宽度等比并夹取上限,同时保证 6 个字不超出可用宽度
        float size = Math.max(minTextSizePx,
                Math.min(textSizePx, Math.min(b.width() * 0.14f, b.width() / 6.5f)));
        textPaint.setTextSize(size);

        // 3) 底部覆盖层(画在图片之上的信息条)高度:让它,别被盖住;没有覆盖层就是 0(纯居中)
        float reserve = Math.min(resolveOverlayReservePx(), Math.max(0f, b.height() - size));

        // 4) 图标:先原尺寸;为了能"整体居中"按需等比缩小(下限 minIconPx),放不下就只留文字
        float iconH = 0f;
        float iconW = 0f;
        if (icon != null) {
            float iw = Math.max(1, icon.getIntrinsicWidth());
            float ih = Math.max(1, icon.getIntrinsicHeight());
            float fitAboveBar = b.height() - reserve - size - iconTextGapPx;   // 硬约束:整组必须排在覆盖层上方
            float fitCentered = b.height() - 2f * reserve - size - iconTextGapPx; // 想真居中:上下各让出覆盖层
            if (fitAboveBar >= minIconPx * 0.5f) {   // 版面太扁(图标只剩一丁点)宁可不画图标,只留文字
                float target = Math.max(minIconPx, fitCentered);
                iconH = Math.min(ih, Math.min(fitAboveBar, target));
                iconW = iw * (iconH / ih);
            }
        }

        // 5) "图标 + 文字"当成一个整体:优先在整块版面里垂直居中;居中的话会压到覆盖层时再整体上移
        float groupH = iconH + (iconH > 0f ? iconTextGapPx : 0f) + size;
        float groupTop = b.top + (b.height() - groupH) / 2f;
        float limitTop = b.bottom - reserve - groupH;   // 整组底边不越过覆盖层
        if (groupTop > limitTop) groupTop = limitTop;
        if (groupTop < b.top) groupTop = b.top;
        if (iconH > 0f) {
            int left = Math.round(b.exactCenterX() - iconW / 2f);
            int top = Math.round(groupTop);
            icon.setBounds(left, top, left + Math.round(iconW), top + Math.round(iconH));
            icon.draw(canvas);
            groupTop += iconH + iconTextGapPx;
        }
        canvas.drawText(TEXT, b.exactCenterX(), groupTop + size, textPaint);
    }

    /**
     * 量出"画在图片之上、贴住图片底边"的兄弟视图高度(海报卡底部的渐变信息条)。
     * <p>
     * 判定:同一父容器里、排在图片视图之后(后画=在上层)、可见,且
     * ①底边与图片底边齐平、②从图片下半部分才开始(高度不超过卡片一半)、③不是 TextView/ImageView
     * (底部的剧名/备注文字、角标贴图都不是覆盖条)的兄弟,取其中最高的一条。
     * <p>
     * 这样量到的是真实高度(信息条随"集数行"显隐变矮/变高),不会被"图片旁边那列文字"误判;
     * 宿主没有这种覆盖层(详情弹窗海报、没有集数的卡)时返回 0,占位就是纯居中。
     * 新增海报卡布局时按这个形态摆覆盖条即可被自动识别,无需改这里的代码。
     */
    private float resolveOverlayReservePx() {
        if (!(getCallback() instanceof View)) return 0f;
        View self = (View) getCallback();
        ViewParent parent = self.getParent();
        if (!(parent instanceof ViewGroup)) return 0f;
        ViewGroup group = (ViewGroup) parent;
        int index = group.indexOfChild(self);
        if (index < 0) return 0f;
        int selfBottom = self.getBottom();
        int selfTop = self.getTop();
        int selfHeight = self.getHeight();
        if (selfHeight <= 0) return 0f;
        float reserve = 0f;
        for (int i = index + 1; i < group.getChildCount(); i++) {
            View sibling = group.getChildAt(i);
            if (sibling == null || sibling.getVisibility() != View.VISIBLE) continue;
            if (sibling instanceof android.widget.TextView || sibling instanceof android.widget.ImageView) continue;
            int h = sibling.getHeight();
            if (h <= 0 || h >= selfHeight) continue;                        // 覆盖条只会占卡片的一部分
            if (Math.abs(sibling.getBottom() - selfBottom) > 1) continue;   // 必须贴着图片底边覆盖
            if (sibling.getTop() < selfTop + selfHeight / 2) continue;      // 且只压住下半部分
            reserve = Math.max(reserve, h);
        }
        return reserve;
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
        if (icon != null) icon.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        bgPaint.setColorFilter(colorFilter);
        textPaint.setColorFilter(colorFilter);
        if (icon != null) icon.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
