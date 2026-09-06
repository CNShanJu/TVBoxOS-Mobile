package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.github.tvbox.osc.util.MD5;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

        // with preview
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvSite, SourceConfigProviders.get().getSource(item.sourceKey).getName());
        helper.setVisible(R.id.tvNote, item.note != null && !item.note.isEmpty());
        if (item.note != null && !item.note.isEmpty()) {
            helper.setText(R.id.tvNote, item.note);
        }
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        if (!TextUtils.isEmpty(item.pic)) {
            // 3:4 大竖图(约高150dp):圆角 12dp 更自然;override/缓存键随图区尺寸变化,避免旧缓存复用
            String cacheKey = MD5.string2MD5(item.pic + "position=" + helper.getLayoutPosition() + "_t130x160");
            Picasso.get()
                    .load(item.pic)
                    .transform(new RoundTransformation(cacheKey)
                            .centerCorp(true)
                            .override(AutoSizeUtils.dp2px(mContext, 130), AutoSizeUtils.dp2px(mContext, 160))
                            .roundRadius(AutoSizeUtils.dp2px(mContext, 12), RoundTransformation.RoundType.ALL))
                    .placeholder(R.drawable.iv_load_fail)
                    .error(R.drawable.iv_load_fail)
                    .into(ivThumb);
        } else {
            ivThumb.setImageResource(R.drawable.iv_load_fail);
        }

    }
}