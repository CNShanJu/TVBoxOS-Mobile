package com.github.tvbox.osc.util;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import org.greenrobot.eventbus.EventBus;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * 详情页"快速搜索"的请求编排与结果聚合(自详情页 DetailActivity 抽取)。
 * <p>
 * 职责:
 * 1. 编排各源快速搜索请求:分词词表维护、源筛选、请求发起;
 * 2. 结果聚合:去重当前正在查看的影片后,累计到 quickSearchData 并广播给快速搜索弹窗;
 * 3. 线程池治理:不再每次 newFixedThreadPool(5),统一提交到应用级共享线程池
 *    {@link HeavyTaskUtil#getBigTaskExecutorService()};
 * 4. 取消/去重:每轮搜索持有自增 token(epoch),真正发起前校验 token 是否仍是最新,
 *    过期的排队任务直接丢弃;弹窗关闭时暂停(未启动的任务收进 pending 队列,弹窗再开时续跑)。
 * <p>
 * 该类为纯逻辑组件,不持有任何 View/Activity 引用;UI(弹窗展示/词表回填)仍由
 * DetailActivity 通过 EventBus 与 getter 完成,与抽取前行为等价。
 */
public class DetailQuickSearchHelper {

    /** 当前详情影片过滤用(结果聚合时去掉正在查看的影片),由宿主在结果到达时传入 */
    public void setCurrentVod(String sourceKey, String vodId) {
        this.currentSourceKey = sourceKey;
        this.currentVodId = vodId;
    }

    /** 待发起搜索的源键(未开始即被暂停时暂存,弹窗再开续跑) */
    private static final class PendingSearch {
        final String key;
        final String title;
        final long epoch;

        PendingSearch(String key, String title, long epoch) {
            this.key = key;
            this.title = title;
            this.epoch = epoch;
        }
    }

    private final SourceViewModel sourceViewModel;

    private String currentSourceKey;
    private String currentVodId;
    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private HashMap<String, String> mCheckSources = null;

    /** 搜索并发控制:epoch 为新一轮搜索 token;暂停标记 + pending 队列复刻"弹窗关闭即暂停"语义 */
    private final Object lock = new Object();
    private long searchEpoch = 0;
    private boolean searchPaused = false;
    private final List<PendingSearch> pendingSearch = new ArrayList<>();

    public DetailQuickSearchHelper(SourceViewModel sourceViewModel) {
        this.sourceViewModel = sourceViewModel;
    }

    /** 供宿主在弹窗展示时广播的当前累计结果(与抽取前共用同一列表对象,行为一致) */
    public List<Movie.Video> getQuickSearchData() {
        return quickSearchData;
    }

    /** 供宿主在弹窗展示时广播的词表(与抽取前共用同一列表对象,行为一致) */
    public List<String> getQuickSearchWords() {
        return quickSearchWord;
    }

    /** 初始化"参与快速搜索"的源勾选集合(原 DetailActivity.initCheckedSourcesForSearch) */
    public void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    /**
     * 发起快速搜索(原 startQuickSearch):仅首次真正初始化;再点只是把已累计结果/词表
     * 交给弹窗(由宿主负责广播)。
     *
     * @param vodName 当前详情影片名(搜索词来源;抽取前读取 mVideo.name)
     */
    public void startQuickSearch(String vodName) {
        initCheckedSourcesForSearch();
        if (hadQuickStart) {
            return;
        }
        hadQuickStart = true;
        HttpClient.cancel("quick_search");
        quickSearchWord.clear();
        searchTitle = vodName == null ? "" : vodName;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        // 分词
        HttpClient.get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1", "fenci", new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        try {
                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
                    }

                    @Override
                    public void onError(Throwable e) {
                    }
                });

        searchResult();
    }

    /** 切换搜索词(原 DetailActivity.switchSearchWord):清空旧结果并按新词重新编排 */
    public void switchSearchWord(String word) {
        HttpClient.cancel("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    /**
     * 编排新一轮搜索(原 DetailActivity.searchResult):
     * 旧实现每轮 newFixedThreadPool(5) 并 shutdownNow 上一轮;现提交到应用级共享线程池,
     * 通过自增 epoch 让上一轮尚未启动的任务自检后丢弃(等价"发起新搜索前取消旧的"),
     * 正在执行的源请求仍可能带回结果(与旧实现相同,HttpClient.cancel 已做尽力取消)。
     */
    private void searchResult() {
        final long epoch;
        synchronized (lock) {
            epoch = ++searchEpoch;
            // 切换词/重开搜索时,丢弃上一轮被暂停、尚未启动的源任务
            pendingSearch.clear();
        }
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            launchSearch(key, epoch);
        }
    }

    /** 提交单个源搜索任务到共享线程池;真正发起前校验 token/暂停状态 */
    private void launchSearch(final String key, final long epoch) {
        Runnable task = () -> {
            final String title;
            synchronized (lock) {
                // token 不再是本轮最新:任务已过期(词已切换/新一轮已发起),直接丢弃(去重/取消)
                if (epoch != searchEpoch) {
                    return;
                }
                // 弹窗已关闭:任务暂存,等弹窗再开时续跑(复刻原 shutdownNow 收集 pauseRunnable)
                if (searchPaused) {
                    pendingSearch.add(new PendingSearch(key, searchTitle, epoch));
                    return;
                }
                title = searchTitle;
            }
            sourceViewModel.getQuickSearch(key, title);
        };
        HeavyTaskUtil.getBigTaskExecutorService().execute(task);
    }

    /** 弹窗再开:放行被暂停的任务(原 pauseRunnable 续跑逻辑) */
    public void onQuickSearchDialogOpened() {
        List<PendingSearch> toResume;
        synchronized (lock) {
            searchPaused = false;
            toResume = new ArrayList<>(pendingSearch);
            pendingSearch.clear();
        }
        for (PendingSearch p : toResume) {
            launchSearch(p.key, p.epoch);
        }
    }

    /** 弹窗关闭:标记暂停,后续排队的源任务进入 pending,不再发起(原 dialog onDismiss shutdownNow 语义) */
    public void onQuickSearchDialogClosed() {
        synchronized (lock) {
            searchPaused = true;
        }
    }

    /**
     * 结果聚合(原 DetailActivity.searchData):去除正在查看的影片后累计并广播。
     * 与旧实现一致:AbsXml 为空或列表为空时静默返回。
     */
    public void handleQuickSearchResult(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (currentSourceKey != null && currentSourceKey.equals(video.sourceKey)
                        && currentVodId != null && currentVodId.equals(video.id)) {
                    continue;
                }
                data.add(video);
            }
            if (data.isEmpty()) {
                return;
            }
            quickSearchData.addAll(data);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    /** 宿主销毁时调用:作废未启动任务、清空暂停队列(共享线程池本身不可关闭) */
    public void release() {
        synchronized (lock) {
            searchEpoch++;
            searchPaused = false;
            pendingSearch.clear();
        }
    }
}
