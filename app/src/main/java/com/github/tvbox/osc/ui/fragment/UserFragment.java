package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.StackBlurBlur;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.ServerEvent;
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
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.SystemConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.Utils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import eightbitlab.com.blurview.BlurView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

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
        GridLayoutManager glm = new GridLayoutManager(this.mContext, span);
        // 末尾 footer("到底了")占满整行,文字才真正屏幕居中:
        // init() 里 setAdapter/addFooterView 时 layoutManager 尚未设置,
        // BRVAH 的 spanSizeLookup 未挂上,footer 默认只占 1 列 → 这里手动补
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position == homeHotVodAdapter.getItemCount() - 1 ? span : 1;
            }
        });
        tvHotList1.setLayoutManager(glm);
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
                if (ApiConfig.get().getSourceBeanList().isEmpty()){
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
                if (ApiConfig.get().getSourceBeanList().isEmpty()) return true;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                Bundle bundle = new Bundle();
                bundle.putString("title", vod.name);
                jumpActivity(FastSearchActivity.class, bundle);
                return true;
            }
        });

        tvHotList1.setAdapter(homeHotVodAdapter);
        addEndFooter();
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
            } else {
                showEmpty();
            }
            finishSwipeRefresh();
            return;
        }
        try {
            Hawk.delete("home_hot_day");
            Hawk.delete("home_hot");
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
     * 列表末尾"到底了"提示:滚动到最底可见,空态由 LoadSir 覆盖层接管不受影响
     */
    private void addEndFooter() {
        TextView footer = new TextView(mContext);
        footer.setText("—— 到底了 ——");
        footer.setTextColor(getResources().getColor(R.color.text_sub_foreground));
        footer.setAlpha(0.55f); // 弱化亮度过高,提升透明感
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        // 占满整行使文字水平居中;上下留 4dp,贴近底部(列表 paddingBottom=12dp)
        footer.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));
        int pad = ConvertUtils.dp2px(4f);
        footer.setPadding(pad, pad, pad, pad);
        homeHotVodAdapter.addFooterView(footer);
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
            String requestDay = Hawk.get("home_hot_day", "");
            if (requestDay.equals(today)) {
                String json = Hawk.get("home_hot", "");
                if (!json.isEmpty()) {
                    ArrayList<Movie.Video> hotMovies = loadHots(json);
                    if (hotMovies != null && hotMovies.size() > 0) {
                        showSuccess();
                        adapter.setNewData(hotMovies);
                        finishSwipeRefresh();
                        return;
                    }
                }
            }
            String doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA.randomOne());
            HttpClient.get(doubanUrl, headers, null, new HCallBack() {
                @Override
                public void onSuccess(String netJson) {
                    Hawk.put("home_hot_day", today);
                    Hawk.put("home_hot", netJson);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ArrayList<Movie.Video> videos = loadHots(netJson);
                            if (videos.size()>0){
                                showSuccess();
                                adapter.setNewData(videos);
                            }else {
                                showEmpty();
                            }
                            finishSwipeRefresh();
                        }
                    });
                }

                @Override
                public void onError(Throwable e) {
                    // 保持原行为(旧列表不变); 下拉刷新要收尾
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
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