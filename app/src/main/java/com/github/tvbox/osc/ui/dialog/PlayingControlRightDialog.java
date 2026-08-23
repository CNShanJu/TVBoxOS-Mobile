package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogPlayingControlBinding;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.PlaybackSettingsController;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.PlayerHelper;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 播放设置右侧抽屉:在线全屏播放与本地播放共用(PlaybackSettingsController),
 * 功能保持一致;下载入口仅在线播放显示。
 */
public class PlayingControlRightDialog extends AppDrawerPopupView {

    @NonNull
    private final Activity mActivity;
    private final PlaybackSettingsController mController;
    private final boolean mShowDownload;
    MyVideoView mPlayer;
    private DialogPlayingControlBinding mBinding;

    private static final DiffUtil.ItemCallback<Integer> INT_DIFF = new DiffUtil.ItemCallback<Integer>() {
        @Override
        public boolean areItemsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
            return oldItem.intValue() == newItem.intValue();
        }

        @Override
        public boolean areContentsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
            return oldItem.intValue() == newItem.intValue();
        }
    };

    public PlayingControlRightDialog(@NonNull @NotNull Context context, PlaybackSettingsController controller,
                                     MyVideoView videoView, boolean showDownload) {
        super(context);
        mActivity = context instanceof Activity ? (Activity) context : null;
        mController = controller;
        mPlayer = videoView;
        mShowDownload = showDownload;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_playing_control;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 播放器设置抽屉:跟随主题深浅(基类已设置 bg_drawer -> bg_popup,不再覆盖为固定深色)
        mBinding = DialogPlayingControlBinding.bind(getPopupImplView());

        initView();
        initListener();
    }

    private void initView(){
        mBinding.scale.setText(mController.settingsScaleBtn().getText());
        mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
        mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
        mBinding.player.setText(mController.settingsPlayerBtn().getText());
        mBinding.decode.setText(mController.settingsIjkBtn().getText());
        //全屏的设置弹窗显示
        mBinding.landscapePortrait.setVisibility(View.VISIBLE);
        //下载入口仅在线播放显示(本地播放无下载弹窗)
        mBinding.download.setVisibility(mShowDownload ? View.VISIBLE : View.GONE);
        updateAboutIjkVisible();
        updateSpeedUi();
    }

    private void initListener(){
        //倍速
        mBinding.speed0.setOnClickListener(view -> setSpeed(mBinding.speed0));
        mBinding.speed1.setOnClickListener(view -> setSpeed(mBinding.speed1));
        mBinding.speed1a.setOnClickListener(view -> setSpeed(mBinding.speed1a));
        mBinding.speed2.setOnClickListener(view -> setSpeed(mBinding.speed2));
        mBinding.speed3.setOnClickListener(view -> setSpeed(mBinding.speed3));
        mBinding.speed4.setOnClickListener(view -> setSpeed(mBinding.speed4));
        mBinding.speed5.setOnClickListener(view -> setSpeed(mBinding.speed5));

        //播放器
        mBinding.scale.setOnClickListener(view -> showScaleDialog());
        mBinding.playTimeStart.setOnClickListener(view -> changeAndUpdateText(mBinding.playTimeStart, mController.settingsTimeStartBtn()));
        mBinding.playTimeEnd.setOnClickListener(view -> changeAndUpdateText(mBinding.playTimeEnd, mController.settingsTimeSkipBtn()));
        mBinding.playTimeStart.setOnLongClickListener(view -> {
            mController.settingsTimeStartBtn().performLongClick();
            mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
            return true;
        });
        mBinding.playTimeEnd.setOnLongClickListener(view -> {
            mController.settingsTimeSkipBtn().performLongClick();
            mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
            return true;
        });
        mBinding.increaseStart.setOnClickListener(view -> {
            mController.increaseTime("st");
            updateSkipText(true);
        });
        mBinding.decreaseStart.setOnClickListener(view -> {
            mController.decreaseTime("st");
            updateSkipText(true);
        });
        mBinding.increaseEnd.setOnClickListener(view -> {
            mController.increaseTime("et");
            updateSkipText(false);
        });
        mBinding.decreaseEnd.setOnClickListener(view -> {
            mController.decreaseTime("et");
            updateSkipText(false);
        });
        mBinding.player.setOnClickListener(view -> showPlayerDialog());
        mBinding.decode.setOnClickListener(view -> changeAndUpdateText(mBinding.decode, mController.settingsIjkBtn()));

        //其他
        mBinding.landscapePortrait.setOnClickListener(view -> dismissWith(() -> changeAndUpdateText(null, mController.settingsLandscapeBtn())));
        mBinding.startEndReset.setOnClickListener(view -> resetSkipStartEnd());
        mBinding.replay.setOnClickListener(view -> changeAndUpdateText(null, mController.settingsRetryBtn()));
        mBinding.refresh.setOnClickListener(view -> changeAndUpdateText(null, mController.settingsRefreshBtn()));
        mBinding.subtitle.setOnClickListener(view -> dismissWith(() -> changeAndUpdateText(null, mController.settingsZimuBtn())));
        mBinding.voice.setOnClickListener(view -> dismissWith(() -> changeAndUpdateText(null, mController.settingsAudioBtn())));
        if (mShowDownload) {
            mBinding.download.setOnClickListener(view -> {
                dismiss();
                if (mActivity instanceof com.github.tvbox.osc.ui.activity.DetailActivity) {
                    ((com.github.tvbox.osc.ui.activity.DetailActivity) mActivity).showDownloadDialogInFullscreen();
                }
            });
        }
    }

    private void updateSkipText(boolean start){
        if (start){
            mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
        }else {
            mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
        }
    }

    /**
     * 点击直接调用controller里面声明好的点击事件,(不改动原逻辑,隐藏controller里的设置view,全由弹窗设置)
     * @param view 不为空变更配置文字,如更换播放器/缩放, 为空只操作点击之间,不需改变文字,如刷新/重播
     * @param targetView
     */
    private void changeAndUpdateText(TextView view,TextView targetView){
        targetView.performClick();
        if (view!=null){
            view.setText(targetView.getText());
            if (view == mBinding.player){
                updateAboutIjkVisible();
            }
       }
    }

    private void setSpeed(TextView textView){
        mController.setSpeed(textView.getText().toString().replace("x",""));
        updateSpeedUi();
    }

    /** 缩放:列出所有选项直接选择 */
    private void showScaleDialog() {
        final int cur = mController.getScaleType();
        SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("选择缩放");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
            @Override
            public void click(Integer value, int pos) {
                dialog.cancel();
                if (value != cur) {
                    mController.setScaleType(value);
                }
                mBinding.scale.setText(PlayerHelper.getScaleName(value));
            }

            @Override
            public String getDisplay(Integer val) {
                return PlayerHelper.getScaleName(val);
            }
        }, INT_DIFF, new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4, 5)), cur);
        dialog.show();
    }

    /** 播放器:列出所有可用播放器直接选择 */
    private void showPlayerDialog() {
        final int cur = mController.getPlayerType();
        final ArrayList<Integer> players = PlayerHelper.getExistPlayerTypes();
        SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("选择播放器");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
            @Override
            public void click(Integer value, int pos) {
                dialog.cancel();
                int type = players.get(pos);
                if (type != cur) {
                    mController.setPlayerType(type);
                }
                mBinding.player.setText(PlayerHelper.getPlayerName(type));
            }

            @Override
            public String getDisplay(Integer val) {
                // val 就是播放器类型值(如 0/1/2),直接取名称,不能再当索引
                return PlayerHelper.getPlayerName(val);
            }
        }, INT_DIFF, players, players.indexOf(cur));
        dialog.show();
    }

    private void updateSpeedUi(){
        for (int i = 0; i <mBinding.containerSpeed.getChildCount(); i++) {
            TextView tv= (TextView) mBinding.containerSpeed.getChildAt(i);
            if (String.valueOf(mPlayer.getSpeed()).equals(tv.getText().toString().replace("x",""))){
                // 选中:与选集一致,无填充背景 + 蓝色文字(主题感知,用 getContext() 解析)
                tv.setBackground(getResources().getDrawable(R.drawable.bg_r_common_stroke_primary));
                tv.setTextColor(ContextCompat.getColor(getContext(), R.color.color_highlight));
            }else {
                tv.setBackground(getResources().getDrawable(R.drawable.bg_r_common_stroke_primary));
                tv.setTextColor(ContextCompat.getColor(getContext(), R.color.text_foreground));
            }
        }
    }

    /**
     * 如切换/使用的是ijk,解码和音轨按钮才显示
     */
    public void updateAboutIjkVisible(){
        mBinding.decode.setVisibility(mController.settingsIjkBtn().getVisibility());
    }

    /**
     * 重置片头/尾,刷新文字
     */
    private void resetSkipStartEnd(){
        changeAndUpdateText(null, mController.settingsTimeResetBtn());
        mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
        mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
    }

}
