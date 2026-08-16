package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.NotificationUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.ServiceUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.constant.IntentKey;
import com.github.tvbox.osc.databinding.ActivityDetailBinding;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.receiver.BatteryReceiver;
import com.github.tvbox.osc.service.PlayService;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesBottomDialog;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesRightDialog;
import com.github.tvbox.osc.ui.dialog.CastListDialog;
import com.github.tvbox.osc.ui.dialog.DownloadSeriesDialog;
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.ui.dialog.VideoDetailDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.ui.widget.LinearSpacingItemDecoration;
import com.github.tvbox.osc.util.BroadcastUtils;
import com.github.tvbox.osc.util.DownloadManager;
import com.github.tvbox.osc.ui.activity.DownloadActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.PipHelper;
import com.github.tvbox.osc.util.PlayUrlResolver;
import com.github.tvbox.osc.util.ScreenShotListenManager;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lxj.xpopup.interfaces.OnSelectListener;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseVbActivity<ActivityDetailBinding> {
    private PlayFragment playFragment = null;
    private SourceViewModel sourceViewModel;
    private Movie.Video mVideo;
    private VodInfo vodInfo;
    public SeriesFlagAdapter seriesFlagAdapter;
    public SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    /** 入口(搜索/列表)传入的剧名,详情拉不到名称时用于下载命名 */
    private String mPassedName = "";
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag = "";
    private HashMap<String, String> mCheckSources = null;
    BatteryReceiver mBatteryReceiver = new BatteryReceiver();
    //改为view模式无法自动响应返回键操作,onBackPress时手动dismiss
    private BasePopupView mAllSeriesRightDialog;
    private BasePopupView mAllSeriesBottomDialog;
    /**
     * Home键广播,用于触发后台服务
     */
    private BroadcastReceiver mHomeKeyReceiver;
    /**
     * 是否开启后台播放标记,不在广播开启,onPause根据标记开启
     */
    boolean openBackgroundPlay;

    /**
     * 截屏监听
     */
    ScreenShotListenManager screenShotListenManager;

    @Override
    protected void init() {
        initReceiver();
        initView();
        initViewModel();
        initData();
        initPipHelper();
        BroadcastUtils.registerReceiverNotExported(this, mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        ImmersionBar.with(this)
                .statusBarColor(R.color.black)
                .navigationBarColor(R.color.white)
                .fitsSystemWindows(true)
                .statusBarDarkFont(false)
                .init();
        toggleScreenShotListen(true);
    }

    /**
     * 初始化画中画(小窗)辅助器,传入详情页专属钩子;本地播放器可复用同一套逻辑。
     */
    private void initPipHelper() {
        pipHelper = new PipHelper(this, new PipHelper.Callback() {
            @Override
            public boolean isPlaying() {
                return playFragment != null && playFragment.getPlayer() != null
                        && playFragment.getPlayer().isPlaying();
            }

            @Override
            public void togglePlay() {
                if (playFragment != null && playFragment.getController() != null) {
                    playFragment.getController().togglePlay();
                }
            }

            @Override
            public void pause() {
                if (playFragment != null && playFragment.getPlayer() != null) {
                    playFragment.getPlayer().pause();
                }
            }

            @Override
            public void playPrevious() {
                if (playFragment != null) {
                    playFragment.playPrevious();
                }
            }

            @Override
            public void playNext() {
                if (playFragment != null) {
                    playFragment.playNext(false);
                }
            }

            @Override
            public boolean isFullscreen() {
                return fullWindows;
            }

            @Override
            public void enterFullscreen() {
                if (!fullWindows) {
                    toggleFullPreview();
                }
            }

            @Override
            public void exitFullscreen() {
                if (fullWindows) {
                    toggleFullPreview();
                }
            }

            @Override
            public int[] getVideoSize() {
                if (playFragment != null && playFragment.getPlayer() != null) {
                    return playFragment.getPlayer().getVideoSize();
                }
                return null;
            }

            @Override
            public void onClose() {
                playServerSwitch(false);
                finish();
                NotificationUtils.cancelAll();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 兜底暂停:Activity 真正不可见且不在小窗中时暂停播放,防止"关闭小窗/退出页面后后台一直出声"。
        // 例外:后台播放=开启(类型1,onUserLeaveHint 已置 openBackgroundPlay=true)时不暂停,
        // 由 PlayService 继续后台播放;进入小窗时 isInPictureInPictureMode() 为 true 也不会误暂停
        if (!isInPictureInPictureMode() && !openBackgroundPlay
                && playFragment != null && playFragment.getPlayer() != null) {
            if (playFragment.getPlayer().isPlaying()) {
                playFragment.getController().togglePlay();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 小窗放大回前台:onStart 是比 onResume 更早的"回前台"信号,用于区分放大/点X关闭。
        // 放大时 Activity 会 onStart(清掉小窗会话标记,保持全屏播放);
        // 点X关闭时 Activity 留在后台不会 onStart(标记保持,走点X处理)
        pipHelper.onActivityStarted();
        // 点X关闭带回前台:播放"小窗逐渐放大铺满全屏"的进入动画,替代生硬的系统切换动画
        if (pipHelper.consumePipCloseAnimation()) {
            playPipExpandAnimation();
        }
    }

    /**
     * "小窗放大铺满全屏"进入动画:内容从屏幕下方(小窗常见位置)由小到大、由淡到实铺满。
     */
    private void playPipExpandAnimation() {
        View root = mBinding.getRoot();
        if (root == null) return;
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        root.setPivotX(w / 2f);
        root.setPivotY(h * 0.88f); // 小窗在屏幕下方,从底部放大铺满
        root.setScaleX(0.35f);
        root.setScaleY(0.35f);
        root.setAlpha(0.3f);
        root.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(420)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pipHelper.onActivityResumed(); // 点X关闭后带回前台:立即补暂停,消除"先播放一下再暂停"
        openBackgroundPlay = false;
        playServerSwitch(false);
        pipExitByBack = false; // 回到前台,清除返回键退出标记
        mBinding.ivPrivateBrowsing.postDelayed(NotificationUtils::cancelAll, 800);
    }

    private void initView() {
        mBinding.ivPrivateBrowsing.setVisibility(Hawk.get(HawkConfig.PRIVATE_BROWSING, false) ? View.VISIBLE : View.GONE);
        mBinding.ivPrivateBrowsing.setOnClickListener(view -> AppBubble.toast("当前为无痕浏览"));
        mBinding.previewPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);

        mBinding.mGridView.setHasFixedSize(true);
        mBinding.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        mBinding.mGridView.addItemDecoration(new LinearSpacingItemDecoration(20, false));

        seriesAdapter = new SeriesAdapter(false);
        mBinding.mGridView.setAdapter(seriesAdapter);
        mBinding.mGridViewFlag.setHasFixedSize(true);
        seriesFlagAdapter = new SeriesFlagAdapter();
        mBinding.mGridViewFlag.setAdapter(seriesFlagAdapter);
        isReverse = false;
        preFlag = "";
        if (showPreview) {
            playFragment = new PlayFragment();
            getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commit();
            getSupportFragmentManager().beginTransaction().show(playFragment).commitAllowingStateLoss();
        }

        findViewById(R.id.ll_title).setOnClickListener(view -> {
            new XPopup.Builder(this)
                    .isViewMode(true)
                    .hasNavigationBar(false)
                    .asCustom(new VideoDetailDialog(this, vodInfo))
                    .show();
        });
        findViewById(R.id.tvDownload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDownloadSeriesDialog();
            }
        });
        mBinding.tvSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                sortSeries();
            }
        });
        mBinding.tvCast.setOnClickListener(v -> {
            showCastDialog();
        });
        mBinding.tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = mBinding.tvCollect.getText().toString();
                if ("加入收藏".equals(text)) {
                    RoomDataManger.insertVodCollect(sourceKey, vodInfo);
                    AppBubble.toast("已加入收藏夹");
                    mBinding.tvCollect.setText("取消收藏");
                } else {
                    RoomDataManger.deleteVodCollect(sourceKey, vodInfo);
                    AppBubble.toast("已移除收藏夹");
                    mBinding.tvCollect.setText("加入收藏");
                }
            }
        });

        seriesFlagAdapter.setOnItemClickListener((adapter, view, position) -> {
            chooseFlag(position);
        });

        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                chooseSeries(position, false);
            }
        });

        mBinding.tvAllSeries.setOnClickListener(view -> {
            showAllSeriesDialog();
        });

        mBinding.tvSite.setOnClickListener(view -> {
            startQuickSearch();
            QuickSearchDialog quickSearchDialog = new QuickSearchDialog(DetailActivity.this);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData));
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
            quickSearchDialog.show();
            if (pauseRunnable != null && pauseRunnable.size() > 0) {
                searchExecutorService = Executors.newFixedThreadPool(5);
                for (Runnable runnable : pauseRunnable) {
                    searchExecutorService.execute(runnable);
                }
                pauseRunnable.clear();
                pauseRunnable = null;
            }
            quickSearchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    try {
                        if (searchExecutorService != null) {
                            pauseRunnable = searchExecutorService.shutdownNow();
                            searchExecutorService = null;
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        });
        mBinding.tvChangeLine.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            quickLineChange();
        });
        setLoadSir(mBinding.llLayout);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (openBackgroundPlay) {
            playServerSwitch(true);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 用户切后台(按Home/切走/进最近任务)时的行为,由"后台播放"设置决定:
        //   0 关闭  :不处理,由 onPause/onStop 兜底暂停,进入后台休眠
        //   1 开启  :后台继续播放(前台服务+通知)
        //   2 画中画:播放窗口自动进入小窗模式
        // 按返回键退出播放页(Activity 正在销毁)不算"切后台",排除
        if (pipExitByBack || isFinishing()) {
            pipExitByBack = false;
            return;
        }
        if (playFragment == null || playFragment.getPlayer() == null || !playFragment.getPlayer().isPlaying()) {
            return;
        }
        int type = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0);
        if (type == 2) {
            pipHelper.enterPip(); // 自动进入小窗
        } else if (type == 1) {
            openBackgroundPlay = true; // onPause 里启动后台播放服务
        }
    }

    private void initReceiver() {
        // 注册广播接收器
        if (mHomeKeyReceiver == null) {
            mHomeKeyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action != null && action.equals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                        openBackgroundPlay = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 1 && playFragment.getPlayer() != null && playFragment.getPlayer().isPlaying();
                    }
                }
            };
            BroadcastUtils.registerReceiverNotExported(this, mHomeKeyReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
    }

    /**
     * 排序(倒序)
     */
    public void sortSeries() {
        if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
            vodInfo.reverseSort = !vodInfo.reverseSort;
            isReverse = !isReverse;
            vodInfo.reverse();
            vodInfo.playIndex = (vodInfo.seriesMap.get(vodInfo.playFlag).size() - 1) - vodInfo.playIndex;
//                    insertVod(sourceKey, vodInfo);

            seriesAdapter.notifyDataSetChanged();
        }
    }

    public void showCastDialog() {

        VodInfo.VodSeries vodSeries = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
        new XPopup.Builder(this)
                .maxWidth(ConvertUtils.dp2px(360))
                .asCustom(new CastListDialog(this, new CastVideo(vodSeries.name
                        , TextUtils.isEmpty(playFragment.getFinalUrl()) ? vodSeries.url : playFragment.getFinalUrl())))
                .show();
    }

    public void showAllSeriesDialog() {
        if (fullWindows) {
            mAllSeriesRightDialog = new XPopup.Builder(this)
                    .isViewMode(true)//隐藏导航栏(手势条)在dialog模式下会闪一下,改为view模式,但需处理onBackPress的隐藏,下方同理
                    .hasNavigationBar(false)
                    .popupHeight(ScreenUtils.getScreenHeight())
                    .popupPosition(PopupPosition.Right)
                    .enableDrag(false)//禁用拖拽,内部有横向rv
                    .asCustom(new AllVodSeriesRightDialog(this));
            mAllSeriesRightDialog.show();
        } else {
            mAllSeriesBottomDialog = new XPopup.Builder(this)
                    .isViewMode(true)
                    .hasNavigationBar(false)
                    .maxHeight(ScreenUtils.getScreenHeight() - (ScreenUtils.getScreenHeight() / 4))
                    .asCustom(new AllVodSeriesBottomDialog(this, seriesAdapter.getData(), (position, text) -> {
                        chooseSeries(position, false);
                    }));
            mAllSeriesBottomDialog.show();
        }
    }

    private void chooseFlag(int position) {
        //新选中的flag
        String newFlag = seriesFlagAdapter.getData().get(position).name;
        if (vodInfo != null && !vodInfo.playFlag.equals(newFlag)) {
            for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {//遍历flag集合
                VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(i);
                if (flag.name.equals(vodInfo.playFlag)) {//取消当前播放的选中状态
                    flag.selected = false;
                    seriesFlagAdapter.notifyItemChanged(i);
                    break;
                }
            }
            //新选中的flag
            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(position);
            flag.selected = true;
            //清除上一个线路集数的选中状态
            List<VodInfo.VodSeries> currentSeriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
            if (currentSeriesList.size() > vodInfo.playIndex) {//有效集数
                currentSeriesList.get(vodInfo.playIndex).selected = false;
            }
            vodInfo.playFlag = newFlag;
            seriesFlagAdapter.notifyItemChanged(position);
            refreshList();
        }
    }

    private void chooseSeries(int position, boolean reloadWithChangeLine) {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            boolean reload = false;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                seriesAdapter.getData().get(j).selected = false;
                seriesAdapter.notifyItemChanged(j);
            }
            //解决倒叙不刷新
            if (vodInfo.playIndex != position) {
                seriesAdapter.getData().get(position).selected = true;
                seriesAdapter.notifyItemChanged(position);
                vodInfo.playIndex = position;

                reload = true;
            }
            //解决当前集不刷新的BUG
            if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
                reload = true;
            }

            seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
            seriesAdapter.notifyItemChanged(vodInfo.playIndex);

            //选集全屏 想选集不全屏的注释下面一行
            if (!showPreview || reload || reloadWithChangeLine) {
                jumpToPlay();
            }
        }
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private List<Runnable> pauseRunnable = null;

    private void jumpToPlay() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            preFlag = vodInfo.playFlag;
            //更新播放地址
            Bundle bundle = new Bundle();
            //保存历史
            insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey);
