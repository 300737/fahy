package com.github.tvbox.osc.drive.localvideo.widget;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 跑马灯 TextView（TV 专用）：文字宽度超过可显示宽度时横向滚动。
 *
 * <p>为什么自己做一个开关，而不是只靠 XML 的
 * {@code android:ellipsize="marquee"}：</p>
 * <ul>
 *   <li>系统 marquee 只有在 View {@code isFocused()} 或 {@code isSelected()} 时才启动，
 *       而列表 item 内 TextView 拿不到焦点（焦点在 item 根布局上），
 *       必须靠 {@code setSelected(true)} 手动开启；</li>
 *   <li>部分电视盒子 / 车机 ROM 对 {@code setSelected} 的 marquee 支持不完整，
 *       这里额外重写 {@link #isFocused()} 兜底，保证滚动一定会被触发；</li>
 *   <li>需要"仅焦点项滚动、其它项正常截断"，所以提供 {@link #setMarqueeEnabled(boolean)}
 *       由 Adapter 在焦点变化时开关。</li>
 * </ul>
 *
 * <p>文字没有超宽时不会滚动（系统 marquee 自身的判断），无需调用方测量。</p>
 */
public class DriveMarqueeTextView extends AppCompatTextView {

    private boolean mMarqueeEnabled = false;

    public DriveMarqueeTextView(Context context) {
        super(context);
        init();
    }

    public DriveMarqueeTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DriveMarqueeTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setHorizontallyScrolling(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setMarqueeRepeatLimit(-1); // -1 = marquee_forever
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
    }

    /**
     * 开关跑马灯：true 时文字超宽就滚动，false 时末尾省略号截断。
     * 由 Adapter 在 item 获得/失去焦点时调用。
     *
     * <p><b>注意：这里只能切 setSelected，不能切 setEllipsize。</b>
     * setEllipsize 会触发 requestLayout，而本方法在 onBindViewHolder 与焦点回调里调用，
     * 一旦在 RecyclerView 滚动/布局过程中触发 requestLayout，会导致列表重排、
     * 焦点丢失并跳回第一项（"按方向键焦点跑到上面"）。</p>
     *
     * <p>ellipsize 始终为 MARQUEE：marquee 未启动时系统按末尾截断渲染，
     * 效果与 TruncateAt.END 一致，无需来回切换。</p>
     */
    public void setMarqueeEnabled(boolean enabled) {
        if (mMarqueeEnabled == enabled) return;
        mMarqueeEnabled = enabled;
        setSelected(enabled); // TextView 内部据此 start/stop marquee，不会 requestLayout
    }

    public boolean isMarqueeEnabled() {
        return mMarqueeEnabled;
    }

    /**
     * 兜底：部分 ROM 只认 isFocused()，这里在开启跑马灯时返回 true，
     * 强制 TextView 启动 marquee（TextView 自身不参与焦点，无副作用）。
     */
    @Override
    public boolean isFocused() {
        return mMarqueeEnabled || super.isFocused();
    }
}
