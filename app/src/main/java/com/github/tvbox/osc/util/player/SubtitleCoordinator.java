package com.github.tvbox.osc.util.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.PlayerTrackHelper;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleDialog;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.obsez.android.lib.filechooser.ChooserDialog;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 字幕协调器（改进.txt §三 PlayFragment 拆分）：承载字幕装载 / 字幕设置弹窗 / 音轨与内置字幕切换
 * 的全部交互与状态同步，宿主（PlayFragment）只保留薄转发。
 * <p>
 * 依赖注入：Activity（弹窗/回调 UI 线程）、{@link VodController}（mSubtitleView / openSubtitle /
 * startProgress）、{@link MyVideoView}（getMediaPlayer）。行为与迁出前的 PlayFragment 私有方法逐行等价，
 * 内核差异（轨道/字幕回调）统一经 {@link PlayerTrackHelper}。
 */
public final class SubtitleCoordinator {

    private final Activity mActivity;
    private final VodController mController;
    private final MyVideoView mVideoView;

    /** 当前播放字幕上下文（每次播放结果变化由宿主 set 一次） */
    @Nullable
    private String mPlaySubtitle;
    @Nullable
    private String mSubtitleCacheKey;

    public SubtitleCoordinator(@NonNull Activity activity, @NonNull VodController controller,
                               @NonNull MyVideoView videoView) {
        mActivity = activity;
        mController = controller;
        mVideoView = videoView;
    }

    /** 更新当前剧集字幕上下文（playResult.subt / subtKey）；每次切集调用 */
    public void updateSubtitleContext(@Nullable String playSubtitle, @Nullable String subtitleCacheKey) {
        mPlaySubtitle = playSubtitle;
        mSubtitleCacheKey = subtitleCacheKey;
    }

    // ── 字幕装载（内核 prepared 后调用一次）──

