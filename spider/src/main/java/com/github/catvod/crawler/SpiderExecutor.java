package com.github.catvod.crawler;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 爬虫串行执行器（quickjs 单线程限制的统一入口）：
 * 所有 spider 调用（播放/详情/搜索/解析）必须走同一串行池，避免 quickjs 并发卡死。
 * 提供带超时的同步调用与异步提交；替代散落的 SourceViewModel.spThreadPool。
 */
public final class SpiderExecutor {

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-spider");
        t.setDaemon(true);
        return t;
    });

    /** 同步调用（带超时）；超时/异常返回 null */
    public <T> T call(long timeoutMs, Callable<T> task) {
        if (task == null) return null;
        Future<T> future = pool.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 异步提交（不阻塞） */
    public void execute(Runnable task) {
        if (task != null) pool.execute(task);
    }

    /** 暴露底层串行池（供既有调用方统一使用，避免 quickjs 并发） */
    public ExecutorService asExecutorService() {
        return pool;
    }
}
