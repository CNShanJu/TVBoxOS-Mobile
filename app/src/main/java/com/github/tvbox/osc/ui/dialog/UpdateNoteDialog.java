package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.util.MdText;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 更新确认弹窗:展示版本与更新说明(说明支持轻量 Markdown 渲染、左对齐、可滚动),
 * 提供「稍后 / 立即更新」;替代旧版纯文本居中拼接的确认框。
 * 统一经 {@link #show(Context, UpdateInfo, Runnable)} 弹出(内部走 Builder 绑定主题)。
 */
public class UpdateNoteDialog extends AppCenterPopupView {

    /** 统一弹出入口:XPopup.Builder 绑定 popupInfo 后 show,context 须为 Activity */
    public static void show(Context context, UpdateInfo info, Runnable onUpdate) {
        new XPopup.Builder(context)
                .isDarkTheme(Utils.isAppDarkTheme())
                .asCustom(new UpdateNoteDialog(context, info, onUpdate))
                .show();
    }

    private final UpdateInfo mInfo;
    private final Runnable mOnUpdate;

    public UpdateNoteDialog(@NonNull @NotNull Context context, UpdateInfo info, Runnable onUpdate) {
        super(context);
        mInfo = info;
        mOnUpdate = onUpdate;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_update_note;
    }

    /**
     * 布局自带滚动区(内部 note_scroll):超高时只让中间"更新内容"滚动,
     * 禁止基类把整卡(标题/按钮)包进外层 ScrollView,避免"所有内容都滚动"。
     */
    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TextView tvTitle = findViewById(R.id.note_title);
        TextView tvBody = findViewById(R.id.note_body);
        String version = mInfo == null || mInfo.versionName == null ? "" : mInfo.versionName;
        tvTitle.setText("发现新版本 v" + version);

        String note = mInfo == null ? "" : mInfo.releaseNote;
        if (note != null && !note.trim().isEmpty()) {
            tvBody.setText(MdText.render(note)); // Markdown → 加粗标题/列表圆点,左对齐
        } else {
            tvBody.setText("是否立即下载并安装?");
        }

        // 说明超长时把"更新内容"区限高到 最大高度−标题/按钮固定区,内容区自滚(标题/按钮固定)
        clampBodyHeight();

        findViewById(R.id.note_close).setOnClickListener(v -> dismiss());
        findViewById(R.id.note_later).setOnClickListener(v -> dismiss());
        findViewById(R.id.note_update).setOnClickListener(v -> {
            dismiss();
            if (mOnUpdate != null) mOnUpdate.run();
        });
    }

    /** 说明区限高:仅当自然高度超过可用空间(最大高度−标题/按钮固定区)时压缩,内容区自滚 */
    private void clampBodyHeight() {
        try {
            final View content = getPopupImplView();
            final ScrollView scroll = findViewById(R.id.note_scroll);
            if (content == null) return;
            content.post(() -> {
                try {
                    int maxH = DialogHeightPolicy.maxHeightPx(getContext());
                    int reserved = content.getHeight() - scroll.getHeight(); // 标题行+按钮行+内边距 等固定高度
                    int available = maxH - reserved;
                    if (available > 0 && scroll.getHeight() > available) {
                        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                        lp.height = available;
                        scroll.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isAppDarkTheme())
                    .asCustom(this).show();
        }
        return super.show();
    }
}
