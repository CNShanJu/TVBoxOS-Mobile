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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.LogUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
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
import com.github.tvbox.osc.util.StackBlurBlur;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import eightbitlab.com.blurview.BlurView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import java.util.List;
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
    /** 底部悬浮"到底了"(布局中默认隐藏,滚到列表最底且确认无更多时显示,贴近底部导航栏) */
    private View mEndTip = null;
    /** 是否已确认"没有更多"(loadmore 判 end 后置真;恢复快照/刷新时按层复位) */
    private boolean mEndReached = false;
    /** 筛选按钮毛玻璃是否已 setup(initView 会被多次调用, 只装一次) */
    private boolean filterBlurSetup = false;
    private boolean isTop = true;
    private View focusedView = null;
    /** 下拉刷新容器(列表页根布局) */
    private ListSwipeRefreshLayout mSwipeRefresh = null;
    /** 下拉刷新监听只绑定一次(initView 会被多次调用) */
    private boolean swipeRefreshBound = false;
    /** 层级快照:每深入一层只把上一层的轻量状态(数据引用/分页/滚动)入栈;
     *  全 fragment 只保留一套 RecyclerView + GridAdapter,不再逐层新建/隐藏视图(避免深目录内存累积) */
    private static class GridInfo{
        public String sortID="";
        public List<Movie.Video> data;    // 该层已加载数据(引用快照;该层不活动时不会被改动)
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public boolean loadMoreEnd = false; // 该层是否已到“没有更多”(返回时还原 footer 状态)
        public int scrollPos = 0;           // 离开该层时列表首个可见条目位置(返回时还原滚动)
        public int scrollOffset = 0;        // 该条目顶部偏移
    }
    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //导航快照栈(只存轻量状态,不再持有每层各自的 RecyclerView)

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
        initView();            // 幂等:确保唯一网格视图/适配器已建立
        saveCurrentView();     // 进入更深层前:把当前层(数据/分页/滚动)压入快照栈
        switchToNewLevel();    // 复用同一视图/适配器,清空旧层数据并复位分页
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
    // 保存当前层级快照(进入更深层前调用):只记录数据引用/分页/滚动等轻量状态
    private void saveCurrentView(){
        if(this.mGridView == null || gridAdapter == null || sortData == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.data = gridAdapter.getData(); // 引用当前层数据列表(该层不活动时不会被改动)
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.loadMoreEnd = this.page > this.maxPage; // 与加载回调中 page>maxPage -> loadMoreEnd 的判定一致
        // 记录离开前的滚动位置,返回时在同一视图上还原,避免回退后列表跳回顶部
        RecyclerView.LayoutManager lm = mGridView.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            GridLayoutManager glm = (GridLayoutManager) lm;
            int first = glm.findFirstVisibleItemPosition();
            if (first != RecyclerView.NO_POSITION) {
                View v = glm.findViewByPosition(first);
                info.scrollPos = first;
                if (v != null) {
                    info.scrollOffset = Math.max(0, glm.getDecoratedTop(v) - mGridView.getPaddingTop());
                }
            }
        }
        this.mGrids.push(info);
    }
    // 返回上一层:弹出快照,把旧层数据重新挂到唯一适配器上(数据还在,秒开且不重新请求网络)
    public boolean restoreView(){
        if(mGrids.empty()) return false;
        GridInfo info = mGrids.pop();
        this.sortData.id = info.sortID;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        if(mGridView != null && gridAdapter != null){
            this.showSuccess(); // 收起加载/空态占位
            gridAdapter.setNewData(info.data); // 恢复该层数据(BRVH 会自动复位加载更多开关)
            if (info.loadMoreEnd) {
                // 该层之前已"没有更多":还原状态(不再渲染 BRVAH end 行,由底部悬浮提示承担)
                mEndReached = true;
                gridAdapter.loadMoreComplete();
                gridAdapter.setEnableLoadMore(false);
            } else {
                mEndReached = false;
            }
            restoreScroll(info.scrollPos, info.scrollOffset);
            mGridView.requestFocus();
            updateEndTip();
        }
        return true;
    }
    // 换数据后 RecyclerView 会重置滚动,布局就绪后再滚回原位置(两次调用幂等,保证生效)
    // 注:scrollToPositionWithOffset 属于 LinearLayoutManager/GridLayoutManager,RecyclerView 本身没有
    private void restoreScroll(int pos, int offset){
        if(mGridView == null || mGridView.getLayoutManager() == null || pos <= 0) return;
        LinearLayoutManager lm = mGridView.getLayoutManager() instanceof LinearLayoutManager
                ? (LinearLayoutManager) mGridView.getLayoutManager() : null;
        if (lm == null) return;
        lm.scrollToPositionWithOffset(pos, offset);
        mGridView.post(() -> {
            if(mGridView != null && mGridView.getLayoutManager() instanceof LinearLayoutManager){
                int itemCount = mGridView.getAdapter() == null ? 0 : mGridView.getAdapter().getItemCount();
                int target = itemCount <= 0 ? 0 : Math.min(pos, itemCount - 1);
                ((LinearLayoutManager) mGridView.getLayoutManager()).scrollToPositionWithOffset(target, offset);
            }
        });
    }
    // 切换到新层级:复用同一套 RecyclerView/Adapter,立即清空视图上的旧层数据并复位分页
    private void switchToNewLevel(){
        if(gridAdapter != null){
            gridAdapter.setNewData(null); // 释放当前视图持有的旧层条目(数据本身已入快照栈)
        }
        this.page = 1;
        this.maxPage = 1;
        this.isLoad = false;
        this.mEndReached = false;
        updateEndTip(); // 清掉旧层残留的"到底了"
    }

    private void initView() {
        if (mGridView != null) return; // 唯一视图/适配器只需初始化一次(initView 会被多次调用)
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(true);
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)));
        gridAdapter = new GridAdapter(); // 单适配器:所有层级复用,层级数据通过 setNewData 进出
        mGridView.setAdapter(gridAdapter);

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
                    SourceBean homeSourceBean = com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getHomeSourceBean();
                    if(("12".indexOf(getUITag()) != -1) && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        changeView(video.id,video.tag.equals("folder"));
                    }
                    else if(homeSourceBean.isQuickSearch() && SystemConfig.isFastSearchMode() && enableFastSearch()){
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

        // 底部悬浮"到底了"(共享组件,布局中默认隐藏):滚到列表最底且确认无更多时才显示
        mEndTip = findViewById(R.id.end_tip);
        if (mEndTip != null) mEndTip.setVisibility(View.GONE);
        mGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                refreshEndTip();
            }
        });

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
        mEndReached = false;
        // 复位 footer: 重新开启加载更多并清除旧的"到底了"状态, 下一页请求期间显示"加载中"
        gridAdapter.loadMoreComplete();
        gridAdapter.setEnableLoadMore(true);
        updateEndTip();
        sourceViewModel.getList(sortData, page);
    }

    /** 刷新完成(数据到达/异常后调用;非下拉刷新期间调用为无操作) */
    private void finishSwipeRefresh() {
        if (mSwipeRefresh != null && mSwipeRefresh.isRefreshing()) {
            mSwipeRefresh.setRefreshing(false);
        }
    }

    /**
     * 同步刷新底部悬浮"到底了"(滚动监听中调用):数据非空、已确认无更多(mEndReached)、
     * 列表确实滚到最底(不能再向下滚)且曾有多屏内容(能向上滚回)才显示;
     * 一屏看完/空数据不显示,避免"到底了"常驻噪音。
     */
    private void refreshEndTip() {
        if (mEndTip == null || mGridView == null || gridAdapter == null) return;
        boolean hasData = !gridAdapter.getData().isEmpty();
        boolean atBottom = !mGridView.canScrollVertically(1);
        boolean scrolledUp = mGridView.canScrollVertically(-1);
        mEndTip.setVisibility(mEndReached && hasData && atBottom && scrolledUp ? View.VISIBLE : View.GONE);
    }

    /** 数据/滚动变化后调度刷新:列表可能尚未完成布局,post 到下一帧再判 */
    private void updateEndTip() {
        if (mGridView == null) return;
        mGridView.post(this::refreshEndTip);
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
                        // 确认没有更多:不再渲染列表尾的 BRVAH end 行,改由底部悬浮提示承担(贴导航栏)
                        mEndReached = true;
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(false);
                        if(page>2)AppBubble.toast("没有更多了");
                    } else {
                        mEndReached = false;
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if(page == 1){
                        showEmpty();
                    }else{
                        AppBubble.toast("没有更多了");
                        mEndReached = true;
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(false);
                    }
                }
                updateEndTip();
                finishSwipeRefresh();
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    private void initData() {
        if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getHomeSourceBean().getApi()==null){// 系统杀死app恢复缓存的fragment后会直接getList,此时首页api都未加载完
            showEmpty();
            return;
        }
        showLoading();
        isLoad = false;
        mEndReached = false;
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
        // 所有层级共用同一个网格视图:只需更新一次跨度;返回旧层时会按新跨度自动重排
        updateGridSpan(mGridView, span);
    }

    private void updateGridSpan(RecyclerView recyclerView, int span) {
        if (recyclerView != null && recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) recyclerView.getLayoutManager()).setSpanCount(span);
        }
    }
}