    /** 装载字幕：恢复缓存/外部字幕，否则自动选中文内置字幕；显隐跟随"字幕"开关 */
    public void initSubtitleView() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = PlayerTrackHelper.getTrackInfo(mediaPlayer);
        if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
            mController.mSubtitleView.hasInternal = true;
        }
        PlayerTrackHelper.setOnSubtitleListener(mediaPlayer, new PlayerTrackHelper.SubtitleListener() {
            @Override
            public void onSubtitle(@Nullable String text) {
                if (mController.mSubtitleView.isInternal) {
                    if (text == null) {
                        mController.mSubtitleView.onSubtitleChanged(null);
                    } else {
                        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                        subtitle.content = text;
                        mController.mSubtitleView.onSubtitleChanged(subtitle);
                    }
                }
            }
        });

        mController.mSubtitleView.bindToMediaPlayer(mVideoView.getMediaPlayer());
        String cacheKey = mSubtitleCacheKey;
        if (cacheKey != null) {
            mController.mSubtitleView.setPlaySubtitleCacheKey(cacheKey);
            String subtitlePathCache = (String) com.github.tvbox.osc.repo.HistoryRepositories.cache().get(MD5.string2MD5(cacheKey));
            if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
                mController.mSubtitleView.setSubtitlePath(subtitlePathCache);
            } else {
                applyFallbackOrInternalSubtitle(trackInfo);
            }
        } else {
            applyFallbackOrInternalSubtitle(trackInfo);
        }
        // 字幕默认关闭:显隐跟随设置(用户可在播放器字幕设置里打开/关闭)
        mController.mSubtitleView.setVisibility(PlayConfig.isSubtitleOpen() ? View.VISIBLE : View.GONE);
    }

    /** 无字幕缓存时：外部字幕优先，其次自动选中文内置字幕（与原 PlayFragment.initSubtitleView 等价） */
    private void applyFallbackOrInternalSubtitle(@Nullable TrackInfo trackInfo) {
        if (mPlaySubtitle != null && mPlaySubtitle.length() > 0) {
            mController.mSubtitleView.setSubtitlePath(mPlaySubtitle);
        } else if (mController.mSubtitleView.hasInternal) {//有则使用内置字幕
            mController.mSubtitleView.isInternal = true;
            if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                List<TrackInfoBean> subtitleTrackList = trackInfo.getSubtitle();
                int selectedIndex = trackInfo.getSubtitleSelected(true);
                boolean hasCh = false;
                for (TrackInfoBean subtitleTrackInfoBean : subtitleTrackList) {
                    String lowerLang = subtitleTrackInfoBean.language == null ? "" : subtitleTrackInfoBean.language.toLowerCase();
                    if (lowerLang.contains("zh") || lowerLang.contains("ch")) {
                        hasCh = true;
                        if (selectedIndex != subtitleTrackInfoBean.trackId) {
                            PlayerTrackHelper.selectTrack(mVideoView.getMediaPlayer(), subtitleTrackInfoBean);
                            break;
                        }
                    }
                }
                if (!hasCh) {
                    PlayerTrackHelper.selectTrack(mVideoView.getMediaPlayer(), subtitleTrackList.get(0));
                }
            }
        }
    }

    // ── 字幕设置弹窗（外挂/内置/字号/延迟/样式/开关）──

    /** 外部字幕路径设置（显隐跟随开关）；宿主薄壳 setSubtitle 委托 */
    public void setSubtitlePath(String path) {
        if (path != null && path.length() > 0) {
            mController.mSubtitleView.setSubtitlePath(path);
            mController.mSubtitleView.setVisibility(PlayConfig.isSubtitleOpen() ? View.VISIBLE : View.GONE);
        }
    }

    /** 打开"字幕"设置弹窗（含在线搜索 / 本地选择 / 字号延迟样式 / 内置切换入口） */
    public void openSubtitleDialog(@NonNull VodInfo vodInfo) {
        SubtitleDialog subtitleDialog = new SubtitleDialog(mActivity);
        subtitleDialog.setSubtitleViewListener(new SubtitleDialog.SubtitleViewListener() {
            @Override
            public void setTextSize(int size) {
                mController.mSubtitleView.setTextSize(size);
            }

            @Override
            public void setSubtitleDelay(int milliseconds) {
                mController.mSubtitleView.setSubtitleDelay(milliseconds);
            }

            @Override
            public void selectInternalSubtitle() {
                openInternalSubtitleDialog();
            }

            @Override
            public void setTextStyle(int style) {
                setSubtitleTextStyle(style);
            }

            @Override
            public void subtitleOpen(boolean b) {
                mController.openSubtitle(b);
            }
        });
        subtitleDialog.setSearchSubtitleListener(new SubtitleDialog.SearchSubtitleListener() {
            @Override
            public void openSearchSubtitleDialog() {
                SearchSubtitleDialog searchSubtitleDialog = new SearchSubtitleDialog(mActivity);
                searchSubtitleDialog.setSubtitleLoader(new SearchSubtitleDialog.SubtitleLoader() {
                    @Override
                    public void loadSubtitle(Subtitle subtitle) {
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String zimuUrl = subtitle.getUrl();
                                LOG.i("Remote Subtitle Url: " + zimuUrl);
                                setSubtitlePath(zimuUrl);//设置字幕
                                searchSubtitleDialog.dismiss();
                            }
                        });
                    }
                });
                String searchWord = vodInfo.name;
                if (vodInfo.playFlag != null && (vodInfo.playFlag.contains("Ali") || vodInfo.playFlag.contains("parse"))) {
                    searchWord = vodInfo.playNote;
                }
                searchSubtitleDialog.setSearchWord(TextUtils.isEmpty(searchWord) ? "" : searchWord);
                searchSubtitleDialog.show();
            }
        });
        subtitleDialog.setLocalFileChooserListener(new SubtitleDialog.LocalFileChooserListener() {
            @Override
            public void openLocalFileChooserDialog() {
                new ChooserDialog(mActivity, R.style.FileChooser)
                        .withFilter(false, false, "srt", "ass", "scc", "stl", "ttml")
                        .withStartFile("/storage/emulated/0/Download")
                        .withChosenListener(new ChooserDialog.Result() {
                            @Override
                            public void onChoosePath(String path, File pathFile) {
                                LOG.i("Local Subtitle Path: " + path);
                                setSubtitlePath(path);//设置字幕
                            }
                        })
                        .build()
                        .show();
            }
        });
        subtitleDialog.show();
    }

    /** 字幕文字颜色样式（0=白 / 1=粉） */
    @SuppressLint("UseCompatLoadingForColorStateLists")
    public void setSubtitleTextStyle(int style) {
        if (style == 0) {
            mController.mSubtitleView.setTextColor(mActivity.getResources().getColorStateList(R.color.color_FFFFFF));
        } else if (style == 1) {
            mController.mSubtitleView.setTextColor(mActivity.getResources().getColorStateList(R.color.color_FFB6C1));
        }
    }

    // ── 音轨 / 内置字幕切换（SelectDialog 单选 + 轨道切换 + 进度恢复）──

    /** 切换音轨 */
    public void openAudioTrackDialog() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = PlayerTrackHelper.getTrackInfo(mediaPlayer);
        if (trackInfo == null) {
            AppBubble.toast("没有音轨");
            return;
        }
        final List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("切换音轨");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                try {
                    for (TrackInfoBean audio : bean) {
                        audio.selected = audio.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                    PlayerTrackHelper.selectTrack(mediaPlayer, value);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                        }
                    }, 800);
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换音轨出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                String name = val.name.replace("AUDIO,", "");
                name = name.replace("N/A,", "");
                name = name.replace(" ", "");
                return name + (TextUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getAudioSelected(false));
        dialog.show();
    }

    /** 切换内置字幕 */
    public void openInternalSubtitleDialog() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = PlayerTrackHelper.getTrackInfo(mediaPlayer);
        if (trackInfo == null) {
            AppBubble.toast("没有内置字幕");
            return;
        }
        final List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("切换内置字幕");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                mController.mSubtitleView.setVisibility(View.VISIBLE);
                try {
                    for (TrackInfoBean subtitle : bean) {
                        subtitle.selected = subtitle.trackGroupId == value.trackGroupId && subtitle.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                    mController.mSubtitleView.destroy();
                    mController.mSubtitleView.clearSubtitleCache();
                    mController.mSubtitleView.isInternal = true;

                    // 轨道切换/进度恢复差异收敛到 PlayerTrackHelper,不感知内核
                    PlayerTrackHelper.selectTrack(mediaPlayer, value);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                            if (PlayerTrackHelper.requiresControllerProgressRestart(mediaPlayer)) {
                                mController.startProgress();
                            }
                        }
                    }, 800);
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换内置字幕出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                return val.name + (TextUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getSubtitleSelected(false));
        dialog.show();
    }

    // ── 其它（外部事件驱动的字幕字号变更）──

    /** 全局字幕字号变更事件（设置页调节后广播） */
    public void applySubtitleSize(int size) {
        mController.mSubtitleView.setTextSize(size);
    }
}
