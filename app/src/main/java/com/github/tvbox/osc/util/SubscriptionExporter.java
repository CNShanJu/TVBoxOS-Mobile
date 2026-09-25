package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.server.ControlManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订阅导出:把勾选的订阅逐个抓下来(clan:// 本地文件直接读盘、http(s) 走 {@link HttpClient}),
 * 交 {@link SubsConfigMerger} 合并成一份配置文本,落到应用外部缓存目录的 txt,供调用方分享出去。
 *
 * <p>约束:
 * <ul>
 *   <li>抓取在 {@link HeavyTaskUtil} 共享执行器上跑,结果切回主线程丢给回调;</li>
 *   <li>同一时刻只认最后一次导出(epoch 自检),Activity 销毁后回来的结果直接丢弃;</li>
 *   <li>clan:// 本地路径先规范化并校验不逃出主存储根(防目录穿越),单份配置有大小上限,防异常大文件。</li>
 * </ul>
 */
public final class SubscriptionExporter {

    /** 单份配置文本上限:配置站点表正常也就几百 KB,超过多半是抓到了别的东西 */
    private static final long MAX_CONFIG_BYTES = 8L * 1024 * 1024;
    /** 导出目录里最多留几个文件(缓存目录也做有界清理,避免越积越多) */
    private static final int KEEP_EXPORT_FILES = 4;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 导出任务代号:新任务会把旧任务的回调判为过期(取消语义) */
    private static final AtomicLong RUN_EPOCH = new AtomicLong();

    public interface Callback {
        void onDone(File file, SubsConfigMerger.Result result);

        /** 一个可用源都没合出来(全抓取失败/全非 JSON 配置) */
        void onError(String message);
    }

    private SubscriptionExporter() {
    }

    /**
     * 后台抓取 + 合并 + 落盘,主线程回调。
     *
     * @param items 勾选的订阅(调用顺序即"先到先得"顺序)
     */
    public static void export(final Context context, final List<Subscription> items, final Callback cb) {
        final Context app = context == null ? null : context.getApplicationContext();
        if (app == null || items == null || items.isEmpty()) {
            if (cb != null) cb.onError("请先选择要导出的订阅");
            return;
        }
        final long epoch = RUN_EPOCH.incrementAndGet();
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            File out = null;
            SubsConfigMerger.Result result = null;
            String err = null;
            try {
                List<SubsConfigMerger.Source> sources = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                for (Subscription item : items) {
                    String text = null;
                    try {
                        text = fetch(app, item.getUrl());
                    } catch (Throwable t) {
                        failed.add(item.getName() + "(" + shortReason(t) + ")");
                        AppLog.log("订阅导出", "抓取失败 " + item.getName() + " " + item.getUrl() + " " + t);
                    }
                    sources.add(new SubsConfigMerger.Source(item.getName(), text));
                }
                result = SubsConfigMerger.merge(sources);
                if (result.isEmpty()) {
                    err = "没有合并出可用配置";
                    if (!failed.isEmpty()) err += "(抓取失败:" + join(failed) + ")";
                } else {
                    out = write(app, result.json);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                AppLog.log("订阅导出", "导出异常 " + t);
                err = "导出失败:" + t.getMessage();
            }
            final File fOut = out;
            final SubsConfigMerger.Result fResult = result;
            final String fErr = err;
            if (epoch != RUN_EPOCH.get()) return; // 已有更新的导出任务,本次结果作废
            MAIN.post(() -> {
                if (cb == null) return;
                if (fOut == null || fResult == null) cb.onError(fErr == null ? "导出失败" : fErr);
                else cb.onDone(fOut, fResult);
            });
        });
    }

    // ------------------------------------------------------------------
    // 抓取
    // ------------------------------------------------------------------

    private static String fetch(Context ctx, String url) throws IOException {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) throw new IOException("地址为空");
        if (u.startsWith("clan://")) return readClan(u);
        // 普通 http(s):带 ;pk; 的加密订阅只取文本(本功能不做解密,拿到的密文会在合并阶段判为不可用)
        int pk = u.indexOf(";pk;");
        String pure = pk > 0 ? u.substring(0, pk) : u;
        if (!pure.startsWith("http")) throw new IOException("不支持的订阅地址");
        return HttpClient.getSync(pure, null);
    }

    /**
     * 读 clan:// 本地订阅:优先按主存储真实路径直接读文件(最快,不依赖本地服务);
     * 读不到再回落 App 内置本地文件服务(/file/<path>),与配置加载走同一条路。
     */
    private static String readClan(String clanUrl) throws IOException {
        String prefix = "clan://localhost/";
        if (!clanUrl.startsWith(prefix)) throw new IOException("不支持的本地订阅地址");
        String path = clanUrl.substring(prefix.length());
        File file = resolveUnderPrimaryStorage(path);
        if (file != null && file.isFile()) return readText(file);
        String base = localServerBase();
        if (base != null) return HttpClient.getSync(base + "file/" + path, null);
        throw new IOException("本地订阅文件不可读");
    }

    /** 主存储根内的规范化路径(拒绝 ../ 等逃逸);越界返回 null */
    private static File resolveUnderPrimaryStorage(String path) {
        try {
            if (path.contains("..")) return null;
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            File f = new File(root, path).getCanonicalFile();
            if (f.equals(root) || !f.getPath().startsWith(root.getPath() + File.separator)) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** App 内置本地服务地址(http://127.0.0.1:<port>/);服务未启动返回 null */
    private static String localServerBase() {
        try {
            String addr = ControlManager.get().getAddress(true);
            return addr == null || addr.isEmpty() ? null : addr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(File file) throws IOException {
        if (file.length() > MAX_CONFIG_BYTES) throw new IOException("配置过大(" + file.length() + "B)");
        StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 1 << 16));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_CONFIG_BYTES) throw new IOException("配置过大");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /** 写入应用外部缓存目录(FileProvider 已覆盖 external-cache-path,可直接分享);保留最近几份 */
    private static File write(Context ctx, String json) throws IOException {
        File dir = new File(ctx.getExternalCacheDir(), "subscription_export");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("导出目录不可用");
        prune(dir);
        String name = ctx.getString(R.string.app_name) + "订阅合并_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        File out = new File(dir, name);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
            writer.write(json);
        }
        return out;
    }

    /** 只留最近 {@link #KEEP_EXPORT_FILES} 份导出文件 */
    private static void prune(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= KEEP_EXPORT_FILES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP_EXPORT_FILES; i < files.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String shortReason(Throwable t) {
        String msg = t == null ? null : t.getMessage();
        if (msg == null || msg.isEmpty()) return "读取失败";
        return msg.length() > 24 ? msg.substring(0, 24) : msg;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 3; i++) {
            if (i > 0) sb.append('、');
            sb.append(list.get(i));
        }
        if (list.size() > 3) sb.append(" 等").append(list.size()).append("项");
        return sb.toString();
    }
}
