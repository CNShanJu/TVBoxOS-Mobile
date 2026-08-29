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
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.ui.widget.RoundChip;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LoadingAnim;
import com.github.tvbox.osc.util.Utils;

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

        /** 倒序排列剧集(与选集抽屉一致) */
        void onSortSeries();
    }

    private final OnDownloadActionListener mListener;
    /** 当前是否已倒序(倒序按钮文字切换;由 DetailActivity 注入 isSeriesReversed) */
    private final java.util.function.BooleanSupplier mIsReversed;
    private List<VodInfo.VodSeries> mList = new ArrayList<>();
    private int[] mStates = new int[0];
    private TextView mTvSelected;
    private TextView mTvSort;
    private RecyclerView mRv;
    private View mFlLoading;
    private ItemAdapter mAdapter;
    private GridLayoutManager mGridManager;
    private GridSpacingItemDecoration mGridDecoration;

    public DownloadSeriesRightDialog(@NonNull @NotNull Context context,
                                     OnDownloadActionListener listener,
                                     java.util.function.BooleanSupplier isReversed) {
        super(context);
        mListener = listener;
        mIsReversed = isReversed;
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

        // 加载动画跟随设置页"加载动画"配置(默认/Glowing Fish 等),与播放器/其他页一致
        LottieAnimationView lav = findViewById(R.id.lottie_loading);
        LoadingAnim.apply(lav);

        // 集数网格:最多3列,基于文字长度自适应(1列/2列/3列);数据未就绪前先用默认3列,setData 时按实际文字重算
        mGridManager = new GridLayoutManager(getContext(), 3);
        mRv.setLayoutManager(mGridManager);
        mGridDecoration = new GridSpacingItemDecoration(3, 14, true);
        mRv.addItemDecoration(mGridDecoration);

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
        // 倒序按钮:文字随共用状态切换(未倒序=倒序, 已倒序=正序)
        mTvSort = findViewById(R.id.tv_sort);
        updateSortButton();
        findViewById(R.id.tv_sort).setOnClickListener(v -> {
            if (mListener != null) mListener.onSortSeries();
            updateSortButton();
        });
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
    /** 当前展示的选集列表(供外部刷新副本时保留勾选, 避免下载状态刷新导致选中自动取消) */
    public List<VodInfo.VodSeries> getCurrentList() {
        return mList;
    }

    public void setData(List<VodInfo.VodSeries> list, int[] states) {
        mList = list != null ? list : new ArrayList<>();
        mStates = states != null ? states : new int[0];
        if (mFlLoading != null) {
            mFlLoading.setVisibility(View.GONE);
        }
        if (mRv != null) {
            mRv.setVisibility(View.VISIBLE);
            applySpan(Utils.getSeriesSpanCount(mList)); // 按实际集名长度自适应列数(最多3列)
            mAdapter.setNewData(mList);
        }
        updateCount();
    }

    /** 更新网格列数与间距装饰(1列/2列/3列) */
    private void applySpan(int span) {
        if (mRv == null || mGridManager == null) return;
        mGridManager.setSpanCount(span);
        mRv.removeItemDecoration(mGridDecoration);
        mGridDecoration = new GridSpacingItemDecoration(span, 14, true);
        mRv.addItemDecoration(mGridDecoration);
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

    /** 倒序按钮文字跟随共用状态:已倒序显示"正序",否则"倒序" */
    private void updateSortButton() {
        try {
            if (mTvSort != null && mIsReversed != null) {
                mTvSort.setText(mIsReversed.getAsBoolean() ? "正序" : "倒序");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 集数条目:RoundChip 文字样式(全屏抽屉专用,文字调大)+ 状态图标(已下载绿✓ / 下载中蓝↓) */
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
            chip.setChipTextSize(16f); // 全屏抽屉文字调大
            if (st == 1 || st == 2) {
                // 已下载 / 下载中:置灰不可选 + 状态图标
                chip.setFailed(false);
                chip.setDisabled(true);
                chip.setSelected(false);
                chip.setStateIcon(st == 1 ? R.drawable.ic_download_done : R.drawable.ic_download_active,
                        st == 1 ? R.color.download_done : R.color.download_active);
            } else if (st == 3) {
                // 下载失败:未选中红字红✗, 选中蓝字蓝✗(可区分, 与可下载项选中一致)
                chip.setFailed(true);
                chip.setDisabled(false);
                chip.setSelected(item.selected);
                chip.setStateIcon(R.drawable.ic_download_fail,
                        item.selected ? R.color.color_highlight : R.color.red);
            } else {
                chip.setFailed(false);
                chip.setDisabled(false);
                chip.setSelected(item.selected);
                chip.setStateIcon(0, 0); // 可下载无图标
            }
        }
    }
}
