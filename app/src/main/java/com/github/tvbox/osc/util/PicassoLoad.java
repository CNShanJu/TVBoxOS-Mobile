package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.widget.ImageView;

import com.github.tvbox.osc.R;
import com.squareup.picasso.Picasso;

/**
 * 图片加载统一入口(首页/历史/收藏等 3:4 竖卡片):
 * 占位不再作为 src 铺满(方形占位在 3:4 卡片 centerCrop 放大再切实图会有"突然挤瘦"跳变),
 * 而是用 ImageView 的背景层(item_grid 的 placeholder_poster = 灰底 + 居中固定比例图标):
 * 图标不随盒子缩放裁剪, 始终居中等比; 真实图片加载成功后 centerCrop 铺满盖住背景层。
 * 空 URL 只清 src, 露出灰底占位。
 */
public class PicassoLoad {

    public static void into(final ImageView iv, String url) {
        if (iv == null) return;
        // 清掉 src:复用列表项时移除上一张真实图, 露出灰底占位层
        iv.setImageDrawable(null);
        if (TextUtils.isEmpty(url)) {
            return;
        }
        String trimUrl = url.trim();
        if (TextUtils.isEmpty(trimUrl)) {
            return;
        }
        Picasso.get()
                .load(DefaultConfig.checkReplaceProxy(trimUrl))
                .into(iv);
    }
}
