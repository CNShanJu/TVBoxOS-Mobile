package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.widget.ImageView;

import com.github.tvbox.osc.ui.kit.PicassoShimmer;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

/**
 * 图片加载统一入口(首页/历史/收藏等 3:4 竖卡片):
 * 占位不再作为 src 铺满(方形占位在 3:4 卡片 centerCrop 放大再切实图会有"突然挤瘦"跳变),
 * 而是用 ImageView 的背景层(item_grid 的 placeholder_poster = 灰底 + 居中固定比例图标):
 * 图标不随盒子缩放裁剪, 始终居中等比; 真实图片加载成功后 centerCrop 铺满盖住背景层。
 * 加载中在背景层上叠"骨架屏扫光"(PicassoShimmer),加载完成/失败移除,避免灰底硬切。
 */
public class PicassoLoad {

    public static void into(final ImageView iv, String url) {
        if (iv == null) return;
        // 清掉 src:复用列表项时移除上一张真实图, 露出灰底占位层
        iv.setImageDrawable(null);
        String trimUrl = url == null ? "" : url.trim();
        if (TextUtils.isEmpty(trimUrl)) {
            PicassoShimmer.stop(iv); // 空 URL:无加载,停扫光
            return;
        }
        // 加载中扫光;结束后(成功或失败)移除
        PicassoShimmer.start(iv);
        Picasso.get()
                .load(DefaultConfig.checkReplaceProxy(trimUrl))
                .into(iv, new Callback() {
                    @Override
                    public void onSuccess() {
                        PicassoShimmer.stop(iv);
                    }

                    @Override
                    public void onError(Exception e) {
                        PicassoShimmer.stop(iv);
                    }
                });
    }
}
