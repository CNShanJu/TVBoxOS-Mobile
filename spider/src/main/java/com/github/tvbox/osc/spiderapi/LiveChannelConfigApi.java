package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.google.gson.JsonArray;

import java.util.List;

/**
 * 直播频道配置契约（改进.txt 收口：直播页读取频道分组 / 注入直播源数据）。
 * <p>
 * 具体实现由 AppCompositionRoot 注入（桥接 :spider ApiConfig），直播 UI 不直读其实现类。
 */
public interface LiveChannelConfigApi {

    /** 当前频道分组列表（直播源加载后填充） */
    List<LiveChannelGroup> getChannelGroupList();

    /** 用直播源 json(lives 数组)重建频道分组 */
    void loadLives(JsonArray livesArray);
}
