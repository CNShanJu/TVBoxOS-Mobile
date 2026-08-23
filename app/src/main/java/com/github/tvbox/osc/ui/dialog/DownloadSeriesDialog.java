package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.drawable.Drawable;
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
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LoadingAnim;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.BottomPopupView;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 选择下载剧集弹窗(底部):三 box 布局(标题 / 集数列表吃满剩余+内部滚动 / 按钮)。
 * 点击"下载"立即弹出,内容先 loading;数据异步准备完成后通过 {@link #setData(List, int[])} 填充。
 * states:0=可下载;1=已下载(置灰);2=下载中/排队(置灰)。
 */
public class DownloadSeriesDialog extends BottomPopupView {

    public interface OnDownloadActionListener {
        /** 开始下载所选剧集(selected 为已勾选的列表) */
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
    private GridLayoutManager mGridManager;
    private GridSpacingItemDecoration mGridDecoration;

    public DownloadSeriesDialog(@NonNull @NotNull Context context,
                                OnDownloadActionListener listener) {
        super(context);
        mListener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_download_series;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mTvSelected = findViewById(R.id.tv_selected);
        mRv = findViewById(R.id.rv);
        mFlLoading = findViewById(R.id.fl_loading);

        // 加载动画用轻量默认动画(仅占位几百毫秒,避免渲染高帧率大动画导致卡顿/ANR)
        LottieAnimationView lav = findViewById(R.id.lottie_loading);
        lav.setAnimation(LoadingAnim.getDefaultFileName());
        lav.setRepeatMode(LottieDrawable.RESTART);
        lav.setRepeatCount(LottieDrawable.INFINITE);
        lav.setSpeed(1f);
        lav.playAnimation();

        // 集数网格:最多3列,基于文字长度自适应(1列/2列/3列);数据未就绪前先用默认3列,setData 时按实际文字重算
        mGridManager = new GridLayoutManager(getContext(), 3);
        mRv.setLayoutManager(mGridManager);
        mGridDecoration = new GridSpacingItemDecoration(3, 20, true);
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
            item.selected = !item.selected; // 多选:点一下选中,再点取消
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
            Log.i("TVBox-Download", "开始下载:已选 " + selected.size() + " 集");
            if (selected.isEmpty()) {
                // 未选择:仅提醒,不关闭弹窗
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
        mGridDecoration = new GridSpacingItemDecoration(span, 20, true);
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

    /** 集数条目:圆角框样式(与选集抽屉一致)+ 状态图标(已下载绿✓ / 下载中蓝↓),选中蓝框蓝字 */
    private class ItemAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
        ItemAdapter() {
            super(R.layout.item_download_select, mList);
        }

        @Override
        protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
            int pos = helper.getAdapterPosition();
            int st = mStates != null && pos >= 0 && pos < mStates.length ? mStates[pos] : 0;
            TextView tvName = helper.getView(R.id.tv_name);
            helper.setText(R.id.tv_name, item.name);
            if (st == 1) {
                // 已下载/本地:绿勾图标 + 置灰不可选
                tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
                tvName.setCompoundDrawables(stateIcon(R.drawable.ic_download_done, R.color.download_done), null, null, null);
                helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
            } else if (st == 2) {
                // 下载中/排队:蓝下箭头图标 + 置灰不可选
                tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
                tvName.setCompoundDrawables(stateIcon(R.drawable.ic_download_active, R.color.download_active), null, null, null);
                helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
            } else {
                // 可下载:选中蓝框蓝字,未选中普通(无图标)
                tvName.setCompoundDrawables(null, null, null, null);
                if (item.selected) {
                    tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.download_active));
                    helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip_selected);
                } else {
                    tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.text_foreground));
                    helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
                }
            }
        }

        private Drawable stateIcon(int resId, int colorRes) {
            Drawable d = ContextCompat.getDrawable(getContext(), resId);
            if (d != null) {
                int size = dp2px(16);
                d.setBounds(0, 0, size, size);
                d.setColorFilter(ContextCompat.getColor(getContext(), colorRes), android.graphics.PorterDuff.Mode.SRC_IN);
            }
            return d;
        }

        private int dp2px(float dp) {
            return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
        }
    }
}
