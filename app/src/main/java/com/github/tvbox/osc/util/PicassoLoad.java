package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
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
 *
 * <p>防闪:① 同一张图已在当前视图显示时直接跳过(滑回/复用不再重载重闪);
 * ② 扫光延迟一小段再启动——命中内存/磁盘缓存的图会在延迟内就绪并取消扫光,只有真正
 * 要网络加载(慢)的图才会扫光。
 */
public class PicassoLoad {

    /** 视图上记录的"当前已展示图片 URL"tag,用于同一张图跳过重载 */
    private static final int TAG_LAST_URL = 0x3D000001;
    /** 视图上记录的"延迟启动扫光"Runnable tag,加载结果到来时取消 */
    private static final int TAG_SHIMMER_RUN = 0x3D000002;
    /** 扫光延迟(ms):加载在此内完成(缓存/较快网络)则不启动骨架屏,只有真正慢(>1s)才扫光 */
    private static final long SHIMMER_DELAY_MS = 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 会话内已成功加载过的 URL:滑回/复用命中则不再重启骨架屏,避免"顶部卡最初不扫、滑回却扫"的不一致 */
    private static final java.util.Set<String> sessionLoaded =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void into(final ImageView iv, String url) {
        if (iv == null) return;
        // 清掉 src:复用列表项时移除上一张真实图, 露出灰底占位层
        String trimUrl = url == null ? "" : url.trim();
        if (TextUtils.isEmpty(trimUrl)) {
            cancelShimmer(iv);
            PicassoShimmer.stop(iv); // 空 URL:无封面,显示"加载失败"占位
            iv.setBackgroundResource(com.github.tvbox.osc.R.drawable.placeholder_poster_error);
            return;
        }
        // 恢复为正常占位(上一张可能是"加载失败")
        iv.setBackgroundResource(com.github.tvbox.osc.R.drawable.placeholder_poster);
        // 同一张图已显示(滑回/复用相同项):不重载、不闪
        if (trimUrl.equals(iv.getTag(TAG_LAST_URL))) return;
        iv.setTag(TAG_LAST_URL, trimUrl);
        iv.setImageDrawable(null);
        // 已成功加载过(会话缓存命中)→ 不再启动骨架屏,直接出图;仅真正加载(慢)才延迟扫光
        boolean cached = sessionLoaded.contains(trimUrl);
        if (!cached) {
            Runnable run = () -> PicassoShimmer.start(iv);
            iv.setTag(TAG_SHIMMER_RUN, run);
            MAIN.postDelayed(run, SHIMMER_DELAY_MS);
        }
        Picasso.get()
                .load(DefaultConfig.checkReplaceProxy(trimUrl))
                .into(iv, new Callback() {
                    @Override
                    public void onSuccess() {
                        sessionLoaded.add(trimUrl);
                        cancelShimmer(iv);
                        PicassoShimmer.stop(iv);
                    }

                    @Override
                    public void onError(Exception e) {
                        cancelShimmer(iv);
                        PicassoShimmer.stop(iv);
                        // 加载失败:切换到"加载失败"占位,让用户知道没拿到图
                        iv.setBackgroundResource(com.github.tvbox.osc.R.drawable.placeholder_poster_error);
                    }
                });
    }

    private static void cancelShimmer(ImageView iv) {
        Object run = iv.getTag(TAG_SHIMMER_RUN);
        if (run instanceof Runnable) {
            MAIN.removeCallbacks((Runnable) run);
        }
        iv.setTag(TAG_SHIMMER_RUN, null);
    }
}
