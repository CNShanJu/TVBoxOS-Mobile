package com.github.tvbox.osc.event;

/**
 * @author pj567
 * @date :2021/1/6
 * @description:
 */
public class RefreshEvent {
    public static final int TYPE_REFRESH_NOTIFY = 15;
    /** 遥控/局域网推送:订阅接口地址变更 */
    public static final int TYPE_API_URL_CHANGE = 16;
    /** 遥控/局域网推送:收到要播放的地址 */
    public static final int TYPE_PUSH_URL = 17;

    public int type;
    public Object obj;

    public RefreshEvent(int type) {
        this.type = type;
    }

    public RefreshEvent(int type, Object obj) {
        this.type = type;
        this.obj = obj;
    }
}
