package com.github.tvbox.osc.drive.localvideo.ui.activity;

import android.app.AlertDialog;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.tvbox.osc.drive.DriveModule;
import com.github.tvbox.osc.drive.R;
import com.github.tvbox.osc.drive.bean.DrivePlayItem;
import com.github.tvbox.osc.drive.callback.DriveCallback;
import com.github.tvbox.osc.drive.localvideo.VideoScanner;
import com.github.tvbox.osc.drive.localvideo.bean.VideoInfo;
import com.github.tvbox.osc.drive.localvideo.ui.adapter.LocalVideoAdapter;
import com.github.tvbox.osc.drive.localvideo.util.FastClickCheckUtil;
import com.github.tvbox.osc.drive.localvideo.widget.DriveTitleBar;
import com.github.tvbox.osc.drive.widget.DriveTvRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频列表页：按文件夹名筛选视频展示，支持多选删除；
 * 点击视频通过统一 {@link DriveCallback} 回调交给宿主播放。
 *
 * <p>合并改动：</p>
 * <ul>
 *   <li>使用 {@link DriveTvRecyclerView} 提供完整 D-pad 焦点导航与点击透传。</li>
 *   <li>使用自绘 {@link DriveTitleBar} 替代 hjq.bar.TitleBar。</li>
 *   <li>TV 上无长按事件，进入"删除模式"通过标题栏右侧"删除"按钮触发；
 *       在删除模式下，再次点击该按钮可退出删除模式。</li>
 *   <li>选中视频后通过 {@link DriveCallback#onPlayFiles} 把当前文件夹的全部视频
 *       组成播放列表（从点击的那个开始，与 LocalFile Spider 行为一致）回调宿主，
 *       不再使用 VideoPlayProvider / VideoPlayProviderHolder。</li>
 *   <li><b>已移除播放进度逻辑</b>：不再读写 PlaybackPrefs，列表 item 不再展示进度条。</li>
 * </ul>
 */
public class VideoListActivity extends AppCompatActivity {

    private DriveTitleBar mTitleBar;
    private DriveTvRecyclerView mRecyclerView;
    private View mLlMenu;
    private View mTvAllCheck;
    private View mTvCancelAll;
    private TextView mTvDelete;

    private String mBucketDisplayName = "";
    /** 当前文件夹的完整绝对路径，用于筛选与标题栏展示 */
    private String mFolderPath = "";
    private final LocalVideoAdapter mAdapter = new LocalVideoAdapter();
    private int mSelectedCount = 0;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.drive_activity_video_list);
        mTitleBar = findViewById(R.id.title_bar);
        mRecyclerView = findViewById(R.id.rv);
        mLlMenu = findViewById(R.id.ll_menu);
        mTvAllCheck = findViewById(R.id.tv_all_check);
        mTvCancelAll = findViewById(R.id.tv_cancel_all_checked);
        mTvDelete = findViewById(R.id.tv_delete);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            mBucketDisplayName = extras.getString("bucketDisplayName", "");
            mFolderPath = extras.getString("folderPath", "");
        }
        if (mBucketDisplayName == null) mBucketDisplayName = "";
        if (mFolderPath == null) mFolderPath = "";
        mTitleBar.setTitle(mBucketDisplayName);
        // 标题栏副标题：显示文件夹完整路径（扫描完成后会再次刷新，见 groupVideosAsync）
        mTitleBar.setSubtitle(mFolderPath);
        mTitleBar.setOnLeftClickListener(v -> {
            FastClickCheckUtil.check(v);
            onBackPressed();
        });
        // 标题栏右侧"删除"按钮：进入/退出删除模式
        mTitleBar.setActionText(getString(R.string.drive_localvideo_delete));
        mTitleBar.getActionTextView().setVisibility(View.VISIBLE);
        mTitleBar.getActionTextView().setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            toggleListSelectMode(!mAdapter.isSelectMode());
        });

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        mRecyclerView.setAdapter(mAdapter);

        mAdapter.setOnSelectCountListener(count -> {
            mSelectedCount = count;
            if (mSelectedCount > 0) {
                mTvDelete.setEnabled(true);
                mTvDelete.setTextColor(ContextCompat.getColor(this, R.color.drive_dialog_text_primary));
            } else {
                mTvDelete.setEnabled(false);
                mTvDelete.setTextColor(ContextCompat.getColor(this, R.color.drive_dialog_text_hint));
            }
        });

        mRecyclerView.setOnItemListener(new DriveTvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(DriveTvRecyclerView rv, View view, int position) { }

            @Override
            public void onItemSelected(DriveTvRecyclerView rv, View view, int position) { }

            @Override
            public void onItemClick(DriveTvRecyclerView parent, View itemView, int position) {
                VideoInfo video = mAdapter.getItem(position);
                if (video == null) return;
                if (mAdapter.isSelectMode()) {
                    video.setChecked(!video.isChecked());
                    mAdapter.notifyDataSetChanged();
                } else {
                    playVideo(video);
                }
            }
        });

        // 全选 / 取消全选 / 删除（底部菜单）
        mTvAllCheck.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            for (VideoInfo item : mAdapter.getData()) {
                item.setChecked(true);
            }
            mAdapter.notifyDataSetChanged();
        });
        mTvCancelAll.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            cancelAll();
        });
        mTvDelete.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.drive_localvideo_tip)
                    .setMessage(R.string.drive_localvideo_delete_confirm)
                    .setPositiveButton(R.string.drive_btn_confirm,
                            (dialog, which) -> deleteCheckedVideos())
                    .setNegativeButton(R.string.drive_btn_cancel, null)
                    .show();
        });
    }

    /**
     * 通过统一回调把视频交给宿主播放。
     * <p>与本地文件 Spider（LocalFile）一致：把当前文件夹内全部视频组成播放列表，
     * 从用户点击的视频开始环形轮转后整列回传，宿主详情页即可展示完整选集并支持
     * 上下集切换；仅当列表构建异常时才退回单文件回调。</p>
     * <p><b>URL 格式与 DriveActivity 的 LOCAL 驱动保持一致</b>：直接使用本地绝对路径
     * （例如 /storage/emulated/0/Movies/video.mp4），<b>不加</b> file:// 前缀。</p>
     * <p>不加 file:// 前缀的原因：</p>
     * <ul>
     *   <li>ExoPlayer / IjkPlayer 内部播放器对绝对路径与 file:// 都能正常播放，
     *       去掉前缀不影响内置播放器；</li>
     *   <li>切换第三方播放器（MX Player / Reex / Kodi / VLC）时，若 URL 以 file://
     *       开头，Android 7+ 会触发 FileUriExposedException，导致外部播放器调用
     *       静默失败，表现就是"播放界面无法切换第三方播放器"；</li>
     *   <li>与 {@link com.github.tvbox.osc.drive.ui.activity.DriveActivity#buildDriveFileUrl}
     *       中 LOCAL 分支保持一致：那里也是用 {@code new File(...).getAbsolutePath()}
     *       返回纯路径，不加 file:// 前缀，因此"从列表选择文件播放"时第三方播放器
     *       可正常切换。</li>
     * </ul>
     */
    private void playVideo(VideoInfo video) {
        DriveCallback cb = DriveModule.getDriveCallback();
        if (cb == null) {
            Toast.makeText(this, R.string.drive_no_play_callback, Toast.LENGTH_SHORT).show();
            return;
        }
        // 1) 在当前列表中定位点击的视频
        List<VideoInfo> data = mAdapter.getData();
        int currentIndex = -1;
        for (int i = 0; i < data.size(); i++) {
            String p = data.get(i).getPath();
            if (p != null && p.equals(video.getPath())) {
                currentIndex = i;
                break;
            }
        }
        // 2) 定位失败（理论上不会发生）→ 退回单文件回调
        if (currentIndex < 0) {
            String name = video.getDisplayName();
            if (name == null || name.isEmpty()) name = video.getTitle();
            cb.onPlayFile(name, video.getPath(), null, null);
            return;
        }
        // 3) 从点击的视频开始环形轮转，构建整个文件夹的播放列表
        List<DrivePlayItem> playlist = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            VideoInfo info = data.get((currentIndex + i) % data.size());
            if (info == null || info.getPath() == null || info.getPath().isEmpty()) continue;
            String name = info.getDisplayName();
            if (name == null || name.isEmpty()) name = info.getTitle();
            playlist.add(new DrivePlayItem(name, info.getPath()));
        }
        if (playlist.isEmpty()) {
            cb.onPlayFile(video.getDisplayName(), video.getPath(), null, null);
            return;
        }
        cb.onPlayFiles(playlist, 0, null, null);
    }

    /** 多线程删除选中视频，完成后回主线程刷新。 */
    private void deleteCheckedVideos() {
        List<VideoInfo> data = mAdapter.getData();
        new Thread(() -> {
            List<VideoInfo> deleteList = new ArrayList<>();
            for (VideoInfo item : data) {
                if (item.isChecked()) {
                    deleteList.add(item);
                    File file = new File(item.getPath());
                    if (file.delete()) {
                        // 文件增删需要通知系统扫描，否则删除文件后 MediaStore 还能查出来
                        File dir = file.getParentFile();
                        MediaScannerConnection.scanFile(getApplicationContext(),
                                new String[]{dir != null ? dir.getAbsolutePath() : item.getPath()},
                                null, null);
                    }
                }
            }
            data.removeAll(deleteList);
            mMainHandler.post(() -> {
                mAdapter.notifyDataSetChanged();
                toggleListSelectMode(false);
                Toast.makeText(this,
                        getString(R.string.drive_localvideo_deleted, deleteList.size()),
                        Toast.LENGTH_SHORT).show();
            });
        }, "lv-delete-videos").start();
    }

    private void toggleListSelectMode(boolean open) {
        mAdapter.setSelectMode(open);
        mLlMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        // 进入删除模式时清空已选中状态，避免上次的勾选残留
        if (open) {
            cancelAll();
            mAdapter.setSelectMode(true);
        }
    }

    private void cancelAll() {
        for (VideoInfo item : mAdapter.getData()) {
            item.setChecked(false);
        }
        mAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        groupVideosAsync();
    }

    private void groupVideosAsync() {
        new Thread(() -> {
            List<VideoInfo> videoList = VideoScanner.scan(this);
            List<VideoInfo> result = new ArrayList<>();
            String realPath = "";
            for (VideoInfo info : videoList) {
                if (matchesFolder(info)) {
                    result.add(info);
                    // 记录真实的父目录绝对路径（首条命中即可）
                    if (realPath.isEmpty()) {
                        realPath = parentPathOf(info.getPath());
                    }
                }
            }
            final String displayPath = realPath.isEmpty() ? mFolderPath : realPath;
            mMainHandler.post(() -> {
                mAdapter.setNewData(result);
                // 用扫描得到的真实完整路径刷新标题栏（兼容旧调用方没传 folderPath 的情况）
                mFolderPath = displayPath;
                mTitleBar.setSubtitle(displayPath);
            });
        }, "lv-scan-videos").start();
    }

    /** 判断视频是否属于当前打开的文件夹：优先按完整路径筛选，无路径时退回 BUCKET 显示名。 */
    private boolean matchesFolder(VideoInfo info) {
        if (!mFolderPath.isEmpty()) {
            return mFolderPath.equals(parentPathOf(info.getPath()));
        }
        return mBucketDisplayName.equals(info.getBucketDisplayName());
    }

    /** 取视频文件所在目录的绝对路径；异常时返回空串。 */
    private static String parentPathOf(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        try {
            File parent = new File(filePath).getParentFile();
            return parent != null ? parent.getAbsolutePath() : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        if (mAdapter.isSelectMode()) {
            if (mSelectedCount > 0) {
                cancelAll();
            } else {
                toggleListSelectMode(false);
            }
        } else {
            super.onBackPressed();
        }
    }
}
