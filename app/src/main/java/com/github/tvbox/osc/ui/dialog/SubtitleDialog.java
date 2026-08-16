package com.github.tvbox.osc.ui.dialog;
import com.github.tvbox.osc.util.AppBubble;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

public class SubtitleDialog extends BaseDialog {

    public TextView selectInternal;
    private TextView selectLocal;
    private TextView selectRemote;
    private TextView subtitleSizeMinus;
    private TextView subtitleSizeText;
    private TextView subtitleSizePlus;
    private TextView subtitleTimeMinus;
    private TextView subtitleTimeText;
    private TextView subtitleTimePlus;
    private TextView subtitleStyleOne;
    private TextView subtitleStyleTwo;
    private TextView subtitleOpen;
    private TextView subtitleClose;

    private SearchSubtitleListener mSearchSubtitleListener;
    private LocalFileChooserListener mLocalFileChooserListener;
    private SubtitleViewListener mSubtitleViewListener;

    public SubtitleDialog(@NonNull @NotNull Context context) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setContentView(R.layout.dialog_subtitle);
        initView(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(getWindow().getAttributes());
        lp.gravity = Gravity.CENTER;
        lp.width = ConvertUtils.dp2px(330);

        getWindow().setAttributes(lp);
        getWindow().setWindowAnimations(R.style.DialogFadeAnimation); // Set the animation style
    }

