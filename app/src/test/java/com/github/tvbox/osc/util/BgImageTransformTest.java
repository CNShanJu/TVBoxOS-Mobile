package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 背景图变换纯计算单测(纯 JVM,不碰真机):覆盖设置页拖动/缩放直接依赖的语义。
 * <p>
 * 视图取竖屏 1080×2400。
 */
public class BgImageTransformTest {

    private static final int VW = 1080;
    private static final int VH = 2400;

    /** 普通壁纸(比例接近屏幕):铺满所需放大倍数 &lt; 3 → 默认缩放 1(铺满) */
    @Test
    public void normalImage_defaultZoomIsCover() {
        assertEquals(1f, BgImageTransform.defaultZoom(941, 1672, VW, VH), 1e-6);
    }

    /** 很小的图(100×100):默认按原始像素显示,不被硬放大 */
    @Test
    public void tinyImage_defaultZoomKeepsNativePixels() {
        float cover = BgImageTransform.coverScale(100, 100, VW, VH); // 24
        assertEquals(24f, cover, 1e-6);
        float zoom = BgImageTransform.defaultZoom(100, 100, VW, VH);
        assertEquals(1f / 24f, zoom, 1e-6);
        // 实际缩放 = cover × zoom = 1 → 100×100 像素原样显示
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, 0f, 0f, 0f);
        assertEquals(1f, r[0], 1e-6);
    }

    /** 默认铺满时:图片正好盖住整个视图(左右或上下不留边) */
    @Test
    public void coverFillsView() {
        float[] r = BgImageTransform.resolve(941, 1672, VW, VH, 1f, 0f, 0f);
        float scale = r[0], left = r[1], top = r[2];
        assertTrue("左边不留缝", left <= 0f);
        assertTrue("上边不留缝", top <= 0f);
        assertTrue("右边不留缝", left + 941 * scale >= VW - 1e-3);
        assertTrue("下边不留缝", top + 1672 * scale >= VH - 1e-3);
    }

    /** 小图拖到右下角:中心贴到视图右下边缘(offset=+0.5),仍可见 */
    @Test
    public void tinyImagePlacedBottomRight() {
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, 0f, 0.5f, 0.5f);
        float scale = r[0], left = r[1], top = r[2];
        assertEquals(1f, scale, 1e-6);
        assertEquals("中心 x = 视图右边缘", VW, left + 100 * scale / 2f, 1e-3);
        assertEquals("中心 y = 视图下边缘", VH, top + 100 * scale / 2f, 1e-3);
    }

    /** 位移钳制:超出 ±0.5 一律按边缘处理,图片不可能被拖出屏幕 */
    @Test
    public void offsetsAreClamped() {
        assertEquals(0.5f, BgImageTransform.clampOffset(9f), 1e-6);
        assertEquals(-0.5f, BgImageTransform.clampOffset(-9f), 1e-6);
        float[] a = BgImageTransform.resolve(100, 100, VW, VH, 0f, 9f, 9f);
        float[] b = BgImageTransform.resolve(100, 100, VW, VH, 0f, 0.5f, 0.5f);
        assertEquals(b[1], a[1], 1e-6);
        assertEquals(b[2], a[2], 1e-6);
    }

    /** 缩放钳制:NaN / 越界都收敛到合法区间 */
    @Test
    public void zoomIsClamped() {
        assertEquals(1f, BgImageTransform.clampZoom(Float.NaN), 1e-6);
        assertEquals(BgImageTransform.MIN_ZOOM, BgImageTransform.clampZoom(1e-9f), 1e-6);
        assertEquals(BgImageTransform.MAX_ZOOM, BgImageTransform.clampZoom(1e9f), 1e-6);
    }

    /** offsetFromLeft/Top 与 resolve 互为逆运算(设置页按焦点缩放要用) */
    @Test
    public void offsetRoundTrip() {
        float zoom = 1.7f;
        float ox = 0.31f, oy = -0.22f;
        float[] r = BgImageTransform.resolve(941, 1672, VW, VH, zoom, ox, oy);
        assertEquals(ox, BgImageTransform.offsetFromLeft(r[1], 941, VW, r[0]), 1e-4);
        assertEquals(oy, BgImageTransform.offsetFromTop(r[2], 1672, VH, r[0]), 1e-4);
    }

    /**
     * 捏合缩放的核心不变量:焦点下的那个图像点在缩放前后仍在焦点位置
     * (模拟设置页双指缩放的算法,确保"缩哪就停在哪儿")。
     */
    @Test
    public void pinchKeepsFocusAnchored() {
        int imgW = 941, imgH = 1672;
        float zoom0 = 1f, ox0 = 0.1f, oy0 = -0.05f;
        float focusX = 300f, focusY = 1800f;

        float[] before = BgImageTransform.resolve(imgW, imgH, VW, VH, zoom0, ox0, oy0);
        float imgPointX = (focusX - before[1]) / before[0];
        float imgPointY = (focusY - before[2]) / before[0];

        float zoom1 = 1.3f;
        float scale1 = BgImageTransform.coverScale(imgW, imgH, VW, VH) * zoom1;
        float left1 = focusX - imgPointX * scale1;
        float top1 = focusY - imgPointY * scale1;
        float ox1 = BgImageTransform.offsetFromLeft(left1, imgW, VW, scale1);
        float oy1 = BgImageTransform.offsetFromTop(top1, imgH, VH, scale1);

        float[] after = BgImageTransform.resolve(imgW, imgH, VW, VH, zoom1, ox1, oy1);
        assertEquals(before[0] * zoom1 / zoom0, after[0], 1e-3);
        // 钳制可能让焦点略微偏移,但必须在 1px 量级内
        float pointX = (focusX - after[1]) / after[0];
        float pointY = (focusY - after[2]) / after[0];
        assertEquals(imgPointX, pointX, 1e-3);
        assertEquals(imgPointY, pointY, 1e-3);
    }

    /** 非法尺寸(未加载完)不抛异常,返回可用兜底值 */
    @Test
    public void invalidSizesFallBack() {
        assertEquals(1f, BgImageTransform.coverScale(0, 0, VW, VH), 1e-6);
        float[] r = BgImageTransform.resolve(0, 0, 0, 0, 0f, 0f, 0f);
        assertEquals(1f, r[0], 1e-6);
        assertEquals(0f, r[1], 1e-6);
        assertEquals(0f, r[2], 1e-6);
    }

    // ── 预设(全屏铺满 / 保持大小 / 居中 / 四角) ──

    /** 全屏铺满 = 盖满屏幕(与"超出裁剪"同一个效果) */
    @Test
    public void presetFillCoversScreen() {
        assertEquals(1f, BgImageTransform.zoomForSize(BgImageTransform.SIZE_FILL, 941, 1672, VW, VH), 1e-6);
        float zoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_FILL, 100, 100, VW, VH);
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, zoom, 0f, 0f);
        // 100×100 小图"铺满"=放大到盖住整屏
        assertTrue(r[1] <= 0f && r[2] <= 0f);
        assertTrue(r[1] + 100 * r[0] >= VW - 1e-3);
        assertTrue(r[2] + 100 * r[0] >= VH - 1e-3);
    }

    /** 保持大小 = 原始像素 1:1(小图不被放大) */
    @Test
    public void presetNativeKeepsPixelSize() {
        float zoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 100, 100, VW, VH);
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, zoom, 0f, 0f);
        assertEquals(1f, r[0], 1e-4);
        assertEquals("居中", VW / 2f, r[1] + 50f, 1e-3);
        assertEquals("居中", VH / 2f, r[2] + 50f, 1e-3);
        // 大图的"原始像素"会小于铺满倍率
        float bigZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 941, 1672, VW, VH);
        assertTrue(bigZoom < 1f);
    }

    /** 四角预设:保持原图大小 + 边贴边放到对应角(整张图都在屏内) */
    @Test
    public void presetCorners() {
        float nativeZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 100, 100, VW, VH);
        float[] tlOffset = BgImageTransform.offsetsForPosition(
                BgImageTransform.POS_TOP_LEFT, 100, 100, VW, VH, nativeZoom);
        float[] tl = BgImageTransform.resolve(100, 100, VW, VH, nativeZoom, tlOffset[0], tlOffset[1]);
        assertEquals(1f, tl[0], 1e-4);
        assertEquals("左边缘贴屏幕左边", 0f, tl[1], 1e-3);
        assertEquals("上边缘贴屏幕上边", 0f, tl[2], 1e-3);

        float[] brOffset = BgImageTransform.offsetsForPosition(
                BgImageTransform.POS_BOTTOM_RIGHT, 100, 100, VW, VH, nativeZoom);
        float[] br = BgImageTransform.resolve(100, 100, VW, VH, nativeZoom, brOffset[0], brOffset[1]);
        assertEquals("右边缘贴屏幕右边", VW, br[1] + 100 * br[0], 1e-3);
        assertEquals("下边缘贴屏幕下边", VH, br[2] + 100 * br[0], 1e-3);

        float[] centerOffset = BgImageTransform.offsetsForPosition(
                BgImageTransform.POS_CENTER, 100, 100, VW, VH, nativeZoom);
        assertEquals(0f, centerOffset[0], 1e-6);
        assertEquals(0f, centerOffset[1], 1e-6);
    }

    /** 高亮判定:命中对应预设为真,偏差过大为假;尺寸未知时一律不亮 */
    @Test
    public void presetHighlightMatching() {
        assertTrue(BgImageTransform.matchesSize(1f, BgImageTransform.SIZE_FILL, 941, 1672, VW, VH));
        assertFalse(BgImageTransform.matchesSize(1.2f, BgImageTransform.SIZE_FILL, 941, 1672, VW, VH));
        float nativeZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 941, 1672, VW, VH);
        assertTrue(BgImageTransform.matchesSize(nativeZoom, BgImageTransform.SIZE_NATIVE, 941, 1672, VW, VH));
        assertFalse(BgImageTransform.matchesSize(nativeZoom, BgImageTransform.SIZE_NATIVE, 0, 0, VW, VH));

        assertTrue(BgImageTransform.matchesPosition(0f, 0f, BgImageTransform.POS_CENTER, 100, 100, VW, VH, nativeZoom));
        assertFalse(BgImageTransform.matchesPosition(0.3f, 0f, BgImageTransform.POS_CENTER, 100, 100, VW, VH, nativeZoom));
        float[] br = BgImageTransform.offsetsForPosition(BgImageTransform.POS_BOTTOM_RIGHT, 100, 100, VW, VH, nativeZoom);
        assertTrue(BgImageTransform.matchesPosition(br[0], br[1], BgImageTransform.POS_BOTTOM_RIGHT, 100, 100, VW, VH, nativeZoom));
        assertFalse(BgImageTransform.matchesPosition(br[0], br[1], BgImageTransform.POS_TOP_LEFT, 100, 100, VW, VH, nativeZoom));
    }
}
