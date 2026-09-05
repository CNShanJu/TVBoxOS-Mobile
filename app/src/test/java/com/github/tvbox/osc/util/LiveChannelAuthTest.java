package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** LiveChannelAuth(直播分组密码门禁纯逻辑)单测 */
public class LiveChannelAuthTest {

    private static LiveChannelGroup group(int idx, String password) {
        LiveChannelGroup g = new LiveChannelGroup();
        g.setGroupIndex(idx);
        g.setGroupPassword(password);
        return g;
    }

    @Test
    public void isPasswordConfirmed_matchesIndex() {
        List<Integer> confirmed = new ArrayList<>();
        confirmed.add(0);
        confirmed.add(3);
        assertTrue(LiveChannelAuth.isPasswordConfirmed(confirmed, 0));
        assertTrue(LiveChannelAuth.isPasswordConfirmed(confirmed, 3));
        assertFalse(LiveChannelAuth.isPasswordConfirmed(confirmed, 1));
        assertFalse(LiveChannelAuth.isPasswordConfirmed(null, 0));
    }

    @Test
    public void needInputPassword_onlyWhenProtectedAndUnconfirmed() {
        List<LiveChannelGroup> groups = new ArrayList<>();
        groups.add(group(0, "1234"));
        groups.add(group(1, ""));   // 无密码
        List<Integer> confirmed = new ArrayList<>();
        confirmed.add(0);
        assertFalse(LiveChannelAuth.needInputPassword(groups, confirmed, 0)); // 已确认
        assertTrue(LiveChannelAuth.needInputPassword(groups, new ArrayList<>(), 0)); // 未确认且设密
        assertFalse(LiveChannelAuth.needInputPassword(groups, new ArrayList<>(), 1)); // 无密码组
    }

    @Test
    public void visibleChannels_authorizedReturnsSameList() {
        List<LiveChannelGroup> groups = new ArrayList<>();
        groups.add(group(0, "1234"));
        ArrayList<LiveChannelItem> channels = new ArrayList<>();
        LiveChannelItem c = new LiveChannelItem();
        c.setChannelName("央视1");
        channels.add(c);
        groups.get(0).setLiveChannels(channels);

        // 未确认:空表
        List<LiveChannelItem> denied = LiveChannelAuth.visibleChannels(groups, new ArrayList<>(), 0, channels);
        assertTrue(denied.isEmpty());
        // 已确认:原引用
        List<Integer> confirmed = new ArrayList<>();
        confirmed.add(0);
        assertSame(channels, LiveChannelAuth.visibleChannels(groups, confirmed, 0, channels));
        assertEquals("央视1", channels.get(0).getChannelName());
    }

    @Test
    public void visibleChannels_unprotectedGroupPassesThrough() {
        List<LiveChannelGroup> groups = new ArrayList<>();
        groups.add(group(0, ""));
        ArrayList<LiveChannelItem> channels = new ArrayList<>();
        groups.get(0).setLiveChannels(channels);
        assertSame(channels, LiveChannelAuth.visibleChannels(groups, new ArrayList<>(), 0, channels));
    }
}
