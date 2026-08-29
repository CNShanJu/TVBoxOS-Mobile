package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.widget.ImageView;

import com.github.tvbox.osc.R;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

/**
 * 图片加载统一入口(首页/历史/收藏等 3:4 竖卡片):
 * 占位→实图切换加 200ms 淡入, 消除占位方图(512×512)在 3:4 卡片 centerCrop
 * 放大后切到实图的视觉跳变("突然挤瘦"); 空 URL 直接显示占位图。
 */
public class PicassoLoad {

    /** 淡入时长(ms) */
    private static final int FADE_MS = 200;

    public static void into(final ImageView iv, String url) {
        if (iv == null) return;
        if (TextUtils.isEmpty(url)) {
            iv.setImageResource(R.drawable.iv_load_fail);
            return;
        }
        final String trimUrl = url.trim();
        iv.setTag(trimUrl);
        Picasso.get()
                .load(DefaultConfig.checkReplaceProxy(trimUrl))
                .placeholder(R.drawable.iv_load_fail)
                .error(R.drawable.iv_load_fail)
                .into(iv, new Callback() {
                    @Override
                    public void onSuccess() {
                        // 复用保护:仅当该 ImageView 仍对应本次 URL 时淡入(列表滚动复用会换 URL)
                        if (trimUrl.equals(iv.getTag())) {
                            iv.setAlpha(0f);
                            iv.animate().alpha(1f).setDuration(FADE_MS).start();
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                    }
                });
    }
}
