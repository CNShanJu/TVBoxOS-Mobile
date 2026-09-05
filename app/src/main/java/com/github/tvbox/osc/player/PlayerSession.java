package com.github.tvbox.osc.player;

import com.github.tvbox.osc.player.MyVideoView;

import java.util.Map;

/**
 * 播放会话门面（改进.txt §三 PlayerSession / 播放器全驱动第一步）。
 * <p>
 * 现状:底层仍是共享 {@link MyVideoView}(doikki VideoView),本类只把"驱动指令"
 * (起播/暂停/恢复/释放/进度键)从 PlayFragment 散点收口为单一入口,行为与直接调用完全等价;
 * 后续把 UI 控制切到 player-api 的 PlayerApi 语义(或替换 Media3 adapter)时,
 * 只改本类内部实现,播放页与控制器零改动。
 */
public final class PlayerSession {

    private final MyVideoView video;

    public PlayerSession(MyVideoView video) {
        this.video = video;
    }

    /** 起播:绑定进度键 → 设 URL → start(headers 可为空) */
    public void play(String url, String progressKey, Map<String, String> headers) {
        if (video == null || url == null) return;
        video.setProgressKey(progressKey);
        if (headers != null && !headers.isEmpty()) {
            video.setUrl(url, headers);
        } else {
            video.setUrl(url);
        }
        video.start();
    }

    /** 起播(无进度键/无头) */
    public void play(String url, Map<String, String> headers) {
        if (video == null || url == null) return;
        if (headers != null && !headers.isEmpty()) {
            video.setUrl(url, headers);
        } else {
            video.setUrl(url);
        }
        video.start();
    }

    public void pause() {
        if (video != null) video.pause();
    }

    public void resume() {
        if (video != null) video.resume();
    }

    /** 释放当前内核/会话(切换、离开、调外部播放器前) */
    public void release() {
        if (video != null) video.release();
    }
}
