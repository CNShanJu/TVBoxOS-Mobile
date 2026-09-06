package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.ui.adapter.TitleWithDelAdapter;
import com.github.tvbox.osc.util.FileUtils;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 备份/还原弹窗（统一走 XPopup 底部弹窗 AppBottomPopupView；观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变：{@code new BackupDialog(ctx).show()}——{@link #show()} 在 popupInfo 未绑定时
 * 自动经 XPopup.Builder 绑定，兼容旧 Dialog 式调用点。
 */
public class BackupDialog extends AppBottomPopupView {

    public BackupDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_backup;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        TitleWithDelAdapter adapter = new TitleWithDelAdapter();
        tvRecyclerView.setAdapter(adapter);
        adapter.setNewData(allBackup());
        adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.tvName) {
                    restore((String) adapter.getItem(position));
                } else if (view.getId() == R.id.tvDel) {
                    delete((String) adapter.getItem(position));
                    adapter.setNewData(allBackup());
                }
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backup();
                adapter.setNewData(allBackup());
            }
        });
    }

    /** 兼容旧调用点：popupInfo 未绑定（直接 new 未走 Builder）时经 Builder 绑定后展示 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return DialogCoordinator.bottom(getContext(), this, -1).show();
        }
        return super.show();
    }

    List<String> allBackup() {
        ArrayList<String> result = new ArrayList<>();
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
            File[] list = file.exists() ? file.listFiles() : null;
            if (list != null) {
                Arrays.sort(list, new Comparator<File>() {
                    @Override
                    public int compare(File o1, File o2) {
                        if (o1.isDirectory() && o2.isFile()) return -1;
                        return o1.isFile() && o2.isDirectory() ? 1 : o2.getName().compareTo(o1.getName());
                    }
                });
                for (File f : list) {
                    if (result.size() > 10) {
                        FileUtils.recursiveDelete(f);
                        continue;
                    }
                    if (f.isDirectory()) {
                        result.add(f.getName());
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return result;
    }

    void restore(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            if (!backup.exists()) {
                AppBubble.toast("未找到备份目录");
                return;
            }
            // 1) 配置域(DataStore:系统/播放/订阅/直播/主页热播/下载配置...)先恢复,写后内存/磁盘立即生效
            File cfgFile = new File(backup, "config.json");
            byte[] cfgData = FileUtils.readSimple(cfgFile);
            if (cfgData != null) {
                com.github.tvbox.osc.config.PrefsDataStore.importJson(new String(cfgData, "UTF-8"));
            }
            // 2) Room DB(历史/收藏/缓存条目):旧备份可能只有 sqlite 没有 config.json,互不阻塞
            boolean dbOk = false;
            File db = new File(backup, "sqlite");
            if (db.exists()) {
                try {
                    dbOk = AppDataManager.restore(db);
                } catch (Throwable ignored) {
                }
            }
            AppBubble.toast("恢复成功,即将重启应用!");
            new Handler().postDelayed(() -> AppUtils.relaunchApp(true), 2000);
        } catch (Throwable e) {
            e.printStackTrace();
            AppBubble.toast("恢复数据流异常");
        }
    }

    void backup() {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File dir = new File(root + "/tvbox_backup/");
            if (!dir.exists())
                dir.mkdirs();
            Date now = new Date();
            File backup = new File(dir, new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(now));
            backup.mkdirs();

            // 1) 配置域(DataStore:系统/播放/订阅/直播/主页热播/下载配置...)——修复前只存了已退役的 Hawk2,导致备份"不生效"
            boolean cfgOk = FileUtils.writeSimple(
                    com.github.tvbox.osc.config.PrefsDataStore.exportJson().getBytes("UTF-8"),
                    new File(backup, "config.json"));

            // 2) Room DB(历史/收藏/缓存条目):新装可缺失,不阻塞整体备份
            boolean dbOk = false;
            try {
                dbOk = AppDataManager.backup(new File(backup, "sqlite"));
            } catch (Throwable ignored) {
            }

            if (cfgOk) {
                AppBubble.toast(dbOk ? "备份成功!" : "备份成功!(无历史/缓存数据)");
            } else {
                FileUtils.recursiveDelete(backup);
                AppBubble.toast("备份失败!");
            }
        } catch (Throwable e) {
            e.printStackTrace();
            AppBubble.toast("备份失败!");
        }
    }

    void delete(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            FileUtils.recursiveDelete(backup);
            AppBubble.toast("删除成功");
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
