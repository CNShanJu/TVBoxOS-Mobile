package com.github.tvbox.osc.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.player.api.PlayListener;
import com.github.tvbox.osc.player.api.PlayOptions;
import com.github.tvbox.osc.player.api.PlayState;
import com.github.tvbox.osc.player.api.PlayerApi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 内核适配器（⑥ 适配层）：包装 DKVideoPlayer 的 {@link AbstractPlayer} 实例，
 * 以引擎无关的 {@link PlayerApi} 契约暴露控制/查询/进度上报。
 * <p>
 * 纯新增（PlayFragment 薄层化时使用，现有播放逻辑不受影响）；
 * Media3 升级 = 新增 Media3Adapter 同样实现 PlayerApi，调用方零改动。
 */
public final class AbstractPlayerAdapter implements PlayerApi {

    private final AbstractPlayer player;
    private final List<PlayListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile PlayState state = PlayState.IDLE;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (state == PlayState.PLAYING || state == PlayState.PAUSED) {
                try {
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    int buf = player.getBufferedPercentage();
                    for (PlayListener l : listeners) {
                        try {
                            l.onProgress(pos, dur, buf);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    public AbstractPlayerAdapter(AbstractPlayer player) {
        this.player = player;
    }

    @Override
    public void init(Context context, PlayOptions options, PlayListener listener) {
        subscribe(listener);
        setState(PlayState.INITIALIZING);
        handler.removeCallbacks(progressRunnable);
        handler.postDelayed(progressRunnable, 500);
    }

    @Override
    public void play() {
        try {
            player.start();
            setState(PlayState.PLAYING);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void pause() {
        try {
            player.pause();
            setState(PlayState.PAUSED);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void seekTo(long positionMs) {
        try {
            player.seekTo(positionMs);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setSpeed(float speed) {
        try {
            player.setSpeed(speed);
        } catch (Throwable ignored) {
        }
    }

    /** 画面缩放由渲染层(外层 VideoView/Controller)处理，适配器不接管 */
    @Override
    public void setScale(int scaleType) {
    }

    @Override
    public PlayState getState() {
        return state;
    }

    @Override
    public long getPosition() {
        try {
            return player.getCurrentPosition();
        } catch (Throwable th) {
            return 0;
        }
    }

    @Override
    public long getDuration() {
        try {
            return player.getDuration();
        } catch (Throwable th) {
            return 0;
        }
    }

    @Override
    public int[] getVideoSize() {
        return new int[]{0, 0}; // 尺寸由渲染层提供，此处未知
    }

    @Override
    public void subscribe(PlayListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    @Override
    public void unsubscribe(PlayListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void release() {
        handler.removeCallbacks(progressRunnable);
        try {
            player.release();
        } catch (Throwable ignored) {
        }
        setState(PlayState.RELEASED);
    }

    /** 小窗/后台由外层 UI 容器承载，适配器不接管 */
    @Override
    public void enterWindow() {
    }

    @Override
    public void backgroundPlay(boolean on) {
    }

    private void setState(PlayState s) {
        state = s;
        for (PlayListener l : listeners) {
            try {
                l.onStateChanged(s);
            } catch (Throwable ignored) {
            }
        }
    }
}
