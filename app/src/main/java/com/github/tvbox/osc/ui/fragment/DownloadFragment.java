package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.os.StatFs;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.bean.VideoFolder;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.databinding.FragmentDownloadBinding;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.activity.LocalPlayActivity;
import com.github.tvbox.osc.ui.adapter.LocalVideoAdapter;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载页:正在下载(按剧名分组的组级 → 任务列表,长按多选:组级选中整组,条目级只作用于选中项)+
 * 下载完成(按 剧名+来源 分组的文件夹 → 文件列表,文件夹级支持长按多选整组删除),
 * 顶部 tab 切换,右上角可调下载并发(1-5)。
 * <p>
 * 布局:顶部为当前位置导航条(组/文件夹级显示),底部为可用存储信息条;
 * 全部暂停/全部开始 与 全选/删除/取消全选 均收纳在长按多选工具条中。
 */
public class DownloadFragment extends BaseVbFragment<FragmentDownloadBinding> {

    private static final int TAB_DOWNLOADING = 0;
    private static final int TAB_DONE = 1;

    private int currentTab = TAB_DOWNLOADING;

    // ------------------------------------------------------------------
    // 正在下载:两级(剧名分组 → 任务列表)
    // ------------------------------------------------------------------
    /** 正在下载:组级(按剧名) */
    private BaseQuickAdapter<String, BaseViewHolder> vodGroupAdapter;
    /** 正在下载:任务级(某剧名下) */
    private BaseQuickAdapter<DownloadTask, BaseViewHolder> downloadingAdapter;
    /** 当前打开的剧名组(非空=该剧的任务列表级) */
    private String currentVodGroup = null;
    /** 正在下载多选模式(组级或条目级共用) */
    private boolean dlSelectMode = false;
    /** 组级选中的剧名 */
    private final Set<String> selectedGroups = new LinkedHashSet<>();
    /** 条目级选中的任务 id */
    private final Set<String> selectedTaskIds = new LinkedHashSet<>();

    // ------------------------------------------------------------------
    // 下载完成:两级(文件夹 → 文件列表)
    // ------------------------------------------------------------------
    /** 下载完成:文件夹列表(剧名+来源分组) */
    private BaseQuickAdapter<DownloadGroup, BaseViewHolder> folderAdapter;
    private LocalVideoAdapter localVideoAdapter;
    /** 当前打开的下载文件夹(非空=文件列表级) */
    private VideoFolder currentFolder = null;
    /** 文件夹级多选模式 */
    private boolean folderSelectMode = false;
    /** 文件夹级选中的分组 key(来源+剧名) */
    private final Set<String> selectedFolderKeys = new LinkedHashSet<>();
    private int mSelectedCount = 0;

    /** 下载完成分组:key = 来源 + 剧名(同剧不同源各自成组) */
    private static class DownloadGroup {
        String key;
        String name;        // 剧名
        String sourceName;
        List<DownloadTask> tasks = new ArrayList<>();
    }

