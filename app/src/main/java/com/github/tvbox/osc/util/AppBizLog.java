package com.github.tvbox.osc.util;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.CategoryLogger;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.log.SubType;

/**
 * app 业务日志门面：统一覆盖 设置/搜索/播放/收藏/历史/删除/清空 等业务操作，
 * 全部写入 LogStore(Room 结构化业务日志, LogActivity Tab1 可筛选查看)。
 * <p>
 * 用法：AppBizLog.LOG.info(AppBizSubType.SEARCH, "搜索关键词: xxx", null);
 */
public final class AppBizLog {

    /** 业务日志小类型（大类型=系统/其他 由各方法归属） */
    public enum BizType implements SubType {
        SETTING("setting", "设置"),
        SEARCH("search", "搜索"),
        PLAY("play", "播放"),
        COLLECT("collect", "收藏"),
        HISTORY("history", "历史"),
        DELETE("delete", "删除"),
        CLEAR("clear", "清空"),
        EXPORT("export", "导出"),
        BACKUP("backup", "备份"),
        OTHER("other", "其他");

        private final String code;
        private final String label;

        BizType(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String label() {
            return label;
        }
    }

    /** 系统类业务日志（设置/收藏/历史/删除/清空等） */
    public static final CategoryLogger<BizType> SYSTEM =
            LogStore.get().register(Category.SYSTEM, BizType.class);

    /** 其他类业务日志（搜索等无明确归属的） */
    public static final CategoryLogger<BizType> OTHER =
            LogStore.get().register(Category.OTHER, BizType.class);

    private AppBizLog() {
    }
}
