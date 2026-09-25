package com.github.tvbox.osc.util;

/**
 * 背景图变换的纯计算(不依赖 Android,可 JVM 单测)。
 * <p>
 * 用于全局背景层({@code ui/kit/PageBackgroundView})与"背景图设置"页的拖动/双指缩放,
 * 两边共用同一套模型,保证"设置页所见 = 页面最终所现"。
 * <p>
 * 变换模型(与设备无关,便于换分辨率/横竖屏还原):
 * <ul>
 *   <li>基准缩放 {@link #coverScale} = max(viewW/imgW, viewH/imgH),即 centerCrop 铺满屏幕;</li>
 *   <li>{@code zoom} 是基准之上的倍率(1=铺满);{@code zoom<=0} 视为"自动",
 *       由 {@link #defaultZoom} 决定(大图铺满、很小的图按原始像素,避免被硬放大到糊);</li>
 *   <li>{@code offsetX/offsetY} 是图片中心相对视图中心的位移,按视图尺寸归一化
 *       (0=居中;±0.5=图片中心贴到视图左/右、上/下边缘),钳制在 ±0.5 内,
 *       保证图片中心永远在屏幕内、不会整张拖没。</li>
 * </ul>
 */
public final class BgImageTransform {

    /**
     * "自动缩放"阈值:铺满屏幕所需的放大倍数超过该值时(图片相对屏幕很小),
     * 不铺满而按原始像素显示 —— 例如 100×100 的小图不会被放大 20 多倍糊成一片,
     * 用户可以自己拖到角上做点缀。
     */
    public static final float AUTO_COVER_MAX_UPSCALE = 3f;

    /** 缩放下限/上限(相对铺满基准的倍率) */
    public static final float MIN_ZOOM = 0.01f;
    public static final float MAX_ZOOM = 20f;

    /** 归一化位移的合法范围(图片中心贴到视图边缘) */
    public static final float MAX_OFFSET = 0.5f;

    private BgImageTransform() {
    }

    /** 铺满视图所需的最小缩放(centerCrop 语义);任一边长非法时返回 1 */
    public static float coverScale(int imgW, int imgH, int viewW, int viewH) {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return 1f;
        return Math.max((float) viewW / imgW, (float) viewH / imgH);
    }

    /**
     * 新选背景图的默认缩放:
     * 普通图片(铺满所需放大倍数 &le; {@link #AUTO_COVER_MAX_UPSCALE}) → 1,铺满屏幕;
     * 很小的图片 → 1/coverScale,按原始像素显示(不放大到糊),由用户自己拖位置。
     */
    public static float defaultZoom(int imgW, int imgH, int viewW, int viewH) {
        float cover = coverScale(imgW, imgH, viewW, viewH);
        if (cover > AUTO_COVER_MAX_UPSCALE) return 1f / cover;
        return 1f;
    }

