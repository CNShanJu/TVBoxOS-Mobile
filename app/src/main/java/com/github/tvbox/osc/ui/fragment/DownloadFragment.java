package com.github.tvbox.osc.ui.fragment;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.StatFs;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ClipboardUtils;
import com.blankj.utilcode.util.ColorUtils;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 下载页:正在下载(暂停/继续/删除)+ 下载完成(与"我的-本地视频"一致:按目录分组的文件夹 → 文件列表),
 * 顶部 tab 切换,右上角可调下载并发(1-5)
 */
public class DownloadFragment extends BaseVbFragment<FragmentDownloadBinding> {

    private static final int TAB_DOWNLOADING = 0;
    private static final int TAB_DONE = 1;

    private int currentTab = TAB_DOWNLOADING;

    /** 正在下载:直接任务列表 */
    private BaseQuickAdapter<DownloadTask, BaseViewHolder> downloadingAdapter;
    /** 下载完成:文件夹列表(剧名/来源,两排显示) */
    private BaseQuickAdapter<VideoFolder, BaseViewHolder> folderAdapter;
    private LocalVideoAdapter localVideoAdapter;
    /** 当前打开的下载文件夹(非空=文件列表级) */
    private VideoFolder currentFolder = null;
    private int mSelectedCount = 0;

    @Override
    protected void init() {
        mBinding.rvDownloading.setLayoutManager(new LinearLayoutManager(mContext));
        mBinding.rvDone.setLayoutManager(new LinearLayoutManager(mContext));

        mBinding.tvTabDownloading.setOnClickListener(v -> switchTab(TAB_DOWNLOADING));
        mBinding.tvTabDone.setOnClickListener(v -> switchTab(TAB_DONE));

        // 顶部信息条:保存位置(点击复制路径,系统目录打开兼容性差/易误判为文件)
        mBinding.tvSavePath.setOnClickListener(v -> {
            try {
                File dir = DownloadConfig.getSaveDir();
                ClipboardUtils.copyText(dir.getAbsolutePath());
                AppBubble.toast("已复制保存路径");
            } catch (Throwable th) {
                AppBubble.toast("无法复制保存路径");
            }
        });

        // 全部暂停 / 全部开始:仅"正在下载"根级且有任务时显示(见 updateActionBar)
        mBinding.btnPauseAll.setOnClickListener(v -> {
            DownloadCore.pauseAll();
            AppBubble.toast("已全部暂停");
        });
        mBinding.btnStartAll.setOnClickListener(v -> {
            DownloadCore.startAll();
            AppBubble.toast("已全部开始");
        });

        // 当前位置导航条:点按返回上一级
        mBinding.llNav.setOnClickListener(v -> onBackPressed());

        // 正在下载:直接任务列表(方案9.1.2样式:封面+剧名·集名+状态·进度+大小·速度+来源+操作按钮)
        downloadingAdapter = new BaseQuickAdapter<DownloadTask, BaseViewHolder>(R.layout.item_download_task_new) {
            @Override
            protected void convert(BaseViewHolder helper, DownloadTask task) {
                // 封面图(Glide 加载,失败灰底)
                ImageView ivCover = helper.getView(R.id.iv_cover);
                if (task.pic != null && !task.pic.isEmpty()) {
                    Glide.with(mContext)
                            .load(task.pic)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .placeholder(R.color.gray_darker_press_alpha)
                            .centerCrop()
                            .into(ivCover);
                } else {
                    ivCover.setImageDrawable(null);
                }
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
                // 进度条
                ProgressBar pb = helper.getView(R.id.progress);
                pb.setProgress(task.getProgressPercent());
                pb.setProgressTintList(ColorStateList.valueOf(
                        task.state == DownloadTask.STATE_DOWNLOADING
                                ? ContextCompat.getColor(mContext, R.color.download_done)
                                : ContextCompat.getColor(mContext, R.color.gray_darker)));
                // 操作按钮:随状态切换 暂停/继续/重试
                TextView btnAction = helper.getView(R.id.btn_action);
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
                // 更多行:失败显示重试,长按/点更多删除
                View llMore = helper.getView(R.id.ll_more);
                llMore.setVisibility(task.state == DownloadTask.STATE_FAILED ? View.VISIBLE : View.GONE);
                helper.addOnClickListener(R.id.tv_more_delete, R.id.tv_more_retry);
            }
        };
        downloadingAdapter.setOnItemChildClickListener((adapter, view, position) -> {
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
        mBinding.rvDownloading.setAdapter(downloadingAdapter);

        // 下载完成:文件夹列表(剧名 + 来源/个数,卡片式)
        folderAdapter = new BaseQuickAdapter<VideoFolder, BaseViewHolder>(R.layout.item_download_folder) {
            @Override
            protected void convert(BaseViewHolder helper, VideoFolder folder) {
                List<VideoInfo> videoList = folder.getVideoList();
                // 第一排:电视剧名称
                helper.setText(R.id.tv_name, folder.getName());
                // 第二排:来源 / N 个视频 / 总容量
                String source = folder.getSourceName();
                long totalSize = 0;
                for (VideoInfo v : videoList) totalSize += v.getSize();
                String count = videoList.size() + " 个视频";
                if (totalSize > 0) count += " · " + formatSize(totalSize);
                helper.setText(R.id.tv_count, (source == null || source.isEmpty() ? "" : source + " / ") + count);
                Glide.with(mContext)
                        .load(videoList.get(0).getPath())
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .placeholder(R.drawable.iv_load_fail)
                        .centerCrop()
                        .into((ImageView) helper.getView(R.id.iv));
            }
        };
        folderAdapter.setOnItemClickListener((adapter, view, position) -> {
            VideoFolder folder = folderAdapter.getItem(position);
            if (folder != null) openFolder(folder);
        });
        mBinding.rvDone.setAdapter(folderAdapter);

        // 下载完成:文件夹内文件列表(复用本地视频 adapter)
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
                toggleSelectMode(true);
                info.setChecked(true);
                localVideoAdapter.notifyDataSetChanged();
            }
            return true;
        });
        localVideoAdapter.setOnSelectCountListener(count -> {
            mSelectedCount = count;
            if (mSelectedCount > 0) {
                mBinding.tvDelete.setEnabled(true);
                mBinding.tvDelete.setTextColor(ColorUtils.getColor(R.color.colorPrimary));
            } else {
                mBinding.tvDelete.setEnabled(false);
                mBinding.tvDelete.setTextColor(ColorUtils.getColor(R.color.disable_text));
            }
        });

