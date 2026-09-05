package com.github.tvbox.osc.ui.dialog;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.ui.activity.LiveActivity;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.LiveConfig;

import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 直播设置面板协调器（合并 LiveSettingDialog / LiveSettingRightDialog 的重复实现）。
 * <p>
 * 负责设置面板的公共编排：分组/条目两列 adapter 绑定、静态分组构建(线路/画面/解码/超时/偏好)、
 * 当前值回读选中、分组切换、条目点击后的动作分发（线路切换/画面/解码回调 LiveActivity，
 * 超时/偏好走 {@link LiveConfig}）。
 * <p>
 * 两个弹窗只做"外壳"差异（底部 {@link AppBottomPopupView} vs 抽屉 {@link AppDrawerPopupView}），
 * 均委托本协调器；LiveActivity 内嵌面板将来若复活也可复用。
 */
final class LiveSettingPanel {

    private final LiveActivity mActivity;
    private final TvRecyclerView mGroupView;
    private final TvRecyclerView mItemView;
    private final LiveSettingGroupAdapter groupAdapter = new LiveSettingGroupAdapter();
    private final LiveSettingItemAdapter itemAdapter = new LiveSettingItemAdapter();
    private final List<LiveSettingGroup> groups = new ArrayList<>();

    LiveSettingPanel(LiveActivity activity, TvRecyclerView groupView, TvRecyclerView itemView) {
        mActivity = activity;
        mGroupView = groupView;
        mItemView = itemView;
    }

    /** 绑定两个列表并初始化全部交互（弹窗 onCreate 调用一次即可） */
    void init() {
        initGroupView();
        initItemView();
        buildStaticGroups();
        loadCurrentSourceList();
        selectGroup(0);
    }

    private void initGroupView() {
        mGroupView.setHasFixedSize(true);
        mGroupView.setLayoutManager(new V7LinearLayoutManager(mActivity, 1, false));
        mGroupView.setAdapter(groupAdapter);
        groupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectGroup(position);
            }
        });
    }

    private void initItemView() {
        mItemView.setHasFixedSize(true);
        mItemView.setLayoutManager(new V7LinearLayoutManager(mActivity, 1, false));
        mItemView.setAdapter(itemAdapter);
        itemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickItem(position);
            }
        });
    }

    /** 静态分组：线路选择(内容运行期填充) / 画面比例 / 播放解码 / 超时换源 / 偏好设置 */
    private void buildStaticGroups() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList("线路选择", "画面比例", "播放解码", "超时换源", "偏好设置"));
        ArrayList<ArrayList<String>> items = new ArrayList<>();
        items.add(new ArrayList<String>()); // 线路: 运行期按当前频道源填充
        items.add(new ArrayList<>(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪")));
        items.add(new ArrayList<>(Arrays.asList("系统", "ijk硬解", "ijk软解", "exo")));
        items.add(new ArrayList<>(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s")));
        items.add(new ArrayList<>(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类")));

        groups.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup group = new LiveSettingGroup();
            group.setGroupIndex(i);
            group.setGroupName(groupNames.get(i));
            ArrayList<LiveSettingItem> groupItems = new ArrayList<>();
            for (int j = 0; j < items.get(i).size(); j++) {
                LiveSettingItem item = new LiveSettingItem();
                item.setItemIndex(j);
                item.setItemName(items.get(i).get(j));
                groupItems.add(item);
            }
            group.setLiveSettingItems(groupItems);
            groups.add(group);
        }
        // 当前值回读:超时档位与偏好开关
        groups.get(3).getLiveSettingItems().get(LiveConfig.connectTimeout()).setItemSelected(true);
        groups.get(4).getLiveSettingItems().get(0).setItemSelected(LiveConfig.showTime());
        groups.get(4).getLiveSettingItems().get(1).setItemSelected(LiveConfig.showNetSpeed());
        groups.get(4).getLiveSettingItems().get(2).setItemSelected(LiveConfig.channelReverse());
        groups.get(4).getLiveSettingItems().get(3).setItemSelected(LiveConfig.crossGroup());
        groupAdapter.setNewData(groups);
    }

    /** 切换分组：更新选中并让条目列回读该组当前值 */
    void selectGroup(int position) {
        if (position == groupAdapter.getSelectedGroupIndex() || position < -1) {
            return;
        }
        groupAdapter.setSelectedGroupIndex(position);
        itemAdapter.setNewData(groups.get(position).getLiveSettingItems());
        switch (position) {
            case 0:
                itemAdapter.selectItem(mActivity.getCurrentLiveChannelItem().getSourceIndex(), true, false);
                break;
            case 1:
                itemAdapter.selectItem(mActivity.getLivePlayerManager().getLivePlayerScale(), true, true);
                break;
            case 2:
                itemAdapter.selectItem(mActivity.getLivePlayerManager().getLivePlayerType(), true, true);
                break;
        }
        int scrollTo = itemAdapter.getSelectedItemIndex();
        if (scrollTo < 0) scrollTo = 0;
        mItemView.scrollToPosition(scrollTo);
    }

    /** 刷新"线路选择"组的来源列表（当前频道切换后调用） */
    void loadCurrentSourceList() {
        ArrayList<String> sourceNames = mActivity.getCurrentLiveChannelItem().getChannelSourceNames();
        ArrayList<LiveSettingItem> sourceItems = new ArrayList<>();
        for (int j = 0; j < sourceNames.size(); j++) {
            LiveSettingItem item = new LiveSettingItem();
            item.setItemIndex(j);
            item.setItemName(sourceNames.get(j));
            sourceItems.add(item);
        }
        groups.get(0).setLiveSettingItems(sourceItems);
    }

    /** 条目点击：动作分发（与旧两弹窗实现逐分支一致） */
    private void clickItem(int position) {
        int groupIndex = groupAdapter.getSelectedGroupIndex();
        if (groupIndex < 4) { // 渲染类分组先画选中态（防连点）
            if (position == itemAdapter.getSelectedItemIndex()) {
                return;
            }
            itemAdapter.selectItem(position, true, true);
        }
        switch (groupIndex) {
            case 0: // 线路切换
                mActivity.switchingLine2Replay(position);
                break;
            case 1: // 画面比例
                mActivity.changeScale(position);
                break;
            case 2: // 播放解码
                mActivity.changePlayer(position);
                break;
            case 3: // 超时换源
                LiveConfig.setConnectTimeout(position);
                break;
            case 4: // 偏好设置
                boolean select = false;
                switch (position) {
                    case 0:
                        select = !LiveConfig.showTime();
                        LiveConfig.setShowTime(select);
                        break;
                    case 1:
                        select = !LiveConfig.showNetSpeed();
                        LiveConfig.setShowNetSpeed(select);
                        break;
                    case 2:
                        select = !LiveConfig.channelReverse();
                        LiveConfig.setChannelReverse(select);
                        break;
                    case 3:
                        select = !LiveConfig.crossGroup();
                        LiveConfig.setCrossGroup(select);
                        break;
                }
                itemAdapter.selectItem(position, select, false);
                break;
        }
    }
}