    /** 缩放钳制(含 NaN 兜底) */
    public static float clampZoom(float zoom) {
        if (Float.isNaN(zoom)) return 1f;
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /** 归一化位移钳制:图片中心必须落在视图内(±0.5) */
    public static float clampOffset(float offset) {
        if (Float.isNaN(offset)) return 0f;
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
    }

    /**
     * 解析成实际绘制参数。
     *
     * @param zoom    &le;0 表示自动(见 {@link #defaultZoom})
     * @return 长度 3 的数组:{@code [0]=实际缩放, [1]=图片左上角 x, [2]=图片左上角 y}
     */
    public static float[] resolve(int imgW, int imgH, int viewW, int viewH,
                                  float zoom, float offsetX, float offsetY) {
        float z = zoom > 0 ? clampZoom(zoom) : defaultZoom(imgW, imgH, viewW, viewH);
        float scale = coverScale(imgW, imgH, viewW, viewH) * z;
        float left = viewW / 2f - imgW * scale / 2f + clampOffset(offsetX) * viewW;
        float top = viewH / 2f - imgH * scale / 2f + clampOffset(offsetY) * viewH;
        return new float[]{scale, left, top};
    }

    /** 由目标左上角反推归一化横向位移(捏合时保持焦点下的图像点不动用) */
    public static float offsetFromLeft(float left, int imgW, int viewW, float scale) {
        if (viewW <= 0 || imgW <= 0) return 0f;
        float center = viewW / 2f - imgW * scale / 2f;
        return clampOffset((left - center) / viewW);
    }

    /** 由目标左上角反推归一化纵向位移 */
    public static float offsetFromTop(float top, int imgH, int viewH, float scale) {
        if (viewH <= 0 || imgH <= 0) return 0f;
        float center = viewH / 2f - imgH * scale / 2f;
        return clampOffset((top - center) / viewH);
    }

    // ── 预设(设置页的"全屏铺满 / 保持大小 / 居中显示 / 四角") ──

    /** 预设尺寸:全屏铺满(等比盖满屏幕、超出部分裁掉;等价于"超出裁剪") */
    public static final int SIZE_FILL = 0;
    /** 预设尺寸:保持大小(按原始像素 1:1 显示,不放大到糊) */
    public static final int SIZE_NATIVE = 1;

    /** 预设位置:居中 */
    public static final int POS_CENTER = 0;
    public static final int POS_TOP_LEFT = 1;
    public static final int POS_TOP_RIGHT = 2;
    public static final int POS_BOTTOM_LEFT = 3;
    public static final int POS_BOTTOM_RIGHT = 4;

    /** 高亮判定容差:倍率 2%(相对)/ 位移 0.02(屏宽比例) */
    private static final float ZOOM_TOLERANCE = 0.02f;
    private static final float OFFSET_TOLERANCE = 0.02f;

    /** 尺寸预设对应的缩放倍率(相对铺满基准):铺满=1;保持大小=1/coverScale(1:1 像素) */
    public static float zoomForSize(int size, int imgW, int imgH, int viewW, int viewH) {
        if (size != SIZE_NATIVE) return 1f;
        float cover = coverScale(imgW, imgH, viewW, viewH);
        return cover > 0f ? 1f / cover : 1f;
    }

    /**
     * 位置预设对应的位移:返回 {@code {offsetX, offsetY}}。
     * <p>
     * 四角/两侧是"边贴边"(图片左边缘贴屏幕左边、右边缘贴右边…),所以必须知道当前倍率才能算 ——
     * 这样 100×100 的小图摆到右下角时整张图都在屏内(而不是中心压在角上、一半在屏外)。
     */
    public static float[] offsetsForPosition(int position, int imgW, int imgH, int viewW, int viewH, float zoom) {
        float scale = coverScale(imgW, imgH, viewW, viewH)
                * (zoom > 0f ? clampZoom(zoom) : defaultZoom(imgW, imgH, viewW, viewH));
        float ox = 0f;
        float oy = 0f;
        if (scale > 0f) {
            switch (position) {
                case POS_TOP_LEFT:
                case POS_BOTTOM_LEFT:
                    ox = offsetFromLeft(0f, imgW, viewW, scale);
                    break;
                case POS_TOP_RIGHT:
                case POS_BOTTOM_RIGHT:
                    ox = offsetFromLeft(viewW - imgW * scale, imgW, viewW, scale);
                    break;
                default:
                    break;
            }
            switch (position) {
                case POS_TOP_LEFT:
                case POS_TOP_RIGHT:
                    oy = offsetFromTop(0f, imgH, viewH, scale);
                    break;
                case POS_BOTTOM_LEFT:
                case POS_BOTTOM_RIGHT:
                    oy = offsetFromTop(viewH - imgH * scale, imgH, viewH, scale);
                    break;
                default:
                    break;
            }
        }
        return new float[]{ox, oy};
    }

    /** 当前倍率是否等于某个尺寸预设(用于设置页高亮;尺寸未知/无效时返回 false) */
    public static boolean matchesSize(float zoom, int size, int imgW, int imgH, int viewW, int viewH) {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return false;
        float target = zoomForSize(size, imgW, imgH, viewW, viewH);
        return Math.abs(zoom - target) <= Math.max(0.01f, target * ZOOM_TOLERANCE);
    }

    /** 当前位移是否等于某个位置预设(用于设置页高亮) */
    public static boolean matchesPosition(float offsetX, float offsetY, int position,
                                          int imgW, int imgH, int viewW, int viewH, float zoom) {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return false;
        float[] target = offsetsForPosition(position, imgW, imgH, viewW, viewH, zoom);
        return Math.abs(offsetX - target[0]) <= OFFSET_TOLERANCE
                && Math.abs(offsetY - target[1]) <= OFFSET_TOLERANCE;
    }
}
