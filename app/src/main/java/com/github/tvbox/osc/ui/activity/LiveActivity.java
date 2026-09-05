package com.github.tvbox.osc.ui.activity;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.player.controller.LiveNewController;
import com.github.tvbox.osc.util.LiveChannelAuth;
import com.github.tvbox.osc.util.LivePlayerManager;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupNewAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemNewAdapter;
import com.github.tvbox.osc.ui.dialog.AllChannelsRightDialog;
import com.github.tvbox.osc.ui.dialog.CastListDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.LivePasswordDialog;
import com.github.tvbox.osc.ui.dialog.LiveSettingDialog;
import com.github.tvbox.osc.ui.dialog.LiveSettingRightDialog;
import com.github.tvbox.osc.ui.kit.LinearSpacingItemDecoration;
import com.github.tvbox.osc.ui.widget.PlayerMenuView;
import com.github.tvbox.osc.ui.widget.PlayerTitleView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.AppLog;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LiveConfig;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.JsonArray;
import com.gyf.immersionbar.BarHide;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import xyz.doikki.videocontroller.component.LiveControlView;
import xyz.doikki.videocontroller.component.TitleView;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveActivity extends BaseActivity {
    public static Context context;
    private VideoView mVideoView;
    private TextView tvChannelInfo;
    private LinearLayout tvLeftChannelListLayout;
    private RecyclerView mChannelGroupView;
    private RecyclerView mLiveChannelView;
    public LiveChannelGroupNewAdapter liveChannelGroupAdapter;
    public LiveChannelItemNewAdapter liveChannelItemAdapter;

    public static  int currentChannelGroupIndex = 0;
    private Handler mHandler = new Handler();

    private List<LiveChannelGroup> liveChannelGroupList = new ArrayList<>();
    private int currentLiveChannelIndex = -1;
    private int currentLiveChangeSourceTimes = 0;
    private LiveChannelItem currentLiveChannelItem = null;
    private LivePlayerManager livePlayerManager = new LivePlayerManager();
    private ArrayList<Integer> channelGroupPasswordConfirmed = new ArrayList<>();

//EPG   by 龍
    private static LiveChannelItem  channel_Name = null;
    TextView tv_channelnum;
    TextView tip_chname;

    TextView tv_srcinfo;
    public String epgStringAddress ="";

    private boolean isBack = false;
    private PlayerTitleView mPlayerTitleView;
    private BasePopupView mSettingRightDialog;
    private BasePopupView mSettingBottomDialog;
    private BasePopupView mAllChannelRightDialog;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live;
    }

    @Override
    protected void init() {
        ImmersionBar.with(this)
                .statusBarColor(R.color.black)
                .statusBarDarkFont(false)
                .navigationBarColor(R.color.black)
                .fitsSystemWindows(true)
                .hideBar(BarHide.FLAG_HIDE_NAVIGATION_BAR)
                .init();
        context = this;
        epgStringAddress = LiveConfig.epgUrl();
        if(epgStringAddress == null || epgStringAddress.length()<5)
            epgStringAddress = "http://epg.51zmt.top:8000/api/diyp/";

        setLoadSir(findViewById(R.id.live_root));
        mVideoView = findViewById(R.id.mVideoView);

        tvLeftChannelListLayout = findViewById(R.id.tvLeftChannnelListLayout);

        mChannelGroupView = findViewById(R.id.mGroupGridView);
        mLiveChannelView = findViewById(R.id.mChannelGridView);
        mChannelGroupView.addItemDecoration(new LinearSpacingItemDecoration(20,true));
        mLiveChannelView.addItemDecoration(new LinearSpacingItemDecoration(20,true));

        tvChannelInfo = findViewById(R.id.tvChannel);

        //EPG  findViewById  by 龍
        tip_chname = findViewById(R.id.tv_channel_bar_name);//底部名称
        tip_chname.setOnClickListener(view -> {
            mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
            mLiveChannelView.scrollToPosition(currentLiveChannelIndex);
        });
        tv_channelnum = findViewById(R.id.tv_channel_bottom_number); //底部数字

        tv_srcinfo = findViewById(R.id.tv_source);//线路状态

        //源切换
        findViewById(R.id.ic_pre_source).setOnClickListener(view -> playPreSource());
        findViewById(R.id.ic_next_source).setOnClickListener(view -> playNextSource());
        tv_srcinfo.setOnClickListener(view -> playNextSource());
        //投屏/设置
        findViewById(R.id.ic_setting).setOnClickListener(view -> showSettingDialog(false));
        findViewById(R.id.ic_cast).setOnClickListener(view -> showCastDialog());

        initVideoView();
        initChannelGroupView();
        initLiveChannelView();
        initLiveChannelList();
    }

    //显示底部EPG
    private void showBottomEpg() {
        if (channel_Name.getChannelName() != null) {
            mPlayerTitleView.setTitle(channel_Name.getChannelName());
            tip_chname.setText(channel_Name.getChannelName());
            tv_channelnum.setText("" + channel_Name.getChannelNum());
            //todo 上一/下一/当前节目信息

            if (channel_Name == null || channel_Name.getSourceNum() <= 0) {
                tv_srcinfo.setText("1/1");
            } else {
                tv_srcinfo.setText("线路" + (channel_Name.getSourceIndex() + 1) + "/" + channel_Name.getSourceNum());
            }
        }
    }


    @Override
    public void onBackPressed() {
        if(isBack){
            isBack= false;
            playPreSource();
        } else if(mSettingBottomDialog!=null && mSettingBottomDialog.isShow()){//适配底部导航栏(手势条闪屏)变成view模式后在back时手动隐藏
            mSettingBottomDialog.dismiss();
        } else if(mSettingRightDialog!=null && mSettingRightDialog.isShow()){
            mSettingRightDialog.dismiss();
        }  else if(mAllChannelRightDialog!=null && mAllChannelRightDialog.isShow()){
            mAllChannelRightDialog.dismiss();
        } else if (!mVideoView.onBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                // 菜单键预留:直播设置已走底部/抽屉弹窗(showSettingDialog)
            } else if (!isListOrSettingLayoutVisible()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (LiveConfig.channelReverse())
                            playNext();
                        else
                            playPrevious();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (LiveConfig.channelReverse())
                            playPrevious();
                        else
                            playNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if(isBack){

                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if(isBack){

                        }else{
                            playNextSource();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        showChannelList();
                        break;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
    }

    private void showChannelList() {
        //重新载入上一次状态
        liveChannelItemAdapter.setNewData(getLiveChannels(currentChannelGroupIndex));
        if (currentLiveChannelIndex > -1){
            mLiveChannelView.smoothScrollToPosition(currentLiveChannelIndex);
            if (currentChannelGroupIndex==0){
                mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
            }else {
                mChannelGroupView.smoothScrollToPosition(currentChannelGroupIndex);
            }
        }
    }

    private void showChannelInfo() {
        tvChannelInfo.setText(String.format(Locale.getDefault(), "%d %s %s(%d/%d)", currentLiveChannelItem.getChannelNum(),
                currentLiveChannelItem.getChannelName(), currentLiveChannelItem.getSourceName(),
                currentLiveChannelItem.getSourceIndex() + 1, currentLiveChannelItem.getSourceNum()));

        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lParams.gravity = Gravity.RIGHT;
        lParams.rightMargin = 60;
        lParams.topMargin = 30;
        tvChannelInfo.setLayoutParams(lParams);

        tvChannelInfo.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideChannelInfoRun);
        mHandler.postDelayed(mHideChannelInfoRun, 3000);
    }

    private Runnable mHideChannelInfoRun = new Runnable() {
        @Override
        public void run() {
            tvChannelInfo.setVisibility(View.INVISIBLE);
        }
    };

    private boolean playChannel(int channelGroupIndex, int liveChannelIndex, boolean changeSource) {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
                || (changeSource && currentLiveChannelItem.getSourceNum() == 1)) {
           // showChannelInfo();
            return true;
        }
        mVideoView.release();
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex;
            currentLiveChannelIndex = liveChannelIndex;
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex).get(currentLiveChannelIndex);
            LiveConfig.setLastChannel(currentLiveChannelItem.getChannelName());
            livePlayerManager.getLiveChannelPlayer(mVideoView, currentLiveChannelItem.getChannelName());
        }

        channel_Name = currentLiveChannelItem;
        isBack = false;
        if(currentLiveChannelItem.getUrl().indexOf("PLTV/8888") !=-1){
            currentLiveChannelItem.setinclude_back(true);
        }else {
            currentLiveChannelItem.setinclude_back(false);
        }
        showBottomEpg();

        AppLog.log("直播", "播放频道[" + currentLiveChannelItem.getChannelName() + "] 源[" + (currentLiveChannelItem.getSourceIndex() + 1) + "/" + currentLiveChannelItem.getSourceNum() + "] " + currentLiveChannelItem.getUrl());
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.OTHER,
                "直播: 播放频道[" + currentLiveChannelItem.getChannelName() + "] 源["
                        + (currentLiveChannelItem.getSourceIndex() + 1) + "/"
                        + currentLiveChannelItem.getSourceNum() + "]");

        mVideoView.setUrl(currentLiveChannelItem.getUrl());
       // showChannelInfo();
        mVideoView.start();
        return true;
    }

    private void playNext() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    private void playPrevious() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(-1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    public void playPreSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.preSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    public void playNextSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.nextSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

//    private void initVideoView() {
//        StandardVideoController controller = new StandardVideoController(this);
//        controller.addControlComponent(new LiveControlView(this)); //直播控制条
//        controller.setEnableInNormal(true);
//        controller.setGestureEnabled(true);
//        mVideoView.setVideoController(controller);
//        mVideoView.setProgressManager(null);
//    }

    private void initVideoView() {
        LiveNewController controller = new LiveNewController(this);
        PlayerMenuView playerMenuView = getPlayerMenuView();
        controller.addControlComponent(playerMenuView); //菜单栏,设置投屏等
        controller.addControlComponent(new LiveControlView(this)); //直播控制条
        //标题栏
        mPlayerTitleView = new PlayerTitleView(this);
        controller.addControlComponent(mPlayerTitleView);
        controller.setListener(new LiveNewController.LiveControlListener() {

            @Override
            public void setting() {
                // 直播设置已走 showSettingDialog(底部/抽屉弹窗),这里不再内嵌面板
            }

            @Override
            public void playStateChanged(int playState) {
                switch (playState) {
                    case VideoView.STATE_IDLE:
                    case VideoView.STATE_PAUSED:
                        break;
                    case VideoView.STATE_PREPARED:
                    case VideoView.STATE_BUFFERED:
                    case VideoView.STATE_PLAYING:
                        currentLiveChangeSourceTimes = 0;
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        break;
                    case VideoView.STATE_ERROR:
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, 2000);
                        break;
                    case VideoView.STATE_PREPARING:
                    case VideoView.STATE_BUFFERING:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, (LiveConfig.connectTimeout() + 1) * 5000);
                        break;
                }
            }

            @Override
            public void changeSource(int direction) {
                if (direction > 0){
                    playNextSource();
                } else {
                    playPreSource();
                }
            }
        });
        controller.setCanChangePosition(false);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setDoubleTapTogglePlayEnabled(false);
        mVideoView.setVideoController(controller);
        mVideoView.setProgressManager(null);
    }

    @NonNull
    private PlayerMenuView getPlayerMenuView() {
        PlayerMenuView playerMenuView = new PlayerMenuView(this);
        playerMenuView.setOnPlayerMenuClickListener(new PlayerMenuView.OnPlayerMenuClickListener() {
            @Override
            public void expand() {
                showAllChannelDialog();
            }

            @Override
            public void onSetting() {
                showSettingDialog(true);
            }

            @Override
            public void onCast() {
                showCastDialog();
            }
        });
        return playerMenuView;
    }

    private Runnable mConnectTimeoutChangeSourceRun = new Runnable() {
        @Override
        public void run() {
            currentLiveChangeSourceTimes++;
            if (currentLiveChannelItem.getSourceNum() == currentLiveChangeSourceTimes) {
                // 当前频道所有线路都失败:停止播放,不自动切下一个频道
                currentLiveChangeSourceTimes = 0;
                try {
                    mVideoView.release();
                } catch (Throwable ignored) {
                }
                AppBubble.toastLong("当前频道无可用线路,请手动切换频道");
            } else {
                playNextSource();
            }
        }
    };

    private void initChannelGroupView() {
        mChannelGroupView.setHasFixedSize(true);
        mChannelGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelGroupAdapter = new LiveChannelGroupNewAdapter();
        mChannelGroupView.setAdapter(liveChannelGroupAdapter);

        //手机/模拟器
        liveChannelGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectChannelGroup(position, false, -1);
            }
        });
    }

    private void selectChannelGroup(int groupIndex, boolean focus, int liveChannelIndex) {
        if (focus) {
            liveChannelGroupAdapter.setFocusedGroupIndex(groupIndex);
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
        }
        if ((groupIndex > -1 && groupIndex != liveChannelGroupAdapter.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex);
                return;
            }
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
        }
    }

    private void initLiveChannelView() {
        mLiveChannelView.setHasFixedSize(true);
        mLiveChannelView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelItemAdapter = new LiveChannelItemNewAdapter();
        mLiveChannelView.setAdapter(liveChannelItemAdapter);

        liveChannelItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickLiveChannel(position);
            }
        });
    }

    private void clickLiveChannel(int position) {
        liveChannelItemAdapter.setSelectedChannelIndex(position);
        playChannel(liveChannelGroupAdapter.getSelectedGroupIndex(), position, false);
    }


    private void initLiveChannelList() {
        List<LiveChannelGroup> list = com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get().getChannelGroupList();
        if (list.isEmpty()) {
            AppBubble.toast("频道列表为空");
            finish();
            return;
        }

        if (list.size() == 1 && list.get(0).getGroupName().startsWith("http://127.0.0.1")) {
            loadProxyLives(list.get(0).getGroupName());
        }
        else {
            liveChannelGroupList.clear();
            liveChannelGroupList.addAll(list);
            showSuccess();
            initLiveState();
        }
    }

    public void loadProxyLives(String url) {
        try {
            Uri parsedUrl = Uri.parse(url);
            url = new String(Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
        } catch (Throwable th) {
            AppBubble.toast("频道列表为空");
            finish();
            return;
        }
        showLoading();
        AppLog.log("直播", "加载直播源: " + url);
        HttpClient.get(url, null, new HCallBack() {

            @Override
            public void onSuccess(String content) {
                try {
                    JsonArray livesArray;
                    LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap = new LinkedHashMap<>();
                    TxtSubscribe.parse(linkedHashMap, content);
                    livesArray = TxtSubscribe.live2JsonArray(linkedHashMap);

                    com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get().loadLives(livesArray);
                    List<LiveChannelGroup> list = com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get().getChannelGroupList();
                    if (list.isEmpty()) {
                        AppBubble.toast("频道列表为空");
                        finish();
                        return;
                    }
                    liveChannelGroupList.clear();
                    liveChannelGroupList.addAll(list);

                    com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.OTHER,
                            "直播: 直播源加载成功, 分组 " + list.size() + " 个");

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            LiveActivity.this.showSuccess();
                            initLiveState();
                        }
                    });
                } catch (Throwable th) {
                    th.printStackTrace();
                    AppLog.log("直播", "解析失败: " + th.getMessage());
                    com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.OTHER,
                            "直播: 直播源解析失败: " + th.getMessage());
                    onLiveLoadFail("直播源解析失败,请检查订阅中的直播源");
                }
            }

            @Override
            public void onError(Throwable e) {
                AppLog.log("直播", "加载失败: " + (e == null ? "null" : e.getMessage()));
                com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.OTHER,
                        "直播: 直播源加载失败: " + (e == null ? "null" : e.getMessage()));
                onLiveLoadFail("直播源加载失败,请检查网络或直播源");
            }
        });
    }

    /** 直播源加载失败:提示并进入空态,避免一直停留在加载中 */
    private void onLiveLoadFail(String msg) {
        try {
            AppBubble.toast(msg);
            if (!isFinishing() && !isDestroyed()) {
                showEmpty();
            }
        } catch (Throwable ignored) {
        }
    }

    private void initLiveState() {
        String lastChannelName = LiveConfig.lastChannel();

        int lastChannelGroupIndex = -1;
        int lastLiveChannelIndex = -1;
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            for (LiveChannelItem liveChannelItem : liveChannelGroup.getLiveChannels()) {
                if (liveChannelItem.getChannelName().equals(lastChannelName)) {
                    lastChannelGroupIndex = liveChannelGroup.getGroupIndex();
                    lastLiveChannelIndex = liveChannelItem.getChannelIndex();
                    break;
                }
            }
            if (lastChannelGroupIndex != -1) break;
        }
        if (lastChannelGroupIndex == -1) {
            lastChannelGroupIndex = getFirstNoPasswordChannelGroup();
            if (lastChannelGroupIndex == -1)
                lastChannelGroupIndex = 0;
            lastLiveChannelIndex = 0;
        }

        if (mVideoView == null || isFinishing() || isDestroyed()) return; // 防御:页面已销毁/播放器未初始化

        livePlayerManager.init(mVideoView);

        liveChannelGroupAdapter.setNewData(liveChannelGroupList);
        selectChannelGroup(lastChannelGroupIndex, false, lastLiveChannelIndex);
    }

    private boolean isListOrSettingLayoutVisible() {
        return tvLeftChannelListLayout.getVisibility() == View.VISIBLE;
    }

    private void showPasswordDialog(int groupIndex, int liveChannelIndex) {

        LivePasswordDialog dialog = new LivePasswordDialog(this);
        dialog.setOnListener(new LivePasswordDialog.OnListener() {
            @Override
            public void onChange(String password) {
                if (password.equals(liveChannelGroupList.get(groupIndex).getGroupPassword())) {
                    channelGroupPasswordConfirmed.add(groupIndex);
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
                } else {
                    AppBubble.toast("密码错误");
                }
            }

            @Override
            public void onCancel() {
                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                    int groupIndex = liveChannelGroupAdapter.getSelectedGroupIndex();
                    liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
                }
            }
        });
        dialog.show();
    }

    private void loadChannelGroupDataAndPlay(int groupIndex, int liveChannelIndex) {
        liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
        if (groupIndex == currentChannelGroupIndex) {
            if (currentLiveChannelIndex > -1)
                mLiveChannelView.smoothScrollToPosition(currentLiveChannelIndex);
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        }
        else {
            mLiveChannelView.smoothScrollToPosition(0);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
        }

        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex);
            if (groupIndex==0){//部分手机smoothScrollToPosition向上划出屏幕
                mChannelGroupView.scrollToPosition(groupIndex);
            }else {
                mChannelGroupView.smoothScrollToPosition(groupIndex);
            }

            mLiveChannelView.smoothScrollToPosition(liveChannelIndex);
            playChannel(groupIndex, liveChannelIndex, false);
        }
    }

    private boolean isNeedInputPassword(int groupIndex) {
        return LiveChannelAuth.needInputPassword(liveChannelGroupList, channelGroupPasswordConfirmed, groupIndex);
    }

    private boolean isPasswordConfirmed(int groupIndex) {
        return LiveChannelAuth.isPasswordConfirmed(channelGroupPasswordConfirmed, groupIndex);
    }

    private ArrayList<LiveChannelItem> getLiveChannels(int groupIndex) {
        if (LiveChannelAuth.needInputPassword(liveChannelGroupList, channelGroupPasswordConfirmed, groupIndex)) {
            return new ArrayList<>();
        }
        return liveChannelGroupList.get(groupIndex).getLiveChannels();
    }

    private Integer[] getNextChannel(int direction) {
        int channelGroupIndex = currentChannelGroupIndex;
        int liveChannelIndex = currentLiveChannelIndex;

        //跨选分组模式下跳过加密频道分组（遥控器上下键换台/超时换源）
        if (direction > 0) {
            liveChannelIndex++;
            if (liveChannelIndex >= getLiveChannels(channelGroupIndex).size()) {
                liveChannelIndex = 0;
                if (LiveConfig.crossGroup()) {
                    do {
                        channelGroupIndex++;
                        if (channelGroupIndex >= liveChannelGroupList.size())
                            channelGroupIndex = 0;
                    } while (!liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty() || channelGroupIndex == currentChannelGroupIndex);
                }
            }
        } else {
            liveChannelIndex--;
            if (liveChannelIndex < 0) {
                if (LiveConfig.crossGroup()) {
                    do {
                        channelGroupIndex--;
                        if (channelGroupIndex < 0)
                            channelGroupIndex = liveChannelGroupList.size() - 1;
                    } while (!liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty() || channelGroupIndex == currentChannelGroupIndex);
                }
                liveChannelIndex = getLiveChannels(channelGroupIndex).size() - 1;
            }
        }

        Integer[] groupChannelIndex = new Integer[2];
        groupChannelIndex[0] = channelGroupIndex;
        groupChannelIndex[1] = liveChannelIndex;

        return groupChannelIndex;
    }

    private int getFirstNoPasswordChannelGroup() {
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            if (liveChannelGroup.getGroupPassword().isEmpty())
                return liveChannelGroup.getGroupIndex();
        }
        return -1;
    }

    private boolean isCurrentLiveChannelValid() {
        if (currentLiveChannelItem == null) {
            AppBubble.toast("请先选择频道");
            return false;
        }
        return true;
    }

    public void showAllChannelDialog() {
        mAllChannelRightDialog = DialogCoordinator.right(this,
                new AllChannelsRightDialog(this), 0, true, false, null);
        mAllChannelRightDialog.show();
    }

    public void showCastDialog() {
        if (currentLiveChannelItem!=null){
            DialogCoordinator.centerMaxWidth(this, new CastListDialog(this,new CastVideo(currentLiveChannelItem.getChannelName(),currentLiveChannelItem.getUrl())), 360)
                    .show();
        }
    }

    public LivePlayerManager getLivePlayerManager(){
        return livePlayerManager;
    }

    public LiveChannelItem getCurrentLiveChannelItem(){
        return currentLiveChannelItem;
    }

    /**
     * 切换某个线路播放
     * @param position
     */
    public void switchingLine2Replay(int position){
        currentLiveChannelItem.setSourceIndex(position);
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex,true);
    }

    /**
     * 切换缩放比例
     * @param position
     */
    public void changeScale(int position){
        livePlayerManager.changeLivePlayerScale(mVideoView, position, currentLiveChannelItem.getChannelName());
    }

    /**
     * 更换播放解码
     * @param position
     */
    public void changePlayer(int position){
        mVideoView.release();
        livePlayerManager.changeLivePlayerType(mVideoView, position, currentLiveChannelItem.getChannelName());
        mVideoView.setUrl(currentLiveChannelItem.getUrl());
        mVideoView.start();
    }

    /**
     * 设置弹窗
     * @param fullScreenStyle 全屏显示侧边弹窗
     */
    private void showSettingDialog(boolean fullScreenStyle) {
        if (!isCurrentLiveChannelValid()){
            AppBubble.toast("当前频道未加载");
            return;
        }
        if (fullScreenStyle){
            mSettingRightDialog = DialogCoordinator.right(this,
                    new LiveSettingRightDialog(this), 300, true);
            mSettingRightDialog.show();
        }else {
            mSettingBottomDialog = DialogCoordinator.bottom(this,
                    new LiveSettingDialog(this), ScreenUtils.getScreenHeight() / 2);
            mSettingBottomDialog.show();
        }

    }

}
