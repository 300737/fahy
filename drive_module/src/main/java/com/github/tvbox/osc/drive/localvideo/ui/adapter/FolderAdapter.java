package com.github.tvbox.osc.drive.localvideo.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.tvbox.osc.drive.R;
import com.github.tvbox.osc.drive.localvideo.bean.VideoFolder;
import com.github.tvbox.osc.drive.localvideo.bean.VideoInfo;
import com.github.tvbox.osc.drive.localvideo.widget.DriveMarqueeTextView;
import com.github.tvbox.osc.drive.widget.DriveTvRecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹列表适配器（已去除 BRVAH，改为标准 RecyclerView.Adapter）。
 *
 * <p>封面取该文件夹第一个视频的帧（Glide 自动从视频路径提取视频缩略图）。</p>
 *
 * <p>TV 适配：item 点击与焦点变化通过 {@link DriveTvRecyclerView} 的
 * OnItemListener / onFocusChange / onClick 透传机制处理，
 * Activity 端在 OnItemListener.onItemClick 中接收点击事件。</p>
 */
public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.ViewHolder> {

    private final List<VideoFolder> mData = new ArrayList<>();
    private RecyclerView mRecyclerView;

    public FolderAdapter() { }

    // ==================== 数据操作（沿用 BRVAH 旧 API 名称以最小化上层改动） ====================

    public void setNewData(List<VideoFolder> data) {
        mData.clear();
        if (data != null) mData.addAll(data);
        notifyDataSetChanged();
    }

    public VideoFolder getItem(int position) {
        if (position >= 0 && position < mData.size()) return mData.get(position);
        return null;
    }

    public List<VideoFolder> getData() { return mData; }

    // ==================== RecyclerView.Adapter ====================

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.mRecyclerView = recyclerView;
    }

    @Override
    public int getItemCount() { return mData.size(); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 左侧目录栏专用窄版 item（id 与原版一致：item / iv / tv_name / tv_count）
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.drive_item_local_folder_nav, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VideoFolder item = mData.get(position);
        List<VideoInfo> videoList = item.getVideoList();

        holder.tvName.setText(item.getName());
        // 左侧目录栏宽度有限，只显示数量数字
        holder.tvCount.setText(String.valueOf(videoList.size()));

        if (!videoList.isEmpty()) {
            Glide.with(holder.itemView.getContext())
                    .load(videoList.get(0).getPath())
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.drawable.drive_iv_video)
                    .centerCrop()
                    .into(holder.iv);
        } else {
            holder.iv.setImageResource(R.drawable.drive_iv_video);
        }

        // 焦点变化 → 通知 DriveTvRecyclerView；同时开关目录名跑马灯
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
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View itemRoot;
        ImageView iv;
        DriveMarqueeTextView tvName;
        TextView tvCount;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            itemRoot = itemView.findViewById(R.id.item);
            iv = itemView.findViewById(R.id.iv);
            tvName = itemView.findViewById(R.id.tv_name);
            tvCount = itemView.findViewById(R.id.tv_count);
        }
    }
}
