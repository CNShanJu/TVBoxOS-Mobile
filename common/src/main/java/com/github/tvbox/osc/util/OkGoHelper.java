package com.github.tvbox.osc.util;

import static okhttp3.ConnectionSpec.CLEARTEXT;
import static okhttp3.ConnectionSpec.COMPATIBLE_TLS;
import static okhttp3.ConnectionSpec.MODERN_TLS;
import static okhttp3.ConnectionSpec.RESTRICTED_TLS;

import android.content.Context;

import com.github.catvod.net.SSLCompat;
import com.github.tvbox.osc.util.urlhttp.BrotliInterceptor;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Cache;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * 全局 OkHttpClient 初始化（独立模块 :common）。
 * Context 由 {@link #init(Context)} 注入（不依赖 app 类）；
 * Exo 播放内核与 Picasso 的初始化已拆回 app 侧（依赖 :player / picasso）。
 */
public class OkGoHelper {
    public static final long DEFAULT_MILLISECONDS = 10000;      //默认的超时时间

    private static Context appContext;

    public static DnsOverHttps dnsOverHttps = null;

    public static ArrayList<String> dnsHttpsList = new ArrayList<>();

    public static List<ConnectionSpec> getConnectionSpec() {
        return Collections.unmodifiableList(Arrays.asList(RESTRICTED_TLS, MODERN_TLS, COMPATIBLE_TLS, CLEARTEXT));
    }

    public static String getDohUrl(int type) {
        switch (type) {
            case 1: {
                return "https://doh.pub/dns-query";
            }
            case 2: {
                return "https://dns.alidns.com/dns-query";
            }
            case 3: {
                return "https://doh.360.cn/dns-query";
            }
        }
        return "";
    }

    static void initDnsOverHttps() {
        if (dnsHttpsList.isEmpty()) {
            dnsHttpsList.add("关闭");
            dnsHttpsList.add("腾讯");
            dnsHttpsList.add("阿里");
            dnsHttpsList.add("360");
        }
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        } else {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.NONE);
        }
        builder.addInterceptor(loggingInterceptor);
        builder.addInterceptor(new BrotliInterceptor());
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        builder.connectionSpecs(getConnectionSpec());
        if (appContext != null) {
            builder.cache(new Cache(new File(appContext.getCacheDir().getAbsolutePath(), "dohcache"), 10 * 1024 * 1024));
        }
        OkHttpClient dohClient = builder.build();
        String dohUrl = getDohUrl(SystemConfig.getDohUrl());
        if (dohUrl.isEmpty()) {
            dnsOverHttps = null;
        } else {
            dnsOverHttps = new DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(dohUrl)).build();
        }
    }

    static OkHttpClient defaultClient = null;
    static OkHttpClient noRedirectClient = null;

    /**
     * 根据当前 Hawk 配置重建 DnsOverHttps(替代原 DnsOverHttps.setUrl 原地修改)
     * 注意:已构建的 OkHttpClient 不会立即生效,重启应用或下次重建客户端时生效
     */
    public static void refreshDnsOverHttps() {
        initDnsOverHttps();
    }

    public static OkHttpClient getDefaultClient() {
        return defaultClient;
    }

    public static OkHttpClient getNoRedirectClient() {
        return noRedirectClient;
    }

    /** App 启动时调用一次（context 注入；Exo/Picasso 初始化由 app 侧在 init 后自行完成） */
    public static void init(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        initDnsOverHttps();

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();

        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        } else {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.NONE);
        }
        builder.addInterceptor(loggingInterceptor);
        // 默认 User-Agent:还原 OkGo 的全局 UA 行为,部分源接口无 UA 会拒绝请求
        builder.addInterceptor(new HttpClient.UserAgentInterceptor());

        //builder.retryOnConnectionFailure(false);
        builder.connectionSpecs(getConnectionSpec());
        builder.addInterceptor(new BrotliInterceptor());
        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                .writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                .connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);
        if (dnsOverHttps != null) {
            builder.dns(dnsOverHttps);
        }
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }

        OkHttpClient okHttpClient = builder.build();

        defaultClient = okHttpClient;

        builder.followRedirects(false);
        builder.followSslRedirects(false);
        noRedirectClient = builder.build();
    }

    private static synchronized void setOkHttpSsl(OkHttpClient.Builder builder) {
        try {
            final SSLSocketFactory sslSocketFactory = new SSLCompat();
            builder.sslSocketFactory(sslSocketFactory, SSLCompat.TM);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
