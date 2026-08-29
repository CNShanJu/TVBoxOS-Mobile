package com.github.tvbox.osc.ui.adapter;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.SPUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.constant.CacheConst;
import com.github.tvbox.osc.util.Utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalVideoAdapter extends BaseQuickAdapter<VideoInfo, BaseViewHolder> {

    public interface OnSelectedCountListener {
        void onSelectedCount(int count);
    }

    OnSelectedCountListener mOnSelectedCountListener;
    private boolean selectMode = false;

    // 视频帧缓存:path -> Bitmap(LRU 上限 64),避免滚动/刷新反复取帧
    private static final int FRAME_CACHE_MAX = 64;
    private final Map<String, Bitmap> frameCache = new LinkedHashMap<String, Bitmap>(FRAME_CACHE_MAX, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > FRAME_CACHE_MAX;
        }
    };
    private static final ExecutorService FRAME_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-video-frame");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 行内 tag key:异步取帧回调校验 holder 是否已复用到其它条目
    private static final int TAG_KEY_PATH = 0x6d000001;
    private static final int TAG_KEY_VIEWS = 0x6d000002;

    public LocalVideoAdapter() {
        super(R.layout.item_local_video);
    }

    @Override
    protected void convert(BaseViewHolder helper, VideoInfo item) {
        // 主标题:剧名(下载完成项);本地视频列表无剧名用文件名
        String name = item.getVodName();
        if (TextUtils.isEmpty(name)) name = item.getDisplayName();
        TextView tvName = helper.getView(R.id.tv_name);
        tvName.setText(name);
        // 主文字色与主题一致(text_foreground=text_main);播放过的剧集标题置灰(主题二级色)
        tvName.setTextColor(ContextCompat.getColor(mContext,
                item.isPlayed() ? R.color.text_sub_foreground : R.color.text_foreground));

        // 集数行:第N集 -> "集数：N";其它集名 -> "集数：<名>";无集数(单文件电影等)隐藏
        TextView tvEpisode = helper.getView(R.id.tv_episode);
        String ep = item.getEpisodeName();
        if (!TextUtils.isEmpty(ep)) {
            if (ep.matches("第\\d+集")) {
                tvEpisode.setText("集数：" + ep.substring(1, ep.length() - 1));
            } else {
                tvEpisode.setText("集数：" + ep);
            }
            tvEpisode.setVisibility(View.VISIBLE);
        } else {
            tvEpisode.setVisibility(View.GONE);
        }

        // 来源行
        TextView tvSource = helper.getView(R.id.tv_source);
        if (!TextUtils.isEmpty(item.getSourceName())) {
            tvSource.setText(item.getSourceName());
            tvSource.setVisibility(View.VISIBLE);
        } else {
            tvSource.setVisibility(View.GONE);
        }

        helper.setText(R.id.tv_video_size, formatSize(item.getSize()));

        // 时长/进度:总长>0 才显示;总长未知时隐藏(异步取帧后会补上),杜绝 "00:02/00:00"
        ProgressBar progressBar = helper.getView(R.id.progressBar);
        TextView tvDuration = helper.getView(R.id.tv_duration);
        long duration = item.getDuration();
        if (duration <= 0) {
            long cache = SPUtils.getInstance(CacheConst.VIDEO_DURATION_SP).getLong(item.getPath(), -1);
            if (cache > 0) duration = cache;
        }
        long progressPlayed = SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).getLong(item.getPath(), -1);
        if (duration > 0) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setMax((int) duration);
            if (progressPlayed > 0 && progressPlayed < duration) {
                tvDuration.setText(Utils.stringForTime(progressPlayed) + "/" + Utils.stringForTime(duration));
                progressBar.setProgress((int) progressPlayed);
            } else {
                tvDuration.setText(Utils.stringForTime(duration));
                progressBar.setProgress(0);
            }
        } else {
            tvDuration.setText("");
            progressBar.setVisibility(View.INVISIBLE);
            progressBar.setProgress(0);
        }

        // 封面:视频内容截图(异步 MediaMetadataRetriever 取帧,第1秒画面避免黑帧;失败回退占位;顺带补时长)
        ImageView iv = helper.getView(R.id.iv);
        iv.setTag(TAG_KEY_PATH, item.getPath());
        iv.setTag(TAG_KEY_VIEWS, new Object[]{tvDuration, progressBar});
        Bitmap cached = frameCache.get(item.getPath());
        if (cached != null) {
            iv.setImageBitmap(cached);
        } else {
            iv.setImageResource(R.drawable.iv_video);
            loadFrameAsync(iv, item.getPath());
        }

        CheckBox cb = helper.getView(R.id.cb);
        cb.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        cb.setChecked(item.isChecked());

        if (mOnSelectedCountListener != null) {
            int count = 0;
            for (VideoInfo videoInfo : getData()) {
                if (videoInfo.isChecked()) {
                    count++;
                }
            }
            mOnSelectedCountListener.onSelectedCount(count);
        }
    }

    /** 后台线程取视频帧(第1秒画面)+ 总时长;完成后回主线程,holder 未复用才更新 */
    private void loadFrameAsync(final ImageView iv, final String path) {
        FRAME_EXECUTOR.execute(() -> {
            long dur = 0;
            Bitmap bmp = null;
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(path);
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    try {
                        dur = Long.parseLong(d);
                    } catch (Throwable ignored) {
                    }
                }
                // 取第 1 秒画面(避开黑屏/灰屏首帧),CLOSEST_SYNC 保证拿到关键帧
                bmp = mmr.getFrameAtTime(1000 * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable th) {
                bmp = null;
            } finally {
                try {
                    if (mmr != null) mmr.release();
                } catch (Throwable ignored) {
                }
            }
            final Bitmap fb = bmp;
            final long fdur = dur;
            mainHandler.post(() -> {
                // holder 复用校验:path 不匹配说明该行已滚走/复用,不更新
                if (!path.equals(iv.getTag(TAG_KEY_PATH))) return;
                if (fb != null) {
                    frameCache.put(path, fb);
                    iv.setImageBitmap(fb);
                } else {
                    iv.setImageResource(R.drawable.iv_load_fail);
                }
                // 时长补写 SP + 刷新该行时长文本/进度条
                if (fdur > 0) {
                    SPUtils.getInstance(CacheConst.VIDEO_DURATION_SP).put(path, fdur);
                    Object[] views = (Object[]) iv.getTag(TAG_KEY_VIEWS);
                    if (views != null && views[0] instanceof TextView) {
                        TextView tvDuration = (TextView) views[0];
                        ProgressBar pb = (ProgressBar) views[1];
                        long played = SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).getLong(path, -1);
                        if (pb != null) {
                            pb.setVisibility(View.VISIBLE);
                            pb.setMax((int) fdur);
                        }
                        if (played > 0 && played < fdur) {
                            tvDuration.setText(Utils.stringForTime(played) + "/" + Utils.stringForTime(fdur));
                            if (pb != null) pb.setProgress((int) played);
                        } else {
                            tvDuration.setText(Utils.stringForTime(fdur));
                            if (pb != null) pb.setProgress(0);
                        }
                    }
                }
            });
        });
    }

    public void setSelectMode(boolean selectMode) {
        this.selectMode = selectMode;
        notifyDataSetChanged();
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public void setOnSelectCountListener(OnSelectedCountListener listener) {
        mOnSelectedCountListener = listener;
    }

    /** 文件大小:MB/KB 去小数点(224.113MB -> 224MB),GB 保留两位小数(1.25GB) */
    private static String formatSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return (size / 1024) + "KB";
        if (size < 1024L * 1024 * 1024) return (size / 1024 / 1024) + "MB";
        return String.format("%.2fGB", size / 1024.0 / 1024.0 / 1024.0);
    }
}
