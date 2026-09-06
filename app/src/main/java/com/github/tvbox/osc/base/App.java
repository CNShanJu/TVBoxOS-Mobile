package com.github.tvbox.osc.base;

import android.text.TextUtils;

import androidx.multidex.MultiDexApplication;

import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.ui.activity.MainActivity;
import com.github.tvbox.osc.util.AppLog;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubscriptionConfig;
import com.github.tvbox.osc.util.Utils;
import com.kingja.loadsir.core.LoadSir;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;

import okhttp3.OkHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;

    public boolean isNormalStart;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // AppLog(common) context 注入: 按天文件/导出用
        AppLog.setAppContext(this);
        // OKGo: 全局 OkHttpClient 初始化(common 模块, context 注入); Exo/Picasso 初始化拆回 app 侧
        OkGoHelper.init(this);
        initExoOkHttpClient();
        initPicasso();
        // EPG JSON 解析从启动主线程移除:首次直播取 EPG 信息时懒加载(EpgUtil.getEpgInfo 内自触发)
        // 初始化Web服务器
        ControlManager.init(this);
        // ApiConfig(:spider 模块) context + 局域网地址注入(替代直接依赖)
        com.github.tvbox.osc.api.ApiConfig.setAppContext(this);
        try {
            // server 未启动时 getAddress 会 NPE,此处兜底跳过(lanBase 由 startServer 就绪后注入)
            com.github.tvbox.osc.api.ApiConfig.setLanBase(ControlManager.get().getAddress(true));
        } catch (Throwable ignored) {
        }
        //初始化数据库(context 注入式:不再依赖 App 单例,见 AppDataManager)
        AppDataManager.init(this);
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance()
                .setExcludeFontScale(true)
                .setCustomFragment(true)
                .getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        // 播放器缓存清理移出启动主线程:延迟到首屏后再由后台低优先级线程执行,且超过阈值才清(见 schedulePlayerCacheCleanup)
        schedulePlayerCacheCleanup();
        initCrashConfig();
        Utils.initTheme();
        AppLog.log("运行", "应用启动(Android " + android.os.Build.VERSION.RELEASE + ")");
        // 业务日志(系统类目):记录本机屏幕尺寸(宽×高,px),便于按机型定位布局/适配问题
        logDeviceScreenToBiz();
        // 崩溃捕获:未捕获异常落库(log 模块)
        LogStore.get().installCrashHandler();
        // 下载模块(:download) context 注入(保存目录/海报/网络监听/通知)
        com.github.tvbox.osc.download.DownloadFacade.init(this);
        // 组合根:收敛 spider-api 服务注入(解析/手动判定/内容服务),见 AppCompositionRoot
        com.github.tvbox.osc.di.AppCompositionRoot.init();
        // 方案A:注册无头 WebView 嗅探器(嗅探型源任务启动前用它拿真实播放地址,串行复用保会话)
        com.github.tvbox.osc.download.DownloadFacade.setUrlSniffer(com.github.tvbox.osc.util.WebSniffResolver.get());
        // 下载完成通知渠道(可选增强)
        
        // 全局系统状态监控(网络/前后台/横竖屏/电量/磁盘, 基座层)
        SystemStateMonitor.init(this);
        // 更新缓存清理:更新完成并安装后,首次启动删除"版本与当前一致"的本地 APK;并清半成品
        try {
            com.github.tvbox.osc.update.UpdateManager.cleanupOnAppStart(this);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记录本机屏幕尺寸到业务日志(系统类目,INFO):每次启动调用一次,便于按机型/分辨率定位
     * 布局与适配问题。用真实显示区域(含状态栏/导航栏,getRealMetrics);LogStore 未启用时静默丢弃。
     */
    private void logDeviceScreenToBiz() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.view.Display display = ((android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
            display.getRealMetrics(dm);
            int wDp = Math.round(dm.widthPixels / dm.density);
            int hDp = Math.round(dm.heightPixels / dm.density);
            LogStore.log(Category.SYSTEM, "设备屏幕: " + dm.widthPixels + "x" + dm.heightPixels
                    + " px(" + wDp + "x" + hDp + " dp,density=" + dm.density + ")");
        } catch (Throwable ignored) {
        }
    }

    /** 播放器缓存清理:延迟到首屏后再执行;仅当缓存超阈值才递归删除,低优先级后台线程 */
    private void schedulePlayerCacheCleanup() {
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(() -> {
            Thread t = new Thread(() -> {
                try {
                    FileUtils.cleanPlayerCacheIfOverflow(100L * 1024 * 1024);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }, "player-cache-clean");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }, 5000L);
    }

    private void initParams() {
        // 现代化偏好存储(Preferences DataStore;标量域逐步迁移,见 DownloadPolicy 试点)
        com.github.tvbox.osc.config.PrefsDataStore.init(this);
        com.github.tvbox.osc.config.PrefsDataStore.put(HawkConfig.DEBUG_OPEN, false);

        putDefault(HawkConfig.HOME_REC, 0);                  //推荐: 0=豆瓣热播, 1=站点推荐
        putDefault(HawkConfig.PLAY_TYPE, 2);                 //播放器: 0=系统, 1=IJK, 2=Exo
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE,2);           //后台播放: 0 关闭,1 开启,2 画中画
        putDefault(HawkConfig.DOH_URL, 0);                   //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.HISTORY_NUM, 2);                //历史记录数量: 0=30, 1=50, 2=70
        putDefault(HawkConfig.APP_LOG, false);                //运行日志:默认关闭,排查问题时开启
        putDefault(HawkConfig.SUBTITLE_OPEN, false);          //字幕:默认关闭,播放器设置里可开关
        putDefault(HawkConfig.IGNORE_SSL_ERROR, false);       //忽略证书错误:默认关闭(开启会降低 TLS 安全性)
        putDefault(HawkConfig.LAN_SERVER_ENABLE, false);      //局域网服务:默认关闭(HTTP 服务仅监听 127.0.0.1)
        putDefault(HawkConfig.LOADING_ANIM, "");               //加载动画:空=默认,或 assets/loading/ 下的文件名
        putDefault(HawkConfig.LIVE_URL, "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.txt"); //直播源:默认地址
        putDefaultApi();
        // 日志模块初始化:按 LogConfig 同步开关(默认关),开启时自动启动 logcat 捕获(package:mine)
        // 注入版本号:日志行首展示(定位问题时确认是哪个版本产生)
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            int c = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            LogStore.setAppVersion(v + "(" + c + ")");
        } catch (Throwable ignored) {
        }
        // 日志配置持久化走 core-storage 配置封装(依赖倒置;先于 LogStore.init,避免同一键两侧分叉)
        com.github.tvbox.osc.log.LogConfig.setStore(com.github.tvbox.osc.config.DefaultLogConfigStore.get());
        LogStore.init(this);
    }

    private void putDefaultApi() {
        // 本地默认订阅文件是默认订阅的唯一来源,每次启动与列表同步:
        // 文件新增的默认订阅 -> 补进列表;文件删除/清空的默认订阅 -> 从列表移除
        List<Subscription> defaults = readDefaultSubscriptions();
        boolean filePresent = defaults != null;
        if (defaults == null) defaults = new ArrayList<>();
        List<Subscription> injected = SubscriptionConfig.getDefaultSubs();
        List<Subscription> subs = SubscriptionConfig.getSubscriptions();
        if (subs == null) subs = new ArrayList<>();

        // 迁移兼容:旧版本注入默认订阅时未记录 DEFAULT_SUBS。
        // 1) 文件已清空:若列表符合旧版注入特征(首项勾选且接口地址=首项地址),视为注入集交给同步逻辑清除;
        // 2) 文件有内容:把与文件匹配的现有订阅视为注入集,文件后续删掉它们时能同步移除。
        if (!SubscriptionConfig.containsDefaultSubs()) {
            if (filePresent && defaults.isEmpty() && !subs.isEmpty()
                    && subs.get(0).isChecked()
                    && TextUtils.equals(subs.get(0).getUrl(), SubscriptionConfig.getApiUrl())) {
                injected = new ArrayList<>(subs);
            } else if (!defaults.isEmpty()) {
                for (Subscription def : defaults) {
                    if (containsSub(subs, def) && !containsSub(injected, def)) {
                        injected.add(def);
                    }
                }
            }
        }

        boolean changed = false;
        boolean removedChecked = false;

        // 1) 移除:文件里已不存在的注入项
        for (Subscription inj : injected) {
            Iterator<Subscription> it = subs.iterator();
            while (it.hasNext()) {
                Subscription s = it.next();
                if (TextUtils.equals(s.getName(), inj.getName()) && TextUtils.equals(s.getUrl(), inj.getUrl())) {
                    if (s.isChecked()) removedChecked = true;
                    it.remove();
                    changed = true;
                    break;
                }
            }
        }
        // 2) 补齐:文件里新增的默认订阅
        for (Subscription def : defaults) {
            if (!containsSub(subs, def)) {
                subs.add(new Subscription(def.getName(), def.getUrl()));
                changed = true;
            }
        }

        // 3) 勾选与接口地址维护
        if (subs.isEmpty()) {
            if (changed) {
                SubscriptionConfig.setSubscriptions(subs);
                SubscriptionConfig.setApiUrl("");
            }
        } else {
            boolean hasChecked = false;
            for (Subscription s : subs) {
                if (s.isChecked()) {
                    hasChecked = true;
                    break;
                }
            }
            if (!hasChecked || removedChecked || TextUtils.isEmpty(SubscriptionConfig.getApiUrl())) {
                subs.get(0).setChecked(true);
                SubscriptionConfig.setApiUrl(subs.get(0).getUrl());
                changed = true;
            }
            if (changed) SubscriptionConfig.setSubscriptions(subs);
        }

        // 4) 记录本次文件内容,供下次同步
        SubscriptionConfig.setDefaultSubs(defaults);
    }

    private static boolean containsSub(List<Subscription> list, Subscription sub) {
        for (Subscription s : list) {
            if (TextUtils.equals(s.getName(), sub.getName()) && TextUtils.equals(s.getUrl(), sub.getUrl())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取本地默认订阅文件 app/src/main/assets/config/default_subscriptions.json
     * 格式: [{"name":"订阅名","url":"订阅地址"}, ...]
     * 文件存在(本地打包)则返回默认订阅列表;文件不存在(线上打包)返回 null
     */
    private List<Subscription> readDefaultSubscriptions() {
        try {
            InputStream is = getAssets().open("config/default_subscriptions.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            JSONArray array = new JSONArray(sb.toString());
            List<Subscription> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String name = obj.optString("name", "");
                String url = obj.optString("url", "");
                if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(url)) {
                    list.add(new Subscription(name, url));
                }
            }
            return list;
        } catch (Throwable th) {
            LOG.e("readDefaultSubscriptions: " + th.getMessage());
            return null;
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.load();
    }

    /** 写默认值:仅当现代化偏好存储尚无该键(各配置门面 getter 亦有默认兜底;不回写旧 Hawk) */
    private void putDefault(String key, Object value) {
        if (!com.github.tvbox.osc.config.PrefsDataStore.contains(key)) {
            com.github.tvbox.osc.config.PrefsDataStore.put(key, value);
        }
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(instance.getExternalCacheDir().getAbsolutePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    /** Exo 播放内核使用与全局共享同一套根配置的 OkHttpClient(公共基础在 :core-network OkGoHelper.newBaseBuilder) */
    private void initExoOkHttpClient() {
        try {
            OkHttpClient.Builder builder = OkGoHelper.newBaseBuilder();
            builder.retryOnConnectionFailure(true);
            builder.followRedirects(true);
            builder.followSslRedirects(true);
            playbackHttpClient = builder.build();
            xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.getInstance(this).setOkClient(playbackHttpClient);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 播放内核客户端(供 NetworkProvider.playback 复用同一实例) */
    public static volatile okhttp3.OkHttpClient playbackHttpClient;

    /** Picasso 全局单例（原 OkGoHelper.initPicasso 拆回 app 侧） */
    private void initPicasso() {
        try {
            OkHttpClient client = OkGoHelper.getDefaultClient();
            if (client == null) return;
            client.dispatcher().setMaxRequestsPerHost(32);
            com.github.tvbox.osc.picasso.MyOkhttpDownLoader downloader = new com.github.tvbox.osc.picasso.MyOkhttpDownLoader(client);
            com.squareup.picasso.Picasso picasso = new com.squareup.picasso.Picasso.Builder(this)
                    .downloader(downloader)
                    .executor(com.github.tvbox.osc.util.HeavyTaskUtil.getBigTaskExecutorService())
                    .defaultBitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .build();
            com.squareup.picasso.Picasso.setSingletonInstance(picasso);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void initCrashConfig(){
        //配置全局异常崩溃操作
        CaocConfig.Builder.create()
                .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT) //背景模式,开启沉浸式
                .enabled(true) //是否启动全局异常捕获
                .showErrorDetails(true) //是否显示错误详细信息
                .showRestartButton(true) //是否显示重启按钮
                .trackActivities(true) //是否跟踪Activity
                .minTimeBetweenCrashesMs(2000) //崩溃的间隔时间(毫秒)
                .errorDrawable(R.drawable.ic_crash) //错误图标(记录空空的,矢量)
                .restartActivity(MainActivity.class) //重新启动后的activity
                .apply();
        // 魅族系统(如 ContentCapture 线程的 com.meizu.internal.picker)存在已知 NPE bug,
        // 属于系统问题而非 App 代码,直接吞掉避免整个 App 被杀,其余异常仍走 CAOC
        final Thread.UncaughtExceptionHandler caoc = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                if (isBenignSystemThrowable(thread, throwable)) {
                    return;
                }
                if (caoc != null) {
                    caoc.uncaughtException(thread, throwable);
                }
            }
        });
    }

    /** 判断是否为魅族系统内部的无害异常(系统线程 NPE 等),不应导致 App 崩溃 */
    private boolean isBenignSystemThrowable(Thread thread, Throwable throwable) {
        try {
            if (thread != null && "ContentCapture".equals(thread.getName())) {
                return true;
            }
            Throwable t = throwable;
            while (t != null) {
                for (StackTraceElement e : t.getStackTrace()) {
                    String cls = e.getClassName();
                    if (cls.startsWith("com.meizu.internal.") || cls.startsWith("com.meizu.picker.")) {
                        return true;
                    }
                }
                t = t.getCause();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

}