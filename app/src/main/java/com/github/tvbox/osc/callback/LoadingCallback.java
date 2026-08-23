package com.github.tvbox.osc.callback;

import android.content.Context;
import android.view.View;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LoadingAnim;
import com.kingja.loadsir.callback.Callback;

/**
 * @author pj567
 * @date :2020/12/24
 * @description: 全局加载中回调(搜索/列表/详情等页面)。动画文件由设置页"加载动画"选项动态切换。
 */
public class LoadingCallback extends Callback {
    @Override
    protected int onCreateView() {
        return R.layout.loadsir_loading_layout;
    }

    @Override
    protected void onViewCreate(Context context, View view) {
        super.onViewCreate(context, view);
        // 按设置页"加载动画"配置动态设置动画文件(默认 anim_loading.json,可选 Glowing Fish)
        LoadingAnim.apply(view.findViewById(R.id.lottie_loading));
    }
}
