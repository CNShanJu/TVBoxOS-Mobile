package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.IDN;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 轻量网络请求封装(替代 OkGo,基于 OkHttp 4.x)
 * <ul>
 *     <li>异步 GET / POST JSON / 文件下载,回调统一切回主线程</li>
 *     <li>同步 GET / 下载,调用方需自行确保不在主线程执行</li>
 *     <li>支持 tag 取消请求(替代 OkGo.getInstance().cancelTag)</li>
 * </ul>
 */
public class HttpClient {
    public static final long DEFAULT_MILLISECONDS = 10000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile OkHttpClient client;

    private HttpClient() {
    }

    public static OkHttpClient getClient() {
        OkHttpClient c = OkGoHelper.getDefaultClient();
        if (c != null) return c;
        if (client == null) {
            synchronized (HttpClient.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                            .readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                            .writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                            .addInterceptor(new UserAgentInterceptor())
                            .build();
                }
            }
        }
        return client;
    }

    /** 为没有显式设置 User-Agent 的请求补充默认 UA(还原 OkGo 全局 UA 行为,保持与旧版一致的 okhttp/3.x 字符串) */
    static class UserAgentInterceptor implements okhttp3.Interceptor {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            okhttp3.Request request = chain.request();
            if (request.header("User-Agent") == null) {
                request = request.newBuilder().header("User-Agent", "okhttp/3.12.11").build();
            }
            return chain.proceed(request);
        }
    }

    // ---------------------------------------------------------------------
    // 异步 GET
    // ---------------------------------------------------------------------

    public static void get(String url, Object tag, HCallBack callback) {
        get(url, null, null, tag, callback);
    }

    public static void get(String url, Map<String, String> headers, Object tag, HCallBack callback) {
        get(url, null, headers, tag, callback);
    }

    public static void get(String url, Map<String, String> params, Map<String, String> headers, Object tag, final HCallBack callback) {
        try {
            final String fullUrl = normalizeUrl(buildUrl(url, params));
            Request.Builder builder = new Request.Builder().url(fullUrl).tag(tag);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            getClient().newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.body() == null) {
                            postSuccess(callback, "");
                            return;
                        }
                        postSuccess(callback, response.body().string());
                    } catch (Throwable th) {
                        postError(callback, th);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable th) {
            // 请求构造异常(如非法 URL)不能崩溃调用线程,走回调
            postError(callback, th);
        }
    }

    // ---------------------------------------------------------------------
    // 同步 GET(返回完整 Response,调用方负责关闭)
    // ---------------------------------------------------------------------

    public static Response getResponseSync(String url, Map<String, String> headers) throws IOException {
        try {
            Request.Builder builder = new Request.Builder().url(normalizeUrl(url));
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            return getClient().newCall(builder.build()).execute();
        } catch (IOException e) {
            throw e;
        } catch (Throwable th) {
            // 非法 URL 等构造异常统一转 IOException,避免逃逸成运行时异常
            throw new IOException("request build failed: " + th.getMessage(), th);
        }
    }

    /** 同步 GET,成功返回字符串,失败抛出 IOException */
    public static String getSync(String url, Map<String, String> headers) throws IOException {
        Response response = getResponseSync(url, headers);
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("response not successful: " + response.code());
            }
            return response.body().string();
        } finally {
            response.close();
        }
    }

    // ---------------------------------------------------------------------
    // 文件下载
    // ---------------------------------------------------------------------

    /** 同步下载到目标文件,返回目标文件 */
    public static File downloadSync(String url, File dest) throws IOException {
        return downloadSync(url, dest, null);
    }

    public static File downloadSync(String url, File dest, Map<String, String> headers) throws IOException {
        Response response = getResponseSync(url, headers);
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("download failed, code=" + response.code());
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // 文件可能被置为只读(防止 dex 校验),覆盖前恢复可写
            if (dest.exists() && !dest.canWrite()) {
                dest.setWritable(true);
            }
            InputStream is = response.body().byteStream();
            OutputStream os = new FileOutputStream(dest);
            try {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
            return dest;
        } finally {
            response.close();
        }
    }

    /** 异步下载到目标文件,成功/失败回调切主线程 */
    public static void download(final String url, final File dest, Map<String, String> headers, final Object tag, final FCallBack callback) {
        try {
            Request.Builder builder = new Request.Builder().url(normalizeUrl(url)).tag(tag);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            getClient().newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (!response.isSuccessful() || response.body() == null) {
                            throw new IOException("download failed, code=" + response.code());
                        }
                        File parent = dest.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        // 文件可能被置为只读(防止 dex 校验),覆盖前恢复可写
                        if (dest.exists() && !dest.canWrite()) {
                            dest.setWritable(true);
                        }
                        InputStream is = response.body().byteStream();
                        OutputStream os = new FileOutputStream(dest);
                        try {
                            byte[] buffer = new byte[8192];
                            int length;
                            while ((length = is.read(buffer)) > 0) {
                                os.write(buffer, 0, length);
                            }
                        } finally {
                            try {
                                is.close();
                            } catch (IOException ignored) {
                            }
                            try {
                                os.close();
                            } catch (IOException ignored) {
                            }
                        }
                        postSuccess(callback, dest);
                    } catch (Throwable th) {
                        postError(callback, th);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable th) {
            postError(callback, th);
        }
    }

    // ---------------------------------------------------------------------
    // 取消
    // ---------------------------------------------------------------------

    public static void cancel(Object tag) {
        if (tag == null) return;
        OkHttpClient c = getClient();
        for (Call call : c.dispatcher().queuedCalls()) {
            if (tag.equals(call.request().tag())) call.cancel();
        }
        for (Call call : c.dispatcher().runningCalls()) {
            if (tag.equals(call.request().tag())) call.cancel();
        }
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------

    private static String buildUrl(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) return url;
        try {
            HttpUrl.Builder builder = HttpUrl.get(normalizeUrl(url)).newBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() == null) continue;
                if (entry.getValue() == null) continue;
                builder.addQueryParameter(entry.getKey(), entry.getValue());
            }
            return builder.build().toString();
        } catch (Throwable th) {
            // 兼容旧 OkGo 的宽松拼接:HttpUrl 解析失败时降级为字符串拼接,避免抛异常
            StringBuilder sb = new StringBuilder(url);
            boolean first = url.indexOf('?') < 0;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                try {
                    sb.append(first ? '?' : '&');
                    first = false;
                    sb.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                } catch (UnsupportedEncodingException ignored) {
                }
            }
            return sb.toString();
        }
    }

    private static final Pattern URL_HOST_PATTERN = Pattern.compile("^(https?://)([^/?#:]+)(:\\d+)?([/?#].*)?$");

    /** 中文域名转 punycode(OkHttp 的 HttpUrl 不接收非 ASCII 域名),失败时原样返回 */
    private static String normalizeUrl(String url) {
        if (url == null) return null;
        try {
            Matcher m = URL_HOST_PATTERN.matcher(url);
            if (m.matches() && isNonAscii(m.group(2))) {
                String host = IDN.toASCII(m.group(2));
                String port = m.group(3) != null ? m.group(3) : "";
                String rest = m.group(4) != null ? m.group(4) : "";
                return m.group(1) + host + port + rest;
            }
        } catch (Throwable ignored) {
        }
        return url;
    }

    private static boolean isNonAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return true;
        }
        return false;
    }

    private static void postSuccess(final HCallBack callback, final String content) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(content);
            }
        });
    }

    private static void postError(final HCallBack callback, final Throwable e) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }

    private static void postSuccess(final FCallBack callback, final File file) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(file);
            }
        });
    }

    private static void postError(final FCallBack callback, final Throwable e) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }
}
