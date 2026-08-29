package com.github.tvbox.osc.player;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.text.Cue;

import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 播放器内核能力收敛层（⑥ 适配层第一步）：
 * 调用方（PlayFragment 等）不再直接 `instanceof` 强转内核类型，
 * track/字幕相关操作统一走本类——后续内核适配器化/Media3 升级时只改这里。
 */
public final class PlayerTrackHelper {

    private PlayerTrackHelper() {
    }

    /** 内置字幕文本回调（内核差异在此收敛：IJK TimedText / Exo Cue） */
    public interface SubtitleListener {
        /** text 为 null 表示字幕清除（Exo 空 cues 场景） */
        void onSubtitle(@Nullable String text);
    }

    /** 获取音轨/字幕信息（内核无关）；不支持的内核返回 null */
    public static TrackInfo getTrackInfo(AbstractPlayer mediaPlayer) {
        if (mediaPlayer instanceof IjkMediaPlayer) {
            return ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof EXOmPlayer) {
            return ((EXOmPlayer) mediaPlayer).getTrackInfo();
        }
        return null;
    }

    /** 切换轨道：IJK 按 trackId / Exo 按 TrackInfoBean；不支持的内核忽略 */
    public static void selectTrack(AbstractPlayer mediaPlayer, TrackInfoBean bean) {
        if (mediaPlayer == null || bean == null) return;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            ((IjkMediaPlayer) mediaPlayer).setTrack(bean.trackId);
        } else if (mediaPlayer instanceof EXOmPlayer) {
            ((EXOmPlayer) mediaPlayer).selectExoTrack(bean);
        }
    }

    /** 切换后是否需调用方恢复进度 UI（仅 Exo 需要 startProgress，IJK 由内核自行恢复） */
    public static boolean requiresControllerProgressRestart(AbstractPlayer mediaPlayer) {
        return mediaPlayer instanceof EXOmPlayer;
    }

    /** 注册内置字幕回调（IJK/Exo 差异在此收敛，回调统一为文本）；不支持的内核忽略 */
    public static void setOnSubtitleListener(@NonNull AbstractPlayer mediaPlayer, @NonNull SubtitleListener listener) {
        if (mediaPlayer instanceof IjkMediaPlayer) {
            ((IjkMediaPlayer) mediaPlayer).setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
                @Override
                public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
                    try {
                        listener.onSubtitle(text == null ? null : text.getText());
                    } catch (Throwable th) {
                        Log.w("PlayerTrackHelper", "IJK timed text 回调异常: " + th.getMessage());
                    }
                }
            });
        } else if (mediaPlayer instanceof EXOmPlayer) {
            ((EXOmPlayer) mediaPlayer).setOnTimedTextListener(new Player.Listener() {
                @Override
                public void onCues(@NonNull List<Cue> cues) {
                    try {
                        if (cues.size() > 0 && cues.get(0).text != null) {
                            listener.onSubtitle(cues.get(0).text.toString());
                        } else {
                            listener.onSubtitle(null);
                        }
                    } catch (Throwable th) {
                        Log.w("PlayerTrackHelper", "Exo cue 回调异常: " + th.getMessage());
                    }
                }
            });
        }
    }
}
