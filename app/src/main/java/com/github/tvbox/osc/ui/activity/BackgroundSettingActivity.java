package com.github.tvbox.osc.ui.activity;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.databinding.ActivityBackgroundSettingBinding;
import com.github.tvbox.osc.ui.kit.BackgroundTuneView;
import com.github.tvbox.osc.ui.kit.PageBackgroundView;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.BgImageTransform;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.PageBackgroundStore;
import com.google.android.material.slider.Slider;

/**
 * 背景图设置(二级页,入口:设置 - 主题颜色 - 设置背景图)。
 * <p>
 * 本页自身就是预览:页面背景层({@link PageBackgroundView})显示的就是<b>正在调整的草稿</b>,
 * 手指直接在背景上单指拖动位置、双指等比缩放 —— 跟其他页面用的是同一套渲染,所见即所得。
 * <p>
 * <b>改动只在点"确认背景"时写入配置</b>(不再边拖边生效),直接返回则整份草稿丢弃、其他页面不受影响。
 * <p>
 * 底部控制面板是抽屉:默认收起(只留一条"背景图设置"手柄,整屏看背景),点手柄展开/收起。
 * <ul>
 *   <li><b>更换图片</b>:标题栏右侧;系统选图(SAF,免存储权限)→ 校验(是图片、非 GIF、≤30MB)→
 *       纠正 EXIF 方向 → 长边限制 2560 → 转成 WebP(照片有损高质量、带透明通道无损);</li>
 *   <li><b>预设</b>:全屏铺满(等比盖满屏幕、超出裁掉)/ 保持大小(原始像素)/ 居中显示 /
 *       四角(保持原始大小并把边贴到该角);尺寸与位置各自独立,当前状态对应的项会高亮;</li>
 *   <li><b>背景图透明度</b>:背景图自身的不透明度(100% = 原图最清楚,0% = 完全看不见);</li>
 *   <li><b>背景遮罩</b>:只开关,不透明度固定({@link SystemConfig#PAGE_BG_SCRIM_DIM}%);关掉后原图直出,
 *       压在底图上的文字对比度会下降;</li>
 *   <li><b>恢复默认</b>:草稿恢复成内置图 + 全屏铺满 + 居中 + 图不透明度 100% + 遮罩开(同样要确认才生效)。</li>
 * </ul>
 */
public class BackgroundSettingActivity extends BaseVbActivity<ActivityBackgroundSettingBinding> {

    /** 系统选图请求码 */
    private static final int REQ_PICK_IMAGE = 0x0B01;

    /** 抽屉展开/收起动画时长(ms) */
    private static final long PANEL_ANIM_MS = 200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 抽屉是否展开(默认收起:整屏看背景) */
    private boolean panelExpanded = false;
    private ValueAnimator panelAnimator;
    /** 内容区展开后的高度缓存(键=面板宽度,宽度变了就重量) */
    private int panelContentFullHeight;
    private int panelContentFullWidth = -1;

    /** 编辑草稿:确认前不落配置 */
    private String draftPath = "";
    /** 草稿是否算"用户显式设置"(false=跟随主题默认背景,确认时不写用户设置) */
    private boolean draftUserSet = false;
    private float draftZoom = 0f;
    private float draftOffsetX = 0f;
    private float draftOffsetY = 0f;
    private int draftAlpha = SystemConfig.PAGE_BG_ALPHA_DEFAULT;
    private boolean draftScrim = true;

