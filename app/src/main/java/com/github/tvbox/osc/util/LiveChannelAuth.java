package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播频道分组密码门禁纯逻辑(自 LiveActivity 抽出,等价搬移):
 * "该分组是否已输密码确认 / 是否需要弹密码框 / 未授权时可见频道列表"。
 * 无 View/Context 依赖,组数据与确认集合由调用方传入,可 JVM 单测。
 */
public final class LiveChannelAuth {

    private LiveChannelAuth() {
    }

    /** 该分组是否已在本次会话输入过正确密码 */
    public static boolean isPasswordConfirmed(List<Integer> confirmedGroupIndexes, int groupIndex) {
        if (confirmedGroupIndexes == null) return false;
        for (Integer confirmedNum : confirmedGroupIndexes) {
            if (confirmedNum == groupIndex) return true;
        }
        return false;
    }

    /** 进入该分组是否需要先输密码:分组设了密码且本次会话尚未确认 */
    public static boolean needInputPassword(List<LiveChannelGroup> groups,
                                           List<Integer> confirmedGroupIndexes, int groupIndex) {
        return !groups.get(groupIndex).getGroupPassword().isEmpty()
                && !isPasswordConfirmed(confirmedGroupIndexes, groupIndex);
    }

    /** 可见频道列表:未授权(需密码未确认)返回空表;已授权返回调用方给的分组频道(保持引用/可空语义) */
    public static List<LiveChannelItem> visibleChannels(List<LiveChannelGroup> groups,
                                                        List<Integer> confirmedGroupIndexes, int groupIndex,
                                                        List<LiveChannelItem> authorizedChannels) {
        if (needInputPassword(groups, confirmedGroupIndexes, groupIndex)) {
            return new ArrayList<>();
        }
        return authorizedChannels;
    }
}
