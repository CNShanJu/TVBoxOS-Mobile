package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.SPUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.databinding.FragmentDownloadBinding;
import com.github.tvbox.osc.constant.CacheConst;
import com.github.tvbox.osc.download.DownloadFacade;
import com.github.tvbox.osc.download.DownloadProgressEvent;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.activity.LocalPlayActivity;
import com.github.tvbox.osc.ui.adapter.LocalVideoAdapter;
import com.github.tvbox.osc.ui.dialog.ConfirmDialog;
import com.github.tvbox.osc.ui.dialog.DeleteDownloadDialog;
import com.github.tvbox.osc.util.DownloadConfig;
import com.github.tvbox.osc.util.DownloadCore;
import com.github.tvbox.osc.util.DownloadManager;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载页(三 box 布局):
 * <ul>
 *   <li>标题 box:DownloadActivity 标题栏(返回 + 下载管理 + 设置齿轮)</li>
 *   <li>下载相关 box:内容超出内部滚动。
 *       聚合根级为剧集网格(收藏页样式,点击进入剧集详情状态页,长按圆形多选);
 *       详情页内为 正在下载/下载完成 两个 tab(该剧任务列表 / 该剧文件列表)。</li>
 *   <li>控件 box:全选/删除/取消全选(聚合与详情共用),下载中详情另有全部暂停/全部开始;
 *       默认隐藏,长按多选时显示,吸底效果。</li>
 *   <li>显示内存和其他信息 box:底部,文字居中。</li>
 * </ul>
 * 路径展示(顶部):仅剧集详情状态页显示,只展示 片名 · 来源。
 */
public class DownloadFragment extends BaseVbFragment<FragmentDownloadBinding> {

    private static final int TAB_DOWNLOADING = 0;
    private static final int TAB_DONE = 1;
    /** 左滑露出的操作区宽度(暂停+删除两个按钮) */
    private static final int SWIPE_REVEAL_WIDTH_DP = 128;

    // ------------------------------------------------------------------
    // 聚合根级:剧集网格(收藏页样式,按 剧名+来源 分组)
    // ------------------------------------------------------------------
    private BaseQuickAdapter<DownloadGroup, BaseViewHolder> aggregateAdapter;
    private boolean aggSelectMode = false;
    /** 聚合多选选中的分组 key(来源+剧名) */
    private final Set<String> selectedAggKeys = new LinkedHashSet<>();

    // ------------------------------------------------------------------
    // 剧集详情状态页(点击聚合卡片进入):正在下载 / 下载完成 两个 tab
    // ------------------------------------------------------------------
    /** 当前剧集名(非空=详情页) */
    private String currentVodGroup = null;
    /** 当前剧集来源(路径展示用) */
    private String currentSourceName = null;
    private int currentTab = TAB_DOWNLOADING;
    /** 该剧正在下载任务列表 */
    private BaseQuickAdapter<DownloadTask, BaseViewHolder> downloadingAdapter;
    private LocalVideoAdapter localVideoAdapter;
    /** 详情页下载中多选 */
    private boolean dlSelectMode = false;
    private final Set<String> selectedTaskIds = new LinkedHashSet<>();
    private int mSelectedCount = 0;
    /** 窄屏左滑已滑出的任务 id(下载中条目操作区保持展开,刷新重建后恢复) */
    private final Set<String> swipedTaskIds = new LinkedHashSet<>();
    /** 左滑操作区宽度缓存(px,<0 表示未计算) */
    private float swipeRevealPxCache = -1;

    /** 聚合分组:key = 来源 + 剧名(同剧不同源各自成卡) */
    private static class DownloadGroup {
        String key;
        String name;        // 剧名
        String sourceName;
        List<DownloadTask> tasks = new ArrayList<>();
        /** 已完成集（档案表长期数据源） */
        List<com.github.tvbox.osc.download.ArchiveItem> doneItems = new ArrayList<>();
    }