//            bundle.putSerializable("VodInfo", vodInfo);
            App.getInstance().setVodInfo(vodInfo);
            if (previewVodInfo == null) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    oos.writeObject(vodInfo);
                    oos.flush();
                    oos.close();
                    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
                    previewVodInfo = (VodInfo) ois.readObject();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (previewVodInfo != null) {
                previewVodInfo.playerCfg = vodInfo.playerCfg;
                previewVodInfo.playFlag = vodInfo.playFlag;
                previewVodInfo.playIndex = vodInfo.playIndex;
                previewVodInfo.seriesMap = vodInfo.seriesMap;
//                    bundle.putSerializable("VodInfo", previewVodInfo);
                App.getInstance().setVodInfo(previewVodInfo);
            }
            playFragment.setData(bundle);

            //定位选集
            mBinding.mGridView.scrollToPosition(vodInfo.playIndex);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        int seriesSize = vodInfo.seriesMap.get(vodInfo.playFlag).size();
        if (seriesSize > 0 && seriesSize <= vodInfo.playIndex) {//当前集数大于新选线路的总集数,设置为最后一集
            vodInfo.playIndex = seriesSize - 1;
        }

        if (vodInfo.seriesMap.get(vodInfo.playFlag) != null) {
            boolean canSelect = true;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                if (vodInfo.seriesMap.get(vodInfo.playFlag).get(j).selected) {
                    canSelect = false;
                    break;
                }
            }
            if (canSelect)
                vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).selected = true;
        }
        seriesAdapter.setNewData(vodInfo.seriesMap.get(vodInfo.playFlag));

    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    showSuccess();
                    mVideo = absXml.movie.videoList.get(0);
                    vodInfo = new VodInfo();
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;

                    mBinding.tvName.setText(TextUtils.isEmpty(mVideo.name) ? "暂无信息" : mVideo.name);
                    String srcName = "";
                    SourceBean detailSource = ApiConfig.get().getSource(mVideo.sourceKey);
                    if (detailSource != null) srcName = detailSource.getName();
                    mBinding.tvSite.setText("来源：" + (TextUtils.isEmpty(srcName) ? "未知" : srcName));

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {//线路
                        mBinding.mGridViewFlag.setVisibility(View.VISIBLE);
                        mBinding.mGridView.setVisibility(View.VISIBLE);
                        mBinding.mEmptyPlaylist.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId);
                        // 读取历史记录
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
//                        setTextShow(tvPlayUrl, "播放地址：", vodInfo.seriesMap.get(vodInfo.playFlag).get(0).url);
                        //设置线路数据
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mBinding.mGridViewFlag.scrollToPosition(flagScrollTo);

                        refreshList();
                        if (showPreview) {
                            jumpToPlay();
                            mBinding.previewPlayer.setVisibility(View.VISIBLE);
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {//空布局
                        mBinding.mGridViewFlag.setVisibility(View.GONE);
                        mBinding.mGridView.setVisibility(View.GONE);
                        mBinding.mEmptyPlaylist.setVisibility(View.VISIBLE);
                    }
                } else {
                    showEmpty();
                    mBinding.previewPlayer.setVisibility(View.GONE);
                }
            }
        });
    }

    private String getHtml(String label, String content) {
        if (content == null) {
            content = "";
        }
        return label + "<font color=\"#FFFFFF\">" + content + "</font>";
    }

    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            // 入口(搜索/列表等)传过来的剧名,用于下载命名兜底
            mPassedName = bundle.getString("vodName", "");
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        if (vid != null) {
            vodId = vid;
            sourceKey = key;
            showLoading();
            sourceViewModel.getDetail(sourceKey, vodId);
            boolean isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId);
            if (isVodCollect) {
                mBinding.tvCollect.setText("取消收藏");
            } else {
                mBinding.tvCollect.setText("加入收藏");
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj instanceof Integer) {
                    int index = (int) event.obj;
                    for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    seriesAdapter.getData().get(index).selected = true;
                    seriesAdapter.notifyItemChanged(index);
                    //mBinding.mGridView.setSelection(index);
                    vodInfo.playIndex = index;
                    //保存历史
                    insertVod(sourceKey, vodInfo);
                } else if (event.obj instanceof JSONObject) {
                    vodInfo.playerCfg = ((JSONObject) event.obj).toString();
                    //保存历史
                    insertVod(sourceKey, vodInfo);
                }

            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                Movie.Video video = (Movie.Video) event.obj;
                loadDetail(video.id, video.sourceKey);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                String word = (String) event.obj;
                switchSearchWord(word);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        }
    }

    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private ExecutorService searchExecutorService = null;

    private void switchSearchWord(String word) {
        HttpClient.cancel("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    private void startQuickSearch() {
        initCheckedSourcesForSearch();
        if (hadQuickStart)
            return;
        hadQuickStart = true;
        HttpClient.cancel("quick_search");
        quickSearchWord.clear();
        searchTitle = mVideo.name;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        // 分词
        HttpClient.get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1", "fenci", new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        try {
                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
                    }

                    @Override
                    public void onError(Throwable e) {
                    }
                });

        searchResult();
    }

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getQuickSearch(key, searchTitle);
                }
            });
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (video.sourceKey.equals(sourceKey) && video.id.equals(vodId))
                    continue;
                data.add(video);
            }
            quickSearchData.addAll(data);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        if (Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) {//无痕浏览
            return;
        }
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    @Override
    protected void onDestroy() {
        pipHelper.setReceiverEnabled(false);
        super.onDestroy();
        unregisterReceiver(mBatteryReceiver);
        // 注销广播接收器
        if (mHomeKeyReceiver != null) {
            unregisterReceiver(mHomeKeyReceiver);
            mHomeKeyReceiver = null;
        }

        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        HttpClient.cancel("fenci");
        HttpClient.cancel("detail");
        HttpClient.cancel("quick_search");
        toggleScreenShotListen(false);
    }

    @Override
    public void onBackPressed() {
        if (mAllSeriesRightDialog != null && mAllSeriesRightDialog.isShow()) {
            mAllSeriesRightDialog.dismiss();
            return;
        }
        if (mAllSeriesBottomDialog != null && mAllSeriesBottomDialog.isShow()) {
            mAllSeriesBottomDialog.dismiss();
            return;
        }
        if (playFragment.hideAllDialogSuccess()) {//fragment有弹窗隐藏并拦截返回
            return;
        }
        if (fullWindows) {
            toggleFullPreview();
            mBinding.mGridView.requestFocus();
            return;
        }
        pipExitByBack = true; // 返回键真正退出播放页,onUserLeaveHint 里排除(不算"切后台")
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // preview
    VodInfo previewVodInfo = null;
    boolean showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true);
    ; // true 开启 false 关闭
    boolean fullWindows = false;
    /** 用户按返回键退出播放页的标记(onUserLeaveHint 里排除,避免"返回退出"被当成"切后台") */
    private boolean pipExitByBack = false;
    /** 画中画(小窗)通用辅助器,封装进入/退出小窗逻辑,详情页与本地播放器复用 */
    private PipHelper pipHelper;

    ViewGroup.LayoutParams windowsPreview = null;
    ViewGroup.LayoutParams windowsFull = null;

    public void toggleFullPreview() {
        if (windowsPreview == null) {
            windowsPreview = mBinding.previewPlayer.getLayoutParams();
        }
        if (windowsFull == null) {//全屏尺寸
            windowsFull = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        fullWindows = !fullWindows;

        //交由fragment处理播放器全屏逻辑
        playFragment.changedLandscape(fullWindows);
        //activity处理预览尺寸(全屏/非全屏预览)
        mBinding.previewPlayer.setLayoutParams(fullWindows ? windowsFull : windowsPreview);
        mBinding.mGridView.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mBinding.mGridViewFlag.setVisibility(fullWindows ? View.GONE : View.VISIBLE);

        //全屏下禁用详情页几个按键的焦点 防止上键跑过来
        mBinding.tvSort.setFocusable(!fullWindows);
        mBinding.tvCollect.setFocusable(!fullWindows);
        toggleSubtitleTextSize();
    }

    void toggleSubtitleTextSize() {
        int subtitleTextSize = SubtitleHelper.getTextSize(this);
        if (!fullWindows) {
            subtitleTextSize *= 0.6;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize));
    }

    /**
     * 打开"选择下载剧集"弹窗:网格多选 + 开始下载/下载管理。
     */
    public void showDownloadSeriesDialog() {
        if (vodInfo == null || vodInfo.seriesMap.get(vodInfo.playFlag) == null
                || vodInfo.seriesMap.get(vodInfo.playFlag).size() <= 0) {
            AppBubble.toast("资源异常,请稍后重试");
            return;
        }
        // 必须视频成功播放过才能下载(当前集才有解析后的可用地址)
        if (playFragment == null || playFragment.getController() == null || !playFragment.getController().hasPlayedOnce) {
            AppBubble.toast("视频播放成功后才能下载");
            return;
        }
        // 拷贝一份选集,弹窗内的选中状态不影响原选集的选中态
        List<VodInfo.VodSeries> copy = new ArrayList<>();
        for (VodInfo.VodSeries s : vodInfo.seriesMap.get(vodInfo.playFlag)) {
            VodInfo.VodSeries c = new VodInfo.VodSeries();
            c.name = s.name;
            c.url = s.url;
            c.selected = false;
            copy.add(c);
        }
        // 基于 来源+剧名+剧集 标记下载状态:0=可下载,1=已下载,2=下载中/排队(弹窗内置灰不可重复选)
        String sourceName = getDownloadSourceName();
        String vodName = getDownloadVodName();
        int[] states = new int[copy.size()];
        for (int i = 0; i < copy.size(); i++) {
            states[i] = DownloadManager.get().getEpisodeDownloadState(sourceName, vodName, copy.get(i).name);
        }
        new XPopup.Builder(this)
                .isViewMode(true)
                .hasNavigationBar(false)
                .asCustom(new DownloadSeriesDialog(this, copy, states, new DownloadSeriesDialog.OnDownloadActionListener() {
                    @Override
                    public void onStartDownload(List<VodInfo.VodSeries> selected) {
                        startDownloads(selected);
                    }

                    @Override
                    public void onOpenDownloadManager() {
                        jumpActivity(DownloadActivity.class);
                    }
                }))
                .show();
    }

    /**
     * 批量加入下载任务:先解析每集真实地址(后台线程),再入队;区分空选择与重复下载
     *
     * @param selected 已勾选的剧集列表
     */
    private void startDownloads(List<VodInfo.VodSeries> selected) {
        if (selected == null || selected.isEmpty()) {
            AppBubble.toast("请先选择要下载的剧集");
            return;
        }
        // 网络控制:默认仅 WiFi 下载;移动网络下强提醒流量风险,确认后才继续
        if (DownloadManager.get().isWifiOnly() && DownloadManager.isMobileNetwork()) {
            new XPopup.Builder(this)
                    .isDarkTheme(Utils.isDarkTheme())
                    .asConfirm("流量提醒", "当前为移动网络,继续下载将消耗手机流量,是否继续?",
                            "继续下载", "取消", () -> doStartDownloads(selected), null, false)
                    .show();
            return;
        }
        doStartDownloads(selected);
    }

    private void doStartDownloads(List<VodInfo.VodSeries> selected) {
        List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (seriesList == null || seriesList.isEmpty()) {
            AppBubble.toast("资源异常,请稍后重试");
            return;
        }
        final String sourceName = getDownloadSourceName();
        final String vodName = getDownloadVodName();
        final String sourceKey = vodInfo.sourceKey;
        final String playFlag = vodInfo.playFlag;
        final String currentName = seriesList.get(vodInfo.playIndex).name;
        // 当前播放视频的分辨率标签:取宽高中的高(如 1280x720→720P;1080→1080P;1440→2K;2160+→4K)
        final String resLabel = (playFragment != null && playFragment.getPlayer() != null)
                ? getResolutionLabel(playFragment.getPlayer().getVideoSize()) : null;
        Log.i("TVBox-Download", "startDownloads: 已选 " + selected.size() + " 集, 来源=" + sourceName
                + ", 剧名=" + vodName + ", 当前集=" + currentName + ", 分辨率=" + resLabel);
        AppBubble.toast("正在解析下载地址,请稍候...");
        // 用与播放一致的爬虫单线程池解析地址,避免 quickjs 并发
        SourceViewModel.spThreadPool.execute(() -> {
            int added = 0;
            int duplicated = 0;
            int failed = 0;
            for (VodInfo.VodSeries s : selected) {
                String url;
                if (s.name != null && s.name.equals(currentName) && playFragment != null) {
                    String finalUrl = playFragment.getFinalUrl();
                    url = TextUtils.isEmpty(finalUrl) ? PlayUrlResolver.resolve(sourceKey, playFlag, s.url) : finalUrl;
                } else {
                    url = PlayUrlResolver.resolve(sourceKey, playFlag, s.url);
                }
                if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    Log.i("TVBox-Download", "  - " + s.name + " 解析失败/无有效地址,跳过");
                    failed++;
                    continue;
                }
                // 文件名拼接分辨率:剧名_集名_720P.mp4;集名已含分辨率字样则不多拼;单集(名=剧名)不拼
                String epName = s.name;
                if (resLabel != null && s.name != null && !s.name.isEmpty()
                        && !s.name.equals(vodName) && !containsResolution(s.name)) {
                    epName = s.name + "_" + resLabel;
                }
                boolean ok = DownloadManager.get().enqueue(url, sourceKey, playFlag, s.url, sourceName, vodName, epName);
                Log.i("TVBox-Download", "  - " + s.name + " enqueue=" + ok + " 文件名=" + epName + " url=" + url);
                if (ok) {
                    added++;
                } else {
                    duplicated++;
                }
            }
            final int fAdded = added;
            final int fDup = duplicated;
            final int fFailed = failed;
            runOnUiThread(() -> {
                if (fAdded > 0) {
                    AppBubble.toast(fDup > 0
                            ? "已加入 " + fAdded + " 个下载任务," + fDup + " 个已存在"
                            : "已加入 " + fAdded + " 个下载任务,可在\"我的-下载\"查看");
                } else if (fDup > 0) {
                    AppBubble.toast("所选剧集均已下载过或已在任务中");
                } else if (fFailed > 0) {
                    AppBubble.toast("所选剧集解析失败,无法下载");
                } else {
                    AppBubble.toast("所选剧集地址无效,无法下载");
                }
            });
        });
    }

    /** 由视频宽高生成分辨率标签:取高度归类(1280x720→720P;1080→1080P;1440→2K;2160+→4K);无法识别返回 null */
    private String getResolutionLabel(int[] size) {
        if (size == null || size.length < 2) return null;
        int h = size[1];
        if (h <= 0) return null;
        if (h >= 2000) return "4K";
        if (h >= 1400) return "2K";
        if (h >= 1000) return "1080P";
        if (h >= 700) return "720P";
        if (h >= 500) return "480P";
        return null;
    }

    /** 集名是否已含分辨率字样(4K/2K/1080P/720P 等),含则不再拼接 */
    private boolean containsResolution(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        return n.contains("4K") || n.contains("2K") || n.contains("2160P") || n.contains("1440P")
                || n.contains("1080P") || n.contains("720P") || n.contains("480P") || n.contains("360P");
    }

    /** 来源名(一级目录,如 饭太硬) */
    private String getDownloadSourceName() {
        String sourceName = "未分类";
        try {
            SourceBean sb = ApiConfig.get().getSource(vodInfo.sourceKey);
            if (sb != null && !TextUtils.isEmpty(sb.getName())) {
                sourceName = sb.getName();
            } else if (!TextUtils.isEmpty(vodInfo.sourceKey)) {
                sourceName = vodInfo.sourceKey;
            }
        } catch (Throwable ignored) {
        }
        return sourceName;
    }

    /** 剧名:优先详情接口的名称,其次入口(搜索/列表)传入的名称,最后用页面 tvName 兜底 */
    private String getDownloadVodName() {
        String vodName = vodInfo.name;
        if (TextUtils.isEmpty(vodName)) {
            vodName = mPassedName;
        }
        if (TextUtils.isEmpty(vodName)) {
            CharSequence title = mBinding.tvName.getText();
            vodName = title == null ? "" : title.toString().trim();
            if ("暂无信息".equals(vodName)) vodName = "";
        }
        return vodName;
    }

    /**
     * 画中画模式(小窗):进入小窗。逻辑封装在 PipHelper,详情页/本地播放器复用。
     */
    public void enterPip() {
        pipHelper.enterPip();
        playFragment.getController().hideBottom();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 兜底:本设备上点X关闭不触发 onPictureInPictureModeChanged(false),只触发配置变化,
        // 由 PipHelper 统一延迟判断"放大/点X关闭"
        pipHelper.onConfigurationChanged();
    }

    /**
     * 后台播放服务开关,开启时注册操作广播,关闭时注销
     */
    private void playServerSwitch(boolean open) {
        if (open) {
            VodInfo.VodSeries vod = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
            PlayService.start(playFragment.getPlayer(), vodInfo.name + "&&" + vod.name);
            pipHelper.setReceiverEnabled(true);
        } else {
            if (ServiceUtils.isServiceRunning(PlayService.class)) {
                PlayService.stop();
                pipHelper.setReceiverEnabled(false);
            }
        }
    }

    public String getCurrentVodUrl() {
        return playFragment == null ? "" : playFragment.getFinalUrl();
    }

    public void quickLineChange() {
        List<VodInfo.VodSeriesFlag> flags = seriesFlagAdapter.getData();
        if (flags.size() > 1) {
            int currentIndex = 0;
            for (int i = 0; i < flags.size(); i++) {
                if (flags.get(i).selected) {
                    currentIndex = i;
                }
            }
            currentIndex += 1;
            if (currentIndex >= flags.size()) {
                currentIndex = 0;
            }
            mBinding.mGridViewFlag.smoothScrollToPosition(currentIndex);
            chooseFlag(currentIndex);
            mBinding.mGridView.postDelayed(() -> chooseSeries(vodInfo.playIndex, true), 300);
        }
    }

    public void showParseRoot(boolean show, ParseAdapter adapter) {
        mBinding.rvParse.setAdapter(adapter);
        int defaultIndex = 0;
        for (int i = 0; i < adapter.getData().size(); i++) {
            if (adapter.getData().get(i).isDefault()) {
                defaultIndex = i;
                break;
            }
        }
        if (defaultIndex != 0) {
            mBinding.rvParse.scrollToPosition(defaultIndex);
        }
        mBinding.parseRoot.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toggleScreenShotListen(boolean open) {
        if (open){
            if (screenShotListenManager == null){
                screenShotListenManager = ScreenShotListenManager.newInstance(this);
            }
            screenShotListenManager.setListener(imagePath -> {

                if (playFragment.getPlayer().isInPlaybackState())return;

                new XPopup.Builder(this)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asCenterList("",new String[]{"跳转阿狸","跳转优汐","跳转夸父","关闭"}, null, (position, text) -> {
                            String pkg = "";
                            String cls = "";
                            switch (position){
                                case 0:
                                    pkg = "com.alicloud.databox";
                                    cls = "com.alicloud.databox.launcher.splash.SplashActivity";
                                    break;
                                case 1:
                                    pkg = "com.UCMobile";
                                    cls = "com.uc.browser.InnerUCMobile";
                                    break;
                                case 2:
                                    pkg = "com.quark.browser";
                                    cls = "com.ucpro.MainActivity";
                                    break;
                                case 3:
                                    return;
                            }
                            try {
                                startActivity(new Intent().setComponent(new ComponentName(pkg, cls)));
                            }catch (Exception e){
                                AppBubble.toast("未找到应用");
                            }
                        })
                        .show();
            });
            screenShotListenManager.startListen();
        }else {
            if (screenShotListenManager != null) {
                screenShotListenManager.stopListen();
            }
        }
    }
}