    private void initView(Context context) {
        selectInternal = findViewById(R.id.selectInternal);
        selectLocal = findViewById(R.id.selectLocal);
        selectRemote = findViewById(R.id.selectRemote);
        subtitleSizeMinus = findViewById(R.id.subtitleSizeMinus);
        subtitleSizeText = findViewById(R.id.subtitleSizeText);
        subtitleSizePlus = findViewById(R.id.subtitleSizePlus);
        subtitleTimeMinus = findViewById(R.id.subtitleTimeMinus);
        subtitleTimeText = findViewById(R.id.subtitleTimeText);
        subtitleTimePlus = findViewById(R.id.subtitleTimePlus);
        subtitleStyleOne = findViewById(R.id.subtitleStyleOne);
        subtitleStyleTwo = findViewById(R.id.subtitleStyleTwo);
        subtitleOpen = findViewById(R.id.subtitleOpen);
        subtitleClose = findViewById(R.id.subtitleClose);

        selectLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mLocalFileChooserListener.openLocalFileChooserDialog();
            }
        });

        selectRemote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mSearchSubtitleListener.openSearchSubtitleDialog();
            }
        });

        int size = SubtitleHelper.getTextSize(getOwnerActivity());
        subtitleSizeText.setText(Integer.toString(size));

        subtitleSizeMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String sizeStr = subtitleSizeText.getText().toString();
                int curSize = Integer.parseInt(sizeStr);
                curSize -= 2;
                if (curSize <= 12) {
                    curSize = 12;
                }
                subtitleSizeText.setText(Integer.toString(curSize));
                SubtitleHelper.setTextSize(curSize);
                mSubtitleViewListener.setTextSize(curSize);
            }
        });
        subtitleSizePlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String sizeStr = subtitleSizeText.getText().toString();
                int curSize = Integer.parseInt(sizeStr);
                curSize += 2;
                if (curSize >= 60) {
                    curSize = 60;
                }
                subtitleSizeText.setText(Integer.toString(curSize));
                SubtitleHelper.setTextSize(curSize);
                mSubtitleViewListener.setTextSize(curSize);
            }
        });

        int timeDelay = SubtitleHelper.getTimeDelay();
        String timeStr = "0";
        if (timeDelay != 0) {
            double dbTimeDelay = timeDelay/1000;
            timeStr = Double.toString(dbTimeDelay);
        }
        subtitleTimeText.setText(timeStr);

        subtitleTimeMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                String timeStr = subtitleTimeText.getText().toString();
                double time = Float.parseFloat(timeStr);
                double oneceDelay = -0.5;
                time += oneceDelay;
                if (time == 0.0) {
                    timeStr = "0";
                } else {
                    timeStr = Double.toString(time);
                }
                subtitleTimeText.setText(timeStr);
                int mseconds = (int)(oneceDelay*1000);
                SubtitleHelper.setTimeDelay((int)(time*1000));
                mSubtitleViewListener.setSubtitleDelay(mseconds);
            }
        });
        subtitleTimePlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                String timeStr = subtitleTimeText.getText().toString();
                double time = Float.parseFloat(timeStr);
                double oneceDelay = 0.5;
                time += oneceDelay;
                if (time == 0.0) {
                    timeStr = "0";
                } else {
                    timeStr = Double.toString(time);
                }
                subtitleTimeText.setText(timeStr);
                int mseconds = (int)(oneceDelay*1000);
                SubtitleHelper.setTimeDelay((int)(time*1000));
                mSubtitleViewListener.setSubtitleDelay(mseconds);
            }
        });
        selectInternal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mSubtitleViewListener.selectInternalSubtitle();
            }
        });

        subtitleStyleOne.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int style = 0;
                dismiss();
                mSubtitleViewListener.setTextStyle(style);
                AppBubble.toast("设置样式成功");
            }
        });

        subtitleStyleTwo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int style = 1;
                dismiss();
                mSubtitleViewListener.setTextStyle(style);
                AppBubble.toast("设置样式成功");
            }
        });
        findViewById(R.id.subtitleOpen).setOnClickListener(v -> {
            updateSubtitleState(true);
            dismiss();
            mSubtitleViewListener.subtitleOpen(true);
        });
        findViewById(R.id.subtitleClose).setOnClickListener(v -> {
            updateSubtitleState(false);
            dismiss();
            mSubtitleViewListener.subtitleOpen(false);
        });

        // 清楚显示当前字幕开/关状态(默认关闭)
        updateSubtitleState(Hawk.get(HawkConfig.SUBTITLE_OPEN, false));
    }

    /** 高亮当前字幕状态:开启->"✓ 打开字幕",关闭->"关闭字幕"(无勾);选项区仅开启时展示 */
    private void updateSubtitleState(boolean open) {
        int activeColor = getContext().getResources().getColor(R.color.colorPrimary);
        int normalColor = getContext().getResources().getColor(R.color.text_foreground);
        if (open) {
            subtitleOpen.setText("✓ 打开字幕");
            subtitleOpen.setTextColor(activeColor);
            subtitleClose.setText("关闭字幕");
            subtitleClose.setTextColor(normalColor);
        } else {
            subtitleClose.setText("关闭字幕");
            subtitleClose.setTextColor(activeColor);
            subtitleOpen.setText("打开字幕");
            subtitleOpen.setTextColor(normalColor);
        }
        // 字幕选项(字号/样式/时间/本地/内置/搜索)仅"打开字幕"时展示
        findViewById(R.id.ll_subtitle_options).setVisibility(open ? View.VISIBLE : View.GONE);
    }

    public void setLocalFileChooserListener(LocalFileChooserListener localFileChooserListener) {
        mLocalFileChooserListener = localFileChooserListener;
    }

    public interface LocalFileChooserListener {
        void openLocalFileChooserDialog();
    }

    public void setSearchSubtitleListener(SearchSubtitleListener searchSubtitleListener) {
        mSearchSubtitleListener = searchSubtitleListener;
    }

    public interface SearchSubtitleListener {
        void openSearchSubtitleDialog();
    }

    public void setSubtitleViewListener(SubtitleViewListener subtitleViewListener) {
        mSubtitleViewListener = subtitleViewListener;
    }

    public interface SubtitleViewListener {
        void setTextSize(int size);
        void setSubtitleDelay(int milliseconds);
        void selectInternalSubtitle();
        void setTextStyle(int style);
        void subtitleOpen(boolean b);
    }
}