    /** 背景手势:实时改草稿预览,手势结束只更新草稿(不落配置) */
    private final BackgroundTuneView.Callback tuneCallback = new BackgroundTuneView.Callback() {
        @Override
        public BackgroundTuneView.State onTuneBegin() {
            PageBackgroundView layer = PageBackgroundView.find(BackgroundSettingActivity.this);
            if (layer == null || layer.getImageWidth() <= 0) return null; // 图片没加载完,先不给拖
            return new BackgroundTuneView.State(
                    layer.getImageWidth(), layer.getImageHeight(),
                    layer.getEffectiveZoom(), layer.getOffsetX(), layer.getOffsetY());
        }

        @Override
        public void onTune(float zoom, float offsetX, float offsetY) {
            PageBackgroundView layer = PageBackgroundView.find(BackgroundSettingActivity.this);
            if (layer != null) layer.setTransform(zoom, offsetX, offsetY);
        }

        @Override
        public void onTuneCommitted(float zoom, float offsetX, float offsetY) {
            draftZoom = zoom;
            draftOffsetX = offsetX;
            draftOffsetY = offsetY;
            syncPresetChips();
        }
    };

    @Override
    protected void init() {
        mBinding.tune.setCallback(tuneCallback);
        loadDraftFromConfig();
        initPanel();
        initPresets();
        initAlphaSlider();
        initScrimSwitch();
        initButtons();
        applyDraft();
        // 面板默认收起,进页先提示一句怎么调(不然不知道能直接拖背景)
        AppBubble.toast("单指拖动调整位置,双指等比缩放;调好后点\"确认背景\"");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // BaseActivity.onResume 会按"配置里的背景"重挂一次(比如刚从选图页回来),
        // 这里再套回草稿,保证预览始终是用户正在调的那份
        applyDraft();
    }

    // ── 草稿 ──

    private void loadDraftFromConfig() {
        // 生效图源 = 用户设置 > 主题默认(见 SystemConfig.getPageBackgroundPath);没显式设过就是"跟随主题"
        draftPath = SystemConfig.getPageBackgroundPath();
        draftUserSet = SystemConfig.isPageBackgroundUserSet();
        draftZoom = SystemConfig.getPageBackgroundZoom();
        draftOffsetX = SystemConfig.getPageBackgroundOffsetX();
        draftOffsetY = SystemConfig.getPageBackgroundOffsetY();
        draftAlpha = SystemConfig.getPageBackgroundAlpha();
        draftScrim = SystemConfig.isPageBackgroundScrimEnabled();
    }

    /** 把草稿套到本页背景层(仅预览,不写配置) */
    private void applyDraft() {
        PageBackgroundView.attach(this, new PageBackgroundView.Config(
                draftPath,
                draftScrim ? SystemConfig.PAGE_BG_SCRIM_DIM : 0,
                draftAlpha, draftZoom, draftOffsetX, draftOffsetY));
        PageBackgroundView layer = PageBackgroundView.find(this);
        if (layer != null) layer.setOnImageReadyListener(this::syncPresetChips);
        syncControls();
        syncControlsEnabled();
        syncPresetChips();
    }

    /**
     * 有没有背景图:没图时"默认=纯色",缩放/位置/透明度/遮罩都无从谈起 ——
     * 这排控件置灰并提示去换图,避免点了没反应。
     */
    private void syncControlsEnabled() {
        boolean hasImage = draftPath != null && !draftPath.isEmpty();
        float alpha = hasImage ? 1f : 0.4f;
        mBinding.tvHint.setText(hasImage
                ? "单指拖动调整位置,双指等比缩放;小图按原始大小显示"
                : "当前是纯色背景:点右上角「更换图片」选一张图");
        TextView[] chips = {mBinding.chipFill, mBinding.chipNative, mBinding.chipCenter,
                mBinding.chipTl, mBinding.chipTr, mBinding.chipBl, mBinding.chipBr};
        for (TextView chip : chips) {
            chip.setEnabled(hasImage);
            chip.setAlpha(alpha);
        }
        mBinding.sliderAlpha.setEnabled(hasImage);
        mBinding.sliderAlpha.setAlpha(alpha);
        mBinding.llScrim.setEnabled(hasImage);
        mBinding.llScrim.setAlpha(alpha);
        mBinding.switchScrim.setEnabled(hasImage);
        mBinding.switchScrim.setAlpha(alpha);
    }

