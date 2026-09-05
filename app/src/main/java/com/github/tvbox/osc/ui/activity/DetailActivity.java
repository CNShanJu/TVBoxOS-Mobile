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
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.download.DownloadFacade;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.repo.HistoryRepositories;
import com.github.tvbox.osc.constant.IntentKey;
import com.github.tvbox.osc.databinding.ActivityDetailBinding;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.service.PlayService;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesBottomDialog;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesRightDialog;
import com.github.tvbox.osc.ui.dialog.CastListDialog;
import com.github.tvbox.osc.ui.dialog.ConfirmDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.DownloadSeriesDialog;
import com.github.tvbox.osc.ui.dialog.DownloadSeriesRightDialog;
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.ui.dialog.VideoDetailDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.ui.kit.LinearSpacingItemDecoration;
import com.github.tvbox.osc.util.BroadcastUtils;
import com.github.tvbox.osc.util.DetailQuickSearchHelper;
import com.github.tvbox.osc.util.EpisodeDownloadBatch;
import com.github.tvbox.osc.ui.activity.DownloadActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.PipHelper;
import com.github.catvod.crawler.PlayUrlResolver;
import com.github.tvbox.osc.util.ScreenShotListenManager;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.config.SystemConfig;
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
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

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
import java.util.Map;
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
    /** 详情页"快速搜索"请求编排(共享线程池 + epoch 去重/暂停;UI 只负责弹窗展示与直喂数据) */
    private DetailQuickSearchHelper quickSearchHelper;
    /** 快速搜索弹窗(当前打开的实例;宿主直调喂数据/收回调,不再经 EventBus) */
    private QuickSearchDialog mQuickSearchDialog;
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
        navigatingAway = false; // 回到前台,清除应用内跳转标记
        mBinding.ivPrivateBrowsing.postDelayed(NotificationUtils::cancelAll, 800);
    }

    private void initView() {
        mBinding.ivPrivateBrowsing.setVisibility(SystemConfig.isPrivateBrowsing() ? View.VISIBLE : View.GONE);
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
            DialogCoordinator.center(this, new VideoDetailDialog(this, vodInfo)).show();
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
                updateSortButtonText();
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
                    com.github.tvbox.osc.repo.HistoryRepositories.collect().save(sourceKey, vodInfo);
                    com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                            "收藏: " + (vodInfo.name == null ? "?" : vodInfo.name));
                    AppBubble.toast("已加入收藏夹");
                    mBinding.tvCollect.setText("取消收藏");
                } else {
                    com.github.tvbox.osc.repo.HistoryRepositories.collect().delete(sourceKey, vodInfo.id);
                    com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                            "取消收藏: " + (vodInfo.name == null ? "?" : vodInfo.name));
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
            // 快速搜索编排已收敛到 DetailQuickSearchHelper(共享线程池+epoch 去重/暂停语义)
            if (mQuickSearchDialog != null && mQuickSearchDialog.isShow()) {
                return; // 已展示中,忽略连点
            }
            quickSearchHelper.startQuickSearch(mVideo.name);
            mQuickSearchDialog = new QuickSearchDialog(DetailActivity.this);
            // 点击行为直调宿主(原 EventBus SELECT/WORD_CHANGE 收口)
            mQuickSearchDialog.setHost(new QuickSearchDialog.Host() {
                @Override
                public void onVideoSelected(Movie.Video video) {
                    loadDetail(video.id, video.sourceKey);
                }

                @Override
                public void onWordChange(String word) {
                    quickSearchHelper.switchSearchWord(word);
                }
            });
            // helper 结果/词表桥到弹窗:只在弹窗展示期间有效(原 EventBus 订阅窗口语义)
            quickSearchHelper.setQuickSearchOutput(new DetailQuickSearchHelper.QuickSearchOutput() {
                @Override
                public void onResults(List<Movie.Video> data) {
                    runOnUiThread(() -> {
                        QuickSearchDialog dialog = mQuickSearchDialog;
                        if (dialog != null && dialog.isShow()) dialog.appendResults(data);
                    });
                }

                @Override
                public void onWords(List<String> words) {
                    runOnUiThread(() -> {
                        QuickSearchDialog dialog = mQuickSearchDialog;
                        if (dialog != null && dialog.isShow()) dialog.updateWords(words);
                    });
                }
            });
            // 弹窗打开:放行被暂停/暂存的源搜索(等价旧 pauseRunnable 续跑)
            quickSearchHelper.onQuickSearchDialogOpened();
            mQuickSearchDialog.setOnDismissListener(dialog -> {
                // 弹窗关闭:暂停后续排队任务(等价旧 shutdownNow 收集 pauseRunnable),断开输出桥
                quickSearchHelper.onQuickSearchDialogClosed();
                quickSearchHelper.setQuickSearchOutput(null);
                mQuickSearchDialog = null;
            });
            mQuickSearchDialog.show();
            // 初始直喂已累计结果/词表(替代原 show 前广播;show 后 onCreate 已建 adapter)
            mQuickSearchDialog.appendResults(new ArrayList<>(quickSearchHelper.getQuickSearchData()));
            mQuickSearchDialog.updateWords(new ArrayList<>(quickSearchHelper.getQuickSearchWords()));
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
        // 应用内跳转(打开下载管理/设置等)也不算"切后台",排除(避免返回时误触小窗/后台播放)
        if (pipExitByBack || navigatingAway || isFinishing()) {
            pipExitByBack = false;
            navigatingAway = false;
            return;
        }
        if (playFragment == null || playFragment.getPlayer() == null || !playFragment.getPlayer().isPlaying()) {
            return;
        }
        int type = PlayConfig.getBackgroundPlayType();
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
                        openBackgroundPlay = PlayConfig.getBackgroundPlayType() == 1 && playFragment.getPlayer() != null && playFragment.getPlayer().isPlaying();
                    }
                }
            };
            BroadcastUtils.registerReceiverNotExported(this, mHomeKeyReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
    }

    /**
     * 排序(倒序/正序切换):反转全集列表(与弹窗/详情页共用同一状态 vodInfo.reverseSort)。
     * 同步刷新详情页选集区排序按钮文字(弹窗/下载抽屉内排序后详情页文字跟随一致)。
     *
     * @return 反转后的状态:true=已倒序(按钮应显示"正序");false=正序(按钮显示"倒序")
     */
    public boolean sortSeries() {
        if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
            vodInfo.reverseSort = !vodInfo.reverseSort;
            isReverse = !isReverse;
            vodInfo.reverse();
            vodInfo.playIndex = (vodInfo.seriesMap.get(vodInfo.playFlag).size() - 1) - vodInfo.playIndex;
            seriesAdapter.notifyDataSetChanged();
        }
        // 详情页选集区按钮文字跟随共用状态(弹窗内排序也同步;原调用点手调 updateSortButtonText 保留无害)
        updateSortButtonText();
        return vodInfo != null && vodInfo.reverseSort;
    }

    /** 当前是否已倒序(供各弹窗倒序按钮文字同步;与 sortSeries 共用同一状态) */
    public boolean isSeriesReversed() {
        return vodInfo != null && vodInfo.reverseSort;
    }

    /** 详情页选集区倒序按钮文字跟随共用状态:已倒序显示"正序",否则"倒序" */
    private void updateSortButtonText() {
        try {
            mBinding.tvSort.setText(isSeriesReversed() ? "正序" : "倒序");
        } catch (Throwable ignored) {
        }
    }

    public void showCastDialog() {

        VodInfo.VodSeries vodSeries = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
        DialogCoordinator.centerMaxWidth(this, new CastListDialog(this, new CastVideo(vodSeries.name
                , TextUtils.isEmpty(playFragment.getFinalUrl()) ? vodSeries.url : playFragment.getFinalUrl())), 360)
                .show();
    }

    public void showAllSeriesDialog() {
        // 按当前方向决定形态: 横屏用右侧抽屉, 竖屏用底部弹层并限制高度(与详情页一致)
        if (ScreenUtils.isLandscape()) {
            // 右侧抽屉:固定宽度 360,与下载右侧抽屉一致,避免线路列表把弹窗撑开;内部有横向 rv 禁拖拽
            mAllSeriesRightDialog = DialogCoordinator.right(this,
                    new AllVodSeriesRightDialog(this), 360, false);
            mAllSeriesRightDialog.show();
        } else {
            mAllSeriesBottomDialog = DialogCoordinator.bottomMaxHeight(this,
                    new AllVodSeriesBottomDialog(this, seriesAdapter.getData(), (position, text) -> {
                        chooseSeries(position, false);
                    }, this::sortSeries, this::isSeriesReversed),
                    ScreenUtils.getScreenHeight() - (ScreenUtils.getScreenHeight() / 4));
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
        quickSearchHelper = new DetailQuickSearchHelper(sourceViewModel);
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
                    SourceBean detailSource = com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSource(mVideo.sourceKey);
                    if (detailSource != null) srcName = detailSource.getName();
                    mBinding.tvSite.setText("来源：" + (TextUtils.isEmpty(srcName) ? "未知" : srcName));

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {//线路
                        mBinding.mGridViewFlag.setVisibility(View.VISIBLE);
                        mBinding.mGridView.setVisibility(View.VISIBLE);
                        mBinding.mEmptyPlaylist.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = HistoryRepositories.history().get(sourceKey, vodId);
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
                        updateSortButtonText(); // 历史恢复的倒序状态同步到详情页按钮文字

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
            boolean isVodCollect = com.github.tvbox.osc.repo.HistoryRepositories.collect().isSaved(sourceKey, vodId);
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
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                quickSearchHelper.handleQuickSearchResult(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                quickSearchHelper.handleQuickSearchResult(null);
            }
        }
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        if (SystemConfig.isPrivateBrowsing()) {//无痕浏览
            return;
        }
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        com.github.tvbox.osc.repo.HistoryRepositories.history().save(sourceKey, vodInfo);
    }

    @Override
    protected void onDestroy() {
        pipHelper.setReceiverEnabled(false);
        super.onDestroy();
        // 注销广播接收器
        if (mHomeKeyReceiver != null) {
            unregisterReceiver(mHomeKeyReceiver);
            mHomeKeyReceiver = null;
        }

        // 作废未启动的快速搜索任务、清空暂停队列(共享线程池不可关闭)
        try {
            if (quickSearchHelper != null) {
                quickSearchHelper.release();
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
    boolean showPreview = SystemConfig.isShowPreview();
    ; // true 开启 false 关闭
    boolean fullWindows = false;
    /** 用户按返回键退出播放页的标记(onUserLeaveHint 里排除,避免"返回退出"被当成"切后台") */
    private boolean pipExitByBack = false;
    /** 应用内跳转(如打开下载管理/设置等)标记:onUserLeaveHint 里排除,避免"应用内导航"被当成"切后台"触发小窗/后台播放 */
    private boolean navigatingAway = false;
    /** 下载选择弹窗防重入:短时间内多次点"下载"只弹一个抽屉(延迟链 + 弹窗引用双重保护) */
    private boolean isDownloadDialogShowing = false;
    private BasePopupView mDownloadDialog = null;
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
        // 预览播放器与详情页同屏,直调取代 EventBus TYPE_SUBTITLE_SIZE_CHANGE 广播
        if (playFragment != null) {
            playFragment.applySubtitleTextSize(subtitleTextSize);
        }
    }

    /**
     * 打开"选择下载剧集"弹窗:网格多选 + 开始下载/下载管理。
     * 不要求必须先播放成功(未播放时所有集统一走后台解析);全屏时先退出全屏再弹窗,避免小屏叠加。
     */
    public void showDownloadSeriesDialog() {
        // 防重入:弹窗已在显示或延迟链已排队,直接忽略(详情页"下载"按钮连点也不会叠抽屉)
        if (isDownloadDialogShowing || mDownloadDialog != null && mDownloadDialog.isShow()) {
            return;
        }
        if (vodInfo == null || vodInfo.seriesMap.get(vodInfo.playFlag) == null
                || vodInfo.seriesMap.get(vodInfo.playFlag).size() <= 0) {
            isDownloadDialogShowing = false;
            AppBubble.toast("资源异常,请稍后重试");
            return;
        }
        // 全屏播放下发起下载:先退出全屏回到详情页布局,再弹窗(手机小屏上避免播放器与抽屉叠加)
        if (fullWindows) {
            toggleFullPreview();
            // 等退全屏布局稳定后再弹窗,避免弹窗与全屏切换动画叠加(修复:返回时全屏/抽屉残留)
            mBinding.previewPlayer.postDelayed(this::showDownloadSeriesDialogInner, 250);
            return;
        }
        showDownloadSeriesDialogInner();
    }

    /**
     * 全屏控制栏"下载"按钮:立即弹出右侧下载抽屉(内容先 loading),数据后台准备完成后填充,不退出全屏。
     * 仅全屏状态调用(由播放器控制栏触发);非全屏走底部弹窗 showDownloadSeriesDialog。
     */
    public void showDownloadDialogInFullscreen() {
        // 防重入:下载抽屉已显示或底部弹窗在排队中,忽略重复点击
        if (isDownloadDialogShowing || mDownloadDialog != null && mDownloadDialog.isShow()) {
            return;
        }
        if (vodInfo == null || vodInfo.seriesMap.get(vodInfo.playFlag) == null
                || vodInfo.seriesMap.get(vodInfo.playFlag).size() <= 0) {
            AppBubble.toast("资源异常,请稍后重试");
            return;
        }
        // 竖屏(含竖屏全窗):与设置/选集一致,改为底部弹窗(限高2/3),仅横屏才用右侧下载抽屉
        if (!ScreenUtils.isLandscape()) {
            showDownloadSeriesDialogInner();
            return;
        }
        // 立即弹抽屉(空数据 + loading),避免主线程构建选集/状态造成卡顿
        isDownloadDialogShowing = true;
        mDownloadDialog = DialogCoordinator.right(this,
                new DownloadSeriesRightDialog(this, new DownloadSeriesRightDialog.OnDownloadActionListener() {
                    @Override
                    public void onStartDownload(List<VodInfo.VodSeries> selected) {
                        startDownloads(selected);
                    }

                    @Override
                    public void onOpenDownloadManager() {
                        navigatingAway = true;
                        jumpActivity(DownloadActivity.class);
                    }

                    @Override
                    public void onSortSeries() {
                        sortSeries(); // 与选集抽屉一致:反转全集列表(副本随正表重建)
                        refreshDownloadDialogStates();
                    }
                }, this::isSeriesReversed),
                360, false, downloadDialogCallback());
        mDownloadDialog.show();
        // 后台准备数据(选集副本 + 下载状态批量查询),完成后主线程填充抽屉
        final String sourceName = getDownloadSourceName();
        final String vodName = getDownloadVodName();
        SourceViewModel.spThreadPool.execute(() -> {
            List<VodInfo.VodSeries> copy = buildDownloadSeriesCopy();
            int[] states = buildDownloadStates(copy, sourceName, vodName);
            runOnUiThread(() -> {
                if (mDownloadDialog instanceof DownloadSeriesRightDialog && mDownloadDialog.isShow()) {
                    ((DownloadSeriesRightDialog) mDownloadDialog).setData(copy, states);
                }
            });
        });
    }

    /** 构建下载选择弹窗的选集副本(带统一剧集标识),供底部弹窗与全屏右侧抽屉复用;
        保留弹窗当前已勾选的集(按集名匹配, 排序/刷新均不丢选中) */
    private List<VodInfo.VodSeries> buildDownloadSeriesCopy() {
        java.util.Set<String> selectedNames = new java.util.HashSet<>();
        if (mDownloadDialog != null) {
            List<VodInfo.VodSeries> shown = null;
            if (mDownloadDialog instanceof DownloadSeriesDialog) {
                shown = ((DownloadSeriesDialog) mDownloadDialog).getCurrentList();
            } else if (mDownloadDialog instanceof DownloadSeriesRightDialog) {
                shown = ((DownloadSeriesRightDialog) mDownloadDialog).getCurrentList();
            }
            if (shown != null) {
                for (VodInfo.VodSeries s : shown) {
                    if (s.selected && s.name != null) selectedNames.add(s.name);
                }
            }
        }
        List<VodInfo.VodSeries> copy = new ArrayList<>();
        int copyIdx = 0;
        for (VodInfo.VodSeries s : vodInfo.seriesMap.get(vodInfo.playFlag)) {
            VodInfo.VodSeries c = new VodInfo.VodSeries();
            c.name = s.name;
            c.url = s.url;
            c.selected = s.name != null && selectedNames.contains(s.name);
            c.episodeId = DownloadFacade.get().buildEpisodeId(vodInfo.sourceKey, vodInfo.id, vodInfo.playFlag, copyIdx);
            copyIdx++;
            copy.add(c);
        }
        return copy;
    }

    /** 构建下载状态数组:0=可下载,1=已下载,2=下载中/排队(批量查询,一次快照避免逐集拷贝任务列表) */
    private int[] buildDownloadStates(List<VodInfo.VodSeries> copy, String sourceName, String vodName) {
        int n = copy.size();
        String[] episodeIds = new String[n];
        String[] episodeNames = new String[n];
        for (int i = 0; i < n; i++) {
            episodeIds[i] = copy.get(i).episodeId;
            episodeNames[i] = copy.get(i).name;
        }
        return DownloadFacade.get().getEpisodeStates(episodeIds, sourceName, vodName, episodeNames);
    }

    /** 下载弹窗统一关闭回调:关闭后清除防重入标记,允许再次打开 */
    private com.lxj.xpopup.interfaces.XPopupCallback downloadDialogCallback() {
        return new com.lxj.xpopup.interfaces.XPopupCallback() {
            @Override
            public void onCreated(BasePopupView popupView) {
            }

            @Override
            public void beforeShow(BasePopupView popupView) {
            }

            @Override
            public void onShow(BasePopupView popupView) {
            }

            @Override
            public void onDismiss(BasePopupView popupView) {
                isDownloadDialogShowing = false;
                mDownloadDialog = null;
                // 实时刷新: 弹窗关闭后注销下载状态订阅
                com.github.tvbox.osc.download.DownloadFacade.get().unregister(downloadStatusListener);
            }

            @Override
            public void beforeDismiss(BasePopupView popupView) {
            }

            @Override
            public boolean onBackPressed(BasePopupView popupView) {
                return false;
            }

            @Override
            public void onKeyBoardStateChanged(BasePopupView popupView, int height) {
            }

            @Override
            public void onDrag(BasePopupView popupView, int value, float fraction, boolean isScrollShadow) {
            }

            @Override
            public void onClickOutside(BasePopupView popupView) {
            }
        };
    }

    /** 弹窗主体(退全屏完成后调用):立即弹窗(loading),数据后台准备完成后填充 */
    private void showDownloadSeriesDialogInner() {
        // 防重入:延迟回调已触发过(弹窗已创建),跳过重复创建
        if (isDownloadDialogShowing && mDownloadDialog != null && mDownloadDialog.isShow()) {
            return;
        }
        isDownloadDialogShowing = true;
        // 底部弹窗封顶 2/3 屏,列表吃满剩余+滚动,按钮固定底部;
        // 弹窗关闭(确认/取消/点外部/返回键)后清除防重入标记,允许再次打开
        mDownloadDialog = DialogCoordinator.bottomMaxHeight(this,
                new DownloadSeriesDialog(this, new DownloadSeriesDialog.OnDownloadActionListener() {
                    @Override
                    public void onStartDownload(List<VodInfo.VodSeries> selected) {
                        startDownloads(selected);
                    }

                    @Override
                    public void onOpenDownloadManager() {
                        // 应用内跳转:标记避免 onUserLeaveHint 误判为"切后台"触发小窗/后台播放(修复:返回时全屏播放)
                        navigatingAway = true;
                        jumpActivity(DownloadActivity.class);
                    }

                    @Override
                    public void onSortSeries() {
                        sortSeries(); // 与选集抽屉一致:反转全集列表(副本随正表重建)
                        refreshDownloadDialogStates();
                    }
                }, this::isSeriesReversed),
                ScreenUtils.getScreenHeight() * 2 / 3,
                downloadDialogCallback());
        mDownloadDialog.show();
        // 实时刷新: 订阅下载状态变化(下载中进度/完成/失败 → 弹窗实时更新勾选态)
        com.github.tvbox.osc.download.DownloadFacade.get().register(downloadStatusListener);
        // 后台准备数据(选集副本 + 下载状态批量查询),完成后主线程填充弹窗
        refreshDownloadDialogStates();
    }

    /** 下载状态变化监听(DownloadFacade 去抖 500ms 回调,主线程): 弹窗仍显示则重查并填充 */
    private final com.github.tvbox.osc.download.DownloadFacade.DownloadStatusListener downloadStatusListener = () -> {
        if (mDownloadDialog instanceof DownloadSeriesDialog && mDownloadDialog.isShow()) {
            refreshDownloadDialogStates();
        }
    };

    /** 后台准备弹窗数据(选集副本 + 下载状态批量查询),完成后主线程填充弹窗 */
    private void refreshDownloadDialogStates() {
        final String sourceName = getDownloadSourceName();
        final String vodName = getDownloadVodName();
        SourceViewModel.spThreadPool.execute(() -> {
            // 先清理该剧的文件不存在档案(本地删文件后, 打开抽屉立即恢复"未下载")
            com.github.tvbox.osc.download.DownloadFacade.get().removeArchiveOrphansByVod(vodName, sourceName);
            List<VodInfo.VodSeries> copy = buildDownloadSeriesCopy();
            int[] states = buildDownloadStates(copy, sourceName, vodName);
            runOnUiThread(() -> {
                if (mDownloadDialog instanceof DownloadSeriesDialog && mDownloadDialog.isShow()) {
                    ((DownloadSeriesDialog) mDownloadDialog).setData(copy, states);
                }
            });
        });
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
        // 网络控制:默认仅 WiFi 下载;移动网络下强提醒流量风险,确认后才继续(统一走 DownloadConfig)
        if (DownloadFacade.get().isWifiOnly() && DownloadFacade.get().isMobileNetwork()) {
            // 统一主题化确认弹窗(替代 XPopup 默认 asConfirm)
            ConfirmDialog.show(this, "流量提醒", "当前为移动网络,继续下载将消耗手机流量,是否继续?",
                    "继续下载", () -> doStartDownloads(selected));
            return;
        }
        doStartDownloads(selected);
    }

    private void doStartDownloads(List<VodInfo.VodSeries> selected) {
        List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap == null
                ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
        if (seriesList == null || seriesList.isEmpty()) {
            AppBubble.toast("资源异常,请稍后重试");
            return;
        }
        final String sourceName = getDownloadSourceName();
        final String vodName = getDownloadVodName();
        final String sourceKey = vodInfo.sourceKey;
        final String playFlag = vodInfo.playFlag;
        final String vodId = vodInfo.id;
        final int playIndex = vodInfo.playIndex;
        final String currentName = playIndex >= 0 && playIndex < seriesList.size()
                ? seriesList.get(playIndex).name : null;
        // 当前播放视频的分辨率标签(由播放器画面尺寸归类),不可用则不拼分辨率
        final String resLabel = (playFragment != null && playFragment.getPlayer() != null)
                ? EpisodeDownloadBatch.resolutionLabel(playFragment.getPlayer().getVideoSize()) : null;
        Log.i("TVBox-Download", "startDownloads: 已选 " + selected.size() + " 集, 来源=" + sourceName
                + ", 剧名=" + vodName + ", 当前集=" + currentName + ", 分辨率=" + resLabel);
        AppBubble.toast("正在解析下载地址,请稍候...");
        // 用与播放一致的爬虫单线程池解析地址,避免 quickjs 并发;解析/入队/计数收敛到 EpisodeDownloadBatch
        SourceViewModel.spThreadPool.execute(() -> {
            EpisodeDownloadBatch.Outcome r = EpisodeDownloadBatch.enqueue(selected, vodInfo, sourceName,
                    vodName, currentName, resLabel, playFragment == null ? null : new EpisodeDownloadBatch.CurrentEpisode() {
                        @Override
                        public String finalUrl() {
                            return playFragment.getFinalUrl();
                        }

                        @Override
                        public Map<String, String> playHeaders() {
                            return playFragment.getPlayHeaders();
                        }
                    });
            final int fAdded = r.added;
            final int fDownloaded = r.downloadedExisted;
            final int fInQueue = r.existedInQueue;
            final int fFailed = r.failed;
            runOnUiThread(() -> {
                if (fAdded > 0) {
                    int dups = fDownloaded + fInQueue;
                    AppBubble.toast(dups > 0
                            ? "已加入 " + fAdded + " 个下载任务," + dups + " 个已存在"
                            : "已加入 " + fAdded + " 个下载任务,可在\"我的-下载\"查看");
                } else if (fDownloaded > 0 || fInQueue > 0) {
                    // 修正:原实现 duplicated 计数从未累加,导致"均已下载/已在任务中"分支不可达
                    if (fDownloaded > 0 && fInQueue > 0) {
                        AppBubble.toast(fDownloaded + " 集已下载," + fInQueue + " 集已在任务中");
                    } else if (fDownloaded > 0) {
                        AppBubble.toast("所选剧集均已下载完成");
                    } else {
                        AppBubble.toast("所选剧集已在下载任务中");
                    }
                } else if (fFailed > 0) {
                    // 嗅探型源(WebView 解析)无法批量解析: 提示引导先播放目标集
                    AppBubble.toast(fFailed > 1
                            ? "所选剧集解析失败(" + fFailed + " 集),该源可能仅支持下载当前播放的剧集"
                            : "该集解析失败,该源可能仅支持下载当前播放的剧集,请先播放该集再试");
                } else {
                    AppBubble.toast("所选剧集地址无效,无法下载");
                }
            });
        });
    }

    /** 来源名(一级目录,如 饭太硬) */
    private String getDownloadSourceName() {
        String sourceName = "未分类";
        try {
            SourceBean sb = com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSource(vodInfo.sourceKey);
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