        mBinding.tvAllCheck.setOnClickListener(v -> {
            for (VideoInfo item : localVideoAdapter.getData()) {
                item.setChecked(true);
            }
            localVideoAdapter.notifyDataSetChanged();
        });
        mBinding.tvCancelAllChecked.setOnClickListener(v -> cancelAllChecked());
        mBinding.tvDelete.setOnClickListener(v -> deleteChecked());

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

    /** 返回键处理:文件列表级先返回文件夹级,多选模式先取消多选 */
    public boolean onBackPressed() {
        if (localVideoAdapter.isSelectMode()) {
            if (mSelectedCount > 0) {
                cancelAllChecked();
            } else {
                toggleSelectMode(false);
            }
            return true;
        }
        if (currentFolder != null) {
            backToFolders();
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
        if (!downloading) toggleSelectMode(false);
        updateNavBar();
    }

    private void refresh() {
        refreshDownloadingList();
        refreshDoneList();
        updateTabCounts();
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

    /** 刷新"正在下载":直接任务列表(按加入时间排序) */
    private void refreshDownloadingList() {
        List<DownloadTask> all = DownloadManager.get().getTasks();
        List<DownloadTask> downloading = new ArrayList<>();
        for (DownloadTask t : all) {
            if (t.state != DownloadTask.STATE_COMPLETED) {
                downloading.add(t);
            }
        }
        downloading.sort(Comparator.comparingLong(t -> t.createTime));
        downloadingAdapter.setNewData(downloading);
        updateNavBar();
    }

    /** 刷新"下载完成":基于下载完成记录表,先对账清理文件已不存在的失效记录 */
    private void refreshDoneList() {
        DownloadManager.get().pruneMissingCompleted(); // 静默清理(不广播,避免刷新循环)
        if (currentFolder == null) {
            folderAdapter.setNewData(buildDoneFoldersFromRecords());
        } else {
            List<VideoInfo> files = buildFolderVideosFromRecords(currentFolder.getName());
            if (files.isEmpty()) {
                // 记录/文件已清空,退回文件夹级
                backToFolders();
                return;
            }
            currentFolder = new VideoFolder(currentFolder.getName(), files);
            localVideoAdapter.setNewData(files);
            if (localVideoAdapter.isSelectMode()) toggleSelectMode(false);
        }
        updateNavBar();
    }

    /** 刷新当前位置导航条:仅"下载完成"进入文件夹后显示;下载中为直接列表无层级 */
    private void updateNavBar() {
        if (currentTab == TAB_DOWNLOADING) {
            mBinding.llNav.setVisibility(View.GONE);
        } else {
            boolean inFolder = currentFolder != null;
            mBinding.llNav.setVisibility(inFolder ? View.VISIBLE : View.GONE);
            if (inFolder) mBinding.tvNavPath.setText("下载完成 › " + currentFolder.getName());
        }
        updateActionBar();
        updateStorageText();
    }

    /** 刷新底部"可用存储"与"保存位置"提示 */
    private void updateStorageText() {
        try {
            File dir = DownloadConfig.getSaveDir();
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long free = stat.getAvailableBytes();
            String wifi = DownloadConfig.isWifiOnly() ? "仅Wi-Fi" : "Wi-Fi+流量";
            mBinding.tvStorage.setText("可用 " + formatSize(free) + "  |  " + wifi + " · 并发 " + DownloadConfig.getMaxConcurrent());
            mBinding.tvSavePath.setText("保存: " + dir.getAbsolutePath());
        } catch (Throwable th) {
            mBinding.tvStorage.setText("");
            mBinding.tvSavePath.setText("");
        }
    }

    /** 刷新"全部暂停/全部开始"操作行:下载中 Tab 且有任务时显示,按钮按状态置灰 */
    private void updateActionBar() {
        boolean show = currentTab == TAB_DOWNLOADING;
        if (show) {
            int running = 0;
            int waiting = 0;
            int paused = 0;
            int failed = 0;
            for (DownloadTask t : DownloadManager.get().getTasks()) {
                if (t.state == DownloadTask.STATE_COMPLETED) continue;
                if (t.state == DownloadTask.STATE_DOWNLOADING) running++;
                else if (t.state == DownloadTask.STATE_WAITING) waiting++;
                else if (t.state == DownloadTask.STATE_SYSTEM_PAUSED) waiting++;
                else if (t.state == DownloadTask.STATE_PAUSED) paused++;
                else if (t.state == DownloadTask.STATE_FAILED) failed++;
            }
            boolean hasAny = (running + waiting + paused + failed) > 0;
            mBinding.llActions.setVisibility(hasAny ? View.VISIBLE : View.GONE);
            boolean canPause = running + waiting > 0;
            boolean canStart = paused + failed > 0;
            mBinding.btnPauseAll.setEnabled(canPause);
            mBinding.btnStartAll.setEnabled(canStart);
        } else {
            mBinding.llActions.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // 下载完成:基于"下载完成记录表"(DownloadManager 持久化的已完成任务)构建
    // 展示前按记录里的 savePath 检查文件是否还在,不在则更新记录表移除
    // ------------------------------------------------------------------

    /** 从下载完成记录构建文件夹列表(按剧名分组) */
    private List<VideoFolder> buildDoneFoldersFromRecords() {
        List<VideoFolder> folders = new ArrayList<>();
        Map<String, List<DownloadTask>> groups = new LinkedHashMap<>();
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED || t.savePath == null) continue;
            groups.computeIfAbsent(t.vodName == null ? t.groupName : t.vodName, k -> new ArrayList<>()).add(t);
        }
        for (Map.Entry<String, List<DownloadTask>> e : groups.entrySet()) {
            List<VideoInfo> videos = new ArrayList<>();
            for (DownloadTask t : e.getValue()) {
                File f = new File(t.savePath);
                if (!f.exists()) continue; // 双保险:文件已不存在则跳过
                VideoInfo info = new VideoInfo();
                info.setPath(f.getAbsolutePath());
                info.setDisplayName(t.fileName == null ? f.getName() : t.fileName);
                info.setTitle(info.getDisplayName());
                info.setSize(f.length());
                info.setEpisodeId(t.episodeId); // 统一剧集标识:回跳详情页/本地播放联动用
                videos.add(info);
            }
            if (!videos.isEmpty()) {
                VideoFolder folder = new VideoFolder(e.getKey(), videos);
                folder.setSourceName(e.getValue().get(0).sourceName);
                folders.add(folder);
            }
        }
        folders.sort(Comparator.comparing(VideoFolder::getName));
        return folders;
    }

    /** 某剧名下已完成且文件存在的视频列表(记录驱动,按文件名排序) */
    private List<VideoInfo> buildFolderVideosFromRecords(String vodName) {
        List<VideoInfo> videos = new ArrayList<>();
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED || t.savePath == null) continue;
            if (!vodName.equals(t.vodName == null ? t.groupName : t.vodName)) continue;
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

    private void openFolder(VideoFolder folder) {
        currentFolder = folder;
        toggleSelectMode(false);
        localVideoAdapter.setNewData(folder.getVideoList());
        mBinding.rvDone.setAdapter(localVideoAdapter);
        updateNavBar();
    }

    private void backToFolders() {
        currentFolder = null;
        toggleSelectMode(false);
        mBinding.rvDone.setAdapter(folderAdapter);
        folderAdapter.setNewData(buildDoneFoldersFromRecords());
        updateNavBar();
    }

    private void toggleSelectMode(boolean open) {
        localVideoAdapter.setSelectMode(open);
        mBinding.llMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        if (!open) {
            mBinding.tvDelete.setEnabled(false);
            mBinding.tvDelete.setTextColor(ColorUtils.getColor(R.color.disable_text));
            localVideoAdapter.notifyDataSetChanged();
        }
    }

    private void cancelAllChecked() {
        for (VideoInfo item : localVideoAdapter.getData()) {
            item.setChecked(false);
        }
        localVideoAdapter.notifyDataSetChanged();
    }

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
                    toggleSelectMode(false);
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
