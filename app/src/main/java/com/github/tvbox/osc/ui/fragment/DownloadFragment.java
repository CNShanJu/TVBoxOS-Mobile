package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ColorUtils;
import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.ToastUtils;
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
import com.github.tvbox.osc.util.DownloadManager;
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

        // 下载并发选择(1-5)
        mBinding.tvConcurrent.setText("并发:" + DownloadManager.get().getMaxConcurrent());
        mBinding.tvConcurrent.setOnClickListener(v -> {
            String[] options = new String[]{"并发 1", "并发 2", "并发 3", "并发 4", "并发 5"};
            new XPopup.Builder(mContext)
                    .asBottomList("选择下载并发", options, (position, text) -> {
                        DownloadManager.get().setMaxConcurrent(position + 1);
                        mBinding.tvConcurrent.setText("并发:" + (position + 1));
                    })
                    .show();
        });

        // 正在下载:按剧名分组(文件夹级)
        vodGroupAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_download_vod_group) {
            @Override
            protected void convert(BaseViewHolder helper, String vodName) {
                helper.setText(R.id.tv_vod_name, vodName);
                int count = 0;
                for (DownloadTask t : DownloadManager.get().getTasks()) {
                    if (t.state != DownloadTask.STATE_COMPLETED && vodName.equals(vodNameOf(t))) count++;
                }
                helper.setText(R.id.tv_vod_count, count + " 个任务");
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
                helper.setText(R.id.tv_group, task.vodName == null ? task.groupName : task.vodName);
                helper.setText(R.id.tv_name, task.fileName);
                int percent = task.getProgressPercent();
                helper.setText(R.id.tv_percent, buildPercentText(task));
                ProgressBar pb = helper.getView(R.id.progress);
                pb.setProgress(percent);
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
        mBinding.rvDownloading.setAdapter(downloadingAdapter);

        // 下载完成:文件夹列表(剧名 + 来源/个数,两排,与本地视频同款封面)
        folderAdapter = new BaseQuickAdapter<VideoFolder, BaseViewHolder>(R.layout.item_folder) {
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
                        .placeholder(R.drawable.iv_video)
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
    }

    private String vodNameOf(DownloadTask t) {
        return t.vodName == null ? t.groupName : t.vodName;
    }

    /** 刷新"下载完成":根据当前层级刷新 */
    private void refreshDoneList() {
        if (currentFolder == null) {
            folderAdapter.setNewData(scanDownloadFolders());
        } else {
            List<VideoInfo> files = scanFolderFiles(currentFolderNameToPath());
            if (files == null) {
                // 文件夹已不存在,退回文件夹级
                backToFolders();
                return;
            }
            currentFolder = new VideoFolder(currentFolder.getName(), files);
            localVideoAdapter.setNewData(files);
            if (localVideoAdapter.isSelectMode()) toggleSelectMode(false);
        }
    }

    // ------------------------------------------------------------------
    // 下载完成:文件夹 / 文件 两级浏览
    // ------------------------------------------------------------------

    /** 扫描下载根目录,按 来源/剧名 目录分组为文件夹(名称=剧名,来源单独存) */
    private List<VideoFolder> scanDownloadFolders() {
        List<VideoFolder> folders = new ArrayList<>();
        File base = DownloadManager.getSaveDir();
        File[] sources = base.listFiles();
        if (sources == null) return folders;
        for (File src : sources) {
            if (!src.isDirectory()) continue;
            File[] vods = src.listFiles();
            if (vods == null) continue;
            for (File vod : vods) {
                if (!vod.isDirectory()) continue;
                List<VideoInfo> videos = scanFolderFiles(vod.getAbsolutePath());
                if (videos != null && !videos.isEmpty()) {
                    VideoFolder folder = new VideoFolder(vod.getName(), videos);
                    folder.setSourceName(src.getName());
                    folders.add(folder);
                }
            }
        }
        folders.sort(Comparator.comparing(VideoFolder::getName));
        return folders;
    }

    /** 扫描某目录下的视频文件(按文件名排序) */
    private List<VideoInfo> scanFolderFiles(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return new ArrayList<>();
        List<VideoInfo> videos = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && isVideoFile(f.getName())) {
                VideoInfo info = new VideoInfo();
                info.setPath(f.getAbsolutePath());
                info.setDisplayName(f.getName());
                info.setTitle(f.getName());
                info.setSize(f.length());
                videos.add(info);
            }
        }
        videos.sort(Comparator.comparing(VideoInfo::getDisplayName));
        return videos;
    }

    private String currentFolderNameToPath() {
        // 路径 = 下载根目录/来源/剧名
        String source = currentFolder.getSourceName();
        String name = currentFolder.getName();
        return new File(new File(DownloadManager.getSaveDir(), source == null ? "" : source), name).getAbsolutePath();
    }

    private boolean isVideoFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".flv") || lower.endsWith(".ts") || lower.endsWith(".mov")
                || lower.endsWith(".webm") || lower.endsWith(".m4v");
    }

    private void openFolder(VideoFolder folder) {
        currentFolder = folder;
        toggleSelectMode(false);
        localVideoAdapter.setNewData(folder.getVideoList());
        mBinding.rvDone.setAdapter(localVideoAdapter);
    }

    private void backToFolders() {
        currentFolder = null;
        toggleSelectMode(false);
        mBinding.rvDone.setAdapter(folderAdapter);
        folderAdapter.setNewData(scanDownloadFolders());
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
                .isDarkTheme(true)
                .asConfirm("提示", "确定删除所选视频吗？", () -> {
                    List<VideoInfo> data = new ArrayList<>(localVideoAdapter.getData());
                    for (VideoInfo item : data) {
                        if (item.isChecked()) {
                            removeTaskAndFile(item.getPath());
                        }
                    }
                    toggleSelectMode(false);
                    // 刷新(文件删除后重新扫描)
                    refresh();
                })
                .show();
    }

    /** 删除下载文件并同步移除对应下载任务 */
    private void removeTaskAndFile(String path) {
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (t.savePath != null && t.savePath.equals(path)) {
                DownloadManager.get().remove(t); // remove 内部会删除文件
                return;
            }
        }
        // 无任务记录,直接删文件
        File f = new File(path);
        if (f.exists()) f.delete();
    }

    /** 用内置播放器播放下载的文件(与"我的-本地视频"一致) */
    private void playFile(VideoInfo info) {
        try {
            if (!new File(info.getPath()).exists()) {
                ToastUtils.showShort("文件不存在");
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
            ToastUtils.showShort("播放失败:" + th.getMessage());
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
        if (t.state == DownloadTask.STATE_FAILED && t.message != null && !t.message.isEmpty()) {
            sb.append(" 失败:").append(t.message);
        }
        return sb.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
