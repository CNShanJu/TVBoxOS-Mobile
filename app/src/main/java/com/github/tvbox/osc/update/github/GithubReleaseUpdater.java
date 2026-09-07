package com.github.tvbox.osc.update.github;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.di.AppCompositionRoot;
import com.github.tvbox.osc.update.UpdateManager;
import com.github.tvbox.osc.update.Updater;
import com.github.tvbox.osc.update.UpdaterConfig;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.util.HeavyTaskUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * GitHub Releases 更新实现:从仓库 latest release 拉取版本信息、匹配 APK 资产并交给
 * {@link UpdateManager} 统一下载(断点续传/暂停继续/全局悬浮圈),安装完成后再触发系统安装器。
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
        String apkName = null;
        long size = -1;
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String n = a.optString("name", "");
                if (!isApkAsset(n)) continue;
                long s = a.optLong("size", -1);
                if (!isDebugAsset(n)) {
                    apkName = n;
                    size = s;
                    break;
                }
                if (apkName == null) {
                    apkName = n;
                    size = s;
                }
            }
        }
        if (apkName == null) return null;
        List<String> candidates = buildDownloadCandidates(
                String.format("https://github.com/%s/%s/releases/download/%s/%s",
                        UpdaterConfig.getGithubOwner(), UpdaterConfig.getGithubRepo(), tag, apkName));
        if (candidates.isEmpty()) return null;
        return new UpdateInfo(version, tag, -1, candidates, apkName, size, note);
    }

    /** 构建下载候选地址:优先拼接 GitHub 加速代理,末尾保留直连作为兜底 */
    private static List<String> buildDownloadCandidates(String directUrl) {
        List<String> list = new ArrayList<>();
        if (directUrl == null || directUrl.trim().isEmpty()) return list;
        String direct = directUrl.trim();
        String proxy = UpdaterConfig.getGithubDownloadProxy();
        if (!proxy.isEmpty()) {
            // 拼接形如 https://gh-proxy.org/https://github.com/...
            list.add(proxy + direct);
        }
        list.add(direct);
        return list;
    }

    // ------------------------------------------------------------------
    // 下载 + 安装(统一下放 UpdateManager)
    // ------------------------------------------------------------------

    @Override
    public void downloadAndInstall(final Context context, final UpdateInfo info, final Callback cb) {
        // 下载/暂停/继续/取消/断点续传/复用缓存/安装全部收口到 UpdateManager,与 UI 无关,
        // 关闭弹窗不中断;全局悬浮圈(UpdateFloatIndicator)自动展示进度与控制入口。
        UpdateManager.get().start(context, info, cb);
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
