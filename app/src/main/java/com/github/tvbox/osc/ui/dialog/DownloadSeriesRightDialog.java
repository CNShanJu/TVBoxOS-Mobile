package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.PorterDuff;
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
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LoadingAnim;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏"下载"右侧抽屉:选择下载剧集(网格多选),样式与选集右侧抽屉一致。
 * <p>
 * 点击"下载"立即弹出抽屉,内容先显示 loading;数据(选集副本+下载状态)异步准备完成后
 * 通过 {@link #setData(List, int[])} 填充。states:0=可下载;1=已下载(✓置灰);2=下载中/排队(↓置灰)。
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

        // 加载动画跟随设置页"加载动画"选项
        LoadingAnim.apply(findViewById(R.id.lottie_loading));

        mRv.setLayoutManager(new GridLayoutManager(getContext(), 3));
        mRv.addItemDecoration(new GridSpacingItemDecoration(3, 20, true));

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
            // 网格高度按行数自适应,封顶避免全集数把抽屉撑到全屏
            int rows = Math.max(1, (int) Math.ceil(mList.size() / 3.0f));
            int gridHeight = Math.min(rows * dp2px(72), dp2px(340));
            mRv.getLayoutParams().height = gridHeight;
            mRv.requestLayout();
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

    private int dp2px(float dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 弹窗专用适配器:圆角框 + 状态图标(与底部下载弹窗一致) */
    private class ItemAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
        ItemAdapter() {
            super(R.layout.item_download_select, mList);
        }

        @Override
        protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
            int pos = helper.getAdapterPosition();
            int st = mStates != null && pos >= 0 && pos < mStates.length ? mStates[pos] : 0;
            boolean selected = item.selected;
            TextView tvName = helper.getView(R.id.tv_name);
            helper.setText(R.id.tv_name, item.name);
            if (st == 1) {
                tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
                tvName.setCompoundDrawables(stateIcon(R.drawable.ic_download_done, R.color.download_done), null, null, null);
                helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
            } else if (st == 2) {
                tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
                tvName.setCompoundDrawables(stateIcon(R.drawable.ic_download_active, R.color.download_active), null, null, null);
                helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
            } else {
                tvName.setCompoundDrawables(null, null, null, null);
                if (selected) {
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
                d.setColorFilter(ContextCompat.getColor(getContext(), colorRes), PorterDuff.Mode.SRC_IN);
            }
            return d;
        }
    }
}
