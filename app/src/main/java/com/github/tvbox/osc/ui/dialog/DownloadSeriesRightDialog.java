package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.ui.widget.RoundChip;
import com.github.tvbox.osc.util.AppBubble;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏"下载"右侧抽屉:选择下载剧集,布局与全屏选集抽屉一致。
 * <p>
 * 三 box 布局:标题 box / 集数列表 box(吃满剩余高度,内容超出内部滚动) / 按钮 box。
 * 点击"下载"立即弹出,数据异步准备完成后通过 {@link #setData(List, int[])} 填充。
 * states:0=可下载;1=已下载(置灰);2=下载中/排队(置灰)。
 */
public class DownloadSeriesRightDialog extends AppDrawerPopupView {

    public interface OnDownloadActionListener {
        /** 开始下载所选剧集 */
        void onStartDownload(List<VodInfo.VodSeries> selected);

        /** 打开下载管理页 */
        void onOpenDownloadManager();
    }

    private final OnDownloadActionListener mListener;
    private List<VodInfo.VodSeries> mList = new ArrayList<>();
    private int[] mStates = new int[0];
    private TextView mTvSelected;
    private RecyclerView mRv;
    private View mFlLoading;
    private ItemAdapter mAdapter;

    public DownloadSeriesRightDialog(@NonNull @NotNull Context context,
                                     OnDownloadActionListener listener) {
        super(context);
        mListener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_download_series_right;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mTvSelected = findViewById(R.id.tv_selected);
        mRv = findViewById(R.id.rv);
        mFlLoading = findViewById(R.id.fl_loading);

        // 加载动画用轻量默认动画(仅占位几百毫秒,避免渲染高帧率大动画导致卡顿/ANR)
        LottieAnimationView lav = findViewById(R.id.lottie_loading);
        lav.setAnimation("anim_loading.json");
        lav.setRepeatMode(LottieDrawable.RESTART);
        lav.setRepeatCount(LottieDrawable.INFINITE);
        lav.setSpeed(1f);
        lav.playAnimation();

        // 集数网格:固定3列,条目样式与选集一致(RoundChip 文字,无边框)
        mRv.setLayoutManager(new GridLayoutManager(getContext(), 3));
        mRv.addItemDecoration(new GridSpacingItemDecoration(3, 14, true));

        mAdapter = new ItemAdapter();
        mRv.setAdapter(mAdapter);
        mAdapter.setOnItemClickListener((a, view, position) -> {
            VodInfo.VodSeries item = mList != null && position >= 0 && position < mList.size() ? mList.get(position) : null;
            if (item == null) return;
            int st = mStates != null && position >= 0 && position < mStates.length ? mStates[position] : 0;
            if (st == 1) {
                AppBubble.toast("该集已下载完成");
                return;
            }
            if (st == 2) {
                AppBubble.toast("该集下载中或已在任务中");
                return;
            }
            item.selected = !item.selected;
            a.notifyItemChanged(position);
            updateCount();
        });

        // 数据未就绪前显示 loading
        if (mList == null || mList.isEmpty()) {
            mFlLoading.setVisibility(View.VISIBLE);
            mRv.setVisibility(View.GONE);
        }

        updateCount();
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            List<VodInfo.VodSeries> selected = new ArrayList<>();
            if (mList != null) {
                for (VodInfo.VodSeries s : mList) {
                    if (s.selected) selected.add(s);
                }
            }
            Log.i("TVBox-Download", "全屏下载抽屉:开始下载,已选 " + selected.size() + " 集");
            if (selected.isEmpty()) {
                AppBubble.toast("请先选择要下载的剧集");
                return;
            }
            dismiss();
            if (mListener != null) mListener.onStartDownload(selected);
        });
        findViewById(R.id.btn_manager).setOnClickListener(v -> {
            dismiss();
            if (mListener != null) mListener.onOpenDownloadManager();
        });
    }

    /** 数据准备完成后填充(主线程调用):显示选集网格,隐藏 loading */
    public void setData(List<VodInfo.VodSeries> list, int[] states) {
        mList = list != null ? list : new ArrayList<>();
        mStates = states != null ? states : new int[0];
        if (mFlLoading != null) {
            mFlLoading.setVisibility(View.GONE);
        }
        if (mRv != null) {
            mRv.setVisibility(View.VISIBLE);
            mAdapter.setNewData(mList);
        }
        updateCount();
    }

    private void updateCount() {
        int count = 0;
        if (mList != null) {
            for (VodInfo.VodSeries s : mList) {
                if (s.selected) count++;
            }
        }
        mTvSelected.setText("(已选 " + count + ")");
    }

    /** 集数条目:RoundChip 文字样式(与选集抽屉一致,无边框) */
    private class ItemAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
        ItemAdapter() {
            super(R.layout.item_series, mList);
        }

        @Override
        protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
            int pos = helper.getAdapterPosition();
            int st = mStates != null && pos >= 0 && pos < mStates.length ? mStates[pos] : 0;
            RoundChip chip = helper.getView(R.id.sl);
            chip.setTitle(item.name);
            if (st == 1 || st == 2) {
                // 已下载 / 下载中:置灰不可选
                chip.setDisabled(true);
                chip.setSelected(false);
            } else {
                chip.setDisabled(false);
                chip.setSelected(item.selected);
            }
        }
    }
}
