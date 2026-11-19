package com.github.tvbox.osc.drive.localvideo.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.drive.R;

/**
 * 自绘 TitleBar（替代 hjq.bar.TitleBar 第三方控件）。
 *
 * <p>设计目标：与 drive_module 已有风格一致——LinearLayout 标题行 + 自绘返回/操作按钮，
 * 完全不依赖 BRVAH / TitleBar 等第三方库，可在宿主无该库环境下直接编译运行。</p>
 *
 * <p>对外提供：</p>
 * <ul>
 *   <li>{@link #setTitle(CharSequence)} 设置标题文字</li>
 *   <li>{@link #setSubtitle(CharSequence)} 设置副标题（用于展示当前目录的完整路径，支持多行不被截断）</li>
 *   <li>{@link #setOnLeftClickListener(View.OnClickListener)} 绑定左上角返回按钮回调</li>
 *   <li>{@link #getActionTextView()} 取右侧操作 TextView，由调用方自由定制点击逻辑</li>
 * </ul>
 *
 * <p>TV 适配：返回按钮与右侧操作按钮均设为 focusable，配合遥控器 D-pad 导航；
 * 焦点背景使用宿主已有的 {@code drive_shape_user_home} selector。</p>
 */
public class DriveTitleBar extends LinearLayout {

    private final View mRootView;
    private final TextView mTitleView;
    private final TextView mSubtitleView;
    private final View mBackView;
    private final TextView mActionView;

    public DriveTitleBar(Context context) {
        this(context, null);
    }

    public DriveTitleBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DriveTitleBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        mRootView = LayoutInflater.from(context).inflate(R.layout.drive_title_bar, this, true);
        mTitleView = mRootView.findViewById(R.id.drive_title_text);
        mSubtitleView = mRootView.findViewById(R.id.drive_title_subtitle);
        mBackView = mRootView.findViewById(R.id.drive_title_back);
        mActionView = mRootView.findViewById(R.id.drive_title_action);
    }

    public void setTitle(CharSequence text) {
        mTitleView.setText(text);
    }

    public void setTitle(int resId) {
        mTitleView.setText(resId);
    }

    /**
     * 设置副标题（显示当前目录的完整路径）。
     * <p>传 null / 空串时自动隐藏该行，避免留下空白；非空时自动显示。</p>
     */
    public void setSubtitle(CharSequence text) {
        if (text == null || text.length() == 0) {
            mSubtitleView.setVisibility(GONE);
            mSubtitleView.setText("");
        } else {
            mSubtitleView.setVisibility(VISIBLE);
            mSubtitleView.setText(text);
        }
    }

    public void setSubtitleVisibility(int visibility) {
        mSubtitleView.setVisibility(visibility);
    }

    /** 当前副标题文字（用于调用方判断是否需要重设，避免无意义的 requestLayout） */
    public CharSequence getSubtitle() {
        return mSubtitleView.getText();
    }

    public int getSubtitleVisibility() {
        return mSubtitleView.getVisibility();
    }

    public void setOnLeftClickListener(OnClickListener l) {
        mBackView.setOnClickListener(l);
    }

    public TextView getActionTextView() {
        return mActionView;
    }

    public void setActionText(CharSequence text) {
        mActionView.setText(text);
    }

    public void setActionVisibility(int visibility) {
        mActionView.setVisibility(visibility);
    }
}