    @Override
    protected void init() {
        mBinding.rvDownloading.setLayoutManager(new LinearLayoutManager(mContext));
        mBinding.rvDone.setLayoutManager(new LinearLayoutManager(mContext));

        mBinding.tvTabDownloading.setOnClickListener(v -> switchTab(TAB_DOWNLOADING));
        mBinding.tvTabDone.setOnClickListener(v -> switchTab(TAB_DONE));

        // 顶部当前位置导航条:点按返回上一级
        mBinding.llNav.setOnClickListener(v -> onBackPressed());

        // 长按多选工具条:全部暂停/全部开始(仅下载中,作用于选中组内全部条目或选中条目)
        mBinding.btnPauseAll.setOnClickListener(v -> pauseSelected());
        mBinding.btnStartAll.setOnClickListener(v -> startSelected());
        mBinding.tvAllCheck.setOnClickListener(v -> selectAllChecked());
        mBinding.tvCancelAllChecked.setOnClickListener(v -> cancelAllChecked());
        mBinding.tvDelete.setOnClickListener(v -> {
            if (currentTab == TAB_DOWNLOADING) {
                deleteSelectedDownloading();
            } else if (currentFolder == null) {
                deleteSelectedFolders();
            } else {
                deleteChecked();
            }
        });

        // ------------------------------------------------------------------
        // 正在下载:组级(按剧名收纳;海报本地文件,缺失占位图+懒拉取)
        // ------------------------------------------------------------------
        vodGroupAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_download_vod_group_new) {
            @Override
            protected void convert(@NonNull BaseViewHolder helper, String vodName) {
                bindPoster(helper.getView(R.id.iv_cover), vodName, picOf(vodName));
                helper.setText(R.id.tv_vod_name, vodName);
                int count = 0;
                int downloading = 0;
                for (DownloadTask t : DownloadManager.get().getTasks()) {
                    if (t.state != DownloadTask.STATE_COMPLETED && vodName.equals(vodNameOf(t))) {
                        count++;
                        if (t.state == DownloadTask.STATE_DOWNLOADING) downloading++;
                    }
                }
                String desc = count + " 个任务";
                if (downloading > 0) desc = downloading + " 个下载中 · " + desc;
                helper.setText(R.id.tv_vod_count, desc);
                CheckBox cb = helper.getView(R.id.cb);
                cb.setVisibility(dlSelectMode && currentVodGroup == null ? View.VISIBLE : View.GONE);
                cb.setChecked(selectedGroups.contains(vodName));
            }
        };
        vodGroupAdapter.setOnItemClickListener((adapter, view, position) -> {
            String vodName = vodGroupAdapter.getItem(position);
            if (vodName == null) return;
            if (dlSelectMode && currentVodGroup == null) {
                // 组级多选:点选/取消组,全部暂停/全部开始作用于组内全部条目
                toggleSet(selectedGroups, vodName);
                vodGroupAdapter.notifyDataSetChanged();
                updateToolbar();
            } else {
                openVodGroup(vodName);
            }
        });
        vodGroupAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            String vodName = vodGroupAdapter.getItem(position);
            if (vodName == null) return false;
            if (!dlSelectMode) dlSelectMode = true;
            selectedGroups.add(vodName);
            vodGroupAdapter.notifyDataSetChanged();
            updateToolbar();
            return true;
        });
        mBinding.rvDownloading.setAdapter(vodGroupAdapter);

        // ------------------------------------------------------------------
        // 正在下载:任务级(某剧名下;无进度条,海报本地文件,多选只作用于选中条目)
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
                } else if (task.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    status = "排队中";
                    statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                } else if (task.state == DownloadTask.STATE_WAITING) {
                    status = "等待中";
                    statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                } else {
                    if (DownloadManager.MSG_VERIFYING.equals(task.message)) {
                        status = DownloadManager.MSG_VERIFYING;
                    } else if (DownloadManager.MSG_MERGING.equals(task.message)) {
                        status = DownloadManager.MSG_MERGING;
                    } else {
                        status = "下载中";
                    }
                    statusColor = ContextCompat.getColor(mContext, R.color.download_active);
                }
                TextView tvStatus = helper.getView(R.id.tv_status);
                tvStatus.setText(status + " · " + task.getProgressPercent() + "%");
                tvStatus.setTextColor(statusColor);
                // 行3:大小 · 速度
                helper.setText(R.id.tv_size_speed, buildPercentText(task));
                // 行4:来源 · 存储位置
                String src = task.sourceName == null ? "" : task.sourceName;
                helper.setText(R.id.tv_source, "来源 " + (src.isEmpty() ? "未知" : src));
                // 操作按钮:随状态切换 暂停/继续/重试(多选模式下隐藏)
                TextView btnAction = helper.getView(R.id.btn_action);
                if (dlSelectMode) {
                    btnAction.setVisibility(View.GONE);
                } else {
                    btnAction.setVisibility(View.VISIBLE);
                    if (task.state == DownloadTask.STATE_PAUSED) {
                        btnAction.setText("继续");
                        btnAction.setTextColor(ContextCompat.getColor(mContext, R.color.download_active));
                    } else if (task.state == DownloadTask.STATE_FAILED) {
                        btnAction.setText("重试");
                        btnAction.setTextColor(ContextCompat.getColor(mContext, R.color.download_active));
                    } else {
                        btnAction.setText("暂停");
                        btnAction.setTextColor(ContextCompat.getColor(mContext, R.color.text_foreground));
                    }
                    helper.addOnClickListener(R.id.btn_action);
                }
                // 更多行:失败显示重试,长按/点更多删除(多选模式下隐藏)
                View llMore = helper.getView(R.id.ll_more);
                llMore.setVisibility(!dlSelectMode && task.state == DownloadTask.STATE_FAILED ? View.VISIBLE : View.GONE);
                if (!dlSelectMode) {
                    helper.addOnClickListener(R.id.tv_more_delete, R.id.tv_more_retry);
                }
                // 多选勾选框:仅长按多选(条目级)时显示
                CheckBox cb = helper.getView(R.id.cb);
                cb.setVisibility(dlSelectMode ? View.VISIBLE : View.GONE);
                cb.setChecked(selectedTaskIds.contains(task.id));
            }
        };
        downloadingAdapter.setOnItemClickListener((adapter, view, position) -> {
            if (!dlSelectMode) return;
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return;
            DownloadTask t = data.get(position);
            toggleSet(selectedTaskIds, t.id);
            downloadingAdapter.notifyDataSetChanged();
            updateToolbar();
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
            if (id == R.id.btn_action) {
                if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
                    DownloadManager.get().resume(t);
                } else {
                    DownloadManager.get().pause(t);
                }
            } else if (id == R.id.tv_more_retry) {
                DownloadManager.get().resume(t);
            } else if (id == R.id.tv_more_delete) {
                // 下载中的任务:删除 = 记录 + 过程文件一起删,简单确认后直接全删
                new XPopup.Builder(mContext)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asConfirm("删除任务", "将删除该任务及其未完成的下载文件,确定?",
                                "删除", "取消", () -> {
                                    DownloadCore.remove(t, true);
                                    refresh();
                                }, null, false)
                        .show();
            }
        });
        // 注意:不能在这里 setAdapter(downloadingAdapter),否则会顶掉组级适配器,
        // 导致"正在下载"默认视图空白(任务只在点进剧名组后可见)

        // ------------------------------------------------------------------
        // 下载完成:文件夹列表(按 剧名+来源 分组;海报本地文件优先,缺失显示首集缩略图/占位图)
        // ------------------------------------------------------------------
        folderAdapter = new BaseQuickAdapter<DownloadGroup, BaseViewHolder>(R.layout.item_download_folder) {
            @Override
            protected void convert(@NonNull BaseViewHolder helper, DownloadGroup group) {
                // 第一排:电视剧名称
                helper.setText(R.id.tv_name, group.name);
                // 第二排:来源 / N 个视频 / 总容量
                long totalSize = 0;
                int count = 0;
                for (DownloadTask t : group.tasks) {
                    File f = new File(t.savePath);
                    if (f.exists()) {
                        count++;
                        totalSize += f.length();
                    }
                }
                String src = group.sourceName == null || group.sourceName.isEmpty() ? "" : group.sourceName;
                String desc = (src.isEmpty() ? "" : src + " / ") + count + " 个视频";
                if (totalSize > 0) desc += " · " + formatSize(totalSize);
                helper.setText(R.id.tv_count, desc);
                // 封面:本地海报优先,其次首集视频缩略图,再否则占位图
                ImageView iv = helper.getView(R.id.iv);
                File pf = DownloadManager.getPosterFile(group.name);
                if (pf != null) {
                    Glide.with(mContext)
                            .load(pf)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .placeholder(R.drawable.iv_load_fail)
                            .error(R.drawable.iv_load_fail)
                            .centerCrop()
                            .into(iv);
                } else {
                    String firstPath = null;
                    for (DownloadTask t : group.tasks) {
                        if (t.savePath != null && new File(t.savePath).exists()) {
                            firstPath = t.savePath;
                            break;
                        }
                    }
                    if (firstPath != null) {
                        Glide.with(mContext)
                                .load(firstPath)
                                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                .placeholder(R.drawable.iv_load_fail)
                                .centerCrop()
                                .into(iv);
                    } else {
                        iv.setImageResource(R.drawable.iv_load_fail);
                    }
                    String pic = picOf(group.name);
                    if (pic != null && !pic.isEmpty()) DownloadManager.get().ensurePosterAsync(pic, group.name);
                }
                // 多选勾选框:仅长按多选(文件夹级)时显示
                CheckBox cb = helper.getView(R.id.cb);
                cb.setVisibility(folderSelectMode ? View.VISIBLE : View.GONE);
                cb.setChecked(selectedFolderKeys.contains(group.key));
            }
        };
        folderAdapter.setOnItemClickListener((adapter, view, position) -> {
            DownloadGroup g = folderAdapter.getItem(position);
            if (g == null) return;
            if (folderSelectMode) {
                toggleSet(selectedFolderKeys, g.key);
                folderAdapter.notifyDataSetChanged();
                updateToolbar();
            } else {
                openFolder(g);
            }
        });
        folderAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            DownloadGroup g = folderAdapter.getItem(position);
            if (g == null) return false;
            if (!folderSelectMode) folderSelectMode = true;
            selectedFolderKeys.add(g.key);
            folderAdapter.notifyDataSetChanged();
            updateToolbar();
            return true;
        });
        mBinding.rvDone.setAdapter(folderAdapter);

        // ------------------------------------------------------------------
        // 下载完成:文件夹内文件列表(复用本地视频 adapter)
        // ------------------------------------------------------------------
        localVideoAdapter = new LocalVideoAdapter();
        localVideoAdapter.setOnItemClickListener((adapter, view, position) -> {
            VideoInfo info = localVideoAdapter.getItem(position);
            if (info == null) return;
            if (localVideoAdapter.isSelectMode()) {
                info.setChecked(!info.isChecked());
                localVideoAdapter.notifyDataSetChanged();
            } else {
                playFile(info);
            }
        });
        localVideoAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            VideoInfo info = localVideoAdapter.getItem(position);
            if (info != null) {
                if (!localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(true);
                info.setChecked(true);
                localVideoAdapter.notifyDataSetChanged();
                updateToolbar();
            }
            return true;
        });
        localVideoAdapter.setOnSelectCountListener(count -> {
            mSelectedCount = count;
            updateToolbar();
        });

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

    /** 返回键处理:多选优先取消多选,再按层级逐级返回 */
    public boolean onBackPressed() {
        if (currentTab == TAB_DOWNLOADING) {
            if (currentVodGroup != null) {
                if (dlSelectMode) {
                    if (!selectedTaskIds.isEmpty()) {
                        cancelAllChecked();
                    } else {
                        dlSelectMode = false;
                        downloadingAdapter.notifyDataSetChanged();
                        updateToolbar();
                    }
                } else {
                    backToVodGroups();
                }
                return true;
            }
            if (dlSelectMode) {
                if (!selectedGroups.isEmpty()) {
                    cancelAllChecked();
                } else {
                    dlSelectMode = false;
                    vodGroupAdapter.notifyDataSetChanged();
                    updateToolbar();
                }
                return true;
            }
            return false;
        }
        if (localVideoAdapter.isSelectMode()) {
            if (mSelectedCount > 0) {
                cancelAllChecked();
            } else {
                localVideoAdapter.setSelectMode(false);
                updateToolbar();
            }
            return true;
        }
        if (currentFolder != null) {
            backToFolders();
            return true;
        }
        if (folderSelectMode) {
            if (!selectedFolderKeys.isEmpty()) {
                cancelAllChecked();
            } else {
                folderSelectMode = false;
                folderAdapter.notifyDataSetChanged();
                updateToolbar();
            }
            return true;
        }
        return false;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        refresh();
    }

    private void switchTab(int tab) {
        currentTab = tab;
        boolean downloading = tab == TAB_DOWNLOADING;
        mBinding.tvTabDownloading.setTextColor(getResources().getColor(downloading ? R.color.colorPrimary : R.color.text_sub_foreground));
        mBinding.tvTabDownloading.setTextSize(16);
        mBinding.tvTabDownloading.setTypeface(null, downloading ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        mBinding.tvTabDone.setTextColor(getResources().getColor(downloading ? R.color.text_sub_foreground : R.color.colorPrimary));
        mBinding.tvTabDone.setTextSize(16);
        mBinding.tvTabDone.setTypeface(null, downloading ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        mBinding.rvDownloading.setVisibility(downloading ? View.VISIBLE : View.GONE);
        mBinding.rvDone.setVisibility(downloading ? View.GONE : View.VISIBLE);
        if (!downloading) {
            // 切走下载中:退出组级/条目级多选与组内视图不强制,仅清选择态
            dlSelectMode = false;
            selectedGroups.clear();
            selectedTaskIds.clear();
            vodGroupAdapter.notifyDataSetChanged();
            downloadingAdapter.notifyDataSetChanged();
        } else {
            // 切到下载中:退出下载完成的两级多选
            exitFolderSelectMode();
            if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        }
        updateNavBar();
    }

    private void refresh() {
        refreshDownloadingList();
        refreshDoneList();
        updateTabCounts();
        updateToolbar();
    }

    /** Tab 数量角标:下载中 (N)  已完成 (M) */
    private void updateTabCounts() {
        int downloading = 0;
        int done = 0;
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.state == DownloadTask.STATE_COMPLETED) {
                if (t.savePath != null && new File(t.savePath).exists()) done++;
            } else {
                downloading++;
            }
        }
        mBinding.tvTabDownloading.setText(downloading > 0 ? "正在下载 (" + downloading + ")" : "正在下载");
        mBinding.tvTabDone.setText(done > 0 ? "下载完成 (" + done + ")" : "下载完成");
    }

    /** 刷新"正在下载":组级按剧名分组,条目级显示该剧任务 */
    private void refreshDownloadingList() {
        List<DownloadTask> all = DownloadManager.get().getTasks();
        List<DownloadTask> downloading = new ArrayList<>();
        for (DownloadTask t : all) {
            if (t.state != DownloadTask.STATE_COMPLETED) {
                downloading.add(t);
            }
        }
        downloading.sort(Comparator.comparingLong(t -> t.createTime));
        if (currentVodGroup == null) {
            // 组级:按剧名分组,组顺序按组内最早加入时间
            Map<String, Long> firstTime = new LinkedHashMap<>();
            for (DownloadTask t : downloading) {
                firstTime.computeIfAbsent(vodNameOf(t), k -> t.createTime);
            }
            List<String> groups = new ArrayList<>(firstTime.keySet());
            groups.sort(Comparator.comparingLong(firstTime::get));
            vodGroupAdapter.setNewData(groups);
        } else {
            // 条目级:该剧的任务,按加入时间排序
            List<DownloadTask> list = tasksInGroup(currentVodGroup);
            if (list.isEmpty()) {
                // 该剧任务已清空,退回组级
                currentVodGroup = null;
                dlSelectMode = false;
                selectedTaskIds.clear();
                mBinding.rvDownloading.setAdapter(vodGroupAdapter);
                refreshDownloadingList();
                return;
            }
            downloadingAdapter.setNewData(list);
        }
        updateNavBar();
    }

    /** 刷新"下载完成":基于下载完成记录表,先对账清理文件已不存在的失效记录 */
    private void refreshDoneList() {
        DownloadManager.get().pruneMissingCompleted(); // 静默清理(不广播,避免刷新循环)
        if (currentFolder == null) {
            folderAdapter.setNewData(buildDoneGroups());
        } else {
            List<VideoInfo> files = buildFolderVideosFromRecords(currentFolder.getName(), currentFolder.getSourceName());
            if (files.isEmpty()) {
                // 记录/文件已清空,退回文件夹级
                backToFolders();
                return;
            }
            String src = currentFolder.getSourceName();
            currentFolder = new VideoFolder(currentFolder.getName(), files);
            currentFolder.setSourceName(src);
            localVideoAdapter.setNewData(files);
            if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        }
        updateNavBar();
    }

    /** 刷新顶部当前位置导航条:下载中组级 / 下载完成文件夹级 显示 */
    private void updateNavBar() {
        if (currentTab == TAB_DOWNLOADING) {
            boolean inGroup = currentVodGroup != null;
            mBinding.llNav.setVisibility(inGroup ? View.VISIBLE : View.GONE);
            if (inGroup) mBinding.tvNavPath.setText("正在下载 › " + currentVodGroup);
        } else {
            boolean inFolder = currentFolder != null;
            mBinding.llNav.setVisibility(inFolder ? View.VISIBLE : View.GONE);
            if (inFolder) mBinding.tvNavPath.setText("下载完成 › " + currentFolder.getName());
        }
        updateToolbar();
        updateStorageText();
    }

    /** 刷新底部"可用存储 / Wi-Fi / 并发"信息条(原顶部信息条移下,去掉保存路径) */
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

    // ------------------------------------------------------------------
    // 长按多选工具条:全选/删除/取消全选 + 全部暂停/全部开始(仅下载中)
    // ------------------------------------------------------------------

    /** 刷新工具条:可见性、全部暂停/全部开始可用态、删除按钮颜色(启用=红,深浅主题均清晰) */
    private void updateToolbar() {
        boolean dl = currentTab == TAB_DOWNLOADING && dlSelectMode;
        boolean done = currentTab == TAB_DONE && (folderSelectMode || localVideoAdapter.isSelectMode());
        boolean show = dl || done;
        mBinding.llMenu.setVisibility(show ? View.VISIBLE : View.GONE);
        mBinding.llToolbarActions.setVisibility(dl ? View.VISIBLE : View.GONE);
        if (dl) {
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
        boolean hasSel = dl ? !currentDlScope().isEmpty()
                : done && (folderSelectMode ? !selectedFolderKeys.isEmpty() : mSelectedCount > 0);
        mBinding.tvDelete.setEnabled(hasSel);
        mBinding.tvDelete.setTextColor(ContextCompat.getColor(mContext,
                hasSel ? R.color.red : R.color.disable_text));
    }

    /** 全选:组级=全部剧名组,条目级=该剧全部任务,文件夹级=全部文件夹,文件级=全部文件 */
    private void selectAllChecked() {
        if (currentTab == TAB_DOWNLOADING) {
            if (currentVodGroup == null) {
                for (String g : vodGroupAdapter.getData()) selectedGroups.add(g);
                vodGroupAdapter.notifyDataSetChanged();
            } else {
                for (DownloadTask t : tasksInGroup(currentVodGroup)) selectedTaskIds.add(t.id);
                downloadingAdapter.notifyDataSetChanged();
            }
        } else if (currentFolder == null) {
            for (DownloadGroup g : folderAdapter.getData()) selectedFolderKeys.add(g.key);
            folderAdapter.notifyDataSetChanged();
        } else {
            for (VideoInfo item : localVideoAdapter.getData()) {
                item.setChecked(true);
            }
            localVideoAdapter.notifyDataSetChanged();
        }
        updateToolbar();
    }

    /** 取消全选:清空当前层级的选择 */
    private void cancelAllChecked() {
        if (currentTab == TAB_DOWNLOADING) {
            if (currentVodGroup == null) {
                selectedGroups.clear();
                vodGroupAdapter.notifyDataSetChanged();
            } else {
                selectedTaskIds.clear();
                downloadingAdapter.notifyDataSetChanged();
            }
        } else if (currentFolder == null) {
            selectedFolderKeys.clear();
            folderAdapter.notifyDataSetChanged();
        } else {
            for (VideoInfo item : localVideoAdapter.getData()) {
                item.setChecked(false);
            }
            localVideoAdapter.notifyDataSetChanged();
        }
        updateToolbar();
    }

    /** 当前下载中多选的作用域:组级=选中剧名组内全部任务,条目级=选中的任务 */
    private List<DownloadTask> currentDlScope() {
        List<DownloadTask> scope = new ArrayList<>();
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.state == DownloadTask.STATE_COMPLETED) continue;
            if (currentVodGroup == null) {
                if (selectedGroups.contains(vodNameOf(t))) scope.add(t);
            } else {
                if (currentVodGroup.equals(vodNameOf(t)) && selectedTaskIds.contains(t.id)) scope.add(t);
            }
        }
        return scope;
    }

    /** 全部暂停(仅下载中多选):作用于当前作用域内下载中/等待中/排队中的任务 */
    private void pauseSelected() {
        List<DownloadTask> scope = currentDlScope();
        int n = 0;
        for (DownloadTask t : scope) {
            if (t.state == DownloadTask.STATE_DOWNLOADING
                    || t.state == DownloadTask.STATE_WAITING
                    || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                DownloadManager.get().pause(t);
                n++;
            }
        }
        if (n > 0) AppBubble.toast("已暂停 " + n + " 个任务");
    }

    /** 全部开始(仅下载中多选):作用于当前作用域内已暂停/失败的任务 */
    private void startSelected() {
        List<DownloadTask> scope = currentDlScope();
        int n = 0;
        for (DownloadTask t : scope) {
            if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
                DownloadManager.get().resume(t);
                n++;
            }
        }
        if (n > 0) AppBubble.toast("已开始 " + n + " 个任务");
    }

    /** 删除选中的下载中任务:确认后记录+过程文件一起删,并清理可能变空的剧名目录 */
    private void deleteSelectedDownloading() {
        List<DownloadTask> scope = new ArrayList<>(currentDlScope());
        if (scope.isEmpty()) return;
        new XPopup.Builder(mContext)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("删除任务", "将删除选中的 " + scope.size() + " 个任务及其未完成的下载文件,确定?",
                        "删除", "取消", () -> {
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
                        }, null, false)
                .show();
    }

    /** 删除选中的下载完成文件夹:确认后记录与整个文件夹(该来源下该剧名目录)一起删除 */
    private void deleteSelectedFolders() {
        List<DownloadGroup> sel = new ArrayList<>();
        for (DownloadGroup g : folderAdapter.getData()) {
            if (selectedFolderKeys.contains(g.key)) sel.add(g);
        }
        if (sel.isEmpty()) return;
        new XPopup.Builder(mContext)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("删除文件夹", "将删除选中的 " + sel.size() + " 个文件夹(剧名+来源)及其本地文件,确定?",
                        "删除", "取消", () -> {
                            for (DownloadGroup g : sel) {
                                Set<File> dirs = new LinkedHashSet<>();
                                for (DownloadTask t : g.tasks) {
                                    if (t.savePath != null) {
                                        File p = new File(t.savePath).getParentFile();
                                        if (p != null) dirs.add(p);
                                    }
                                    DownloadCore.remove(t, false);
                                }
                                // 删除整个文件夹(该来源下该剧名的目录)
                                for (File d : dirs) {
                                    deleteRecursive(d);
                                }
                            }
                            exitFolderSelectMode();
                            refresh();
                        }, null, false)
                .show();
    }

    /** 删除选中的下载完成文件(文件夹内文件级):确认后按"同时删除本地文件"决定 */
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
        if (deleteFiles) {
            File f = new File(path);
            if (f.exists()) f.delete();
        }
    }

    // ------------------------------------------------------------------
    // 正在下载:组级/条目级切换
    // ------------------------------------------------------------------

    private void openVodGroup(String vodName) {
        currentVodGroup = vodName;
        exitDlSelectMode();
        mBinding.rvDownloading.setAdapter(downloadingAdapter);
        refreshDownloadingList();
    }

    private void backToVodGroups() {
        currentVodGroup = null;
        exitDlSelectMode();
        mBinding.rvDownloading.setAdapter(vodGroupAdapter);
        refreshDownloadingList();
    }

    private void exitDlSelectMode() {
        dlSelectMode = false;
        selectedGroups.clear();
        selectedTaskIds.clear();
        vodGroupAdapter.notifyDataSetChanged();
        downloadingAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    private void exitFolderSelectMode() {
        folderSelectMode = false;
        selectedFolderKeys.clear();
        folderAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    // ------------------------------------------------------------------
    // 下载完成:基于"下载完成记录表"(DownloadManager 持久化的已完成任务)构建
    // 展示前按记录里的 savePath 检查文件是否还在,不在则更新记录表移除
    // ------------------------------------------------------------------

    /** 从下载完成记录构建文件夹列表(按 剧名+来源 分组) */
    private List<DownloadGroup> buildDoneGroups() {
        Map<String, DownloadGroup> map = new LinkedHashMap<>();
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED || t.savePath == null) continue;
            if (!new File(t.savePath).exists()) continue;
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
            }
            g.tasks.add(t);
        }
        List<DownloadGroup> groups = new ArrayList<>(map.values());
        groups.sort(Comparator.comparing(g -> g.name));
        return groups;
    }

    /** 某剧名+来源下已完成且文件存在的视频列表(记录驱动,按文件名排序) */
    private List<VideoInfo> buildFolderVideosFromRecords(String vodName, String sourceName) {
        String wantSrc = sourceName == null ? "" : sourceName;
        List<VideoInfo> videos = new ArrayList<>();
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED || t.savePath == null) continue;
            if (!vodName.equals(vodNameOf(t))) continue;
            if (!wantSrc.equals(t.sourceName == null ? "" : t.sourceName)) continue;
            File f = new File(t.savePath);
            if (!f.exists()) continue;
            VideoInfo info = new VideoInfo();
            info.setPath(f.getAbsolutePath());
            info.setDisplayName(t.fileName == null ? f.getName() : t.fileName);
            info.setTitle(info.getDisplayName());
            info.setSize(f.length());
            info.setEpisodeId(t.episodeId); // 统一剧集标识:回跳详情页/本地播放联动用
            videos.add(info);
        }
        videos.sort(Comparator.comparing(VideoInfo::getDisplayName));
        return videos;
    }

    private void openFolder(DownloadGroup g) {
        currentFolder = new VideoFolder(g.name, buildFolderVideosFromRecords(g.name, g.sourceName));
        currentFolder.setSourceName(g.sourceName);
        exitFolderSelectMode();
        mBinding.rvDone.setAdapter(localVideoAdapter);
        localVideoAdapter.setNewData(currentFolder.getVideoList());
        updateNavBar();
    }

    private void backToFolders() {
        currentFolder = null;
        exitFolderSelectMode();
        mBinding.rvDone.setAdapter(folderAdapter);
        folderAdapter.setNewData(buildDoneGroups());
        updateNavBar();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 剧名(兼容旧字段 groupName) */
    private String vodNameOf(DownloadTask t) {
        return t.vodName == null ? t.groupName : t.vodName;
    }

    /** 某剧名下未完成的任务列表(按加入时间排序) */
    private List<DownloadTask> tasksInGroup(String vodName) {
        List<DownloadTask> list = new ArrayList<>();
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED && vodName.equals(vodNameOf(t))) {
                list.add(t);
            }
        }
        list.sort(Comparator.comparingLong(t -> t.createTime));
        return list;
    }

    /** 某剧名的封面 URL(取该剧任一任务携带的 pic) */
    private String picOf(String vodName) {
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (vodName.equals(vodNameOf(t)) && t.pic != null && !t.pic.isEmpty()) {
                return t.pic;
            }
        }
        return null;
    }

    /** 绑定剧集海报:优先本地文件(私有目录,不入相册),缺失显示搜索页同款占位图并懒拉取 */
    private void bindPoster(ImageView iv, String vodName, String pic) {
        File pf = DownloadManager.getPosterFile(vodName);
        if (pf != null) {
            Glide.with(mContext)
                    .load(pf)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.drawable.iv_load_fail)
                    .error(R.drawable.iv_load_fail)
                    .centerCrop()
                    .into(iv);
        } else {
            iv.setImageResource(R.drawable.iv_load_fail);
            if (pic != null && !pic.isEmpty()) {
                DownloadManager.get().ensurePosterAsync(pic, vodName);
            }
        }
    }

    private void toggleSet(Set<String> set, String key) {
        if (set.contains(key)) set.remove(key);
        else set.add(key);
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
        // 实时网速(仅下载中显示)
        if (t.state == DownloadTask.STATE_DOWNLOADING && t.speed > 0) {
            sb.append("  ").append(formatSpeed(t.speed));
        }
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
        if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
