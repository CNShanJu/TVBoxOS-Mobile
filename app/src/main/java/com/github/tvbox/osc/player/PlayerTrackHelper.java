package com.github.tvbox.osc.player;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 播放器内核能力收敛层（⑥ 适配层第一步）：
 * 调用方（PlayFragment 等）不再直接 `instanceof` 强转内核类型，
 * track 相关操作统一走本类——后续内核适配器化/Media3 升级时只改这里。
 */
public final class PlayerTrackHelper {

    private PlayerTrackHelper() {
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
}
