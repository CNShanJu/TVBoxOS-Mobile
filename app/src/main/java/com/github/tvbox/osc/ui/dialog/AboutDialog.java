package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.update.Updater;
import com.github.tvbox.osc.update.UpdaterProvider;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.util.AppBubble;
import com.google.android.material.button.MaterialButton;
import com.lxj.xpopup.core.BottomPopupView;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends AppBottomPopupView {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_about;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        findViewById(R.id.iv_close).setOnClickListener(v -> dismiss());

        final android.widget.TextView tvStatus = findViewById(R.id.tv_update_status);
        final MaterialButton btn = findViewById(R.id.btn_check_update);

        btn.setOnClickListener(v -> {
            btn.setEnabled(false);
            showStatus(tvStatus, "正在检查更新...");

            // 整个"检查更新"动作经 Updater 接口触发,实现由配置切换(见 UpdaterProvider)
            final Updater updater = UpdaterProvider.get();
            updater.checkUpdate(getContext(), new Updater.Callback() {
                @Override
                public void onCheckStart() {
                    showStatus(tvStatus, "正在检查更新...");
                }

                @Override
                public void onCheckResult(UpdateInfo newVersion) {
                    btn.setEnabled(true);
                    if (newVersion == null) {
                        tvStatus.setText("当前已是最新版本");
                        return;
                    }
                    tvStatus.setText("发现新版本 v" + newVersion.versionName);
                    String note = newVersion.releaseNote;
                    String msg = (note == null || note.trim().isEmpty())
                            ? "发现新版本 v" + newVersion.versionName + ",是否下载并安装?"
                            : "发现新版本 v" + newVersion.versionName + ":\n" + note.trim();
                    ConfirmDialog.show(getContext(), "检查更新", msg, "立即更新", () -> {
                        AppBubble.toast("已开始下载,进度可通过悬浮圆圈查看/控制");
                        updater.downloadAndInstall(getContext(), newVersion, new Updater.Callback() {
                                @Override
                                public void onCheckStart() {
                                }

                                @Override
                                public void onCheckResult(UpdateInfo info) {
                                }

                                @Override
                                public void onDownloadProgress(long current, long total) {
                                    if (total > 0) {
                                        int p = (int) (current * 100 / total);
                                        showStatus(tvStatus, "正在下载 " + p + "%");
                                    } else {
                                        showStatus(tvStatus, "正在下载...");
                                    }
                                }

                                @Override
                                public void onDownloadReady(UpdateInfo info) {
                                    tvStatus.setText("下载完成,点悬浮圆圈安装");
                                    btn.setEnabled(true);
                                }

                                @Override
                                public void onError(String message) {
                                    tvStatus.setVisibility(View.GONE);
                                    btn.setEnabled(true);
                                    AppBubble.toast(message);
                                }
                            });
                    });
                }

                @Override
                public void onDownloadProgress(long current, long total) {
                }

                @Override
                public void onDownloadReady(UpdateInfo info) {
                }

                @Override
                public void onError(String message) {
                    tvStatus.setVisibility(View.GONE);
                    btn.setEnabled(true);
                    AppBubble.toast(message);
                }
            });
        });
    }

    private static void showStatus(android.widget.TextView tvStatus, String text) {
        if (tvStatus != null) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(text);
        }
    }
}
