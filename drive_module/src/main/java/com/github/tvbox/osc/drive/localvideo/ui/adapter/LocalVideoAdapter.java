package com.github.tvbox.osc.drive.localvideo.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.tvbox.osc.drive.R;
import com.github.tvbox.osc.drive.localvideo.bean.VideoInfo;
import com.github.tvbox.osc.drive.localvideo.util.FormatUtils;
import com.github.tvbox.osc.drive.localvideo.widget.DriveMarqueeTextView;
import com.github.tvbox.osc.drive.widget.DriveTvRecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频列表适配器（已去除 BRVAH，改为标准 RecyclerView.Adapter）。
 *
 * <p>Glide 加载视频路径自动提取封面帧；时长直接取 MediaStore 字段。</p>
 *
 * <p>TV 适配：item 点击与焦点变化通过 {@link DriveTvRecyclerView} 的
 * OnItemListener / onFocusChange / onClick 透传机制处理。</p>
 *
 * <p><b>已移除播放进度逻辑</b>：原 PlaybackPrefs / ProgressBar 不再使用，
 * 仅保留时长显示。如未来需要恢复播放进度，可在宿主侧通过 SharedPreferences 写入并在此处读取。</p>
 */
public class LocalVideoAdapter extends RecyclerView.Adapter<LocalVideoAdapter.ViewHolder> {

    public interface OnSelectedCountListener {
        void onSelectedCount(int count);
    }

    private final List<VideoInfo> mData = new ArrayList<>();
    private RecyclerView mRecyclerView;
    private OnSelectedCountListener mOnSelectedCountListener;
    private boolean selectMode = false;

    public LocalVideoAdapter() { }

    // ==================== 数据操作 ====================

    public void setNewData(List<VideoInfo> data) {
        mData.clear();
        if (data != null) mData.addAll(data);
        notifyDataSetChanged();
    }

    public VideoInfo getItem(int position) {
        if (position >= 0 && position < mData.size()) return mData.get(position);
        return null;
    }

    public List<VideoInfo> getData() { return mData; }

    public void setSelectMode(boolean selectMode) {
        this.selectMode = selectMode;
        notifyDataSetChanged();
    }

    public boolean isSelectMode() { return selectMode; }

    public void setOnSelectCountListener(OnSelectedCountListener listener) {
        mOnSelectedCountListener = listener;
    }

    // ==================== RecyclerView.Adapter ====================

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mRecyclerView = recyclerView;
    }

    @Override
    public int getItemCount() { return mData.size(); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.drive_item_local_video, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VideoInfo item = mData.get(position);

        Glide.with(holder.itemView.getContext())
                .load(item.getPath())
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.drive_iv_video)
                .centerCrop()
                .into(holder.iv);

        holder.tvName.setText(item.getDisplayName());
        holder.tvVideoSize.setText(FormatUtils.byte2FitSize(item.getSize()));
        holder.tvVideoResolution.setText(item.getResolution());

        // 时长：直接取 MediaStore 字段，不再读 PlaybackPrefs 缓存
        long duration = item.getDuration();
        if (duration > 0) {
            holder.tvDuration.setText(FormatUtils.stringForTime(duration));
        } else {
            holder.tvDuration.setText("");
        }

        // 多选模式下显示 CheckBox
        holder.cb.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        holder.cb.setChecked(item.isChecked());

        // 焦点变化 → 通知 DriveTvRecyclerView；同时开关文件名跑马灯
        holder.tvName.setMarqueeEnabled(holder.itemRoot.hasFocus());
        holder.itemRoot.setOnFocusChangeListener((v, hasFocus) -> {
            holder.tvName.setMarqueeEnabled(hasFocus);
            if (mRecyclerView instanceof DriveTvRecyclerView) {
                ((DriveTvRecyclerView) mRecyclerView).onFocusChange(holder.itemView, hasFocus);
            }
        });

        // 点击 → 通知 DriveTvRecyclerView
        holder.itemRoot.setOnClickListener(v -> {
            if (mRecyclerView instanceof DriveTvRecyclerView) {
                ((DriveTvRecyclerView) mRecyclerView).onClick(holder.itemView);
            }
        });

        // 实时统计选中数量
        if (mOnSelectedCountListener != null) {
            int count = 0;
            for (VideoInfo v : mData) {
                if (v.isChecked()) count++;
            }
            mOnSelectedCountListener.onSelectedCount(count);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View itemRoot;
        ImageView iv;
        DriveMarqueeTextView tvName;
        TextView tvDuration;
        TextView tvVideoSize;
        TextView tvVideoResolution;
        CheckBox cb;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            itemRoot = itemView.findViewById(R.id.item);
            iv = itemView.findViewById(R.id.iv);
            tvName = itemView.findViewById(R.id.tv_name);
            tvDuration = itemView.findViewById(R.id.tv_duration);
            tvVideoSize = itemView.findViewById(R.id.tv_video_size);
            tvVideoResolution = itemView.findViewById(R.id.tv_video_resolution);
            cb = itemView.findViewById(R.id.cb);
        }
    }
}
