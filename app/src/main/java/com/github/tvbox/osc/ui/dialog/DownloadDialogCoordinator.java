package com.github.tvbox.osc.ui.dialog;

import android.text.TextUtils;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.download.DownloadFacade;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.util.DownloadSeriesModel;
import com.github.tvbox.osc.util.EpisodeDownloadBatch;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lxj.xpopup.core.BasePopupView;

import java.util.List;
import java.util.Map;

/**
 * 详情页"选择下载剧集"弹窗协调器(自 DetailActivity 下载弹窗状态层等值搬移):
 * - 弹窗实例/防重入状态(底部弹窗 + 全屏右侧抽屉两条路径)
 * - 数据准备(选集副本 + 下载状态批量查询,后台线程)与 DownloadFacade 状态订阅
 * - 批量下载入口(Wi-Fi 确认 / 解析入队 / 结果文案)
 * 宿主能力(全屏退出时序/binding/跳转/播放器引用等)经 {@link Host} 注入,便于替换与测试。
 */
public final class DownloadDialogCoordinator {

    /** 宿主回调:DetailActivity 提供需与自身/View 强绑定的能力 */
    public interface Host {
        VodInfo currentVodInfo();

        /** 剧名兜底(入口传入名/tvName),详情接口无名称时下载命名用 */
        String downloadVodName();

        /** 当前预览播放器(可能为 null) */
        PlayFragment currentPlayFragment();

        /** 是否全屏预览中 */
        boolean isFullscreen();

        /** 横屏(右侧抽屉与底部弹窗分派;竖屏全窗也走底部) */
        boolean isLandscape();

        /** 全屏下退出全屏并延迟打开底部弹窗(宿主负责退全屏 + previewPlayer.postDelayed 时序) */
        void exitFullscreenThenOpenBottom();

        /** 排序(反转全集列表,与选集共用状态;返回是否翻转成功) */
        boolean sortSeries();

        /** 当前是否倒序 */
        boolean isSeriesReversed();

        /** 打开下载管理页(应用内跳转标记由宿主维护) */
        void openDownloadManager();

        void toast(String msg);

        void runOnUi(Runnable action);
    }

    private final android.content.Context context;
    private final Host host;

    /** 下载选择弹窗防重入:短时间内多次点"下载"只弹一个抽屉(延迟链 + 弹窗引用双重保护) */
    private boolean isDownloadDialogShowing = false;
    private BasePopupView mDownloadDialog = null;

    /** 下载状态变化监听(DownloadFacade 去抖 500ms 回调,主线程): 弹窗仍显示则重查并填充 */
    private final DownloadFacade.DownloadStatusListener downloadStatusListener = () -> {
        if (mDownloadDialog instanceof DownloadSeriesDialog && mDownloadDialog.isShow()) {
            refreshDownloadDialogStates();
        }
    };

    public DownloadDialogCoordinator(android.content.Context context, Host host) {
        this.context = context;
        this.host = host;
    }

