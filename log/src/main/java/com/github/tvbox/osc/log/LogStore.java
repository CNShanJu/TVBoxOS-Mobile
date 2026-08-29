package com.github.tvbox.osc.log;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 日志模块门面（唯一对外入口，独立模块，零业务依赖）。
 * <p>
 * 职责：
 * <ul>
 *   <li>注册：{@link #register(Category, Class)} 返回绑定大类型的限定对象 {@link CategoryLogger}；</li>
 *   <li>采集：异步单线程队列 + 批量写（不卡 UI）；高频进度类请勿逐条调用（去抖采样由调用方控制）；</li>
 *   <li>存储：Room 业务日志（可筛选，按天 7 天清理）；logcat --uid 全部日志（package:mine）见 {@link LogcatCapture}；</li>
 *   <li>查询 / 开关 / 导出 / 崩溃捕获。</li>
 * </ul>
 * 级别常量：{@link #LEVEL_DEBUG} / {@link #LEVEL_INFO} / {@link #LEVEL_WARN} / {@link #LEVEL_ERROR}。
 */
public final class LogStore {

    public static final int LEVEL_DEBUG = 0;
    public static final int LEVEL_INFO = 1;
    public static final int LEVEL_WARN = 2;
    public static final int LEVEL_ERROR = 3;

    private static final String[] LEVEL_NAMES = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int MAX_DETAIL = 4096;
    private static final int MAX_REASON = 4096;
    private static final int BATCH_SIZE = 50;
    private static final int FLUSH_DELAY_MS = 1000;
    private static final int MAX_ROWS = 100_000;
    private static final long DAY_MS = 24L * 3600 * 1000;

    private static volatile LogStore instance;
    /** 未初始化时的降级空实现（no-op）：任何模块在 :log 未 init/未引入时调用也安全，日志静默丢弃 */
    private static volatile LogStore noopInstance;

    private final LogDatabase db;
    /** 写库/查询统一走单线程，避免 Room 并发写 */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-log-io");
        t.setDaemon(true);
        return t;
    });
    /** 批量落库定时器 */
    private final ScheduledExecutorService flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tvbox-log-flush");
        t.setDaemon(true);
        return t;
    });

    private final List<LogEntry> pending = new ArrayList<>();
    private final Object pendingLock = new Object();

    private final ConcurrentHashMap<String, CategoryLogger<?>> loggers = new ConcurrentHashMap<>();
    private final Set<String> disabledCategories = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = false;
    private volatile int minLevel = LEVEL_INFO;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LogStore(Context context) {
        this(context, false);
    }

    /** noop=true 时不建数据库（降级空实现，无任何副作用） */
    private LogStore(Context context, boolean noop) {
        db = noop ? null : LogDatabase.get(context);
    }

    /**
     * 门面入口（降级安全）：未 {@link #init(Context)} 时返回 no-op 空实现——
     * 其他模块即使没引入/没初始化 :log 也能正常工作（日志不记录、查询返回空）。
     * 永不返回 null。
     */
    public static LogStore get() {
        if (instance != null) return instance;
        if (noopInstance == null) {
            synchronized (LogStore.class) {
                if (noopInstance == null) {
                    noopInstance = new LogStore(null, true);
                }
            }
        }
        return noopInstance;
    }

    /** App 启动时调用一次；内部按 LogConfig 同步开关并启动 logcat 捕获（如已开启） */
    public static void init(Context context) {
        if (instance == null) {
            synchronized (LogStore.class) {
                if (instance == null) {
                    instance = new LogStore(context.getApplicationContext());
                }
            }
        }
        LogcatCapture.setAppContext(context); // 独立模块: context 注入,不依赖 app 类
        instance.enabled = LogConfig.isEnabled();
        instance.minLevel = LogConfig.getLevel();
        if (instance.enabled) {
            LogcatCapture.start();
        }
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /** 通用小类型（便捷方法用）：code=generic, label=通用 */
    private enum GenericSubType implements SubType {
        GENERIC("generic", "通用");

        private final String code;
        private final String label;

        GenericSubType(String code, String label) {
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

    private static final CategoryLogger<GenericSubType> genericLogger = new LoggerImpl<>(Category.SYSTEM.name());

    /**
     * 通用业务日志便捷方法（INFO 级别）：任意业务点直接记录，无需自建小类型枚举。
     * 大类型=系统(SYSTEM)，小类型=通用；适合 设置/搜索/播放/收藏/删除/清空 等零散业务操作。
     */
    public static void log(Category category, String detail) {
        try {
            CategoryLogger<GenericSubType> l = category == Category.OTHER ? otherLogger : genericLogger;
            l.info(GenericSubType.GENERIC, detail, null);
        } catch (Throwable ignored) {
        }
    }

    private static final CategoryLogger<GenericSubType> otherLogger = new LoggerImpl<>(Category.OTHER.name());

    /**
     * 注册大类型，返回绑定该大类型的限定对象（幂等：同大类型返回同一实例）。
     * 模块 init 时调用一次并持有返回值；之后所有日志调用自动带大类型。
     */
    public <T extends Enum<T> & SubType> CategoryLogger<T> register(Category category, Class<T> subTypeClass) {
        return register(category.name(), subTypeClass);
    }

    /** 动态注册大类型（字符串，扩展用） */
    public <T extends Enum<T> & SubType> CategoryLogger<T> register(String categoryName, Class<T> subTypeClass) {
        @SuppressWarnings("unchecked")
        CategoryLogger<T> logger = (CategoryLogger<T>) loggers.get(categoryName);
        if (logger == null) {
            logger = new LoggerImpl<>(categoryName);
            CategoryLogger<?> old = loggers.putIfAbsent(categoryName, logger);
            if (old != null) {
                @SuppressWarnings("unchecked")
                CategoryLogger<T> cast = (CategoryLogger<T>) old;
                return cast;
            }
        }
        return logger;
    }

    // ------------------------------------------------------------------
    // 写入（LoggerImpl 调用；也供 logcat/崩溃等内部场景使用）
    // ------------------------------------------------------------------

    void log(String categoryName, int level, String subTypeCode, String subTypeLabel,
             String detail, String result, String reason, JSONObject extras, String taskKey) {
        if (!enabled) return;
        if (level < minLevel) return;
        if (disabledCategories.contains(categoryName)) return;
        LogEntry e = new LogEntry();
        e.timestamp = System.currentTimeMillis();
        e.category = categoryName;
        e.subTypeCode = subTypeCode;
        e.subTypeLabel = subTypeLabel;
        e.level = level;
        e.detail = truncate(detail, MAX_DETAIL);
        e.result = result;
        e.reason = truncate(reason, MAX_REASON);
        e.extras = extras != null ? extras.toString() : null;
        if (taskKey == null && extras != null) {
            taskKey = extras.optString("episodeId", null);
            if (taskKey != null && taskKey.isEmpty()) taskKey = null;
        }
        e.taskKey = taskKey;
        enqueue(e);
    }

    private void enqueue(LogEntry e) {
        boolean first;
        synchronized (pendingLock) {
            pending.add(e);
            first = pending.size() == 1;
            if (pending.size() >= BATCH_SIZE) {
                flushNow();
                return;
            }
        }
        if (first) {
            flushScheduler.schedule(this::flushNow, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushNow() {
        if (db == null) return; // 降级模式: 不落库
        final List<LogEntry> batch;
        synchronized (pendingLock) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        ioExecutor.execute(() -> {
            try {
                db.logDao().insertAll(batch);
                if (db.logDao().count() > MAX_ROWS) {
                    db.logDao().trimTo(MAX_ROWS);
                }
            } catch (Throwable th) {
                Log.e("LogStore", "日志落库失败", th);
            }
        });
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 组合筛选查询（阻塞至结果返回；页面调用建议放后台线程）；降级模式返回 null */
    public List<LogEntry> query(final LogFilter f) {
        if (db == null) return null;
        return await(() -> db.logDao().query(
                f.category, f.subType, f.minLevel, f.taskKey,
                f.fromTs, f.toTs, f.keyword, f.limit, f.offset));
    }

    /** 任务维度视图（"任务详情→查看日志"）；降级模式返回 null */
    public List<LogEntry> queryByTask(String taskKey, int limit, int offset) {
        if (db == null) return null;
        return await(() -> db.logDao().queryByTask(taskKey, limit, offset));
    }

    private <T> T await(Callable<T> callable) {
        try {
            Future<T> f = ioExecutor.submit(callable);
            return f.get(5, TimeUnit.SECONDS);
        } catch (Throwable th) {
            Log.e("LogStore", "日志查询失败", th);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 开关 / 级别
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    /** 总开关（由 LogConfig 驱动；开启时联动 logcat 捕获） */
    public void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            LogcatCapture.start();
        } else {
            LogcatCapture.stop();
        }
    }

    public int getLevel() {
        return minLevel;
    }

    public void setLevel(int level) {
        minLevel = Math.max(LEVEL_DEBUG, Math.min(LEVEL_ERROR, level));
    }

    /** 按大类型动态开关（如线上按需开 下载 DEBUG） */
    public void setCategoryEnabled(Category category, boolean on) {
        if (on) {
            disabledCategories.remove(category.name());
        } else {
            disabledCategories.add(category.name());
        }
    }

    // ------------------------------------------------------------------
    // 运维
    // ------------------------------------------------------------------

    /** 按保留天数清理（默认 7 天；只删日志，不碰任务元数据与档案）；降级模式空操作 */
    public void clear(int retentionDays) {
        if (db == null) return;
        final long before = System.currentTimeMillis() - Math.max(1, retentionDays) * DAY_MS;
        ioExecutor.execute(() -> {
            try {
                db.logDao().deleteBefore(before);
            } catch (Throwable th) {
                Log.e("LogStore", "日志清理失败", th);
            }
        });
    }

    /** 清空全部业务日志；降级模式空操作 */
    public void clearAll() {
        if (db == null) return;
        ioExecutor.execute(() -> {
            try {
                db.logDao().clearAll();
            } catch (Throwable th) {
                Log.e("LogStore", "日志清空失败", th);
            }
        });
    }

    /** 按当前筛选导出 txt（放 cacheDir，可 FileProvider 分享）；无结果/降级模式返回 null */
    public File export(LogFilter f) {
        if (db == null) return null;
        List<LogEntry> entries = query(f);
        if (entries == null || entries.isEmpty()) return null;
        try {
            File out = new File(LogcatCapture.appContext().getCacheDir(), "log_export_" + System.currentTimeMillis() + ".txt");
            FileWriter fw = new FileWriter(out);
            try {
                for (LogEntry e : entries) {
                    fw.write(formatEntry(e) + "\n");
                }
            } finally {
                fw.close();
            }
            return out;
        } catch (Throwable th) {
            Log.e("LogStore", "日志导出失败", th);
            return null;
        }
    }

    /** 崩溃捕获：未捕获异常落库 + 立即落盘 + 转交原 handler */
    public void installCrashHandler() {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringBuilder sb = new StringBuilder("未捕获异常: " + e);
                for (StackTraceElement el : e.getStackTrace()) {
                    sb.append('\n').append("    at ").append(el);
                    if (sb.length() > 4000) break;
                }
                log(Category.SYSTEM.name(), LEVEL_ERROR, "crash", "崩溃", sb.toString(),
                        "FAILURE", sb.toString(), null, null);
                flushNow();
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    /** 日志页一行展示：[时间] [大类型] [小类型] 干了啥 ✓/✗ */
    public String formatEntry(LogEntry e) {
        StringBuilder sb = new StringBuilder(96);
        sb.append('[').append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(e.timestamp))).append("] ");
        sb.append('[').append(categoryLabel(e.category)).append("] ");
        String sub = e.subTypeLabel != null && !e.subTypeLabel.isEmpty() ? e.subTypeLabel : e.subTypeCode;
        sb.append('[').append(sub).append("] ");
        sb.append(e.detail == null ? "" : e.detail);
        if ("SUCCESS".equals(e.result)) {
            sb.append("  ✓");
        } else if ("FAILURE".equals(e.result)) {
            sb.append("  ✗");
        }
        return sb.toString();
    }

    /** 级别常量 → 名称 */
    public static String levelName(int level) {
        return LEVEL_NAMES[Math.max(LEVEL_DEBUG, Math.min(LEVEL_ERROR, level))];
    }

    private String categoryLabel(String categoryName) {
        if (categoryName == null) return "";
        for (Category c : Category.values()) {
            if (c.name().equals(categoryName)) return c.label();
        }
        return categoryName;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** LoggerImpl：CategoryLogger 的默认实现，大类型由构造注入 */
    private static final class LoggerImpl<T extends Enum<T> & SubType> implements CategoryLogger<T> {
        private final String categoryName;

        LoggerImpl(String categoryName) {
            this.categoryName = categoryName;
        }

        @Override
        public void success(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_INFO, subType.code(), subType.label(), detail, "SUCCESS", null, extras, null);
        }

        @Override
        public void fail(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_ERROR, subType.code(), subType.label(), detail, "FAILURE", detail, extras, null);
        }

        @Override
        public void fail(T subType, String detail, Throwable t, JSONObject extras) {
            StringBuilder reason = new StringBuilder();
            if (detail != null) reason.append(detail);
            reason.append(" | ").append(t == null ? "null" : t);
            Throwable cause = t;
            int lines = 0;
            while (cause != null && cause.getStackTrace() != null && lines < 40) {
                for (StackTraceElement el : cause.getStackTrace()) {
                    reason.append('\n').append("    at ").append(el);
                    if (++lines >= 40) break;
                }
                cause = cause.getCause();
            }
            get().log(categoryName, LEVEL_ERROR, subType.code(), subType.label(), detail, "FAILURE", reason.toString(), extras, null);
        }

        @Override
        public void info(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_INFO, subType.code(), subType.label(), detail, "INFO", null, extras, null);
        }

        @Override
        public void warn(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_WARN, subType.code(), subType.label(), detail, "INFO", null, extras, null);
        }

        @Override
        public void debug(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_DEBUG, subType.code(), subType.label(), detail, "INFO", null, extras, null);
        }
    }
}
