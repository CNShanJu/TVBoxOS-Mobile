package com.github.tvbox.osc.update.github;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.di.AppCompositionRoot;
import com.github.tvbox.osc.update.Updater;
import com.github.tvbox.osc.update.UpdaterConfig;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.util.HeavyTaskUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * GitHub Releases 更新实现:从仓库 latest release 拉取版本信息、匹配 APK 资产并下载安装。
 * <p>
 * 网络请求经 {@link AppCompositionRoot#network()} 提供的共享客户端(不 self-new OkHttpClient),
 * 后台任务复用 {@link HeavyTaskUtil} 共享执行器,回调统一切回主线程。
 */
public class GithubReleaseUpdater implements Updater {

    private static final String GITHUB_API = "https://api.github.com/repos/%s/%s/releases/latest";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public String name() {
        return UpdaterConfig.SOURCE_GITHUB;
    }

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    @Override
    public void checkUpdate(final Context context, final Callback cb) {
        if (cb == null) return;
        cb.onCheckStart();
        final String api = String.format(GITHUB_API, UpdaterConfig.getGithubOwner(), UpdaterConfig.getGithubRepo());
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            UpdateInfo info = null;
            String err = null;
            try {
                String current = AppUtils.getAppVersionName();
                okhttp3.Response resp = null;
                try {
                    OkHttpClient client = AppCompositionRoot.network().general();
                    Request req = new Request.Builder()
                            .url(api)
                            .header("Accept", "application/vnd.github.v3+json")
                            .build();
                    resp = client.newCall(req).execute();
                    if (!resp.isSuccessful() || resp.body() == null) {
                        err = "检查更新失败: HTTP " + resp.code();
                    } else {
                        info = parseRelease(resp.body().string(), current);
                    }
                } finally {
                    if (resp != null) resp.close();
                }
            } catch (Throwable t) {
                err = "检查更新失败: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            final UpdateInfo fi = info;
            final String fe = err;
            post(() -> {
                if (fe != null) cb.onError(fe);
                else cb.onCheckResult(fi);
            });
        });
    }

    /** 解析 latest release JSON;无更新时返回 null */
    private UpdateInfo parseRelease(String json, String current) throws Exception {
        JSONObject root = new JSONObject(json);
        String tag = root.optString("tag_name", "");
        String version = stripV(tag);
        if (version.isEmpty()) return null;
        // 当前版本非空时,仅当远端严格更大才提示(避免降级/同版本重复提示)
        if (current != null && !current.isEmpty()) {
            if (!isNewerVersion(version, current)) return null;
        }
        String note = root.optString("body", "");
        String url = null;
        String apkName = null;
        long size = -1;
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String n = a.optString("name", "");
                if (!isApkAsset(n)) continue;
                String u = a.optString("browser_download_url", a.optString("url", ""));
                long s = a.optLong("size", -1);
                if (!isDebugAsset(n)) {
                    apkName = n;
                    url = u;
                    size = s;
                    break;
                }
                if (apkName == null) {
                    apkName = n;
                    url = u;
                    size = s;
                }
            }
        }
        if (url == null) return null;
        return new UpdateInfo(version, tag, -1, url, apkName, size, note);
    }

    // ------------------------------------------------------------------
    // 下载 + 安装
    // ------------------------------------------------------------------

    @Override
    public void downloadAndInstall(final Context context, final UpdateInfo info, final Callback cb) {
        if (info == null) {
            if (cb != null) cb.onError("更新信息为空");
            return;
        }
        final String apkName = (info.apkName == null || info.apkName.isEmpty()) ? "update.apk" : info.apkName;
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            File dest = null;
            String err = null;
            try {
                File dir = new File(context.getCacheDir(), "update");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建下载目录");
                }
                dest = new File(dir, apkName);
                OkHttpClient client = AppCompositionRoot.network().general();
                Request req = new Request.Builder().url(info.downloadUrl).build();
                okhttp3.Response resp = client.newCall(req).execute();
                try {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new IOException("下载失败: HTTP " + resp.code());
                    }
                    long total = resp.body().contentLength();
                    InputStream is = resp.body().byteStream();
                    FileOutputStream fos = new FileOutputStream(dest);
                    byte[] buf = new byte[8192];
                    long done = 0;
                    int len;
                    try {
                        while ((len = is.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                            done += len;
                            final long d = done;
                            final long t = total;
                            post(() -> {
                                if (cb != null) cb.onDownloadProgress(d, t);
                            });
                        }
                    } finally {
                        try {
                            is.close();
                        } catch (IOException ignored) {
                        }
                        try {
                            fos.close();
                        } catch (IOException ignored) {
                        }
                    }
                } finally {
                    resp.close();
                }
            } catch (Throwable t) {
                err = "下载失败: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            final File fdest = dest;
            final String ferr = err;
            post(() -> {
                if (ferr != null) {
                    if (cb != null) cb.onError(ferr);
                    return;
                }
                if (installApk(context, fdest)) {
                    if (cb != null) cb.onDownloadReady(info);
                } else {
                    if (cb != null) cb.onError("安装失败: 请允许安装未知应用后重试");
                }
            });
        });
    }

    /** 通过系统安装器安装 APK(API 26+ 先校验"安装未知应用"授权) */
    private boolean installApk(Context context, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    try {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + context.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Throwable ignored) {
                    }
                    return false;
                }
            }
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void post(Runnable r) {
        if (r != null) MAIN.post(r);
    }

    /** 去掉 tag 前缀的 v/V(展示用) */
    private static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) {
            return t.substring(1);
        }
        return t;
    }

    private static boolean isApkAsset(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    private static boolean isDebugAsset(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("debug");
    }

    /** 版本比较:remote 严格大于 current 时为 true(数值分段比较,忽略非数字前缀) */
    private static boolean isNewerVersion(String remote, String current) {
        int[] r = parseVersion(remote);
        int[] c = parseVersion(current);
        int len = Math.max(r.length, c.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? r[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (rv != cv) return rv > cv;
        }
        return false;
    }

    /** 提取版本中的连续数字分段,如 "v3.2.0" -> [3,2,0] */
    private static int[] parseVersion(String s) {
        if (s == null) return new int[0];
        List<Integer> list = new ArrayList<>();
        StringBuilder num = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) {
                num.append(ch);
            } else if (num.length() > 0) {
                list.add(Integer.parseInt(num.toString()));
                num.setLength(0);
            }
        }
        if (num.length() > 0) list.add(Integer.parseInt(num.toString()));
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }
}