    /**
     * 打开"选择下载剧集"弹窗(底部):网格多选 + 开始下载/下载管理。
     * 不要求必须先播放成功(未播放时所有集统一走后台解析);全屏时先退出全屏再弹窗,避免小屏叠加。
     */
    public void showDownloadSeriesDialog() {
        // 防重入:弹窗已在显示或延迟链已排队,直接忽略(详情页"下载"按钮连点也不会叠抽屉)
        if (isDownloadDialogShowing || mDownloadDialog != null && mDownloadDialog.isShow()) {
            return;
        }
        VodInfo vodInfo = host.currentVodInfo();
        if (vodInfo == null || vodInfo.seriesMap.get(vodInfo.playFlag) == null
                || vodInfo.seriesMap.get(vodInfo.playFlag).size() <= 0) {
            isDownloadDialogShowing = false;
            host.toast("资源异常,请稍后重试");
            return;
        }
        // 全屏播放下发起下载:先退出全屏回到详情页布局,再弹窗(手机小屏上避免播放器与抽屉叠加)
        if (host.isFullscreen()) {
            host.exitFullscreenThenOpenBottom();
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
        VodInfo vodInfo = host.currentVodInfo();
        if (vodInfo == null || vodInfo.seriesMap.get(vodInfo.playFlag) == null
                || vodInfo.seriesMap.get(vodInfo.playFlag).size() <= 0) {
            host.toast("资源异常,请稍后重试");
            return;
        }
        // 竖屏(含竖屏全窗):与设置/选集一致,改为底部弹窗(限高2/3),仅横屏才用右侧下载抽屉
        if (!host.isLandscape()) {
            showDownloadSeriesDialogInner();
            return;
        }
        // 立即弹抽屉(空数据 + loading),避免主线程构建选集/状态造成卡顿
        isDownloadDialogShowing = true;
        mDownloadDialog = DialogCoordinator.right(context,
                new DownloadSeriesRightDialog(context, actions, host::isSeriesReversed),
                360, false, downloadDialogCallback());
        mDownloadDialog.show();
        // 后台准备数据(选集副本 + 下载状态批量查询),完成后主线程填充抽屉
        final String sourceName = getDownloadSourceName();
        final String vodName = host.downloadVodName();
        SourceViewModel.spThreadPool.execute(() -> {
            List<VodInfo.VodSeries> copy = buildDownloadSeriesCopy();
            int[] states = buildDownloadStates(copy, sourceName, vodName);
            host.runOnUi(() -> {
                if (mDownloadDialog instanceof DownloadSeriesRightDialog && mDownloadDialog.isShow()) {
                    ((DownloadSeriesRightDialog) mDownloadDialog).setData(copy, states);
                }
            });
        });
    }

    /** 弹窗主体(退全屏完成后调用):立即弹窗(loading),数据后台准备完成后填充 */
    public void showDownloadSeriesDialogInner() {
        // 防重入:延迟回调已触发过(弹窗已创建),跳过重复创建
        if (isDownloadDialogShowing && mDownloadDialog != null && mDownloadDialog.isShow()) {
            return;
        }
        isDownloadDialogShowing = true;
        // 底部弹窗封顶 2/3 屏,列表吃满剩余+滚动,按钮固定底部;
        // 弹窗关闭(确认/取消/点外部/返回键)后清除防重入标记,允许再次打开
        mDownloadDialog = DialogCoordinator.bottomMaxHeight(context,
                new DownloadSeriesDialog(context, actions, host::isSeriesReversed),
                ScreenUtils.getScreenHeight() * 2 / 3,
                downloadDialogCallback());
        // 懒加载:进详情页不预载下载数据,抽屉打开(onShow)才查询;
        // 这里再监听拖拽“展开”动作:每次展开(拉到 70%)也重查一次下载状态,保证新数据即时可见
        if (mDownloadDialog instanceof DownloadSeriesDialog) {
            ((DownloadSeriesDialog) mDownloadDialog).setSheetActionListener(
                    new SheetResizeController.ActionListener() {
                        @Override public void onSheetExpanded() {
                            refreshDownloadDialogStates();
                        }
                        @Override public void onSheetCollapsed() { }
                        @Override public void onSheetClosed() { }
                    });
        }
        mDownloadDialog.show();
        // 实时刷新: 订阅下载状态变化(下载中进度/完成/失败 → 弹窗实时更新勾选态)
        DownloadFacade.get().register(downloadStatusListener);
        // 数据首次查询放到抽屉展开回调 onShow 内执行(每次打开必查一次)
    }

    /** 两个弹窗共享的动作回调(同时满足两个弹窗各自的 Listener 子接口) */
    private final class DialogActions
            implements DownloadSeriesDialog.OnDownloadActionListener,
            DownloadSeriesRightDialog.OnDownloadActionListener {
        @Override
        public void onStartDownload(List<VodInfo.VodSeries> selected) {
            startDownloads(selected);
        }

        @Override
        public void onOpenDownloadManager() {
            host.openDownloadManager();
        }

        @Override
        public void onSortSeries() {
            host.sortSeries(); // 与选集抽屉一致:反转全集列表(副本随正表重建)
            refreshDownloadDialogStates();
        }
    }

    private final DialogActions actions = new DialogActions();

    /** 下载弹窗统一关闭回调:关闭后清除防重入标记,允许再次打开 */
    private com.lxj.xpopup.interfaces.XPopupCallback downloadDialogCallback() {
        return new com.lxj.xpopup.interfaces.XPopupCallback() {
            @Override public void onCreated(BasePopupView popupView) { }
            @Override public void beforeShow(BasePopupView popupView) { }

            @Override
            public void onShow(BasePopupView popupView) {
                // 懒加载:抽屉真正展开后再查下载状态(每次打开都会查一次;进详情页不预载)
                if (popupView instanceof DownloadSeriesDialog) {
                    refreshDownloadDialogStates();
                }
            }

            @Override
            public void onDismiss(BasePopupView popupView) {
                isDownloadDialogShowing = false;
                mDownloadDialog = null;
                // 实时刷新: 弹窗关闭后注销下载状态订阅
                DownloadFacade.get().unregister(downloadStatusListener);
            }

            @Override public void beforeDismiss(BasePopupView popupView) { }
            @Override public boolean onBackPressed(BasePopupView popupView) { return false; }
            @Override public void onKeyBoardStateChanged(BasePopupView popupView, int height) { }
            @Override public void onDrag(BasePopupView popupView, int value, float fraction, boolean isScrollShadow) { }
            @Override public void onClickOutside(BasePopupView popupView) { }
        };
    }

    /** 构建下载选择弹窗的选集副本(带统一剧集标识),供底部弹窗与全屏右侧抽屉复用;
        保留弹窗当前已勾选的集(按集名匹配, 排序/刷新均不丢选中)。纯逻辑见 DownloadSeriesModel */
    private List<VodInfo.VodSeries> buildDownloadSeriesCopy() {
        List<VodInfo.VodSeries> shown = null;
        if (mDownloadDialog != null) {
            if (mDownloadDialog instanceof DownloadSeriesDialog) {
                shown = ((DownloadSeriesDialog) mDownloadDialog).getCurrentList();
            } else if (mDownloadDialog instanceof DownloadSeriesRightDialog) {
                shown = ((DownloadSeriesRightDialog) mDownloadDialog).getCurrentList();
            }
        }
        VodInfo vodInfo = host.currentVodInfo();
        java.util.Set<String> selectedNames = DownloadSeriesModel.collectSelectedNames(shown);
        List<VodInfo.VodSeries> master = vodInfo.seriesMap.get(vodInfo.playFlag);
        return DownloadSeriesModel.rebuildCopy(master, selectedNames,
                idx -> DownloadFacade.get().buildEpisodeId(vodInfo.sourceKey, vodInfo.id, vodInfo.playFlag, idx));
    }

    /** 构建下载状态数组:0=可下载,1=已下载,2=下载中/排队(批量查询,一次快照避免逐集拷贝任务列表) */
    private int[] buildDownloadStates(List<VodInfo.VodSeries> copy, String sourceName, String vodName) {
        return DownloadFacade.get().getEpisodeStates(
                DownloadSeriesModel.episodeIdsOf(copy), sourceName, vodName, DownloadSeriesModel.episodeNamesOf(copy));
    }

    /** 后台准备弹窗数据(选集副本 + 下载状态批量查询),完成后主线程填充弹窗 */
    private void refreshDownloadDialogStates() {
        final String sourceName = getDownloadSourceName();
        final String vodName = host.downloadVodName();
        SourceViewModel.spThreadPool.execute(() -> {
            // 先清理该剧的文件不存在档案(本地删文件后, 打开抽屉立即恢复"未下载")
            DownloadFacade.get().removeArchiveOrphansByVod(vodName, sourceName);
            List<VodInfo.VodSeries> copy = buildDownloadSeriesCopy();
            int[] states = buildDownloadStates(copy, sourceName, vodName);
            host.runOnUi(() -> {
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
            host.toast("请先选择要下载的剧集");
            return;
        }
        // 仅WiFi为硬性限制:当前为移动网络时不开始(与下载页"仅Wi-Fi"开关语义一致);
        // 需用流量请先在下载设置里改为"Wi-Fi+流量",或连接 Wi-Fi
        if (DownloadFacade.get().isWifiOnly() && DownloadFacade.get().isMobileNetwork()) {
            host.toast("已开启仅Wi-Fi下载。");
            return;
        }
        doStartDownloads(selected);
    }

    private void doStartDownloads(List<VodInfo.VodSeries> selected) {
        VodInfo vodInfo = host.currentVodInfo();
        List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap == null
                ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
        if (seriesList == null || seriesList.isEmpty()) {
            host.toast("资源异常,请稍后重试");
            return;
        }
        final String sourceName = getDownloadSourceName();
        final String vodName = host.downloadVodName();
        final String sourceKey = vodInfo.sourceKey;
        final String playFlag = vodInfo.playFlag;
        final String vodId = vodInfo.id;
        final int playIndex = vodInfo.playIndex;
        final String currentName = playIndex >= 0 && playIndex < seriesList.size()
                ? seriesList.get(playIndex).name : null;
        final PlayFragment playFragment = host.currentPlayFragment();
        // 当前播放视频的分辨率标签(由播放器画面尺寸归类),不可用则不拼分辨率
        final String resLabel = (playFragment != null && playFragment.getPlayer() != null)
                ? EpisodeDownloadBatch.resolutionLabel(playFragment.getPlayer().getVideoSize()) : null;
        android.util.Log.i("TVBox-Download", "startDownloads: 已选 " + selected.size() + " 集, 来源=" + sourceName
                + ", 剧名=" + vodName + ", 当前集=" + currentName + ", 分辨率=" + resLabel);
        host.toast("正在解析下载地址,请稍候...");
        // 用与播放一致的爬虫单线程池解析地址,避免 quickjs 并发;解析/入队/计数/文案收敛到 EpisodeDownloadBatch
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
            final String msg = EpisodeDownloadBatch.toastMessage(r);
            host.runOnUi(() -> {
                if (msg != null) {
                    host.toast(msg);
                } else {
                    host.toast("所选剧集地址无效,无法下载");
                }
            });
        });
    }

    /** 来源名(一级目录,如 饭太硬) */
    private String getDownloadSourceName() {
        String sourceName = "未分类";
        try {
            VodInfo vodInfo = host.currentVodInfo();
            SourceBean sb = SourceConfigProviders.get().getSource(vodInfo.sourceKey);
            if (sb != null && !TextUtils.isEmpty(sb.getName())) {
                sourceName = sb.getName();
            } else if (!TextUtils.isEmpty(vodInfo.sourceKey)) {
                sourceName = vodInfo.sourceKey;
            }
        } catch (Throwable ignored) {
        }
        return sourceName;
    }
}
