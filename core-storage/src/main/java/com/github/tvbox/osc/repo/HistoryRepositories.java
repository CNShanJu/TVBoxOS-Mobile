package com.github.tvbox.osc.repo;

/** 仓储静态访问点(过渡期兼容;后续可换注入) */
public final class HistoryRepositories {

    private static volatile HistoryRepository history = RoomHistoryRepository.get();

    private HistoryRepositories() {
    }

    public static HistoryRepository history() {
        return history;
    }

    /** 测试/注入用 */
    public static void setHistory(HistoryRepository repo) {
        if (repo != null) {
            history = repo;
        }
    }
}
