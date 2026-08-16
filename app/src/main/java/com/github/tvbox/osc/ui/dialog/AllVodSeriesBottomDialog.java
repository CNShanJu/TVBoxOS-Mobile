package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
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
import com.lxj.xpopup.core.BottomPopupView;
import com.lxj.xpopup.interfaces.OnSelectListener;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 全集弹窗(详情页"全部"):圆角框样式(与下载选择一致),固定3列,单选。
 * 不像全屏右侧弹窗一样共用activity的adapter,adapter横向和网格布局逻辑不同,同屏显示切换会有视觉差
 */
public class AllVodSeriesBottomDialog extends BottomPopupView {

    List<VodInfo.VodSeries> mList;
    private final OnSelectListener mSelectListener;

    public AllVodSeriesBottomDialog(@NonNull @NotNull Context context, List<VodInfo.VodSeries> list, OnSelectListener selectListener) {
        super(context);
        mList = list;
        mSelectListener = selectListener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_all_series;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        RecyclerView rv = findViewById(R.id.rv);

        // 固定3列,圆角框条目(与下载选择弹窗同款)
        rv.setLayoutManager(new GridLayoutManager(getContext(), 3));
        rv.addItemDecoration(new GridSpacingItemDecoration(3, 20, true));

        BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> seriesAdapter =
                new BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder>(R.layout.item_download_select, mList) {
                    @Override
                    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
                        TextView tv = helper.getView(R.id.tv_name);
                        tv.setText(item.name);
                        if (item.selected) {
                            tv.setTextColor(ContextCompat.getColor(getContext(), R.color.download_active));
                            helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip_selected);
                        } else {
                            tv.setTextColor(ContextCompat.getColor(getContext(), R.color.text_foreground));
                            helper.getView(R.id.item_root).setBackgroundResource(R.drawable.bg_episode_chip);
                        }
                    }
                };
        rv.setAdapter(seriesAdapter);

        rv.postDelayed(() -> {//xpopup重写maxHeight后布局完成未滑动完毕导致定位异常,加延时可正常滑动
            for (int i = 0; i < mList.size(); i++) {
                if (mList.get(i).selected){
                    rv.smoothScrollToPosition(i);
                }
            }
        },500);

        seriesAdapter.setOnItemClickListener((adapter, view, position) -> {
            for (int j = 0; j < seriesAdapter.getData().size(); j++) {
                seriesAdapter.getData().get(j).selected = false;
                seriesAdapter.notifyItemChanged(j);
            }
            seriesAdapter.getData().get(position).selected = true;
            seriesAdapter.notifyItemChanged(position);
            mSelectListener.onSelect(position,"");
        });

    }
}
