package com.github.tvbox.osc.ui.fragment;
import com.github.tvbox.osc.util.AppBubble;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.BounceInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.LogUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.ui.widget.ListSwipeRefreshLayout;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.StackBlurBlur;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import eightbitlab.com.blurview.BlurView;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import java.util.Stack;
import android.view.ViewGroup;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private RecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    /** 筛选按钮毛玻璃是否已 setup(initView 会被多次调用, 只装一次) */
    private boolean filterBlurSetup = false;
    private boolean isTop = true;
    private View focusedView = null;
    /** 下拉刷新容器(列表页根布局) */
    private ListSwipeRefreshLayout mSwipeRefresh = null;
    /** 下拉刷新监听只绑定一次(initView 会被多次调用) */
    private boolean swipeRefreshBound = false;
    private class GridInfo{
        public String sortID="";
        public RecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public View focusedView= null;
    }
    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && this.sortData == null) {
            //activity销毁再进入,会直接恢复fragment,从而直接getList,导致sortData为空闪退
            this.sortData = GsonUtils.fromJson(savedInstanceState.getString("sortDataJson"), MovieSort.SortData.class);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sortDataJson", GsonUtils.toJson(sortData));
    }

    private void changeView(String id, Boolean isFolder){
        if(isFolder){
            this.sortData.flag ="1"; // 修改sortData.flag
        }else {
            this.sortData.flag =null; // 修改sortData.flag
        }
        initView();
        this.sortData.id =id; // 修改sortData.id为新的ID
        initViewModel();
        initData();
    }
    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag(){
        System.out.println(sortData);
        return (sortData == null || sortData.flag == null || sortData.flag.length() ==0 ) ?  '0' : sortData.flag.charAt(0);
    }
    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch(){  return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }
    // 保存当前页面
    private void saveCurrentView(){
        if(this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }
    // 丢弃当前页面，将页面还原成上一个保存的页面
    public boolean restoreView(){
        if(mGrids.empty()) return false;
        this.showSuccess();
        ((ViewGroup) mGridView.getParent()).removeView(this.mGridView); // 重父窗口移除当前控件
        GridInfo info = mGrids.pop();// 还原上次保存的控件
        this.sortData.id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
//        if(this.focusedView != null){ this.focusedView.requestFocus(); }
        if(mGridView != null) mGridView.requestFocus();
        return true;
    }
    // 更改当前页面
    private void createView(){
        this.saveCurrentView(); // 保存当前页面
        if(mGridView == null){ // 从layout中拿view
            mGridView = findViewById(R.id.mGridView);
        }else{ // 复制当前view
            TvRecyclerView v3 = new TvRecyclerView(this.mContext);
            v3.setSpacingWithMargins(10,10);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            ((ViewGroup) mGridView.getParent()).addView(v3);
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        gridAdapter = new GridAdapter();
        this.page =1;
        this.maxPage =1;
        this.isLoad = false;
    }

    private void initView() {
        this.createView();
        mGridView.setAdapter(gridAdapter);
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)));

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                gridAdapter.setEnableLoadMore(true);
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    bundle.putString("vodName", video.name);
                    SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
                    if(("12".indexOf(getUITag()) != -1) && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        changeView(video.id,video.tag.equals("folder"));
                    }
                    else if(homeSourceBean.isQuickSearch() && Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) && enableFastSearch()){
                        jumpActivity(FastSearchActivity.class, bundle);
                    }else{
                        if(TextUtils.isEmpty(video.id) || video.id.startsWith("msearch:")){
                            jumpActivity(FastSearchActivity.class, bundle);
//                            jumpActivity(SearchActivity.class, bundle);
                        }else {
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }

                }
            }
        });
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());

        findViewById(R.id.btn_filter).setOnClickListener(view -> showFilter());
        setupFilterBlur();
        setupSwipeRefresh();
        setLoadSir2(mGridView);
    }

    /**
     * 首页下拉刷新:从第 1 页重新拉取当前分类(不清空旧列表,数据到达后整体替换,避免刷新瞬间白屏);
     * 返回时调用方负责结束刷新动画
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

    private void onPullRefresh() {
        if (sourceViewModel == null) {
            finishSwipeRefresh();
            return;
        }
        page = 1;
        maxPage = 1;
        isLoad = false;
        // 复位 footer: 重新开启加载更多并清除旧的"到底了"状态, 下一页请求期间显示"加载中"
        gridAdapter.loadMoreComplete();
        gridAdapter.setEnableLoadMore(true);
        sourceViewModel.getList(sortData, page);
    }

    /** 刷新完成(数据到达/异常后调用;非下拉刷新期间调用为无操作) */
    private void finishSwipeRefresh() {
        if (mSwipeRefresh != null && mSwipeRefresh.isRefreshing()) {
            mSwipeRefresh.setRefreshing(false);
        }
    }

    /**
     * 筛选悬浮按钮毛玻璃:模糊其后方(列表)内容, 与底栏同一套 StackBlur 算法; 只初始化一次
     */
    private void setupFilterBlur() {
        if (filterBlurSetup) return;
        filterBlurSetup = true;
        try {
            BlurView blur = findViewById(R.id.blur_filter);
            ViewGroup root = mActivity.getWindow().getDecorView()
                    .findViewById(android.R.id.content);
            blur.setupWith(root)
                    .setFrameClearDrawable(mActivity.getWindow().getDecorView().getBackground())
                    .setBlurAlgorithm(new StackBlurBlur())
                    .setBlurRadius(14f)
                    .setBlurAutoUpdate(true);
        } catch (Throwable th) {
            // 模糊失败降级:按钮保留纯色遮罩,不影响功能
            View blur = findViewById(R.id.blur_filter);
            if (blur != null) blur.setVisibility(View.GONE);
        }
    }

    private void initViewModel() {
        if(sourceViewModel != null) { return;}
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
//                if(mGridView != null) mGridView.requestFocus();
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        gridAdapter.setNewData(absXml.movie.videoList);
                        // 复位 footer 状态, 避免上一次"到底了"残留(下一页请求期间应显示"加载中")
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    } else {
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;

                    if (page > maxPage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                        if(page>2)AppBubble.toast("没有更多了");
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if(page == 1){
                        showEmpty();
                    }else{
                        AppBubble.toast("没有更多了");
                        gridAdapter.loadMoreEnd();
                    }
                    gridAdapter.setEnableLoadMore(false);
                }
                finishSwipeRefresh();
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    private void initData() {
        if (ApiConfig.get().getHomeSourceBean().getApi()==null){// 系统杀死app恢复缓存的fragment后会直接getList,此时首页api都未加载完
            showEmpty();
            return;
        }
        showLoading();
        isLoad = false;
        scrollTop();
        sourceViewModel.getList(sortData, page);
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        mGridView.scrollToPosition(0);
    }

    public void showFilter() {
        if (sortData!=null && !sortData.filters.isEmpty() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
            gridFilterDialog.setData(sortData);
            gridFilterDialog.setOnDismiss(new GridFilterDialog.Callback() {
                @Override
                public void change() {
                    page = 1;
                    initData();
                }
            });
        }
        if (gridFilterDialog != null)
            gridFilterDialog.show();
    }

    /**
     * 屏幕旋转 / 窗口尺寸变化(大屏横竖屏切换)时,按新宽度重算网格列数,
     * 配合卡片布局的固定宽高比,让整页自动刷新,无需重启页面。
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int span = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP);
        updateGridSpan(mGridView, span);
        for (GridInfo info : mGrids) {
            updateGridSpan(info.mGridView, span);
        }
    }

    private void updateGridSpan(RecyclerView recyclerView, int span) {
        if (recyclerView != null && recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) recyclerView.getLayoutManager()).setSpanCount(span);
        }
    }
}