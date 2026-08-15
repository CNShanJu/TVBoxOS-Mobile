package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

/**
 * 投屏设备列表适配器(桩实现)
 * <p>
 * DLNA 投屏功能暂不可用:原依赖 com.github.devin1014.DLNA-Cast:dlna-dmc:V1.0.0
 * 已从 JitPack 消失,原始实现备份于项目根目录 _backup_dlna/ 下。
 */
public class CastDevicesAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    public CastDevicesAdapter() {
        super(R.layout.item_title);
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        // no-op
    }
}
