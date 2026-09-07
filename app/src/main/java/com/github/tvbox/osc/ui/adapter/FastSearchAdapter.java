package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.chad.library.adapter.base.util.MultiTypeDelegate;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.github.tvbox.osc.util.MD5;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 搜索结果适配器：支持「单列列表」、「宫格网格」、「通栏卡片」三种展示
 * （MultiTypeDelegate 按 mode 分发 item 布局）。{@link #setMode(int)} 切换展示形态，
 * 切换后需 notifyDataSetChanged；数据与点击监听不受影响。
 */
public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    /** 单列列表 */
    public static final int MODE_LIST = 0;
    /** 宫格/网格(3:4 卡,多列) */
    public static final int MODE_GRID = 1;
    /** 通栏卡片(整行满宽横向卡) */
    public static final int MODE_BANNER = 2;

    private static final int TYPE_LIST = 0;
    private static final int TYPE_GRID = 1;
    private static final int TYPE_BANNER = 2;

    private int mode = MODE_LIST;

    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
        setMultiTypeDelegate(new MultiTypeDelegate<Movie.Video>() {
            @Override
            protected int getItemType(Movie.Video item) {
                return mode == MODE_GRID ? TYPE_GRID : (mode == MODE_BANNER ? TYPE_BANNER : TYPE_LIST);
            }
        });
        getMultiTypeDelegate().registerItemType(TYPE_LIST, R.layout.item_search);
        getMultiTypeDelegate().registerItemType(TYPE_GRID, R.layout.item_search_grid);
        getMultiTypeDelegate().registerItemType(TYPE_BANNER, R.layout.item_search_banner);
    }

    public int getMode() {
        return mode;
    }

    public boolean isGrid() {
        return mode == MODE_GRID;
    }

    /** 切换展示形态(MODE_LIST/MODE_GRID/MODE_BANNER)；需在使用方 setLayoutManager 后调用，随后 notify 刷新布局 */
    public void setMode(int mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        notifyDataSetChanged();
    }

    /** 兼容旧调用：单列/宫格切换 */
    public void setGrid(boolean grid) {
        setMode(grid ? MODE_GRID : MODE_LIST);
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        // 片名
        setText(helper, R.id.tvName, item.name);

        // 海报卡公共:左上角评分角标 + 底部渐变黑底叠集数(有才显示)
        bindPosterCard(helper, item);

        switch (mode) {
            case MODE_GRID:
                bindGrid(helper, item);
                break;
            case MODE_BANNER:
                bindBanner(helper, item);
                break;
            default:
                bindList(helper, item);
        }

        // 来源(置底;来源名空则整行隐藏,内容上移)
        String sourceName = safeSourceName(item.sourceKey);
        setVisible(helper, R.id.llSite, !sourceName.isEmpty());
        setText(helper, R.id.tvSite, sourceName);

        ImageView ivThumb = helper.getView(R.id.ivThumb);
        loadPoster(ivThumb, item);
    }

    /** 共用海报卡:评分(左上角橙色徽标) + 集数(底部渐变黑底),有才显示 */
    private void bindPosterCard(BaseViewHolder helper, Movie.Video item) {
        String score = item.score == null ? "" : item.score.trim();
        setVisible(helper, R.id.tvScore, !score.isEmpty());
        setText(helper, R.id.tvScore, score);

        // 集数:内容为空则整块渐变黑底隐藏(避免空条)
        String note = item.note == null ? "" : item.note.trim();
        setVisible(helper, R.id.llNoteBar, !note.isEmpty());
        setText(helper, R.id.tvNote, note);
    }

    /** 宫格:类型地区语言 一行,导演+时间(直接显示导演名),演员(带前缀) */
    private void bindGrid(BaseViewHolder helper, Movie.Video item) {
        // 类型 空格 地区 空格 语言
        String meta = joinTypeAreaLang(item);
        setVisible(helper, R.id.tvMeta, !meta.isEmpty());
        setText(helper, R.id.tvMeta, meta);

        // 导演 时间:直接显示导演名(无"导演："前缀),有年份则加空格年
        String directorTime = joinDirectorYear(item);
        setVisible(helper, R.id.tvDirector, !directorTime.isEmpty());
        setText(helper, R.id.tvDirector, directorTime);

        boolean hasActor = item.actor != null && !item.actor.isEmpty();
        setVisible(helper, R.id.tvActor, hasActor);
        if (hasActor) {
            setText(helper, R.id.tvActor, "演员：" + item.actor.trim());
        }
    }

    /** 通栏卡片:剧集名称(支持换行);评分/集数已由海报卡处理 */
    private void bindBanner(BaseViewHolder helper, Movie.Video item) {
        setText(helper, R.id.tvName, item.name);
    }

    /** 单列列表:时间(单独一行),类型 地区(一行),集数;演员不展示,集数信息由文字行承载 */
    private void bindList(BaseViewHolder helper, Movie.Video item) {
        // 时间
        String year = item.year > 0 ? String.valueOf(item.year) : "";
        setVisible(helper, R.id.tvYear, !year.isEmpty());
        setText(helper, R.id.tvYear, year);

        // 类型 空格 地区
        String typeArea = joinTypeArea(item);
        setVisible(helper, R.id.tvMeta, !typeArea.isEmpty());
        setText(helper, R.id.tvMeta, typeArea);

        // 集数(原演员行;空则隐藏,内容上移)
        String note = item.note == null ? "" : item.note.trim();
        setVisible(helper, R.id.tvActor, !note.isEmpty());
        setText(helper, R.id.tvActor, note);

        // 集数已在文字行显示,隐藏海报角底部渐变黑底(避免重复)
        setVisible(helper, R.id.llNoteBar, false);
    }

    /** 类型 空格 地区(空段跳过;整行为空则隐藏) */
    private String joinTypeArea(Movie.Video item) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(item.type)) sb.append(item.type.trim()).append(' ');
        if (!TextUtils.isEmpty(item.area)) sb.append(item.area.trim());
        return sb.toString().trim();
    }

    /** 类型 空格 地区 空格 语言(空段跳过;整行为空则隐藏) */
    private String joinTypeAreaLang(Movie.Video item) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(item.type)) sb.append(item.type.trim()).append(' ');
        if (!TextUtils.isEmpty(item.area)) sb.append(item.area.trim()).append(' ');
        if (!TextUtils.isEmpty(item.lang)) sb.append(item.lang.trim());
        return sb.toString().trim();
    }

    /** 导演 时间:直接显示导演名(无前缀),有年份则加空格年(如"韦正 2012");两者皆无则空 */
    private String joinDirectorYear(Movie.Video item) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(item.director)) sb.append(item.director.trim()).append(' ');
        if (item.year > 0) sb.append(item.year);
        return sb.toString().trim();
    }

    private void loadPoster(ImageView ivThumb, Movie.Video item) {
        if (ivThumb == null) return;
        // 占位统一走 ImageView 背景层(placeholder_poster),src 只放实图:先清旧图露出占位,
        // 三布局(列表/宫格/通栏)共用同一目标规格与稳定缓存键(不含 position),
        // 切换布局/滚动复用均命中同一缓存,不再重复下载或拉原图。
        ivThumb.setImageDrawable(null);
        String url = item.pic == null ? "" : item.pic.trim();
        if (url.isEmpty()) return;
        int w = AutoSizeUtils.dp2px(mContext, 200);
        int h = AutoSizeUtils.dp2px(mContext, 267);
        int radius = AutoSizeUtils.dp2px(mContext, 12);
        String cacheKey = MD5.string2MD5(url + "_search_poster_200x267");
        Picasso.get()
                .load(url)
                .transform(new RoundTransformation(cacheKey)
                        .centerCorp(true)
                        .override(w, h)
                        .roundRadius(radius, RoundTransformation.RoundType.ALL))
                .into(ivThumb);
    }

    private String safeSourceName(String sourceKey) {
        try {
            if (TextUtils.isEmpty(sourceKey)) return "";
            SourceBean source = SourceConfigProviders.get().getSource(sourceKey);
            return source == null ? "" : source.getName();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 布局可能不含某 id(列表无 tvDirector 等),setVisible/setText 需空安全 */
    private void setVisible(BaseViewHolder helper, int id, boolean visible) {
        View v = helper.getView(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setText(BaseViewHolder helper, int id, String text) {
        View v = helper.getView(id);
        if (v instanceof TextView) ((TextView) v).setText(text);
    }
}
