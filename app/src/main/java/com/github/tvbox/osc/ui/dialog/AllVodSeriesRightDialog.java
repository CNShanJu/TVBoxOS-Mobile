package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.Utils;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 点播右侧全集弹窗
 */
public class AllVodSeriesRightDialog extends AppDrawerPopupView {

    @NonNull
    private final DetailActivity mDetailActivity;

    public AllVodSeriesRightDialog(@NonNull @NotNull Context context) {
        super(context);
        mDetailActivity = (DetailActivity) context;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_all_series_with_group;
    }

    @Override
    protected void onCreate() {
        super.onCreate();

        RecyclerView mGridViewFlag = findViewById(R.id.mGridViewFlag);
        mGridViewFlag.setHasFixedSize(true);
        mGridViewFlag.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        if (mDetailActivity.seriesFlagAdapter != null){//复用activity的adapter
            mGridViewFlag.setAdapter(mDetailActivity.seriesFlagAdapter);

            List<VodInfo.VodSeriesFlag> data = mDetailActivity.seriesFlagAdapter.getData();
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).selected){
                    mGridViewFlag.scrollToPosition(i);
                }
            }
        }

        if (mDetailActivity.seriesAdapter!=null){//复用activity的adapter
            RecyclerView rv = findViewById(R.id.rv);
            rv.setLayoutManager(new GridLayoutManager(getContext(),Utils.getSeriesSpanCount(mDetailActivity.seriesAdapter.getData())));
            rv.addItemDecoration(new GridSpacingItemDecoration(Utils.getSeriesSpanCount(mDetailActivity.seriesAdapter.getData()), 20, true));
            mDetailActivity.seriesAdapter.setGird(true);
            mDetailActivity.seriesAdapter.setChipTextSize(16f); // 全屏选集文字调大
            mDetailActivity.seriesAdapter.notifyDataSetChanged();
            rv.setAdapter(mDetailActivity.seriesAdapter);

            List<VodInfo.VodSeries> data = mDetailActivity.seriesAdapter.getData();
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).selected){
                    rv.scrollToPosition(i);
                }
            }
        }

        // 倒序按钮:文字随状态切换(未倒序=倒序, 已倒序=正序);与下载/详情弹窗共用 sortSeries 状态
        updateSortButton();
        findViewById(R.id.tvSort).setOnClickListener(view -> {
            mDetailActivity.sortSeries();
            updateSortButton();
        });
   }

    /** 倒序按钮文字跟随共用状态:已倒序显示"正序",否则"倒序" */
    private void updateSortButton() {
        try {
            TextView tv = findViewById(R.id.tvSort);
            if (tv != null) {
                tv.setText(mDetailActivity.isSeriesReversed() ? "正序" : "倒序");
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDismiss() {
        super.onDismiss();
        if (mDetailActivity.seriesAdapter!=null){//重置状态,避免竖屏时显示异常
            mDetailActivity.seriesAdapter.setGird(false);
            mDetailActivity.seriesAdapter.setChipTextSize(12f); // 恢复详情页默认字号
            mDetailActivity.seriesAdapter.notifyDataSetChanged();
        }
    }
}