    @Override
    protected void init() {
        // 聚合网格列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mBinding.rvAggregate.setLayoutManager(new GridLayoutManager(mContext, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)));
        mBinding.rvDownloading.setLayoutManager(new LinearLayoutManager(mContext));
        mBinding.rvDone.setLayoutManager(new LinearLayoutManager(mContext));

        mBinding.tvTabDownloading.setOnClickListener(v -> switchTab(TAB_DOWNLOADING));
        mBinding.tvTabDone.setOnClickListener(v -> switchTab(TAB_DONE));

        // 顶部路径展示:点按返回聚合根级
        mBinding.llNav.setOnClickListener(v -> onBackPressed());

        // 控件 box(吸底):全部暂停/全部开始(仅详情下载中多选)+ 全选/删除/取消全选
        mBinding.btnPauseAll.setOnClickListener(v -> pauseSelected());
        mBinding.btnStartAll.setOnClickListener(v -> startSelected());
        mBinding.tvAllCheck.setOnClickListener(v -> selectAllChecked());
        mBinding.tvCancelAllChecked.setOnClickListener(v -> cancelAllChecked());
        mBinding.tvDelete.setOnClickListener(v -> {
            if (currentVodGroup == null) {
                deleteSelectedAggregates();
            } else if (currentTab == TAB_DOWNLOADING) {
                deleteSelectedDownloading();
            } else {
                deleteChecked();
            }
        });

        // ------------------------------------------------------------------
        // 聚合根级:剧集网格(收藏页样式;海报本地文件,左上角来源徽标/圆形多选框)
        // ------------------------------------------------------------------
        aggregateAdapter = new BaseQuickAdapter<DownloadGroup, BaseViewHolder>(R.layout.item_download_vod_grid) {
            @Override
            protected void convert(@NonNull BaseViewHolder helper, DownloadGroup group) {
                bindPoster(helper.getView(R.id.ivThumb), group.name, picOf(group.name, group.sourceName));
                // 左上角:正常=来源徽标;多选=圆形勾选框(未选中外环,选中外环+内部填充圆,两圆有边距)
                boolean sel = aggSelectMode;
                helper.setVisible(R.id.layout_source, !sel);
                helper.setVisible(R.id.iv_select, sel);
                if (sel) {
                    ImageView ivSel = helper.getView(R.id.iv_select);
                    ivSel.setImageResource(selectedAggKeys.contains(group.key)
                            ? R.drawable.ic_select_checked : R.drawable.ic_select_ring);
                } else {
                    String src = group.sourceName == null || group.sourceName.isEmpty() ? "未知" : group.sourceName;
                    helper.setText(R.id.tv_source, src);
                }
                helper.setText(R.id.tv_name, group.name);
                // 聚合状态:任务数(未完成) + 已完成集数(档案表)
                int tasks = 0;
                int done = 0;
                for (DownloadTask t : group.tasks) {
                    if (t.state != DownloadTask.STATE_COMPLETED) tasks++;
                }
                for (com.github.tvbox.osc.download.ArchiveItem it : group.doneItems) {
                    if (it.savePath != null && new File(it.savePath).exists()) done++;
                }
                String note = tasks > 0 ? tasks + " 个任务" : "";
                if (done > 0) {
                    note = (note.isEmpty() ? "" : note + " · ") + "已完成 " + done + " 集";
                }
                helper.setText(R.id.tv_note, note);
            }
        };
        aggregateAdapter.setOnItemClickListener((adapter, view, position) -> {
            DownloadGroup g = aggregateAdapter.getItem(position);
            if (g == null) return;
            if (aggSelectMode) {
                toggleSet(selectedAggKeys, g.key);
                aggregateAdapter.notifyDataSetChanged();
                updateToolbar();
            } else {
                enterDetail(g);
            }
        });
        aggregateAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            DownloadGroup g = aggregateAdapter.getItem(position);
            if (g == null) return false;
            if (!aggSelectMode) aggSelectMode = true;
            selectedAggKeys.add(g.key);
            aggregateAdapter.notifyDataSetChanged();
            updateToolbar();
            return true;
        });
        mBinding.rvAggregate.setAdapter(aggregateAdapter);

        // ------------------------------------------------------------------
        // 剧集详情状态页:正在下载(该剧任务列表;多选只作用于选中条目)
        // ------------------------------------------------------------------
        downloadingAdapter = new BaseQuickAdapter<DownloadTask, BaseViewHolder>(R.layout.item_download_task_new) {
            @Override
            protected void convert(@NonNull BaseViewHolder helper, DownloadTask task) {
                // 封面图:本地海报文件(缺失显示搜索页同款占位图并懒拉取)
                bindPoster(helper.getView(R.id.iv_cover), vodNameOf(task), task.pic);
                // 行1:剧名 · 集名
                String name = task.vodName == null ? "" : task.vodName;
                String ep = task.episodeName;
                if (ep != null && !ep.isEmpty() && !ep.equals(task.vodName)) {
                    name = name + " · " + ep;
                }
                helper.setText(R.id.tv_name, name);
                // 行2:状态 · 进度
                String status;
                int statusColor;
                if (task.state == DownloadTask.STATE_FAILED) {
                    status = "失败";
                    statusColor = ContextCompat.getColor(mContext, R.color.red);
                } else if (task.state == DownloadTask.STATE_PAUSED) {
                    status = "已暂停";
                    statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                } else if (task.state == DownloadTask.STATE_NETWORK_PAUSED) {
                    status = "网络中断";
                    statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                } else if (task.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    status = "排队中";
                    statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                } else if (task.state == DownloadTask.STATE_WAITING) {
                    status = "等待中";
                    statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                } else if (task.state == DownloadTask.STATE_CANCELLED) {
                    status = "已取消";
                    statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                } else {
                    // 收尾阶段(message 带阶段+进度,如 "文件合并中(45%)"/"补片中(剩3片)"/"文件封装中"),前缀匹配
                    if (task.message != null && task.message.startsWith(DownloadManager.MSG_REPAIRING)) {
                        status = task.message;
                    } else if (task.message != null && task.message.startsWith(DownloadManager.MSG_VERIFYING)) {
                        status = task.message;
                    } else if (task.message != null && task.message.startsWith(DownloadManager.MSG_MERGING)) {
                        status = task.message;
                    } else if (task.message != null && task.message.startsWith(DownloadManager.MSG_REMUX)) {
                        status = task.message;
                    } else {
                        status = "下载中";
                    }
                    statusColor = ContextCompat.getColor(mContext, R.color.download_active);
                }
                TextView tvStatus = helper.getView(R.id.tv_status);
                // 收尾阶段 message 自带进度(合并x%/剩K片),不再追加整体百分比(此时进度恒为100%)
                boolean stageHasProgress = task.message != null
                        && (task.message.startsWith(DownloadManager.MSG_MERGING)
                        || task.message.startsWith(DownloadManager.MSG_REPAIRING));
                String statusText = stageHasProgress
                        ? status
                        : status + " · " + task.getProgressPercent() + "%";
                // 实时网速:仅真正下载中显示,放在"下载中 xx%"后面(大小行不显示,避免被挤压)
                if (task.state == DownloadTask.STATE_DOWNLOADING
                        && task.message != null
                        && !task.message.startsWith(DownloadManager.MSG_VERIFYING)
                        && !task.message.startsWith(DownloadManager.MSG_MERGING)
                        && !task.message.startsWith(DownloadManager.MSG_REMUX)
                        && !task.message.startsWith(DownloadManager.MSG_REPAIRING)
                        && task.speed > 0) {
                    statusText += " · " + formatSpeed(task.speed);
                }
                tvStatus.setText(statusText);
                tvStatus.setTextColor(statusColor);
                // 行3:大小 · 速度
                helper.setText(R.id.tv_size_speed, buildPercentText(task));
                // 行4:来源 · 存储位置
                String src = task.sourceName == null ? "" : task.sourceName;
                helper.setText(R.id.tv_source, "来源 " + (src.isEmpty() ? "未知" : src));
                // 操作按钮:统一左滑滑出(不区分大小屏,宽屏同样滑出右侧 暂停/删除 操作区)
                View swipeBehind = helper.getView(R.id.swipe_behind);
                View front = helper.getView(R.id.item_front);
                if (dlSelectMode) {
                    swipeBehind.setVisibility(View.GONE);
                    front.setTranslationX(0);
                    front.setOnTouchListener(null);
                } else {
                    swipeBehind.setVisibility(View.VISIBLE);
                    TextView btnSwipe = helper.getView(R.id.btn_swipe_pause);
                    if (task.state == DownloadTask.STATE_PAUSED) {
                        btnSwipe.setText("继续");
                    } else if (task.state == DownloadTask.STATE_FAILED) {
                        btnSwipe.setText("重试");
                    } else {
                        btnSwipe.setText("暂停");
                    }
                    front.setTranslationX(swipedTaskIds.contains(task.id) ? -swipeRevealPx() : 0);
                    attachSwipe(front, task);
                    helper.addOnClickListener(R.id.btn_swipe_pause, R.id.btn_swipe_delete);
                }
                // 多选勾选框:仅长按多选时显示
                CheckBox cb = helper.getView(R.id.cb);
                cb.setVisibility(dlSelectMode ? View.VISIBLE : View.GONE);
                cb.setChecked(selectedTaskIds.contains(task.id));
            }
        };
        downloadingAdapter.setOnItemClickListener((adapter, view, position) -> {
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return;
            DownloadTask t = data.get(position);
            if (dlSelectMode) {
                toggleSet(selectedTaskIds, t.id);
                downloadingAdapter.notifyDataSetChanged();
                updateToolbar();
            } else {
                // 点击切换:暂停/失败 -> 开始下载;下载中/等待/排队 -> 暂停
                toggleTaskPlay(t);
            }
        });
        downloadingAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return false;
            DownloadTask t = data.get(position);
            if (!dlSelectMode) dlSelectMode = true;
            selectedTaskIds.add(t.id);
            downloadingAdapter.notifyDataSetChanged();
            updateToolbar();
            return true;
        });
        downloadingAdapter.setOnItemChildClickListener((adapter, view, position) -> {
            if (dlSelectMode) return;
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return;
            DownloadTask t = data.get(position);
            int id = view.getId();
            if (id == R.id.btn_swipe_pause) {
                if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
                    DownloadFacade.get().resume(t);
                } else {
                    DownloadFacade.get().pause(t);
                }
                // 左滑操作区点击后收起
                swipedTaskIds.remove(t.id);
                downloadingAdapter.notifyDataSetChanged();
            } else if (id == R.id.btn_swipe_delete) {
                // 删除:确认后记录+过程文件一起删,收起并刷新
                swipedTaskIds.remove(t.id);
                downloadingAdapter.notifyDataSetChanged();
                new XPopup.Builder(mContext)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asCustom(new ConfirmDialog(mContext,
                                "删除任务",
                                "将删除该任务及其未完成的下载文件,确定?",
                                "删除",
                                () -> {
                                    DownloadCore.remove(t, true);
                                    refresh();
                                }))
                        .show();
            }
        });
        mBinding.rvDownloading.setAdapter(downloadingAdapter);

        // ------------------------------------------------------------------
        // 剧集详情状态页:下载完成(该剧文件列表,复用本地视频 adapter)
        // ------------------------------------------------------------------
        localVideoAdapter = new LocalVideoAdapter();
        localVideoAdapter.setOnItemClickListener((adapter, view, position) -> {
            VideoInfo info = localVideoAdapter.getItem(position);
            if (info == null) return;
            if (localVideoAdapter.isSelectMode()) {
                // 走适配器勾选入口:计数增量维护 + 只刷新该行
                localVideoAdapter.setItemChecked(info, !info.isChecked());
            } else {
                playFile(info);
            }
        });
        localVideoAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            VideoInfo info = localVideoAdapter.getItem(position);
            if (info != null) {
                if (!localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(true);
                localVideoAdapter.setItemChecked(info, true);
                updateToolbar();
            }
            return true;
        });
        localVideoAdapter.setOnSelectCountListener(count -> {
            mSelectedCount = count;
            updateToolbar();
        });
        mBinding.rvDone.setAdapter(localVideoAdapter);

        EventBus.getDefault().register(this);
        refresh();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    /** 返回键处理:详情页先退多选再退聚合,聚合根级退多选后返回 false */
    public boolean onBackPressed() {
        if (currentVodGroup != null) { // 剧集详情状态页
            if (currentTab == TAB_DOWNLOADING && dlSelectMode) {
                if (!selectedTaskIds.isEmpty()) {
                    cancelAllChecked();
                } else {
                    dlSelectMode = false;
                    downloadingAdapter.notifyDataSetChanged();
                    updateToolbar();
                }
                return true;
            }
            if (currentTab == TAB_DONE && localVideoAdapter.isSelectMode()) {
                if (mSelectedCount > 0) {
                    cancelAllChecked();
                } else {
                    localVideoAdapter.setSelectMode(false);
                    updateToolbar();
                }
                return true;
            }
            backToAggregate();
            return true;
        }
        // 聚合根级
        if (aggSelectMode) {
            if (!selectedAggKeys.isEmpty()) {
                cancelAllChecked();
            } else {
                aggSelectMode = false;
                aggregateAdapter.notifyDataSetChanged();
                updateToolbar();
            }
            return true;
        }
        return false;
    }

    /** 结构性变更(新增/删除/状态机切换/批量变更/初始化) → 全量重建(仅低频发生,频率可接受) */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        refresh();
    }

    /**
     * 任务级进度事件(带任务 id,高频,由 DownloadManager.flushProgress 节流后广播):
     * 只对该任务在"正在下载"列表中的可见条目做局部 notifyItemChanged,不做全量重建——
     * 避免每次 HLS 分片进度都重新聚合全部任务/检查文件/setNewData(任务 B)。
     * UI 状态(展开/多选/勾选/滑动)均由任务对象与 adapter 字段驱动,单行重绑不会丢状态。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadProgressEvent(DownloadProgressEvent event) {
        if (event == null || event.taskId == null || event.taskId.isEmpty()) return;
        // 底部"可用空间/设置"条:进度期间磁盘占用持续变化,轻量刷新(StatFs 开销极小)
        updateStorageText();
        // 聚合根级卡片只展示 任务数/已完成集数,不含进度百分比/网速 → 进度事件无需刷聚合
        if (currentVodGroup == null) return;
        // 只有"正在下载"tab 的该剧任务行展示进度
        if (currentTab != TAB_DOWNLOADING) return;
        List<DownloadTask> data = downloadingAdapter.getData();
        if (data == null || data.isEmpty()) return;
        for (int i = 0; i < data.size(); i++) {
            DownloadTask t = data.get(i);
            if (t != null && event.taskId.equals(t.id)) {
                downloadingAdapter.notifyItemChanged(i);
                return;
            }
        }
        // 任务不属于当前剧集分组(未找到行)或已不在该列表:无需任何操作
    }

    private void switchTab(int tab) {
        currentTab = tab;
        applyTabStyle();
        // 切 tab 时退出两页的多选
        exitDlSelectMode();
        if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        updateNavBar();
    }

    /** 刷新 tab 行的选中样式(加粗+主题色) */
    private void applyTabStyle() {
        boolean downloading = currentTab == TAB_DOWNLOADING;
        mBinding.tvTabDownloading.setTextColor(getResources().getColor(downloading ? R.color.colorPrimary : R.color.text_sub_foreground));
        mBinding.tvTabDownloading.setTextSize(16);
        mBinding.tvTabDownloading.setTypeface(null, downloading ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        mBinding.tvTabDone.setTextColor(getResources().getColor(downloading ? R.color.text_sub_foreground : R.color.colorPrimary));
        mBinding.tvTabDone.setTextSize(16);
        mBinding.tvTabDone.setTypeface(null, downloading ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    }

    private void refresh() {
        refreshAggregate();
        refreshDetailLists();
        updateTabCounts();
        updateNavBar();
        updateToolbar();
    }

    /** 刷新聚合网格(按 剧名+来源 分组,含下载中与已完成);无数据时展示空态 */
    private void refreshAggregate() {
        List<DownloadGroup> groups = buildAggregateGroups();
        aggregateAdapter.setNewData(groups);
        boolean empty = groups == null || groups.isEmpty();
        mBinding.rvAggregate.setVisibility(empty ? View.GONE : View.VISIBLE);
        mBinding.llAggregateEmpty.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 当前剧集下载完成列表的数据指纹(路径+大小),内容未变时跳过重建,避免下载进度刷新打断长按多选 */
    private String doneListSignature = "";

    /** 刷新详情页两个 tab 的列表;该剧已被删光时自动退回聚合根级 */
    private void refreshDetailLists() {
        if (currentVodGroup == null) return;
        if (!isGroupPresent(currentVodGroup, currentSourceName)) {
            backToAggregate();
            return;
        }
        downloadingAdapter.setNewData(tasksInGroup(currentVodGroup, currentSourceName));
        List<VideoInfo> files = buildFolderVideosFromRecords(currentVodGroup, currentSourceName);
        String sig = currentVodGroup + "\u0001" + (currentSourceName == null ? "" : currentSourceName)
                + "\u0001" + doneSignature(files);
        if (sig.equals(doneListSignature)) return; // 内容未变:跳过重建(保持多选与滚动状态)
        doneListSignature = sig;        // 重建时保留多选勾选(按路径恢复),且不重置多选模式,避免刷新打断"下载完成"长按多选
        Set<String> checked = new LinkedHashSet<>();
        if (localVideoAdapter.isSelectMode()) {
            for (VideoInfo v : localVideoAdapter.getData()) {
                if (v.isChecked()) checked.add(v.getPath());
            }
        }
        if (!checked.isEmpty()) {
            for (VideoInfo v : files) {
                if (checked.contains(v.getPath())) v.setChecked(true);
            }
        }
        localVideoAdapter.setNewData(files);
        // 数据重建后同步一次选中计数(BRVAH notifyDataSetChanged 为 final,列表重建无法被计数拦截)
        localVideoAdapter.syncSelection();
    }

    /** 下载完成列表指纹:文件路径 + 大小 */
    private static String doneSignature(List<VideoInfo> files) {
        StringBuilder sb = new StringBuilder();
        for (VideoInfo v : files) {
            sb.append(v.getPath()).append('|').append(v.getSize()).append(';');
        }
        return sb.toString();
    }

    /** 刷新剧集展示插槽:聚合组件(剧集网格) / 详情组件(导航+tab+两列表) 二选一换入;
     * 聚合展示时中间 box 移除背景色(收藏页风格);详情页无下载中任务时隐藏 tab 直接展示下载完成 */
    private void updateNavBar() {
        boolean inDetail = currentVodGroup != null;
        // 中间 box 背景:聚合根级透明,详情页恢复卡片背景
        mBinding.llDownloadBox.setBackgroundResource(inDetail ? R.drawable.bg_large_round_gray : 0);
        // 聚合组件显隐:非详情时由 refreshAggregate 按数据是否为空设置列表/空态,此处仅处理详情态
        if (inDetail) {
            mBinding.rvAggregate.setVisibility(View.GONE);
            mBinding.llAggregateEmpty.getRoot().setVisibility(View.GONE);
        }
        mBinding.llDetail.setVisibility(inDetail ? View.VISIBLE : View.GONE);
        if (inDetail) {
            String src = currentSourceName == null || currentSourceName.isEmpty() ? "未知" : currentSourceName;
            mBinding.tvNavPath.setText(src + " · " + currentVodGroup);
            // 该剧没有下载中的任务:隐藏 正在下载/下载完成 tab,直接展示下载完成内容
            boolean hasActive = hasInProgressInGroup();
            mBinding.llTabs.setVisibility(hasActive ? View.VISIBLE : View.GONE);
            if (!hasActive && currentTab != TAB_DONE) {
                currentTab = TAB_DONE;
                applyTabStyle();
            }
            // 列表与空态联动:当前 tab 对应列表为空时展示空态(空态视图与列表二选一)
            boolean dlEmpty = downloadingAdapter.getData() == null || downloadingAdapter.getData().isEmpty();
            boolean doneEmpty = localVideoAdapter.getData() == null || localVideoAdapter.getData().isEmpty();
            mBinding.rvDownloading.setVisibility(inDetail && currentTab == TAB_DOWNLOADING && !dlEmpty ? View.VISIBLE : View.GONE);
            mBinding.llDownloadingEmpty.getRoot().setVisibility(inDetail && currentTab == TAB_DOWNLOADING && dlEmpty ? View.VISIBLE : View.GONE);
            mBinding.rvDone.setVisibility(inDetail && currentTab == TAB_DONE && !doneEmpty ? View.VISIBLE : View.GONE);
            mBinding.llDoneEmpty.getRoot().setVisibility(inDetail && currentTab == TAB_DONE && doneEmpty ? View.VISIBLE : View.GONE);
        }
        updateToolbar();
        updateStorageText();
    }

    /** 当前剧集是否存在未完成(下载中/等待/暂停/失败)的任务 */
    private boolean hasInProgressInGroup() {
        if (currentVodGroup == null) return false;
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED && inGroup(t, currentVodGroup, currentSourceName)) {
                return true;
            }
        }
        return false;
    }

    /** 刷新底部"显示内存和其他信息"条(居中) */
    private void updateStorageText() {
        try {
            File dir = DownloadConfig.getSaveDir();
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long free = stat.getAvailableBytes();
            String wifi = DownloadConfig.isWifiOnly() ? "仅Wi-Fi" : "Wi-Fi+流量";
            mBinding.tvStorage.setText("可用 " + formatSize(free) + "  |  " + wifi + " · 并发 " + DownloadConfig.getMaxConcurrent());
        } catch (Throwable th) {
            mBinding.tvStorage.setText("");
        }
    }

    /** 详情页 Tab 数量角标(该剧维度):正在下载 (N)  已完成 (M, 档案表) */
    private void updateTabCounts() {
        if (currentVodGroup == null) return;
        int downloading = 0;
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state == DownloadTask.STATE_COMPLETED) continue;
            if (inGroup(t, currentVodGroup, currentSourceName)) downloading++;
        }
        int done = 0;
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadArchive.get().queryByVod(currentVodGroup, currentSourceName)) {
            if (it.savePath != null && new File(it.savePath).exists()) done++;
        }
        mBinding.tvTabDownloading.setText(downloading > 0 ? "正在下载 (" + downloading + ")" : "正在下载");
        mBinding.tvTabDone.setText(done > 0 ? "下载完成 (" + done + ")" : "下载完成");
    }

    // ------------------------------------------------------------------
    // 聚合根级 与 详情页 切换
    // ------------------------------------------------------------------

    private void enterDetail(DownloadGroup g) {
        currentVodGroup = g.name;
        currentSourceName = g.sourceName;
        doneListSignature = ""; // 进入新剧集,失效上一剧的列表指纹
        exitAggSelectMode();
        exitDlSelectMode();
        if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        switchTab(TAB_DOWNLOADING);
        refresh();
    }

    private void backToAggregate() {
        currentVodGroup = null;
        currentSourceName = null;
        doneListSignature = "";
        exitAggSelectMode();
        exitDlSelectMode();
        if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        refresh();
    }

    private void exitAggSelectMode() {
        aggSelectMode = false;
        selectedAggKeys.clear();
        aggregateAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    private void exitDlSelectMode() {
        dlSelectMode = false;
        selectedTaskIds.clear();
        downloadingAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    // ------------------------------------------------------------------
    // 控件 box(吸底):全选/删除/取消全选 + 全部暂停/全部开始(仅详情下载中)
    // ------------------------------------------------------------------

    /** 刷新控件 box:可见性、全部暂停/全部开始可用态、删除按钮颜色(启用=红,深浅主题均清晰) */
    private void updateToolbar() {
        boolean inDetail = currentVodGroup != null;
        boolean aggSel = !inDetail && aggSelectMode;
        boolean dlSel = inDetail && currentTab == TAB_DOWNLOADING && dlSelectMode;
        boolean doneSel = inDetail && currentTab == TAB_DONE && localVideoAdapter.isSelectMode();
        boolean show = aggSel || dlSel || doneSel;
        mBinding.llMenu.setVisibility(show ? View.VISIBLE : View.GONE);
        mBinding.llToolbarActions.setVisibility(dlSel ? View.VISIBLE : View.GONE);
        if (dlSel) {
            boolean canPause = false;
            boolean canStart = false;
            for (DownloadTask t : currentDlScope()) {
                if (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_WAITING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    canPause = true;
                } else if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
                    canStart = true;
                }
            }
            mBinding.btnPauseAll.setEnabled(canPause);
            mBinding.btnStartAll.setEnabled(canStart);
        }
        boolean hasSel = aggSel ? !selectedAggKeys.isEmpty()
                : dlSel ? !currentDlScope().isEmpty()
                : doneSel && mSelectedCount > 0;
        mBinding.tvDelete.setEnabled(hasSel);
        mBinding.tvDelete.setTextColor(ContextCompat.getColor(mContext,
                hasSel ? R.color.red : R.color.disable_text));
    }

    /** 全选:聚合=全部剧集,详情下载中=该剧全部任务,详情下载完成=全部文件 */
    private void selectAllChecked() {
        if (currentVodGroup == null) {
            for (DownloadGroup g : aggregateAdapter.getData()) selectedAggKeys.add(g.key);
            aggregateAdapter.notifyDataSetChanged();
        } else if (currentTab == TAB_DOWNLOADING) {
            for (DownloadTask t : tasksInGroup(currentVodGroup, currentSourceName)) selectedTaskIds.add(t.id);
            downloadingAdapter.notifyDataSetChanged();
        } else {
            localVideoAdapter.selectAll();
        }
        updateToolbar();
    }

    /** 取消全选:清空当前层级的选择 */
    private void cancelAllChecked() {
        if (currentVodGroup == null) {
            selectedAggKeys.clear();
            aggregateAdapter.notifyDataSetChanged();
        } else if (currentTab == TAB_DOWNLOADING) {
            selectedTaskIds.clear();
            downloadingAdapter.notifyDataSetChanged();
        } else {
            localVideoAdapter.cancelAllSelection();
        }
        updateToolbar();
    }

    /** 当前详情下载中多选作用域:该剧内选中的任务 */
    private List<DownloadTask> currentDlScope() {
        List<DownloadTask> scope = new ArrayList<>();
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state == DownloadTask.STATE_COMPLETED) continue;
            if (inGroup(t, currentVodGroup, currentSourceName) && selectedTaskIds.contains(t.id)) {
                scope.add(t);
            }
        }
        return scope;
    }

    /** 全部暂停(仅详情下载中多选):作用于作用域内下载中/等待中/排队中的任务 */
    private void pauseSelected() {
        List<DownloadTask> scope = currentDlScope();
        int n = 0;
        for (DownloadTask t : scope) {
            if (t.state == DownloadTask.STATE_DOWNLOADING
                    || t.state == DownloadTask.STATE_WAITING
                    || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                DownloadFacade.get().pause(t);
                n++;
            }
        }
        if (n > 0) AppBubble.toast("已暂停 " + n + " 个任务");
    }

    /** 全部开始(仅详情下载中多选):作用于作用域内已暂停/失败的任务 */
    private void startSelected() {
        List<DownloadTask> scope = currentDlScope();
        int n = 0;
        for (DownloadTask t : scope) {
            if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
                DownloadFacade.get().resume(t);
                n++;
            }
        }
        if (n > 0) AppBubble.toast("已开始 " + n + " 个任务");
    }

    /**
     * 删除选中的聚合剧集(带"同时删除本地文件"勾选框):
     * 勾选 = 下载完成 + 下载中的记录与本地文件一起删(整个文件夹);
     * 不勾选 = 只删下载中的任务(记录+过程文件),保留已完成记录与文件。
     */
    private void deleteSelectedAggregates() {
        List<DownloadGroup> sel = new ArrayList<>();
        for (DownloadGroup g : aggregateAdapter.getData()) {
            if (selectedAggKeys.contains(g.key)) sel.add(g);
        }
        if (sel.isEmpty()) return;
        new XPopup.Builder(mContext)
                .isDarkTheme(Utils.isDarkTheme())
                .asCustom(new DeleteDownloadDialog(mContext, deleteFiles -> {
                    for (DownloadGroup g : sel) {
                        if (deleteFiles) {
                            // 勾选:全部删除,记录 + 整个文件夹(该来源下该剧名目录)。
                            // 注意: 聚合组的 g.tasks 只含运行态任务(COMPLETED 被分组逻辑跳过,已完成集在
                            // g.doneItems)——目录必须从两边收集,否则"全剧已下载完"的组 g.tasks 为空,
                            // 文件夹永远删不掉, 只清档案记录而本地文件残留。
                            Set<File> dirs = new LinkedHashSet<>();
                            for (DownloadTask t : g.tasks) {
                                if (t.savePath != null) {
                                    File p = new File(t.savePath).getParentFile();
                                    if (p != null) dirs.add(p);
                                }
                                DownloadCore.remove(t, false);
                            }
                            for (com.github.tvbox.osc.download.ArchiveItem it : g.doneItems) {
                                if (it.savePath != null) {
                                    File p = new File(it.savePath).getParentFile();
                                    if (p != null) dirs.add(p);
                                }
                            }
                            for (File d : dirs) {
                                deleteRecursive(d);
                            }
                            // 已完成集(档案表): 文件已随文件夹删除, 同步删档案记录;
                            // deleteFile=true 兜底: 文件夹删除失败(权限/占用)时再尝试删单个文件
                            for (com.github.tvbox.osc.download.ArchiveItem it : g.doneItems) {
                                if (it.episodeId != null) {
                                    com.github.tvbox.osc.download.DownloadArchive.get().remove(it.episodeId, true);
                                }
                            }
                        } else {
                            // 不勾选:只删下载中的任务(记录 + 过程文件),已完成记录与文件保留
                            for (DownloadTask t : g.tasks) {
                                if (t.state != DownloadTask.STATE_COMPLETED) {
                                    DownloadCore.remove(t, true);
                                }
                            }
                        }
                    }
                    exitAggSelectMode();
                    refresh();
                }))
                .show();
    }

    /** 删除选中的下载中任务(详情页):确认后记录+过程文件一起删,并清理可能变空的剧名目录 */
    private void deleteSelectedDownloading() {
        List<DownloadTask> scope = new ArrayList<>(currentDlScope());
        if (scope.isEmpty()) return;
        new XPopup.Builder(mContext)
                .isDarkTheme(Utils.isDarkTheme())
                .asCustom(new ConfirmDialog(mContext,
                        "删除任务",
                        "确定删除选中的 " + scope.size() + " 个任务?",
                        "删除",
                        () -> {
                            Set<File> dirs = new LinkedHashSet<>();
                            for (DownloadTask t : scope) {
                                if (t.savePath != null) {
                                    File p = new File(t.savePath).getParentFile();
                                    if (p != null) dirs.add(p);
                                }
                                DownloadCore.remove(t, true);
                            }
                            // 组内任务清空后,删掉空的剧名目录
                            for (File d : dirs) {
                                File[] fs = d.listFiles();
                                if (fs != null && fs.length == 0) d.delete();
                            }
                            exitDlSelectMode();
                            refresh();
                        }))
                .show();
    }

    /** 删除选中的下载完成文件(详情页):确认后按"同时删除本地文件"决定 */
    private void deleteChecked() {
        new XPopup.Builder(mContext)
                .isDarkTheme(Utils.isDarkTheme())
                .asCustom(new DeleteDownloadDialog(mContext, deleteFiles -> {
                    List<VideoInfo> data = new ArrayList<>(localVideoAdapter.getData());
                    for (VideoInfo item : data) {
                        if (item.isChecked()) {
                            removeTaskAndFile(item.getPath(), deleteFiles);
                        }
                    }
                    localVideoAdapter.setSelectMode(false);
                    updateToolbar();
                    refresh();
                }))
                .show();
    }

    /**
     * 删除下载:找到对应任务记录移除;deleteFiles=true 连本地文件一起删,false 只删记录保留文件。
     * 仅在用户确认删除后调用(DeleteDownloadDialog 确认),确认前不动任何数据/文件。
     */
    private void removeTaskAndFile(String path, boolean deleteFiles) {
        for (DownloadTask t : DownloadCore.getTasks()) {
            if (t.savePath != null && t.savePath.equals(path)) {
                DownloadCore.remove(t, deleteFiles);
                return;
            }
        }
        // 完成项可能只在档案表(任务记录已清理): 同步删档案(deleteFiles=true 连文件一起删)
        com.github.tvbox.osc.download.ArchiveItem it =
                com.github.tvbox.osc.download.DownloadArchive.get().findByPath(path);
        if (it != null) {
            com.github.tvbox.osc.download.DownloadArchive.get().remove(it.episodeId, deleteFiles);
            return;
        }
        if (deleteFiles) {
            File f = new File(path);
            if (f.exists()) f.delete();
        }
    }

    // ------------------------------------------------------------------
    // 数据构建
    // ------------------------------------------------------------------

    /** 聚合分组:所有下载记录(未完成 + 已完成且文件存在),按 剧名+来源 分组,组序按最早加入时间 */
    private List<DownloadGroup> buildAggregateGroups() {
        Map<String, DownloadGroup> map = new LinkedHashMap<>();
        Map<String, Long> firstTime = new LinkedHashMap<>();
        // 下载中/未完成任务（运行态）
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state == DownloadTask.STATE_COMPLETED) continue; // 已完成走档案
            String src = t.sourceName == null ? "" : t.sourceName;
            String name = vodNameOf(t);
            String key = src + "\u0001" + name;
            DownloadGroup g = map.get(key);
            if (g == null) {
                g = new DownloadGroup();
                g.key = key;
                g.name = name;
                g.sourceName = src;
                map.put(key, g);
                firstTime.put(key, t.createTime);
            }
            g.tasks.add(t);
        }
        // 已完成集（档案表长期数据源）
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadArchive.get().getAll()) {
            if (it.savePath == null || !new File(it.savePath).exists()) continue;
            String src = it.sourceName == null ? "" : it.sourceName;
            String name = it.vodName == null ? "" : it.vodName;
            String key = src + "\u0001" + name;
            DownloadGroup g = map.get(key);
            if (g == null) {
                g = new DownloadGroup();
                g.key = key;
                g.name = name;
                g.sourceName = src;
                map.put(key, g);
                firstTime.put(key, it.downloadTime);
            }
            g.doneItems.add(it);
        }
        List<DownloadGroup> groups = new ArrayList<>(map.values());
        groups.sort(Comparator.comparingLong(g -> firstTime.get(g.key)));
        return groups;
    }

    /** 该剧(剧名+来源)是否仍存在于聚合(用于详情页自动退回):有下载中任务 或 有已完成档案 */
    private boolean isGroupPresent(String name, String source) {
        String wantSrc = source == null ? "" : source;
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (!name.equals(vodNameOf(t))) continue;
            if (!wantSrc.equals(t.sourceName == null ? "" : t.sourceName)) continue;
            if (t.state != DownloadTask.STATE_COMPLETED) return true;
        }
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadArchive.get().getAll()) {
            if (!name.equals(it.vodName)) continue;
            if (!wantSrc.equals(it.sourceName == null ? "" : it.sourceName)) continue;
            if (it.savePath != null && new File(it.savePath).exists()) return true;
        }
        return false;
    }

    /** 该剧未完成的任务列表(按加入时间排序) */
    private List<DownloadTask> tasksInGroup(String vodName, String sourceName) {
        List<DownloadTask> list = new ArrayList<>();
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED && inGroup(t, vodName, sourceName)) {
                list.add(t);
            }
        }
        list.sort(Comparator.comparingLong(t -> t.createTime));
        return list;
    }

    /** 该剧(剧名+来源)下已完成且文件存在的视频列表(档案表驱动,按文件名排序) */
    private List<VideoInfo> buildFolderVideosFromRecords(String vodName, String sourceName) {
        List<VideoInfo> videos = new ArrayList<>();
        // 播放过的集索引集合(SP key=sourceKey|vodId);episodeId 取第一条推导
        Set<String> played = null;
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadArchive.get().queryByVod(vodName, sourceName)) {
            if (played == null) played = playedIndicesOf(it.episodeId);
            if (it.savePath == null) continue;
            File f = new File(it.savePath);
            if (!f.exists()) continue;
            VideoInfo info = new VideoInfo();
            info.setPath(f.getAbsolutePath());
            info.setDisplayName(f.getName());
            info.setTitle(f.getName());
            info.setSize(f.length());
            info.setEpisodeId(it.episodeId); // 统一剧集标识:回跳详情页/本地播放联动用
            info.setVodName(vodName); // 主标题:剧名
            // 集数名(第1集_720P 去清晰度后缀;无则按索引推导 第N集;仍无则文件名)
            info.setEpisodeName(episodeTitleOf(it, f.getName()));
            // 来源名 + 清晰度(从集数名/文件名解析 480P/720P...)
            info.setSourceName(it.sourceName);
            info.setResolution(resolutionOf(it, f.getName()));
            // 播放过的剧集标题置灰
            if (played != null && it.episodeId != null) {
                String idx = lastSegment(it.episodeId);
                info.setPlayed(idx != null && played.contains(idx));
            }
            videos.add(info);
        }
        videos.sort(Comparator.comparing(VideoInfo::getDisplayName));
        return videos;
    }

    /**
     * 播放过的集索引集合:SP key=sourceKey|vodId(由 episodeId 前两段推导);
     * 无 SP 记录时回填 Room 观看记录里的"上次看到"集(老数据无播放记录);结果按 videoId 缓存。
     */
    private final Map<String, Set<String>> playedCache = new HashMap<>();

    private Set<String> playedIndicesOf(String episodeId) {
        if (episodeId == null) return null;
        int first = episodeId.indexOf('|');
        if (first < 0) return null;
        int second = episodeId.indexOf('|', first + 1);
        if (second < 0) return null;
        String videoId = episodeId.substring(0, second);
        Set<String> cached = playedCache.get(videoId);
        if (cached != null) return cached;
        Set<String> set = SPUtils.getInstance(CacheConst.VIDEO_PLAYED_SP).getStringSet(videoId, null);
        if (set == null) set = new LinkedHashSet<>();
        // 历史回填:观看记录里"上次看到"的集也算播放过(功能上线前的老数据)
        try {
            com.github.tvbox.osc.bean.VodInfo rec = com.github.tvbox.osc.cache.RoomDataManger.getVodInfo(
                    episodeId.substring(0, first), episodeId.substring(first + 1, second));
            if (rec != null && rec.playIndex >= 0) set.add(String.valueOf(rec.playIndex));
        } catch (Throwable ignored) {
        }
        playedCache.put(videoId, set);
        return set;
    }

    /** episodeId 最后一段(playIndex) */
    private static String lastSegment(String episodeId) {
        int i = episodeId.lastIndexOf('|');
        return i >= 0 ? episodeId.substring(i + 1) : null;
    }

    /** 集数名:优先档案 episodeName(去清晰度后缀),空则按索引推导 第N集,再空用文件名(去扩展名) */
    private static String episodeTitleOf(com.github.tvbox.osc.download.ArchiveItem it, String fileName) {
        String label = it.episodeName;
        if (TextUtils.isEmpty(label) && it.episodeId != null) {
            String idx = lastSegment(it.episodeId);
            if (idx != null && idx.matches("\\d+")) label = "第" + idx + "集";
        }
        if (TextUtils.isEmpty(label)) {
            label = fileName;
            int dot = label.lastIndexOf('.');
            if (dot > 0) label = label.substring(0, dot);
        }
        return label.replaceAll("(?i)_?(\\d{3,4}p|4k|2k|8k|sd|hd|fhd|uhd)$", "").trim();
    }

    /** 清晰度:从集数名/文件名解析最后一个 480P/720P/1080P/4K... 段;无则 null */
    private static String resolutionOf(com.github.tvbox.osc.download.ArchiveItem it, String fileName) {
        String s = TextUtils.isEmpty(it.episodeName) ? fileName : it.episodeName;
        if (TextUtils.isEmpty(s)) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(\\d{3,4}p|4k|2k|8k|sd|hd|fhd|uhd)").matcher(s);
        String last = null;
        while (m.find()) last = m.group(1);
        return last == null ? null : last.toUpperCase();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 剧名(兼容旧字段 groupName) */
    private String vodNameOf(DownloadTask t) {
        return t.vodName == null ? t.groupName : t.vodName;
    }

    /** 任务是否属于该剧(剧名+来源)分组 */
    private boolean inGroup(DownloadTask t, String name, String source) {
        if (!name.equals(vodNameOf(t))) return false;
        String src = source == null ? "" : source;
        return src.equals(t.sourceName == null ? "" : t.sourceName);
    }

    /** 该剧的封面 URL(取该剧任一任务携带的 pic; 任务已清理/全部完成时从档案表补找) */
    private String picOf(String name, String source) {
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (inGroup(t, name, source) && t.pic != null && !t.pic.isEmpty()) {
                return t.pic;
            }
        }
        // 聚合组可能只剩档案(任务已清理/全部完成后): 从档案表补找 pic, 避免封面一直占位
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadArchive.get().queryByVod(name, source)) {
            if (it.pic != null && !it.pic.isEmpty()) return it.pic;
        }
        return null;
    }

    /** 绑定剧集海报:优先本地文件(私有目录,不入相册),缺失显示搜索页同款占位图并懒拉取 */
    private void bindPoster(ImageView iv, String vodName, String pic) {
        File pf = DownloadFacade.get().getPosterFile(vodName);
        if (pf != null) {
            // 统一图片加载到 Picasso 单例(共享 OkHttp 连接池/缓存),移除 Glide 双依赖
            com.squareup.picasso.Picasso.get()
                    .load(pf)
                    .placeholder(R.drawable.iv_load_fail)
                    .error(R.drawable.iv_load_fail)
                    .centerCrop()
                    .into(iv);
        } else {
            iv.setImageResource(R.drawable.iv_load_fail);
            if (pic != null && !pic.isEmpty()) {
                DownloadFacade.get().ensurePosterAsync(pic, vodName);
            }
        }
    }

    private void toggleSet(Set<String> set, String key) {
        if (set.contains(key)) set.remove(key);
        else set.add(key);
    }

    /** 左滑操作区宽度(px,懒计算) */
    private float swipeRevealPx() {
        if (swipeRevealPxCache < 0) {
            swipeRevealPxCache = SWIPE_REVEAL_WIDTH_DP * mContext.getResources().getDisplayMetrics().density;
        }
        return swipeRevealPxCache;
    }

    /**
     * 窄屏下载条目的左滑手势(手指从右往左):向左拖出右侧操作区(整条高度),松手按拖出距离吸附开/关;
     * 已展开时点按前面卡片收拢。DOWN 必须消费(true)才能成为触摸目标收到后续 MOVE,
     * 长按进入多选用 Handler 定时触发(手指静止时系统不发 MOVE,不能在 MOVE 里查时长)。
     * 选中态/宽屏不启用(convert 里已按需置 null)。
     */
    private void attachSwipe(final View front, final DownloadTask task) {
        final float reveal = swipeRevealPx();
        final int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        final long longPressTimeout = ViewConfiguration.getLongPressTimeout();
        final Handler swipeHandler = new Handler(Looper.getMainLooper());
        final float[] down = new float[2];
        final float[] startTx = new float[1];
        final boolean[] dragging = new boolean[1];
        final boolean[] longPressed = new boolean[1];
        final Runnable longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (!dragging[0] && !longPressed[0]) {
                    longPressed[0] = true;
                    enterDlSelectMode(task);
                }
            }
        };
        front.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    startTx[0] = v.getTranslationX();
                    dragging[0] = false;
                    longPressed[0] = false;
                    // 必须消费 DOWN 才能成为触摸目标收到 MOVE/UP;定时触发长按。
                    // 注意: 已展开时不在 DOWN 立即收拢——否则手指按住时的微动 MOVE 会
                    // 从收起位置拖回, 造成"收回去又弹出来"的抖动; 收拢判定移到 UP
                    swipeHandler.removeCallbacks(longPressRunnable);
                    swipeHandler.postDelayed(longPressRunnable, longPressTimeout);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!dragging[0] && !longPressed[0] && Math.abs(dx) > touchSlop
                            && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging[0] = true;
                        swipeHandler.removeCallbacks(longPressRunnable);
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        // 左滑:以按下时位置为基准增量移动(范围 -reveal..0)
                        float tx = Math.max(-reveal, Math.min(0, startTx[0] + dx));
                        v.setTranslationX(tx);
                        return true;
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    swipeHandler.removeCallbacks(longPressRunnable);
                    if (longPressed[0]) {
                        longPressed[0] = false;
                        return true;
                    }
                    if (dragging[0]) {
                        boolean open = v.getTranslationX() < -reveal / 2f;
                        if (open) {
                            swipedTaskIds.add(task.id);
                        } else {
                            swipedTaskIds.remove(task.id);
                        }
                        v.animate().translationX(open ? -reveal : 0).setDuration(150).start();
                        dragging[0] = false;
                        return true;
                    }
                    // 未拖动:已展开点按 → 收拢;未展开快速点击 → 切换 暂停/开始
                    if (v.getTranslationX() < 0) {
                        swipedTaskIds.remove(task.id);
                        v.animate().translationX(0).setDuration(150).start();
                    } else {
                        toggleTaskPlay(task);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    swipeHandler.removeCallbacks(longPressRunnable);
                    if (longPressed[0]) longPressed[0] = false;
                    if (dragging[0]) {
                        // 系统中断(滚动拦截等):恢复到按下前位置(展开保持展开), 不触发点击
                        v.animate().translationX(startTx[0]).setDuration(150).start();
                        dragging[0] = false;
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    /** 手动长按进入下载中多选并选中该任务(右滑条目替代适配器自带长按) */
    private void enterDlSelectMode(DownloadTask task) {
        if (!dlSelectMode) dlSelectMode = true;
        selectedTaskIds.add(task.id);
        downloadingAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    /** 点击条目切换播放/暂停:暂停/失败 -> 开始下载;下载中/等待/排队 -> 暂停 */
    private void toggleTaskPlay(DownloadTask t) {
        if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
            DownloadFacade.get().resume(t);
        } else {
            DownloadFacade.get().pause(t);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteRecursive(c);
            }
        }
        f.delete();
    }

    /** 用内置播放器播放下载的文件(与"我的-本地视频"一致) */
    private void playFile(VideoInfo info) {
        try {
            if (!new File(info.getPath()).exists()) {
                AppBubble.toast("文件不存在");
                return;
            }
            List<VideoInfo> list = new ArrayList<>();
            list.add(info);
            Bundle bundle = new Bundle();
            bundle.putString("videoList", GsonUtils.toJson(list));
            bundle.putInt("position", 0);
            jumpActivity(LocalPlayActivity.class, bundle);
        } catch (Throwable th) {
            th.printStackTrace();
            AppBubble.toast("播放失败:" + th.getMessage());
        }
    }

    private String buildPercentText(DownloadTask t) {
        StringBuilder sb = new StringBuilder();
        if (t.isHls()) {
            sb.append("分段 ").append(t.doneSegments).append("/").append(t.totalSegments);
        }
        if (t.totalBytes > 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(formatSize(t.downloadedBytes)).append("/").append(formatSize(t.totalBytes));
        }
        sb.append(" (").append(t.getProgressPercent()).append("%)");
        // 实时网速已移至状态行("下载中 xx%"后面),不在此行显示,避免被挤压
        if (t.state == DownloadTask.STATE_FAILED && t.message != null && !t.message.isEmpty()) {
            sb.append(" 失败:").append(t.message);
        }
        return sb.toString();
    }

    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.1fMB/s", bytesPerSec / 1024.0 / 1024.0);
        }
        if (bytesPerSec >= 1024) {
            return String.format("%.0fKB/s", bytesPerSec / 1024.0);
        }
        return bytesPerSec + "B/s";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + "MB";
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
