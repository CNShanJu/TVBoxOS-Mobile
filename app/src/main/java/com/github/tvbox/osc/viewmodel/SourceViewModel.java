package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.SystemConfig;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.spiderapi.SourceConfigApi;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import com.thoughtworks.xstream.security.NoTypePermission;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> playResult;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        // 源配置元信息契约(AppCompositionRoot 已注入 :spider 的 ApiConfig 实现;可注入 Fake)
        sourceConfig = SourceConfigProviders.get();
    }

    /** 源配置元信息契约(源注册表/首页源/vip 解析旗标;不再直读 :spider 的 ApiConfig) */
    private final SourceConfigApi sourceConfig;

    /** 爬虫串行池：统一委托 SpiderApi（quickjs 单线程限制，全模块共用同一串行执行器） */
    public static final ExecutorService spThreadPool = com.github.catvod.crawler.SpiderApi.serialExecutor();

    // homeContent
    public void getSort(String sourceKey) {
        if (sourceKey == null) {
            sortResult.postValue(null);
            return;
        }
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            // 调试辅助:jar 源首页加载 trace(定位 jar 内 toast 触发源;tag=SpiderTrace)
            android.util.Log.i("SpiderTrace", "[首页] " + sourceBean.getName() + " type=3 homeContent 开始");
            long traceStart = System.currentTimeMillis();
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    boolean typedHit = false;
                    try {
                        AbsSortXml sortXml = null;
                        List<Movie.Video> embedded = null; // 同响应内嵌首页视频(若有)
                        // 强类型试点:homeContent 解析下沉 :spider(15s 超时保护,与旧字符串链路一致)
                        Future<AbsSortXml> typedFuture = executor.submit(new Callable<AbsSortXml>() {
                            @Override
                            public AbsSortXml call() {
                                return com.github.tvbox.osc.spiderapi.SpiderHomeProviders.get()
                                        .homeContent(sourceBean.getKey(), true);
                            }
                        });
                        try {
                            sortXml = typedFuture.get(15, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            e.printStackTrace();
                            typedFuture.cancel(true);
                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                        }
                        typedHit = sortXml != null;
                        if (typedHit) {
                            // impl 已把响应内嵌 list(首页视频)解析到 sortXml.videoList
                            embedded = sortXml.videoList;
                        } else {
                            android.util.Log.w("SpiderBridge", "home(typed) 无结果/超时,回退字符串通道: key="
                                    + sourceBean.getKey());
                            // 字符串通道回退:旧链路语义(拉串后按 sortJson 解析)
                            Future<String> stringFuture = executor.submit(new Callable<String>() {
                                @Override
                                public String call() throws Exception {
                                    return com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                                            .homeContent(sourceBean.getKey(), true);
                                }
                            });
                            String sortJson = null;
                            try {
                                sortJson = stringFuture.get(15, TimeUnit.SECONDS);
                            } catch (TimeoutException e) {
                                e.printStackTrace();
                                stringFuture.cancel(true);
                            } catch (InterruptedException | ExecutionException e) {
                                e.printStackTrace();
                            }
                            if (sortJson != null) {
                                sortXml = sortJson(sortResult, sortJson);
                                // 旧 json() 语义:同响应可能内嵌首页视频(list)
                                if (sortXml != null && sortXml.list != null && sortXml.list.videoList != null
                                        && !sortXml.list.videoList.isEmpty()) {
                                    embedded = sortXml.list.videoList;
                                }
                            }
                        }
                        publishHomeSort(sourceBean, sortXml, embedded);
                    } catch (Throwable th) {
                        th.printStackTrace();
                    } finally {
                        android.util.Log.i("SpiderTrace", "[首页] " + sourceBean.getName()
                                + " homeContent 结束 耗时=" + (System.currentTimeMillis() - traceStart)
                                + "ms typed=" + typedHit);
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            HttpClient.get(sourceBean.getApi(), null, sourceBean.getKey() + "_sort", new HCallBack() {
                        @Override
                        public void onSuccess(String content) {
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = content;
                                sortXml = sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = content;
                                sortXml = sortJson(sortResult, json);
                            }
                            if (sortXml != null && SystemConfig.getHomeRec() == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                AbsSortXml finalSortXml = sortXml;
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        sortResult.postValue(finalSortXml);
                                    }
                                });
                            } else {
                                sortResult.postValue(sortXml);
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            sortResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            Map<String, String> sortParams = new HashMap<>();
            sortParams.put("filter", "true");
            HttpClient.get(sourceBean.getApi(), sortParams, null, sourceBean.getKey() + "_sort", new HCallBack() {
                    @Override
                    public void onSuccess(String sortJson) {
                        if (sortJson != null) {
                            AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            if (sortXml != null && SystemConfig.getHomeRec() == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    sortResult.postValue(sortXml);
                                } else {
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            sortXml.videoList = videos;
                                            sortResult.postValue(sortXml);
                                        }
                                    });
                                }
                            } else {
                                sortResult.postValue(sortXml);
                            }
                        } else {
                            sortResult.postValue(null);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        sortResult.postValue(null);
                    }
                });
        } else {
            sortResult.postValue(null);
        }
    }
    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        SourceBean homeSourceBean = sourceConfig.getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 强类型试点:解析下沉 :spider;失败回退字符串通道
                        com.github.tvbox.osc.bean.AbsXml typed =
                                com.github.tvbox.osc.spiderapi.SpiderHomeProviders.get().category(
                                        homeSourceBean.getKey(), sortData.id, page + "", true, sortData.filterSelect);
                        if (typed != null && typed.movie != null) {
                            absXml(typed, homeSourceBean.getKey());
                            listResult.postValue(typed);
                            return;
                        }
                        android.util.Log.i("SpiderBridge", "category(typed) 不可用,回退字符串通道: key="
                                + homeSourceBean.getKey() + " tid=" + sortData.id + " pg=" + page);
                        json(listResult, com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                                        .categoryContent(homeSourceBean.getKey(), sortData.id, page + "", true, sortData.filterSelect),
                                homeSourceBean.getKey());
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            Map<String, String> listParams = new HashMap<>();
            listParams.put("ac", type == 0 ? "videolist" : "detail");
            listParams.put("t", sortData.id);
            listParams.put("pg", String.valueOf(page));
            if (sortData.filterSelect != null) {
                for (Map.Entry<String, String> entry : sortData.filterSelect.entrySet()) {
                    listParams.put(entry.getKey(), entry.getValue());
                }
            }
            listParams.put("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString());
            HttpClient.get(homeSourceBean.getApi(), listParams, null, homeSourceBean.getApi(), new HCallBack() {

                        @Override
                        public void onSuccess(String content) {
                            if (type == 0) {
                                String xml = content;
                                xml(listResult, xml, homeSourceBean.getKey());
                            } else {
                                String json = content;
                                json(listResult, json, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            listResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            String ext= "";
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    String selectExt = new JSONObject(sortData.filterSelect).toString();
                    ext = Base64.encodeToString(selectExt.getBytes("UTF-8"), Base64.DEFAULT |  Base64.NO_WRAP);
                    LOG.i(ext);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }else {
                ext = Base64.encodeToString("{}".getBytes(), Base64.DEFAULT |  Base64.NO_WRAP);
            }
            Map<String, String> listExtParams = new HashMap<>();
            listExtParams.put("ac", "detail");
            listExtParams.put("filter", "true");
            listExtParams.put("t", sortData.id);
            listExtParams.put("pg", String.valueOf(page));
            listExtParams.put("ext", ext);
            HttpClient.get(homeSourceBean.getApi(), listExtParams, null, homeSourceBean.getApi(), new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        LOG.i(json);
                        json(listResult, json, homeSourceBean.getKey());
                    }

                    @Override
                    public void onError(Throwable e) {
                        listResult.postValue(null);
                    }
                });
        } else {
            listResult.postValue(null);
        }
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }

    /**
     * 发布 type=3 首页结果(typed 命中或字符串回退共用):
     * homeRec=1 且有内嵌首页视频 → 富化后直接发布;无内嵌 → 走 homeVideoContent(typed)补推荐;
     * homeRec=0 → 仅发布分类。
     */
    private void publishHomeSort(final SourceBean sourceBean, AbsSortXml sortXml, List<Movie.Video> embedded) {
        if (sortXml == null) {
            sortResult.postValue(null);
            return;
        }
        if (SystemConfig.getHomeRec() == 1) {
            if (embedded != null && !embedded.isEmpty()) {
                // 同响应内嵌首页视频:与旧 json() 语义一致做富化(sourceKey 归属/urlBean 拆分)
                AbsXml wrap = new AbsXml();
                Movie movie = new Movie();
                movie.videoList = embedded;
                wrap.movie = movie;
                absXml(wrap, sourceBean.getKey());
                sortXml.videoList = embedded;
                sortResult.postValue(sortXml);
            } else {
                getHomeRecList(sourceBean, null, new HomeRecCallback() {
                    @Override
                    public void done(List<Movie.Video> videos) {
                        sortXml.videoList = videos;
                        sortResult.postValue(sortXml);
                    }
                });
            }
        } else {
            sortResult.postValue(sortXml);
        }
    }
//    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<com.github.tvbox.osc.bean.AbsXml> future = executor.submit(new Callable<com.github.tvbox.osc.bean.AbsXml>() {
                        @Override
                        public com.github.tvbox.osc.bean.AbsXml call() throws Exception {
                            // 强类型试点:解析下沉 :spider(带 15s 超时保护,与旧字符串链路一致)
                            return com.github.tvbox.osc.spiderapi.SpiderHomeProviders.get()
                                    .homeVideoContent(sourceBean.getKey());
                        }
                    });
                    com.github.tvbox.osc.bean.AbsXml result = null;
                    try {
                        result = future.get(15, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (result != null && result.movie != null && result.movie.videoList != null) {
                            absXml(result, sourceBean.getKey());
                            android.util.Log.d("SpiderBridge", "homeVideo(typed) 命中: key=" + sourceBean.getKey()
                                    + " size=" + result.movie.videoList.size());
                            callback.done(result.movie.videoList);
                        } else {
                            android.util.Log.w("SpiderBridge", "homeVideo(typed) 无结果,回退空列表: key=" + sourceBean.getKey());
                            callback.done(null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            Map<String, String> homeRecParams = new HashMap<>();
            homeRecParams.put("ac", sourceBean.getType() == 0 ? "videolist" : "detail");
            homeRecParams.put("ids", TextUtils.join(",", ids));
            HttpClient.get(sourceBean.getApi(), homeRecParams, null, "detail", new HCallBack() {

                        @Override
                        public void onSuccess(String content) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = content;
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = content;
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }
    // detailContent
    public void getDetail(String sourceKey, String id) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        if (sourceBean == null) {
            // 源不存在(订阅变更/失效等),通知空结果,避免崩溃
            detailResult.postValue(null);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 强类型试点:解析下沉 :spider(SpiderDetailImpl);失败回退字符串通道(行为不变)
                        com.github.tvbox.osc.bean.AbsXml typed =
                                com.github.tvbox.osc.spiderapi.SpiderDetailProviders.get().detail(sourceKey, id);
                        if (typed != null && typed.movie != null) {
                            absXml(typed, sourceBean.getKey());
                            checkThunder(typed, 0); // 内部按需 postValue(detailResult)
                            return;
                        }
                        android.util.Log.i("SpiderBridge", "detail(typed) 不可用,回退字符串通道: key=" + sourceKey + " id=" + id);
                        List<String> ids = new ArrayList<>();
                        ids.add(id);
                        json(detailResult, com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                                .detailContent(sourceBean.getKey(), ids), sourceBean.getKey());
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            Map<String, String> detailParams = new HashMap<>();
            detailParams.put("ac", type == 0 ? "videolist" : "detail");
            detailParams.put("ids", id);
            HttpClient.get(sourceBean.getApi(), detailParams, null, "detail", new HCallBack() {

                        @Override
                        public void onSuccess(String content) {
                            if (type == 0) {
                                String xml = content;
                                xml(detailResult, xml, sourceBean.getKey());
                            } else {
                                String json = content;
                                LOG.i(json);
                                json(detailResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            detailResult.postValue(null);
                        }
                    });
        } else {
            detailResult.postValue(null);
        }
    }
    // searchContent
    public void getSearch(String sourceKey, String wd) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                // 强类型试点:解析下沉 :spider;失败回退字符串通道(行为不变)
                com.github.tvbox.osc.bean.AbsXml typed =
                        com.github.tvbox.osc.spiderapi.SpiderSearchProviders.get().search(sourceBean.getKey(), wd, false);
                if (typed != null && typed.movie != null) {
                    absXml(typed, sourceBean.getKey());
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, typed));
                    return;
                }
                android.util.Log.i("SpiderBridge", "search(typed) 不可用,回退字符串通道: key=" + sourceBean.getKey()
                        + " word=" + wd);
                String search = com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                        .searchContent(sourceBean.getKey(), wd, false);
                if(!TextUtils.isEmpty(search)){
                    json(searchResult, search, sourceBean.getKey());
                } else {
                    json(searchResult, "", sourceBean.getKey());
                }
            } catch (Throwable th) {
                th.printStackTrace();
                json(searchResult, "", sourceBean.getKey());
            }
        } else if (type == 0 || type == 1) {
            Map<String, String> searchParams = new HashMap<>();
            searchParams.put("wd", wd);
            if (type == 1) {
                searchParams.put("ac", "detail");
            }
            HttpClient.get(sourceBean.getApi(), searchParams, null, "search", new HCallBack() {
                        @Override
                        public void onSuccess(String content) {
                            if (type == 0) {
                                String xml = content;
                                xml(searchResult, xml, sourceBean.getKey());
                            } else {
                                String json = content;
                                json(searchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        }else if (type == 4) {
            Map<String, String> search4Params = new HashMap<>();
            search4Params.put("wd", wd);
            search4Params.put("ac", "detail");
            search4Params.put("quick", "false");
            HttpClient.get(sourceBean.getApi(), search4Params, null, "search", new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        LOG.i(json);
                        json(searchResult, json, sourceBean.getKey());
                    }

                    @Override
                    public void onError(Throwable e) {
                        // searchResult.postValue(null);
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                    }
                });
        } else {
            searchResult.postValue(null);
        }
    }
    // searchContent
    public void getQuickSearch(String sourceKey, String wd) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                // 强类型试点:quick 搜索解析下沉 :spider;失败回退字符串通道
                com.github.tvbox.osc.bean.AbsXml typed =
                        com.github.tvbox.osc.spiderapi.SpiderSearchProviders.get().search(sourceBean.getKey(), wd, true);
                if (typed != null && typed.movie != null) {
                    absXml(typed, sourceBean.getKey());
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, typed));
                    return;
                }
                android.util.Log.i("SpiderBridge", "quickSearch(typed) 不可用,回退字符串通道: key=" + sourceBean.getKey()
                        + " word=" + wd);
                json(quickSearchResult, com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                        .searchContent(sourceBean.getKey(), wd, true), sourceBean.getKey());
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (type == 0 || type == 1) {
            Map<String, String> quickParams = new HashMap<>();
            quickParams.put("wd", wd);
            if (type == 1) {
                quickParams.put("ac", "detail");
            }
            HttpClient.get(sourceBean.getApi(), quickParams, null, "quick_search", new HCallBack() {
                        @Override
                        public void onSuccess(String content) {
                            if (type == 0) {
                                String xml = content;
                                xml(quickSearchResult, xml, sourceBean.getKey());
                            } else {
                                String json = content;
                                json(quickSearchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            // quickSearchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
                        }
                    });
        }else if (type == 4) {
            Map<String, String> quick4Params = new HashMap<>();
            quick4Params.put("wd", wd);
            quick4Params.put("ac", "detail");
            quick4Params.put("quick", "true");
            HttpClient.get(sourceBean.getApi(), quick4Params, null, "search", new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        LOG.i(json);
                        json(quickSearchResult, json, sourceBean.getKey());
                    }

                    @Override
                    public void onError(Throwable e) {
                        // searchResult.postValue(null);
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                    }
                });
        } else {
            quickSearchResult.postValue(null);
        }
    }
    // playerContent
    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String json = com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                                .playerContent(sourceBean.getKey(), playFlag, url, sourceConfig.getVipParseFlags());
                        JSONObject result = new JSONObject(json);
                        result.put("key", url);
                        result.put("proKey", progressKey);
                        result.put("subtKey", subtitleKey);
                        if (!result.has("flag"))
                            result.put("flag", playFlag);
                        playResult.postValue(result);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        playResult.postValue(null);
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(url) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", url);
                } else {
                    result.put("parse", 1);
                    result.put("url", url);
                }
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                playResult.postValue(result);
            } catch (Throwable th) {
                th.printStackTrace();
                playResult.postValue(null);
            }
        } else if (type == 4) {
            Map<String, String> playParams = new HashMap<>();
            playParams.put("play", url);
            playParams.put("flag", playFlag);
            HttpClient.get(sourceBean.getApi(), playParams, null, "play", new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        LOG.i(json);
                        try {
                            JSONObject result = new JSONObject(json);
                            result.put("key", url);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            playResult.postValue(result);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            playResult.postValue(null);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        playResult.postValue(null);
                    }
                });
        }else {
            playResult.postValue(null);
        }
    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_key=ele_obj.has("n")?ele_obj.get("n").getAsString():"";
            String values_value=ele_obj.has("v")?ele_obj.get("v").getAsString():"";
            values.put(values_key, values_value);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        // 解析已抽到 SortParser(纯静态、可单测);result 由调用方发布
        return com.github.tvbox.osc.spiderapi.SortParser.parseSortJson(json);
    }

    /**
     * XStream 反序列化安全白名单:关闭默认"任意类型许可",仅放行业务 bean 包(及其嵌套类)
     * 与基础 JDK 类型。订阅/详情 XML 来自第三方数据源,若无白名单,恶意 XML 可让 XStream
     * 实例化任意类触发 gadget 链(潜在 RCE)。调用时机:processAnnotations 之后、fromXML 之前。
     */
    private static void lockDownXStream(XStream xstream, Class<?> rootType) {
        try {
            xstream.addPermission(NoTypePermission.NONE);
            if (rootType != null) {
                xstream.allowTypeHierarchy(rootType);
            }
            xstream.allowTypesByWildcard(new String[]{
                    "com.github.tvbox.osc.bean.**",   // 业务模型(含嵌套类)
                    "java.lang.**",
                    "java.util.**",
                    "java.time.**",
                    "java.math.**",
                    "java.net.**"
            });
        } catch (Throwable th) {
            LOG.e("XStream whitelist apply failed: " + th.getMessage());
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        // 解析已抽到 SortParser(纯静态、可单测)
        return com.github.tvbox.osc.spiderapi.SortParser.parseSortXml(xml);
    }

    private void absXml(AbsXml data, String sourceKey) {
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
//                        for (String s : str) {
//                            if (s.contains("$")) {
//                                String[] ss = s.split("\\$");
//                                if (ss.length >= 2) {
//                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
//                                }
//                                //infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(s.substring(0, s.indexOf("$")), s.substring(s.indexOf("$") + 1)));
//                            }
//                        }
                        for (String s : str) {
                            String[] ss = s.split("\\$");
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
                boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$");

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }


    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            // XStream 反序列化安全白名单(见 lockDownXStream)
            lockDownXStream(xstream, AbsXml.class);
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    checkThunder(data,0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        try {
            // 测试数据
            /*json = "{\n" +
                    "\t\"list\": [{\n" +
                    "\t\t\"vod_id\": \"137133\",\n" +
                    "\t\t\"vod_name\": \"磁力测试\",\n" +
                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
                    "\t\t\"vod_year\": \"2022\",\n" +
                    "\t\t\"vod_area\": \"中国大陆\",\n" +
                    "\t\t\"vod_remarks\": \"40集全\",\n" +
                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
                    "\t\t\"vod_director\": \"杨阳\",\n" +
                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=%e9%98%b3%e5%85%89%e7%94%b5%e5%bd%b1www.ygdy8.com.%e7%83%ad%e8%a1%80.2022.BD.1080P.%e9%9f%a9%e8%af%ad%e4%b8%ad%e8%8b%b1%e5%8f%8c%e5%ad%97.mkv&tr=udp%3a%2f%2ftracker.opentrackr.org%3a1337%2fannounce&tr=udp%3a%2f%2fexodus.desync.com%3a6969%2fannounce\"\n" +
                    "\t}]\n" +
                    "}";*/
            AbsJson absJson = new Gson().fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    checkThunder(data,0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}