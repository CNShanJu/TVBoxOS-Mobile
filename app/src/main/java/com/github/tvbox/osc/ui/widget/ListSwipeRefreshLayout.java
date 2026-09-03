package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * 支持"任意深度可滚动子 View"的下拉刷新容器。
 * <p>
 * 标准 SwipeRefreshLayout 只检查其直接子 View 是否可向上滚动;
 * 本页结构是 刷新容器 → 内容容器 → RecyclerView(还可能有 LoadSir 覆盖层/文件夹切换的动态列表),
 * 直接子容器不可滚动会导致:列表滚动到中间时下拉也被当成刷新。这里改为扫描全部后代,
 * 只要任一后代列表还能向上滚动(未到顶部)就不触发下拉刷新。
 */
public class ListSwipeRefreshLayout extends SwipeRefreshLayout {

    public ListSwipeRefreshLayout(@NonNull Context context) {
        super(context);
    }

    public ListSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean canChildScrollUp() {
        return findScrollableUp(getChildAt(0));
    }

    private static boolean findScrollableUp(View view) {
        if (view == null) return false;
        if (view.canScrollVertically(-1)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findScrollableUp(group.getChildAt(i))) return true;
            }
        }
        return false;
    }
}
