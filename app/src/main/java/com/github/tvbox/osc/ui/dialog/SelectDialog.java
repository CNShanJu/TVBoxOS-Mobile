package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 通用选择弹窗（统一走 XPopup 居中弹窗 AppCenterPopupView，观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变：{@code new SelectDialog<>(ctx)} + {@code setTip/setAdapter} + {@code show()}——
 * XPopup 内容视图在 onCreate() 才能 findViewById，故 setter 只暂存参数、onCreate 内渲染；
 * {@link #show()} 在 popupInfo 未绑定时自动经 XPopup.Builder 绑定，兼容旧 Dialog 式调用点；
 * {@link #setOnDismissListener} 兼容旧 Dialog API（经 XPopupCallback.onDismiss 触发）。
 */
public class SelectDialog<T> extends AppCenterPopupView {

    private final int layoutId;

    private String tip;
    private SelectDialogAdapter.SelectDialogInterface<T> selectInterface;
    private DiffUtil.ItemCallback<T> itemCallback;
    private List<T> data;
    private int selectPos;
    private DialogInterface.OnDismissListener onDismissListener;

    public SelectDialog(@NonNull @NotNull Context context) {
        this(context, R.layout.dialog_select);
    }

    public SelectDialog(@NonNull @NotNull Context context, int resId) {
        super(context);
        layoutId = resId;
    }

    @Override
    protected int getImplLayoutId() {
        return layoutId;
    }

    /** 布局自带滚动区(TvRecyclerView),超高由列表自滚,不整卡包裹 */
    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        findViewById(R.id.iv_close).setOnClickListener(view -> dismiss());
        if (tip != null) {
            setTip(tip);
        }
        if (selectInterface != null && itemCallback != null && data != null) {
            setAdapter(selectInterface, itemCallback, data, selectPos);
        }
        clampListHeightToFit();
        // 自绘滚动指示条:RecyclerView 系统滚动条静止不绘制,列表超高时用右侧 thumb 提示可滚动
        android.view.View list = findViewById(R.id.list);
        android.view.View thumb = findViewById(R.id.scroll_thumb);
        if (list != null && thumb != null) {
            ScrollThumbIndicator.attach((com.owen.tvrecyclerview.widget.TvRecyclerView) list, thumb);
        }
    }

    /**
     * 内容自带滚动区(TvRecyclerView):超高由列表自滚、标题固定,不整卡包裹。
     * 列表可用高 = maxHeight(70%屏) - 固定区(标题+上下边距);
     * 用 UNSPECIFIED 量"自然内容高"判断是否超高(不受 XPopup 容器已钳高影响),
     * 超高时把列表压到可用高内,由 TvRecyclerView 自己滚动。
     */
    private void clampListHeightToFit() {
        final android.view.View root = findViewById(R.id.cl_root);
        final android.view.View list = findViewById(R.id.list);
        if (root == null || list == null) return;
        list.post(this::applyListHeightClamp);
    }

    private void applyListHeightClamp() {
        try {
            final android.view.View root = findViewById(R.id.cl_root);
            final android.view.View list = findViewById(R.id.list);
            if (root == null || list == null) return;
            int maxH = getMaxHeight();
            if (maxH <= 0) return;
            // 用 UNSPECIFIED 重新测量整卡"自然高"(不受 XPopup 容器钳高影响),判断是否真的超高
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(root.getWidth(), android.view.View.MeasureSpec.EXACTLY);
            int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
            root.measure(wSpec, hSpec);
            int naturalH = root.getMeasuredHeight();
            if (naturalH <= maxH) return; // 未超高:保持 wrap(短列表自适应)
            int naturalListH = list.getMeasuredHeight();
            int fixedH = naturalH - naturalListH;
            int available = maxH - fixedH;
            if (available <= 0) available = maxH / 2;
            android.view.ViewGroup.LayoutParams lp = list.getLayoutParams();
            lp.height = available;
            list.setLayoutParams(lp);
            list.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    /** 兼容旧调用点：popupInfo 未绑定（直接 new 未走 Builder）时经 Builder 绑定后展示 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            XPopup.Builder builder = new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme());
            if (onDismissListener != null) {
                builder.setPopupCallback(new XPopupCallback() {
                    @Override public void onCreated(BasePopupView v) { }
                    @Override public void beforeShow(BasePopupView v) { }
                    @Override public void onShow(BasePopupView v) { }
                    @Override public void onDismiss(BasePopupView v) {
                        DialogInterface.OnDismissListener l = onDismissListener;
                        onDismissListener = null;
                        if (l != null) l.onDismiss(null);
                    }
                    @Override public void beforeDismiss(BasePopupView v) { }
                    @Override public boolean onBackPressed(BasePopupView v) { return false; }
                    @Override public void onKeyBoardStateChanged(BasePopupView v, int h) { }
                    @Override public void onDrag(BasePopupView v, int c, float x, boolean b) { }
                    @Override public void onClickOutside(BasePopupView v) { }
                });
            }
            return builder.asCustom(this).show();
        }
        return super.show();
    }

    /** 兼容旧 Dialog API：dismiss 后回调（同一次展示只触发一次） */
    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        this.onDismissListener = listener;
    }

    /** 兼容旧 Dialog API：cancel() ≈ dismiss() */
    public void cancel() {
        dismiss();
    }

    public void setTip(String tip) {
        this.tip = tip;
        if (findViewById(R.id.title) != null) {
            ((android.widget.TextView) findViewById(R.id.title)).setText(tip);
        }
    }

    public void setAdapter(SelectDialogAdapter.SelectDialogInterface<T> sourceBeanSelectDialogInterface,
                           DiffUtil.ItemCallback<T> sourceBeanItemCallback, List<T> data, int select) {
        this.selectInterface = sourceBeanSelectDialogInterface;
        this.itemCallback = sourceBeanItemCallback;
        this.data = data;
        this.selectPos = select;
        if (findViewById(R.id.list) == null) {
            return; // 尚未 inflate：onCreate 会再渲染
        }
        SelectDialogAdapter<T> adapter = new SelectDialogAdapter(sourceBeanSelectDialogInterface, sourceBeanItemCallback);
        adapter.setData(data, select);
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        tvRecyclerView.setAdapter(adapter);
        tvRecyclerView.setSelectedPosition(select);
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                tvRecyclerView.smoothScrollToPosition(select);
                tvRecyclerView.setSelectionWithSmooth(select);
            }
        });
    }
}

