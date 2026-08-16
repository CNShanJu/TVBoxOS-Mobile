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

    /** 正在下载:按剧名分组的文件夹级 adapter */
    private BaseQuickAdapter<String, BaseViewHolder> vodGroupAdapter;
    private BaseQuickAdapter<DownloadTask, BaseViewHolder> downloadingAdapter;
    /** 正在下载的文件级:当前剧名(非空=该剧的任务列表) */
    private String currentVodGroup = null;
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

        // 设置:右侧设置 icon,内含下载并发/仅WiFi等选项(参考播放页设置入口)
        mBinding.ivSettings.setOnClickListener(v -> {
            boolean wifiOnly = DownloadManager.get().isWifiOnly();
            String[] options = new String[]{
                    "下载并发（当前 " + DownloadManager.get().getMaxConcurrent() + "）",
                    "仅 WiFi 下载（" + (wifiOnly ? "开" : "关") + "）"
            };
            new XPopup.Builder(mContext)
                    .asBottomList("下载设置", options, (position, text) -> {
                        if (position == 0) {
                            String[] concurrent = new String[]{"并发 1", "并发 2", "并发 3", "并发 4", "并发 5"};
                            new XPopup.Builder(mContext)
                                    .asBottomList("选择下载并发", concurrent, (p, t) ->
                                            DownloadManager.get().setMaxConcurrent(p + 1))
                                    .show();
                        } else {
                            boolean newVal = !DownloadManager.get().isWifiOnly();
                            DownloadManager.get().setWifiOnly(newVal);
                            AppBubble.toast("仅 WiFi 下载已" + (newVal ? "开启" : "关闭"));
                        }
                    })
                    .show();
        });

        // 全部暂停 / 全部开始:仅"正在下载"根级且有任务时显示(见 updateActionBar)
        mBinding.btnPauseAll.setOnClickListener(v -> {
            DownloadManager.get().pauseAll();
            AppBubble.toast("已全部暂停");
        });
        mBinding.btnStartAll.setOnClickListener(v -> {
            DownloadManager.get().startAll();
            AppBubble.toast("已全部开始");
        });

        // 当前位置导航条:点按返回上一级
        mBinding.llNav.setOnClickListener(v -> onBackPressed());

        // 正在下载:按剧名分组(文件夹级)
        vodGroupAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_download_vod_group) {
            @Override
            protected void convert(BaseViewHolder helper, String vodName) {
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
            }
        };
        vodGroupAdapter.setOnItemClickListener((adapter, view, position) -> {
            String vodName = vodGroupAdapter.getItem(position);
            if (vodName == null) return;
            currentVodGroup = vodName;
            mBinding.rvDownloading.setAdapter(downloadingAdapter);
            refreshDownloadingList();
        });
        mBinding.rvDownloading.setAdapter(vodGroupAdapter);

        // 正在下载:该剧的任务列表(文件级)
        downloadingAdapter = new BaseQuickAdapter<DownloadTask, BaseViewHolder>(R.layout.item_download_task) {
            @Override
            protected void convert(BaseViewHolder helper, DownloadTask task) {
                helper.setText(R.id.tv_name, task.fileName);
                // 状态徽标:下载中/等待中/排队中(调度暂停)/已暂停(用户暂停)/失败
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
                    // 下载中:按 message 显示合并阶段(文件校验中/文件合并中)
                    if (DownloadManager.MSG_VERIFYING.equals(task.message)) {
                        status = DownloadManager.MSG_VERIFYING;
                    } else if (DownloadManager.MSG_MERGING.equals(task.message)) {
                        status = DownloadManager.MSG_MERGING;
                    } else {
                        status = "下载中";
                    }
                    statusColor = ContextCompat.getColor(mContext, R.color.download_active);
                }
                helper.setText(R.id.tv_group, status);
                ((TextView) helper.getView(R.id.tv_group)).setTextColor(statusColor);
                int percent = task.getProgressPercent();
                helper.setText(R.id.tv_percent, buildPercentText(task));
                ProgressBar pb = helper.getView(R.id.progress);
                pb.setProgress(percent);
                // 进度条状态色:下载中=绿(download_done),其余(暂停/等待/排队/失败)置灰
                if (task.state == DownloadTask.STATE_DOWNLOADING) {
                    pb.setProgressTintList(ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.download_done)));
                } else {
                    pb.setProgressTintList(ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.gray_darker)));
                }
                String btn;
                if (task.state == DownloadTask.STATE_PAUSED) btn = "继 续";
                else if (task.state == DownloadTask.STATE_FAILED) btn = "重 试";
                else btn = "暂 停";
                helper.setText(R.id.btn_pause, btn);
                helper.addOnClickListener(R.id.btn_pause, R.id.btn_delete);
            }
        };
        downloadingAdapter.setOnItemChildClickListener((adapter, view, position) -> {
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return;
            DownloadTask t = data.get(position);
            if (view.getId() == R.id.btn_pause) {
                if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
                    DownloadManager.get().resume(t);
                } else {
                    DownloadManager.get().pause(t);
                }
            } else if (view.getId() == R.id.btn_delete) {
                DownloadManager.get().remove(t);
            }
        });
        // 注意:不能在这里 setAdapter(downloadingAdapter),否则会顶掉上面的文件夹级适配器,
        // 导致"正在下载"默认视图永远空白(任务只在点进文件夹后可见)

        // 下载完成:文件夹列表(剧名 + 来源/个数,卡片式)
        folderAdapter = new BaseQuickAdapter<VideoFolder, BaseViewHolder>(R.layout.item_download_folder) {
            @Override
            protected void convert(BaseViewHolder helper, VideoFolder folder) {
                List<VideoInfo> videoList = folder.getVideoList();
                // 第一排:电视剧名称
                helper.setText(R.id.tv_name, folder.getName());
                // 第二排:来源 / N 个视频
                String source = folder.getSourceName();
                helper.setText(R.id.tv_count, (source == null || source.isEmpty() ? "" : source + " / ") + videoList.size() + " 个视频");
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
        if (currentVodGroup != null) {
            currentVodGroup = null;
            mBinding.rvDownloading.setAdapter(vodGroupAdapter);
            refreshDownloadingList();
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
    }

    /** 刷新"正在下载":文件夹级按剧名分组,文件级显示该剧任务 */
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
            // 文件夹级:按剧名分组,组顺序按组内最早加入时间
            Map<String, Long> firstTime = new LinkedHashMap<>();
            for (DownloadTask t : downloading) {
                firstTime.computeIfAbsent(vodNameOf(t), k -> t.createTime);
            }
            List<String> groups = new ArrayList<>(firstTime.keySet());
            groups.sort(Comparator.comparingLong(firstTime::get));
            vodGroupAdapter.setNewData(groups);
        } else {
            // 文件级:该剧的任务,按加入时间排序
            List<DownloadTask> list = new ArrayList<>();
            for (DownloadTask t : downloading) {
                if (currentVodGroup.equals(vodNameOf(t))) list.add(t);
            }
            if (list.isEmpty()) {
                currentVodGroup = null;
                mBinding.rvDownloading.setAdapter(vodGroupAdapter);
                refreshDownloadingList();
                return;
            }
            downloadingAdapter.setNewData(list);
        }
        updateNavBar();
    }

    private String vodNameOf(DownloadTask t) {
        return t.vodName == null ? t.groupName : t.vodName;
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

    /** 刷新当前位置导航条:进入剧集文件夹后显示"xx › 剧名",点按返回上一级;跟随当前 tab */
    private void updateNavBar() {
        if (currentTab == TAB_DOWNLOADING) {
            boolean inFolder = currentVodGroup != null;
            mBinding.llNav.setVisibility(inFolder ? View.VISIBLE : View.GONE);
            if (inFolder) mBinding.tvNavPath.setText("正在下载 › " + currentVodGroup);
        } else {
            boolean inFolder = currentFolder != null;
            mBinding.llNav.setVisibility(inFolder ? View.VISIBLE : View.GONE);
            if (inFolder) mBinding.tvNavPath.setText("下载完成 › " + currentFolder.getName());
        }
        updateActionBar();
        updateStorageText();
    }

    /** 刷新底部"可用存储"提示 */
    private void updateStorageText() {
        try {
            File dir = DownloadManager.getSaveDir();
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long free = stat.getAvailableBytes();
            mBinding.tvStorage.setText("可用存储 " + formatSize(free));
        } catch (Throwable th) {
            mBinding.tvStorage.setText("");
        }
    }

    /** 刷新"全部暂停/全部开始"操作行:仅"正在下载"根级且有任务时显示,按钮按状态置灰 */
    private void updateActionBar() {
        boolean show = currentTab == TAB_DOWNLOADING && currentVodGroup == null;
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
            groups.computeIfAbsent(vodNameOf(t), k -> new ArrayList<>()).add(t);
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
            if (!vodName.equals(vodNameOf(t))) continue;
            File f = new File(t.savePath);
            if (!f.exists()) continue;
            VideoInfo info = new VideoInfo();
            info.setPath(f.getAbsolutePath());
            info.setDisplayName(t.fileName == null ? f.getName() : t.fileName);
            info.setTitle(info.getDisplayName());
            info.setSize(f.length());
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
     * 删除下载:找到对应任务记录移除;deleteFiles=true 连本地文件一起删,false 只删记录保留文件
     */
    private void removeTaskAndFile(String path, boolean deleteFiles) {
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.savePath != null && t.savePath.equals(path)) {
                DownloadManager.get().remove(t, deleteFiles);
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
