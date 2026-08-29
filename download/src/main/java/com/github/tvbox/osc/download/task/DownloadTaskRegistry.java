package com.github.tvbox.osc.download.task;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.util.DownloadExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 下载任务工厂注册表（4.6 任务对象化）：按资源特征(Feature)选择任务对象子类。
 * <p>
 * 新增下载类型 = 注册 特征判定 + 工厂，调度器零改动：
 * <pre>
 *   DownloadTaskRegistry.register(
 *           t -&gt; 新类型特征,
 *           (t, l, executor) -&gt; new XxxDownloadTask(t, l, executor));
 * </pre>
 * 注册表有兜底（直链），{@link #create} 永不返回 null。
 */
public final class DownloadTaskRegistry {

    /** 特征判定: 命中则用该工厂创建任务对象 */
    public interface Feature {
        boolean matches(DownloadTask t);
    }

    /** 工厂: 创建任务对象(executor 为框架侧执行器,由调度器注入) */
    public interface Factory {
        BaseDownloadTask create(DownloadTask t, TaskListener listener, DownloadExecutor executor);
    }

    private static final class Entry {
        final Feature feature;
        final Factory factory;

        Entry(Feature feature, Factory factory) {
            this.feature = feature;
            this.factory = factory;
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    static {
        // 内置两类: HLS(m3u8) / 直链(兜底)
        register(t -> t.url != null && t.url.toLowerCase().contains(".m3u8"), M3u8DownloadTask::new);
        register(t -> true, NormalFileDownloadTask::new);
    }

    private DownloadTaskRegistry() {
    }

    /** 注册新下载类型(后注册先匹配; 兜底直链永远最后,保证 create 非空) */
    public static void register(Feature feature, Factory factory) {
        ENTRIES.add(0, new Entry(feature, factory));
    }

    /** 按特征创建任务对象; 无匹配时回退直链(永不返回 null) */
    public static BaseDownloadTask create(DownloadTask t, TaskListener listener, DownloadExecutor executor) {
        for (Entry e : ENTRIES) {
            if (e.feature.matches(t)) {
                return e.factory.create(t, listener, executor);
            }
        }
        return new NormalFileDownloadTask(t, listener, executor);
    }
}
