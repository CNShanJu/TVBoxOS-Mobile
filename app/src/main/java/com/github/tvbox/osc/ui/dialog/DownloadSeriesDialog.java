package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.Log;
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
import com.lxj.xpopup.core.BottomPopupView;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 选择下载剧集弹窗:网格多选(点一下选中,再点取消),底部"开始下载 / 下载管理"。
 * 传入的列表为副本,弹窗内的选中状态不会影响详情页原选集的选中态。
 * <p>
 * states 与列表一一对应:0=可下载;1=已下载(✓ 置灰不可选);2=下载中/排队(↓ 置灰不可选)。
 * 点"开始下载"未选择任何剧集时只提醒,不关闭弹窗。
 */
public class DownloadSeriesDialog extends BottomPopupView {

    public interface OnDownloadActionListener {
        /** 开始下载所选剧集(selected 为已勾选的列表) */
        void onStartDownload(List<VodInfo.VodSeries> selected);

        /** 打开下载管理页 */
        void onOpenDownloadManager();
    }

    private final List<VodInfo.VodSeries> mList;
    private final int[] mStates;
    private final OnDownloadActionListener mListener;
    private TextView mTvSelected;

    public DownloadSeriesDialog(@NonNull @NotNull Context context,
                                List<VodInfo.VodSeries> list,
                                int[] states,
                                OnDownloadActionListener listener) {
        super(context);
        mList = list;
        mStates = states;
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
        RecyclerView rv = findViewById(R.id.rv);

        rv.setLayoutManager(new GridLayoutManager(getContext(), 3));
        rv.addItemDecoration(new GridSpacingItemDecoration(3, 20, true));

        ItemAdapter adapter = new ItemAdapter();
        rv.setAdapter(adapter);
        // 网格高度按行数自适应,封顶避免全集数把抽屉撑到全屏
        int rows = Math.max(1, (int) Math.ceil(mList.size() / 3.0f));
        int gridHeight = Math.min(rows * dp2px(72), dp2px(340)); // 行距72dp(配合加高选框),封顶340dp
        // 直接改父容器(LinearLayout)生成的 LayoutParams 高度并请求重排,不做类型转换
        rv.getLayoutParams().height = gridHeight;
        rv.requestLayout();
        adapter.setOnItemClickListener((a, view, position) -> {
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

    /** 弹窗专用适配器:圆角框 + 状态图标(下载中蓝↓ / 完成绿✓,图标用复合drawable贴文字,整体居中) */
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
                // 已下载:绿勾图标 + 置灰
                tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
                tvName.setCompoundDrawables(stateIcon(R.drawable.ic_download_done, R.color.download_done), null, null, null);
                helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
            } else if (st == 2) {
                // 下载中/排队:蓝下箭头图标 + 置灰
                tvName.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
                tvName.setCompoundDrawables(stateIcon(R.drawable.ic_download_active, R.color.download_active), null, null, null);
                helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
            } else {
                // 可下载:选中态蓝框蓝字,未选中普通
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
