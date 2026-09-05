package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.QuickSearchAdapter;
import com.github.tvbox.osc.ui.adapter.SearchWordAdapter;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 其它数据源相关搜索弹窗（统一走 XPopup 底部弹窗 AppBottomPopupView;观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变:{@code new QuickSearchDialog(ctx)} + {@code setOnDismissListener} + {@code show()};
 * EventBus 在 onCreate 注册、关闭(onDismiss)时反注册并触发外部 dismiss 监听(等价原 setOnDismissListener)。
 */
public class QuickSearchDialog extends AppBottomPopupView {
    private SearchWordAdapter searchWordAdapter;
    private QuickSearchAdapter searchAdapter;
    private TvRecyclerView mGridView;
    private TvRecyclerView mGridViewWord;
    List<Movie.Video> results = new ArrayList<>();

    private DialogInterface.OnDismissListener externalDismissListener;

    public QuickSearchDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_quick_search;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        initViews();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_QUICK_SEARCH) {
            if (event.obj != null) {
                List<Movie.Video> data = (List<Movie.Video>) event.obj;
                results.addAll(data);
                if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD) {
            if (event.obj != null) {
                List<String> data = (List<String>) event.obj;
                if (searchWordAdapter != null) searchWordAdapter.setNewData(data);
            }
        }
    }

    /** 兼容旧调用点：popupInfo 未绑定时经 Builder 绑定 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme())
                    .setPopupCallback(new XPopupCallback() {
                        @Override public void onCreated(BasePopupView v) { }
                        @Override public void beforeShow(BasePopupView v) { }
                        @Override public void onShow(BasePopupView v) { }
                        @Override public void onDismiss(BasePopupView v) {
                            unregisterBus();
                            DialogInterface.OnDismissListener l = externalDismissListener;
                            externalDismissListener = null;
                            if (l != null) l.onDismiss(null);
                        }
                        @Override public void beforeDismiss(BasePopupView v) { }
                        @Override public boolean onBackPressed(BasePopupView v) { return false; }
                        @Override public void onKeyBoardStateChanged(BasePopupView v, int h) { }
                        @Override public void onDrag(BasePopupView v, int c, float x, boolean b) { }
                        @Override public void onClickOutside(BasePopupView v) { }
                    })
                    .asCustom(this).show();
        }
        return super.show();
    }

    /** 兼容旧 Dialog API：dismiss 后回调（同一次展示只触发一次） */
    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        this.externalDismissListener = listener;
    }

    private void unregisterBus() {
        try {
            EventBus.getDefault().unregister(this);
        } catch (Throwable ignored) {
        }
    }

    private void initViews() {
        mGridView = findViewById(R.id.mGridView);
        searchAdapter = new QuickSearchAdapter();
        mGridView.setHasFixedSize(true);
        // lite
        mGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        // with preview
        // mGridView.setLayoutManager(new V7GridLayoutManager(getContext(), 3));
        mGridView.setAdapter(searchAdapter);
        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Movie.Video video = searchAdapter.getData().get(position);
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_SELECT, video));
                dismiss();
            }
        });

        searchAdapter.setNewData(results);
        searchWordAdapter = new SearchWordAdapter();
        mGridViewWord = findViewById(R.id.mGridViewWord);
        mGridViewWord.setAdapter(searchWordAdapter);
        mGridViewWord.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
        searchWordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                searchAdapter.getData().clear();
                searchAdapter.notifyDataSetChanged();
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE, searchWordAdapter.getData().get(position)));
            }
        });
        searchWordAdapter.setNewData(new ArrayList<>());
    }
}