    /**
     * 确认:草稿写入配置(其他页面 onResume 自动套用)。
     * 草稿没被改成"用户自己的图"时,<b>清掉用户设置</b>让它继续跟随主题默认
     * (浅/深主题默认是纯色;以后新增内置主题/自定义主题则跟着该主题的默认背景走)。
     */
    private void confirmDraft() {
        if (draftUserSet) {
            SystemConfig.setPageBackgroundPath(draftPath);
        } else {
            SystemConfig.clearPageBackgroundUserSet();
        }
        SystemConfig.setPageBackgroundTransform(draftZoom, draftOffsetX, draftOffsetY);
        SystemConfig.setPageBackgroundAlpha(draftAlpha);
        SystemConfig.setPageBackgroundScrimEnabled(draftScrim);
        AppBubble.toast("背景已保存");
        finish();
    }

    private void syncControls() {
        if ((int) mBinding.sliderAlpha.getValue() != draftAlpha) {
            mBinding.sliderAlpha.setValue(draftAlpha);
        }
        updateAlphaLabel(draftAlpha);
        mBinding.switchScrim.setChecked(draftScrim);
    }

    // ── 抽屉面板 ──

    private void initPanel() {
        applyPanelExpanded(false, false);
        mBinding.panelHandle.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPanelExpanded(!panelExpanded, true);
        });
    }

    /** 抽屉展开/收起:内容区高度 0↔实际高度做动画(收起后只剩手柄那一条) */
    private void applyPanelExpanded(boolean expanded, boolean animate) {
        panelExpanded = expanded;
        mBinding.tvPanelState.setText(expanded ? "收起" : "展开");
        mBinding.ivPanelArrow.animate().rotation(expanded ? 0f : 180f).setDuration(PANEL_ANIM_MS).start();

        final View content = mBinding.panelContent;
        int full = measureContentHeight(content);
        if (panelAnimator != null) {
            panelAnimator.cancel();
            panelAnimator = null;
        }
        if (full <= 0) {
            // 量不出高度(极端情况):直接交给 wrap_content / 0,别把内容锁死成 0
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            lp.height = expanded ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            content.setLayoutParams(lp);
            return;
        }
        if (!animate) {
            setContentHeight(content, expanded ? full : 0);
            return;
        }
        int from = expanded ? 0 : full;
        int to = expanded ? full : 0;
        ValueAnimator animator = ValueAnimator.ofInt(from, to).setDuration(PANEL_ANIM_MS);
        animator.addUpdateListener(a -> setContentHeight(content, (int) a.getAnimatedValue()));
        animator.start();
        panelAnimator = animator;
    }

    /** 内容区自然高度:缓存一份(动画中 getHeight() 是中间值,不能拿来做下一次动画的起点) */
    private int measureContentHeight(View content) {
        int panelWidth = mBinding.panel.getWidth();
        if (panelContentFullHeight > 0 && panelContentFullWidth == panelWidth) {
            return panelContentFullHeight;
        }
        int laidOut = content.getHeight();
        int height;
        if (laidOut > 0) {
            height = laidOut;
        } else {
            // 按"面板宽度减去左右内边距"量,量宽了会让提示文字少算一行 → 展开时底部被裁
            int width = panelWidth > 0
                    ? panelWidth - mBinding.panel.getPaddingLeft() - mBinding.panel.getPaddingRight()
                    : getResources().getDisplayMetrics().widthPixels;
            int widthSpec = View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            content.measure(widthSpec, heightSpec);
            height = content.getMeasuredHeight();
        }
        if (height > 0) {
            panelContentFullHeight = height;
            panelContentFullWidth = panelWidth;
        }
        return height;
    }

    private void setContentHeight(View content, int height) {
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        if (lp.height == height) return;
        lp.height = height;
        content.setLayoutParams(lp);
    }

    // ── 预设(尺寸 + 位置) ──

    private void initPresets() {
        // 全屏铺满(=超出裁剪:等比盖满屏幕,超出裁掉)+ 居中
        mBinding.chipFill.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(BgImageTransform.SIZE_FILL, BgImageTransform.POS_CENTER);
        });
        // 保持大小(原始像素),位置不变
        mBinding.chipNative.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(BgImageTransform.SIZE_NATIVE, -1);
        });
        // 居中显示(尺寸不变)
        mBinding.chipCenter.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(-1, BgImageTransform.POS_CENTER);
        });
        mBinding.chipTl.setOnClickListener(v -> corner(v, BgImageTransform.POS_TOP_LEFT));
        mBinding.chipTr.setOnClickListener(v -> corner(v, BgImageTransform.POS_TOP_RIGHT));
        mBinding.chipBl.setOnClickListener(v -> corner(v, BgImageTransform.POS_BOTTOM_LEFT));
        mBinding.chipBr.setOnClickListener(v -> corner(v, BgImageTransform.POS_BOTTOM_RIGHT));
    }

    /** 四角:保持原始大小 + 把图边贴到该角 */
    private void corner(View chip, int position) {
        FastClickCheckUtil.check(chip);
        applyPreset(BgImageTransform.SIZE_NATIVE, position);
    }

    /**
     * 套用预设到草稿并立即预览。
     *
     * @param size     {@link BgImageTransform#SIZE_FILL}/{@link BgImageTransform#SIZE_NATIVE};-1=不改尺寸
     * @param position 位置预设({@link BgImageTransform#POS_CENTER} 等);-1=不改位置
     */
    private void applyPreset(int size, int position) {
        PageBackgroundView layer = PageBackgroundView.find(this);
        if (layer == null || layer.getImageWidth() <= 0 || layer.getHeight() <= 0) return;
        int imgW = layer.getImageWidth();
        int imgH = layer.getImageHeight();
        int viewW = layer.getWidth();
        int viewH = layer.getHeight();

        float zoom = size < 0 ? layer.getEffectiveZoom()
                : BgImageTransform.zoomForSize(size, imgW, imgH, viewW, viewH);
        float ox;
        float oy;
        if (position < 0) {
            ox = layer.getOffsetX();
            oy = layer.getOffsetY();
        } else {
            float[] offset = BgImageTransform.offsetsForPosition(position, imgW, imgH, viewW, viewH, zoom);
            ox = offset[0];
            oy = offset[1];
        }
        draftZoom = zoom;
        draftOffsetX = ox;
        draftOffsetY = oy;
        layer.setTransform(zoom, ox, oy);
        syncPresetChips();
    }

    /** 按当前背景状态刷新预设高亮(尺寸项与位置项各自独立,可能同时亮两个) */
    private void syncPresetChips() {
        PageBackgroundView layer = PageBackgroundView.find(this);
        int imgW = layer == null ? 0 : layer.getImageWidth();
        int imgH = layer == null ? 0 : layer.getImageHeight();
        int viewW = layer == null ? 0 : layer.getWidth();
        int viewH = layer == null ? 0 : layer.getHeight();
        float zoom = layer == null ? 1f : layer.getEffectiveZoom();
        float ox = layer == null ? 0f : layer.getOffsetX();
        float oy = layer == null ? 0f : layer.getOffsetY();

        setChipSelected(mBinding.chipFill,
                BgImageTransform.matchesSize(zoom, BgImageTransform.SIZE_FILL, imgW, imgH, viewW, viewH));
        setChipSelected(mBinding.chipNative,
                BgImageTransform.matchesSize(zoom, BgImageTransform.SIZE_NATIVE, imgW, imgH, viewW, viewH));
        setChipSelected(mBinding.chipCenter,
                BgImageTransform.matchesPosition(ox, oy, BgImageTransform.POS_CENTER, imgW, imgH, viewW, viewH, zoom));
        setChipSelected(mBinding.chipTl,
                BgImageTransform.matchesPosition(ox, oy, BgImageTransform.POS_TOP_LEFT, imgW, imgH, viewW, viewH, zoom));
        setChipSelected(mBinding.chipTr,
                BgImageTransform.matchesPosition(ox, oy, BgImageTransform.POS_TOP_RIGHT, imgW, imgH, viewW, viewH, zoom));
        setChipSelected(mBinding.chipBl,
                BgImageTransform.matchesPosition(ox, oy, BgImageTransform.POS_BOTTOM_LEFT, imgW, imgH, viewW, viewH, zoom));
        setChipSelected(mBinding.chipBr,
                BgImageTransform.matchesPosition(ox, oy, BgImageTransform.POS_BOTTOM_RIGHT, imgW, imgH, viewW, viewH, zoom));
    }

    private void setChipSelected(TextView chip, boolean selected) {
        if (chip.isSelected() != selected) chip.setSelected(selected);
    }

    // ── 透明度 / 遮罩 ──

    /** 背景图透明度滑杆:直接就是背景图自身的不透明度(100=原图) */
    private void initAlphaSlider() {
        Slider slider = mBinding.sliderAlpha;
        slider.setValueFrom(0f);
        slider.setValueTo(100f);
        slider.setStepSize(1f);
        slider.setValue(draftAlpha);
        updateAlphaLabel(draftAlpha);
        slider.addOnChangeListener((s, value, fromUser) -> {
            draftAlpha = (int) value;
            PageBackgroundView layer = PageBackgroundView.find(this);
            if (layer != null) layer.setImageAlpha(draftAlpha);
            updateAlphaLabel(draftAlpha);
        });
    }

    /** 遮罩开关:不透明度固定,只有开/关 */
    private void initScrimSwitch() {
        mBinding.switchScrim.setChecked(draftScrim);
        mBinding.llScrim.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            draftScrim = !draftScrim;
            mBinding.switchScrim.setChecked(draftScrim);
            PageBackgroundView layer = PageBackgroundView.find(this);
            if (layer != null) layer.setDim(draftScrim ? SystemConfig.PAGE_BG_SCRIM_DIM : 0);
        });
    }

    private void initButtons() {
        // 更换图片:标题栏右侧
        mBinding.tvPick.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            pickImage();
        });
        mBinding.btnReset.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            // 草稿恢复成"跟随主题默认背景"(浅/深主题即纯色) + 位置/透明度/遮罩回默认;确认后才真正生效
            draftUserSet = false;
            draftPath = SystemConfig.getThemeDefaultBackground();
            draftZoom = 0f;
            draftOffsetX = 0f;
            draftOffsetY = 0f;
            draftAlpha = SystemConfig.PAGE_BG_ALPHA_DEFAULT;
            draftScrim = true;
            applyDraft();
        });
        mBinding.btnConfirm.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            confirmDraft();
        });
    }

    private void updateAlphaLabel(int percent) {
        mBinding.tvAlpha.setText(percent + "%");
    }

    // ── 选图 / 导入 ──

    /** 系统选图:SAF(ACTION_OPEN_DOCUMENT),不需要存储权限 */
    private void pickImage() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable th) {
            AppBubble.toast("没有可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        // 校验/转码是磁盘与解码重活:放共享执行器后台做,期间给个加载提示
        showLoadingDialog("正在导入图片…");
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            final PageBackgroundStore.ImportResult result =
                    PageBackgroundStore.importFromUri(getApplicationContext(), uri);
            mainHandler.post(() -> {
                dismissLoadingDialog();
                if (isFinishing()) return;
                if (!result.ok()) {
                    AppBubble.toast(result.error == null ? "图片导入失败,换一张试试" : result.error);
                    return;
                }
                // 换新图进草稿:位置居中、缩放回到"自动"(普通图铺满屏幕,很小的图按原始像素显示)
                draftPath = result.path;
                draftUserSet = true;
                draftZoom = 0f;
                draftOffsetX = 0f;
                draftOffsetY = 0f;
                applyDraft();
                AppBubble.toast("已换图,可拖动调整位置;记得点\"确认背景\"");
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (panelAnimator != null) {
            panelAnimator.cancel();
            panelAnimator = null;
        }
        super.onDestroy();
    }
}
