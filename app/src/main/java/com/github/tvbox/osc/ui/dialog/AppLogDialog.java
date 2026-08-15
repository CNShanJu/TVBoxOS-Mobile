package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.databinding.DialogAppLogBinding;
import com.github.tvbox.osc.util.AppLog;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.CenterPopupView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行日志弹窗:按天查看 / 清空 / 导出
 */
public class AppLogDialog extends CenterPopupView {

    public AppLogDialog(@NonNull Context context) {
        super(context);
    }

    private DialogAppLogBinding binding;
    private final List<File> dayFiles = new ArrayList<>();
    private File selectedFile = null;

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_app_log;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        binding = DialogAppLogBinding.bind(getPopupImplView());

        binding.rvDays.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.btnClose.setOnClickListener(v -> dismiss());
        binding.btnClear.setOnClickListener(v -> confirmClear());
        binding.btnExport.setOnClickListener(v -> export());

        refreshDays();
        refreshContent();
    }

    private void refreshDays() {
        dayFiles.clear();
        dayFiles.addAll(AppLog.listLogFiles());
        List<String> display = new ArrayList<>();
        for (File f : dayFiles) {
            int count = AppLog.readLines(f).size();
            display.add(f.getName().replace("app-", "").replace(".log", "") + " (" + count + "条)");
        }
        BaseQuickAdapter<String, BaseViewHolder> adapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_title, display) {
            @Override
            protected void convert(BaseViewHolder helper, String item) {
                helper.setText(R.id.title, item);
            }
        };
        adapter.setOnItemClickListener((adapter1, view, position) -> {
            if (position >= 0 && position < dayFiles.size()) {
                selectedFile = dayFiles.get(position);
                refreshContent();
            }
        });
        binding.rvDays.setAdapter(adapter);
        if (!dayFiles.isEmpty()) {
            selectedFile = dayFiles.get(0);
        }
    }

    private void refreshContent() {
        if (selectedFile == null) {
            binding.tvContent.setText("暂无日志");
            return;
        }
        List<String> lines = AppLog.readLines(selectedFile);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append("\n");
        binding.tvContent.setText(sb.length() == 0 ? "暂无内容" : sb.toString());
    }

    private void confirmClear() {
        new XPopup.Builder(getContext())
                .asConfirm("清空日志", "确定清空全部运行日志吗？", () -> {
                    AppLog.clearAll();
                    selectedFile = null;
                    refreshDays();
                    refreshContent();
                    ToastUtils.showShort("已清空");
                }).show();
    }

    private void export() {
        File file = AppLog.exportAll();
        if (file == null) {
            ToastUtils.showShort("暂无日志可导出");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(getContext(), App.getInstance().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(Intent.createChooser(intent, "导出运行日志"));
        } catch (Throwable th) {
            th.printStackTrace();
            ToastUtils.showShort("导出失败:" + th.getMessage());
        }
    }
}
