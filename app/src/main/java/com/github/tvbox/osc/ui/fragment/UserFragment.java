package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.StackBlurBlur;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.LiveActivity;

import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.widget.ListSwipeRefreshLayout;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HomeHotCache;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.Utils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import eightbitlab.com.blurview.BlurView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment {

    private GridAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    RecyclerView tvHotList1;
    /** 下拉刷新容器(首页列表根布局),绑定一次 */
    private ListSwipeRefreshLayout mSwipeRefresh = null;
    private boolean swipeRefreshBound = false;
    /** 列表末尾"到底了"提示(共享组件 item_view_end_tip):默认隐藏,仅列表可滚动(超过一屏)时显示 */
    private View mEndTip = null;

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();

        tvHotList1.setHasFixedSize(true);
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        final int span = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP);
        tvHotList1.setLayoutManager(new GridLayoutManager(this.mContext, span));
    }

    /**
     * 屏幕旋转 / 窗口尺寸变化(大屏横竖屏切换)时,按新宽度重算列数并刷新
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (tvHotList1 != null && tvHotList1.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) tvHotList1.getLayoutManager()).setSpanCount(Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP));
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        tvHotList1 = findViewById(R.id.tvHotList1);
        // 主页右下角直播悬浮按钮
        findViewById(R.id.btn_live).setOnClickListener(view -> jumpActivity(LiveActivity.class));
        setupLiveBlur();
        homeHotVodAdapter = new GridAdapter();
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSourceBeanList().isEmpty()){
                    AppBubble.toast("暂无订阅");
                    return;
                }
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                Bundle bundle = new Bundle();
                if (!TextUtils.isEmpty(vod.id)) {
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    bundle.putString("vodName", vod.name);
                    jumpActivity(DetailActivity.class, bundle);
                } else {
                    bundle.putString("title", vod.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
            }
        });

        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSourceBeanList().isEmpty()) return true;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                Bundle bundle = new Bundle();
                bundle.putString("title", vod.name);
                jumpActivity(FastSearchActivity.class, bundle);
                return true;
            }
        });

        tvHotList1.setAdapter(homeHotVodAdapter);
        // 底部悬浮"到底了"(共享组件 item_view_end_tip,布局中已默认隐藏):
        // 滚到列表底部时才出现,贴底部导航栏;滚动联动显隐
        mEndTip = findViewById(R.id.end_tip);
        tvHotList1.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                refreshEndTip();
            }
        });
        setLoadSir2(tvHotList1);
        setupSwipeRefresh();
        initHomeHotVod(homeHotVodAdapter);
    }

    /**
     * 下拉刷新容器绑定:监听只设一次(init 只会调用一次);颜色用主题高亮色
     */
    private void setupSwipeRefresh() {
        if (swipeRefreshBound) return;
        swipeRefreshBound = true;
        mSwipeRefresh = findViewById(R.id.swipe_refresh);
        if (mSwipeRefresh == null) return;
        // 刷新指示器跟随主题: 暗色用组件底色+亮色圈, 亮色用白底+深色圈
        mSwipeRefresh.setProgressBackgroundColorSchemeResource(
                Utils.isAppDarkTheme() ? R.color.bg_component : R.color.white);
        mSwipeRefresh.setColorSchemeResources(R.color.text_highlight);
        mSwipeRefresh.setOnRefreshListener(() -> onPullRefresh());
    }

    /**
     * 下拉刷新首页:站点推荐直接重设列表;豆瓣热门清掉当日缓存强制重新拉取(网络请求完成后收起动画)
     */
    private void onPullRefresh() {
        if (SystemConfig.getHomeRec() == 1) {
            if (homeSourceRec != null && homeSourceRec.size() > 0) {
                homeHotVodAdapter.setNewData(homeSourceRec);
                showSuccess();
                updateEndTip();
            } else {
                showEmpty();
            }
            finishSwipeRefresh();
            return;
        }
        try {
            HomeHotCache.clear();
        } catch (Throwable ignored) {
        }
        initHomeHotVod(homeHotVodAdapter);
    }

    /** 刷新完成(各加载路径收尾调用;非下拉刷新期间调用为无操作) */
    private void finishSwipeRefresh() {
        if (mSwipeRefresh != null && mSwipeRefresh.isRefreshing()) {
            mSwipeRefresh.setRefreshing(false);
        }
    }

    /**
     * 同步刷新底部悬浮"到底了"(滚动监听中调用):
     * 数据非空、列表确实滚到最底部(不能再向下滚)且曾有多屏内容(能向上滚回)才显示;
     * 一屏即可看完或空数据不显示,避免"到底了"常驻造成假噪音。
     */
    private void refreshEndTip() {
        if (mEndTip == null || tvHotList1 == null) return;
        boolean hasData = !homeHotVodAdapter.getData().isEmpty();
        boolean atBottom = !tvHotList1.canScrollVertically(1);
        boolean scrolledUp = tvHotList1.canScrollVertically(-1);
        mEndTip.setVisibility(hasData && atBottom && scrolledUp ? View.VISIBLE : View.GONE);
    }

    /**
     * 数据/滚动变化后调度刷新:列表可能尚未完成布局(如刚 setNewData),
     * post 到下一帧再判,保证 canScrollVertically 反映真实内容高度。
     */
    private void updateEndTip() {
        if (tvHotList1 == null) return;
        tvHotList1.post(this::refreshEndTip);
    }

    /**
     * 直播悬浮按钮毛玻璃:模糊其后方(列表)内容, 与底栏同一套 StackBlur 算法
     */
    private void setupLiveBlur() {
        try {
            BlurView blur = findViewById(R.id.blur_live);
            ViewGroup root = mActivity.getWindow().getDecorView()
                    .findViewById(android.R.id.content);
            blur.setupWith(root)
                    .setFrameClearDrawable(mActivity.getWindow().getDecorView().getBackground())
                    .setBlurAlgorithm(new StackBlurBlur())
                    .setBlurRadius(14f)
                    .setBlurAutoUpdate(true);
        } catch (Throwable th) {
            // 模糊失败降级:按钮保留纯色遮罩,不影响功能
            View blur = findViewById(R.id.blur_live);
            if (blur != null) blur.setVisibility(View.GONE);
        }
    }

    private void initHomeHotVod(GridAdapter adapter) {
        if (SystemConfig.getHomeRec() == 1) {
            if (homeSourceRec != null && homeSourceRec.size() > 0) {
                showSuccess();
                adapter.setNewData(homeSourceRec);
                updateEndTip();
            }else {
                showEmpty();
            }
            finishSwipeRefresh();
            return;
        }
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = HomeHotCache.getDay();
            if (requestDay.equals(today)) {
                String json = HomeHotCache.getData();
                if (!json.isEmpty()) {
                    ArrayList<Movie.Video> hotMovies = loadHots(json);
                    if (hotMovies != null && hotMovies.size() > 0) {
                        showSuccess();
                        adapter.setNewData(hotMovies);
                        updateEndTip();
                        finishSwipeRefresh();
                        return;
                    }
                }
            }
            // 首次加载给出状态;下拉刷新时已有旧数据则不整页盖住
            if (adapter.getData().isEmpty()) {
                showLoading();
            }
            String doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA.randomOne());
            HttpClient.get(doubanUrl, headers, null, new HCallBack() {
                @Override
                public void onSuccess(String netJson) {
                    HomeHotCache.save(today, netJson);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ArrayList<Movie.Video> videos = loadHots(netJson);
                            if (videos.size()>0){
                                showSuccess();
                                adapter.setNewData(videos);
                                updateEndTip();
                            }else {
                                showEmpty();
                            }
                            finishSwipeRefresh();
                        }
                    });
                }

                @Override
                public void onError(Throwable e) {
                    // 保持原行为(旧列表不变); 首载失败也要有状态,避免一直停在 loading
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (adapter.getData().isEmpty()) {
                                showEmpty();
                            }
                            finishSwipeRefresh();
                        }
                    });
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
            if (adapter.getData().isEmpty()){
                showEmpty();
            }
            finishSwipeRefresh();
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            for (JsonElement ele : array) {
                JsonObject obj = (JsonObject) ele;
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                if (!vod.note.isEmpty()) vod.note += " 分";
                vod.pic = obj.get("cover").getAsString() + "@Referer=https://movie.douban.com/@User-Agent=" + UA.random();
                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }
}