package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class EpisodeDialog extends BaseDialog {
    private static final String TAG = "EpisodeDialog";
    // 选集固定一排 6 个按钮
    private static final int SPAN_COUNT = 6;

    public interface EpisodeSelectListener {
        void selectEpisode(int position);
    }

    private final List<VodInfo.VodSeries> episodes;
    private final int selectedPosition;
    private final EpisodeSelectListener listener;
    private final int controlBarHeight;

    public EpisodeDialog(@NonNull @NotNull Context context, String title, List<VodInfo.VodSeries> episodes,
                         int selectedPosition, int controlBarHeight, EpisodeSelectListener listener) {
        super(context);
        if (context instanceof Activity) setOwnerActivity((Activity) context);
        this.controlBarHeight = controlBarHeight;
        this.episodes = episodes == null ? new ArrayList<>() : new ArrayList<>(episodes);
        this.selectedPosition = Math.max(0, Math.min(selectedPosition, Math.max(0, this.episodes.size() - 1)));
        this.listener = listener;
        setContentView(R.layout.dialog_episode);
        init(title);
    }

    private void init(String title) {
        findViewById(R.id.episode_dismiss).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
            }
        });

        TvRecyclerView episodeList = findViewById(R.id.episode_list);
        EpisodeAdapter adapter = new EpisodeAdapter(selectedPosition);
        episodeList.setHasFixedSize(true);
        V7GridLayoutManager layoutManager = new V7GridLayoutManager(getContext(), SPAN_COUNT);
        episodeList.setLayoutManager(layoutManager);
        episodeList.setAdapter(adapter);
        adapter.setNewData(episodes);
        scrollToSelectedPosition(layoutManager);
        episodeList.setSelectedPosition(selectedPosition);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                dismiss();
                if (listener != null) listener.selectEpisode(position);
            }
        });
        episodeList.post(new Runnable() {
            @Override
            public void run() {
                if (episodeList.getWidth() <= 0) {
                    episodeList.postDelayed(this, 50);
                    return;
                }
                episodeList.setSelectedPosition(selectedPosition);
                requestSelectedEpisodeFocus(episodeList, layoutManager, 0);
            }
        });
    }

    private void scrollToSelectedPosition(V7GridLayoutManager layoutManager) {
        // 当前集上方预留两排，避免贴在顶部时遥控上键找不到可移动的焦点项。
        int rowsBeforeSelected = 2;
        int startPosition = Math.max(0, selectedPosition - layoutManager.getSpanCount() * rowsBeforeSelected);
        layoutManager.scrollToPositionWithOffset(startPosition, 0);
    }

    private void requestSelectedEpisodeFocus(final TvRecyclerView episodeList,
                                             final V7GridLayoutManager layoutManager,
                                             final int retryCount) {
        episodeList.postDelayed(new Runnable() {
            @Override
            public void run() {
                View selectedView = layoutManager.findViewByPosition(selectedPosition);
                if (selectedView != null) {
                    selectedView.requestFocus();
                } else if (retryCount < 3) {
                    scrollToSelectedPosition(layoutManager);
                    requestSelectedEpisodeFocus(episodeList, layoutManager, retryCount + 1);
                }
            }
        }, 50);
    }

    @Override
    public void show() {
        super.show();
        if (!isShowing()) return;
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        fitPanelToControlBar();
    }

    /**
     * 弹窗是全屏独立 window，控制条在宿主 Activity 里，弹窗弹出时控制条是 gone，
     * 所以不能按可见性/屏幕坐标取高度。直接 measure 控制条(即使 gone 也能测出
     * 它显示时占用的高度)，把选集面板下边距设成该高度，
     * 让第4排底边紧贴控制条上边线。
     */
    private void fitPanelToControlBar() {
        final View panel = findViewById(R.id.episode_panel);
        if (panel == null) {
            Log.d(TAG, "fitPanel: panel==null");
            return;
        }
        panel.post(new Runnable() {
            @Override
            public void run() {
                applyFit(panel);
                // 控制条可能延迟显示，多校准几次
                panel.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyFit(panel);
                    }
                }, 300);
                panel.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyFit(panel);
                    }
                }, 1000);
            }
        });
    }

    private void applyFit(View panel) {
        int controlHeight = controlBarHeight;
        Log.d(TAG, "applyFit: controlHeight=" + controlHeight
                + " panelH=" + panel.getHeight() + " panelBottom=" + panel.getBottom());
        if (controlHeight <= 0) return;

        // 列表高度固定(4排)，最后一行按钮底边到列表底边往往还有留白。
        // 把这段留白补进位移，让按钮底边真正贴住控制条上边线，消掉中间那段空隙。
        int bottomGap = 0;
        TvRecyclerView list = findViewById(R.id.episode_list);
        if (list != null && list.getHeight() > 0) {
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                int d = list.getHeight() - child.getBottom();
                if (d >= 0 && d < best) best = d;
            }
            if (best != Integer.MAX_VALUE) bottomGap = best;
        }

        int offset = -controlHeight + bottomGap;
        panel.setTranslationY(offset);
        Log.d(TAG, "applyFit: bottomGap=" + bottomGap + " setTranslationY=" + offset);
    }

    private static class EpisodeAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
        private final int selectedPosition;

        EpisodeAdapter(int selectedPosition) {
            super(R.layout.item_series, new ArrayList<VodInfo.VodSeries>());
            this.selectedPosition = selectedPosition;
        }

        @Override
        protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
            TextView series = helper.getView(R.id.tvSeries);
            series.setText(item == null ? "" : item.name);
            series.setTextColor(helper.getLayoutPosition() == selectedPosition
                    ? mContext.getResources().getColor(R.color.color_02F8E1)
                    : Color.WHITE);
        }
    }
}
