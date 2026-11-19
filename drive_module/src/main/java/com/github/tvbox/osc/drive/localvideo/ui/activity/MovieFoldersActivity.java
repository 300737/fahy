package com.github.tvbox.osc.drive.localvideo.ui.activity;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.tvbox.osc.drive.DriveModule;
import com.github.tvbox.osc.drive.R;
import com.github.tvbox.osc.drive.bean.DrivePlayItem;
import com.github.tvbox.osc.drive.callback.DriveCallback;
import com.github.tvbox.osc.drive.localvideo.VideoScanner;
import com.github.tvbox.osc.drive.localvideo.bean.VideoFolder;
import com.github.tvbox.osc.drive.localvideo.bean.VideoInfo;
import com.github.tvbox.osc.drive.localvideo.ui.adapter.FolderAdapter;
import com.github.tvbox.osc.drive.localvideo.ui.adapter.LocalVideoAdapter;
import com.github.tvbox.osc.drive.localvideo.util.FastClickCheckUtil;
import com.github.tvbox.osc.drive.localvideo.util.StoragePermission;
import com.github.tvbox.osc.drive.localvideo.widget.DriveTitleBar;
import com.github.tvbox.osc.drive.widget.DriveTvRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地视频浏览页（TV 双栏布局）。
 *
 * <p>布局：左侧目录列表（{@link FolderAdapter}）+ 右侧视频列表（{@link LocalVideoAdapter}）。</p>
 *
 * <p>遥控器交互（TV 核心）：</p>
 * <ul>
 *   <li>焦点在左侧目录上<b>移动</b>（上下键）→ 右侧列表即时切换为该目录的视频，无需按确认键；</li>
 *   <li>在左侧按<b>确认键</b>或<b>方向键右</b> → 焦点跳到右侧视频列表第一项；</li>
 *   <li>在右侧按<b>方向键左</b> → 焦点回到左侧当前目录项；</li>
 *   <li>右侧按确认键 → 播放该视频，并把当前目录全部视频组成播放列表回传宿主
 *       （与 {@link VideoListActivity}、LocalFile Spider 行为一致）；</li>
 *   <li>标题栏右侧"删除"按钮进入右侧多选删除模式（TV 没有长按事件）。</li>
 * </ul>
 *
 * <p>分组 key 用视频所在目录的<b>完整绝对路径</b>而非 BUCKET 显示名，
 * 同名文件夹（如 /sdcard/Movies 与 /usb/Movies）不会被合并，
 * 且标题栏副标题可直接显示当前目录的完整路径。</p>
 */
public class MovieFoldersActivity extends AppCompatActivity {

    private DriveTitleBar mTitleBar;
    private DriveTvRecyclerView mFolderRv;
    private DriveTvRecyclerView mVideoRv;
    private TextView mTvVideoEmpty;

    private View mLlMenu;
    private View mTvAllCheck;
    private View mTvCancelAll;
    private TextView mTvDelete;

    private final FolderAdapter mFolderAdapter = new FolderAdapter();
    private final LocalVideoAdapter mVideoAdapter = new LocalVideoAdapter();

    /** 当前选中的目录（左侧焦点所在项） */
    private int mFolderPos = 0;
    private String mCurrentFolderPath = "";
    private List<VideoFolder> mFolders = new ArrayList<>();

    private boolean mPermissionRequested = false;
    private int mSelectedCount = 0;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.drive_activity_movie_folders);

        mTitleBar = findViewById(R.id.title_bar);
        mFolderRv = findViewById(R.id.rv_folder);
        mVideoRv = findViewById(R.id.rv_video);
        mTvVideoEmpty = findViewById(R.id.tv_video_empty);
        mLlMenu = findViewById(R.id.ll_menu);
        mTvAllCheck = findViewById(R.id.tv_all_check);
        mTvCancelAll = findViewById(R.id.tv_cancel_all_checked);
        mTvDelete = findViewById(R.id.tv_delete);

        mTitleBar.setTitle(getString(R.string.drive_localvideo_title));
        mTitleBar.setOnLeftClickListener(v -> {
            FastClickCheckUtil.check(v);
            onBackPressed();
        });
        // 扫描完成前先占位显示主存储根目录
        mTitleBar.setSubtitle(defaultRootPath());
        // 右侧"删除"按钮：进入/退出多选删除模式
        mTitleBar.setActionText(getString(R.string.drive_localvideo_delete));
        mTitleBar.getActionTextView().setVisibility(View.VISIBLE);
        mTitleBar.getActionTextView().setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            toggleListSelectMode(!mVideoAdapter.isSelectMode());
        });

        initFolderList();
        initVideoList();
        initMenu();
    }

    // ==================== 左侧目录栏 ====================

    private void initFolderList() {
        LinearLayoutManager lm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        mFolderRv.setLayoutManager(lm);
        mFolderRv.setAdapter(mFolderAdapter);
        mFolderRv.setOnItemListener(new DriveTvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(DriveTvRecyclerView rv, View view, int position) { }

            @Override
            public void onItemSelected(DriveTvRecyclerView rv, View view, int position) {
                // TV 关键交互：焦点在目录上移动，右侧立即切换显示该目录的视频
                if (position == mFolderPos && sameFolder(position)) return;
                mFolderPos = position;
                showVideos(mFolderAdapter.getItem(position));
            }

            @Override
            public void onItemClick(DriveTvRecyclerView parent, View itemView, int position) {
                // 确认键：立即填充右侧（否则焦点跳过去时数据还没到位），再把焦点交给右侧
                mFolderPos = position;
                VideoFolder folder = mFolderAdapter.getItem(position);
                showVideos(folder, false);
                if (!focusVideoList()) {
                    Toast.makeText(MovieFoldersActivity.this,
                            R.string.drive_localvideo_empty_folder, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 当前 position 是否仍是已展示的目录，避免重复刷新右侧列表 */
    private boolean sameFolder(int position) {
        VideoFolder f = mFolderAdapter.getItem(position);
        String path = f == null ? "" : (f.getPath() == null ? "" : f.getPath());
        return path.equals(mCurrentFolderPath);
    }

    // ==================== 右侧视频栏 ====================

    private void initVideoList() {
        mVideoRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        mVideoRv.setAdapter(mVideoAdapter);

        mVideoAdapter.setOnSelectCountListener(count -> {
            mSelectedCount = count;
            if (mSelectedCount > 0) {
                mTvDelete.setEnabled(true);
                mTvDelete.setTextColor(ContextCompat.getColor(this, R.color.drive_dialog_text_primary));
            } else {
                mTvDelete.setEnabled(false);
                mTvDelete.setTextColor(ContextCompat.getColor(this, R.color.drive_dialog_text_hint));
            }
        });

        mVideoRv.setOnItemListener(new DriveTvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(DriveTvRecyclerView rv, View view, int position) { }

            @Override
            public void onItemSelected(DriveTvRecyclerView rv, View view, int position) { }

            @Override
            public void onItemClick(DriveTvRecyclerView parent, View itemView, int position) {
                VideoInfo video = mVideoAdapter.getItem(position);
                if (video == null) return;
                if (mVideoAdapter.isSelectMode()) {
                    video.setChecked(!video.isChecked());
                    mVideoAdapter.notifyDataSetChanged();
                } else {
                    playVideo(video);
                }
            }
        });
    }

    /**
     * 展示指定目录的视频，并同步标题栏副标题（完整路径）与空态。
     *
     * <p><b>为什么放在 post 里延后一帧：</b>本方法由左侧焦点变化触发，
     * 若在同一帧内同步执行"右栏 notifyDataSetChanged + 空态切可见性 + 副标题改文字"，
     * 会造成整页 requestLayout，与左栏正在进行的焦点滚动竞争，
     * 结果是左栏焦点丢失并被系统重新分配给第一项（表现为"按下键焦点跑到上面"）。
     * 延后一帧执行可让焦点滚动先完成。</p>
     */
    private void showVideos(VideoFolder folder) {
        showVideos(folder, true);
    }

    /**
     * @param defer true = 延后一帧（焦点在左侧移动时，避免与焦点滚动抢布局）；
     *              false = 立即执行（确认键跳转、扫描完成首次填充，需要马上拿到数据）
     */
    private void showVideos(VideoFolder folder, boolean defer) {
        mCurrentFolderPath = (folder == null || folder.getPath() == null) ? "" : folder.getPath();
        List<VideoInfo> list = folder == null ? new ArrayList<VideoInfo>() : folder.getVideoList();
        if (defer) {
            mVideoRv.post(() -> applyVideos(list));
        } else {
            applyVideos(list);
        }
    }

    private void applyVideos(List<VideoInfo> list) {
        mVideoAdapter.setNewData(list);
        setVisibility(mTvVideoEmpty, list.isEmpty() ? View.VISIBLE : View.GONE);
        updateSubtitle();
        restoreFolderFocusIfLost();
    }

    /** 只在可见性真的变化时才设置，避免无意义的 requestLayout */
    private static void setVisibility(View view, int visibility) {
        if (view != null && view.getVisibility() != visibility) {
            view.setVisibility(visibility);
        }
    }

    /**
     * 焦点兜底：如果刷新过程中焦点丢了，或被系统重新分配到了别的目录项，
     * 把焦点还原到当前目录项（mFolderPos）。
     * 焦点已正确落在当前项、或已移到右侧视频栏、或在多选删除模式下，都不干预。
     */
    private void restoreFolderFocusIfLost() {
        if (mVideoAdapter.isSelectMode()) return;
        View focus = getCurrentFocus();
        if (focus != null) {
            if (isChildOf(mVideoRv, focus)) return;          // 焦点在右栏，正常
            if (isChildOf(mFolderRv, focus)) {
                int pos = mFolderRv.getChildAdapterPosition(focus);
                if (pos == mFolderPos) return;               // 焦点就在当前目录项，正常
            }
        }
        requestFocusAt(mFolderRv, Math.max(0, mFolderPos));
    }

    // ==================== 底部多选菜单 ====================

    private void initMenu() {
        mTvAllCheck.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            for (VideoInfo item : mVideoAdapter.getData()) {
                item.setChecked(true);
            }
            mVideoAdapter.notifyDataSetChanged();
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

    private void toggleListSelectMode(boolean open) {
        mVideoAdapter.setSelectMode(open);
        mLlMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            cancelAll();
            mVideoAdapter.setSelectMode(true);
            // 进入删除模式时把焦点给底部菜单，方便遥控器操作
            mTvAllCheck.post(() -> mTvAllCheck.requestFocus());
        } else {
            focusVideoList();
        }
    }

    private void cancelAll() {
        for (VideoInfo item : mVideoAdapter.getData()) {
            item.setChecked(false);
        }
        mVideoAdapter.notifyDataSetChanged();
    }

    /** 多线程删除选中视频，完成后回主线程刷新并重新扫描目录。 */
    private void deleteCheckedVideos() {
        List<VideoInfo> data = mVideoAdapter.getData();
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
                mVideoAdapter.notifyDataSetChanged();
                toggleListSelectMode(false);
                Toast.makeText(this,
                        getString(R.string.drive_localvideo_deleted, deleteList.size()),
                        Toast.LENGTH_SHORT).show();
                // 删除后目录数量/分组可能变化，重新扫描
                groupVideosAsync();
            });
        }, "lv-delete-videos").start();
    }

    // ==================== 播放（与 VideoListActivity 行为一致） ====================

    /**
     * 通过统一回调把视频交给宿主播放：把当前目录内全部视频组成播放列表，
     * 从点击的那个开始环形轮转后整列回传，宿主详情页可展示完整选集并支持上下集切换。
     * URL 直接用本地绝对路径（不加 file:// 前缀），与 DriveActivity 的 LOCAL 分支保持一致，
     * 避免 Android 7+ 切换第三方播放器时触发 FileUriExposedException。
     */
    private void playVideo(VideoInfo video) {
        DriveCallback cb = DriveModule.getDriveCallback();
        if (cb == null) {
            Toast.makeText(this, R.string.drive_no_play_callback, Toast.LENGTH_SHORT).show();
            return;
        }
        List<VideoInfo> data = mVideoAdapter.getData();
        int currentIndex = -1;
        for (int i = 0; i < data.size(); i++) {
            String p = data.get(i).getPath();
            if (p != null && p.equals(video.getPath())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            String name = video.getDisplayName();
            if (name == null || name.isEmpty()) name = video.getTitle();
            cb.onPlayFile(name, video.getPath(), null, null);
            return;
        }
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

    // ==================== TV 焦点：左右栏互跳 ====================

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View focus = getCurrentFocus();
            if (focus != null) {
                if (isChildOf(mFolderRv, focus) && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (focusVideoList()) return true;
                } else if (isChildOf(mVideoRv, focus) && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (focusFolderList()) return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isChildOf(ViewGroup group, View view) {
        if (group == null || view == null) return false;
        ViewParent p = view.getParent();
        while (p != null) {
            if (p == group) return true;
            p = p.getParent();
        }
        return false;
    }

    /** 焦点移到右侧视频列表（首项），右侧无数据时返回 false */
    private boolean focusVideoList() {
        if (mVideoAdapter.getItemCount() == 0) return false;
        mVideoRv.post(() -> requestFocusAt(mVideoRv, 0));
        return true;
    }

    /** 焦点回到左侧当前目录项 */
    private boolean focusFolderList() {
        if (mFolderAdapter.getItemCount() == 0) return false;
        mFolderRv.post(() -> requestFocusAt(mFolderRv, Math.max(0, mFolderPos)));
        return true;
    }

    /**
     * 让指定位置的 item 拿到焦点；该 item 在屏幕外时先滚动过去，
     * 滚动后的布局要等到后续帧才完成，所以重试几次直到拿到 view。
     */
    private static void requestFocusAt(DriveTvRecyclerView rv, int position) {
        if (rv.getLayoutManager() == null) return;
        View v = rv.getLayoutManager().findViewByPosition(position);
        if (v != null) {
            v.requestFocus();
            return;
        }
        rv.getLayoutManager().scrollToPosition(position);
        postFocusRetry(rv, position, 3);
    }

    private static void postFocusRetry(DriveTvRecyclerView rv, int position, int retry) {
        rv.post(() -> {
            if (rv.getLayoutManager() == null) return;
            View v = rv.getLayoutManager().findViewByPosition(position);
            if (v != null) {
                v.requestFocus();
            } else if (retry > 0) {
                postFocusRetry(rv, position, retry - 1);
            }
        });
    }

    // ==================== 扫描与分组 ====================

    @Override
    protected void onResume() {
        super.onResume();
        if (StoragePermission.has(this)) {
            groupVideosAsync();
        } else if (!mPermissionRequested) {
            mPermissionRequested = true;
            StoragePermission.request(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            groupVideosAsync();
        } else {
            Toast.makeText(this, R.string.drive_localvideo_no_perm, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 异步扫描避免主线程 IO；MediaStore 扫描可能耗时数百毫秒。
     * 扫描完成后重建左侧目录，并恢复右侧为之前选中的目录（首次进入则选中第一项）。
     */
    private void groupVideosAsync() {
        new Thread(() -> {
            List<VideoInfo> videoList = VideoScanner.scan(this);
            Map<String, List<VideoInfo>> map = new LinkedHashMap<>();
            for (VideoInfo info : videoList) {
                // 以父目录绝对路径作为 key；取不到路径时归到"未知"组
                String key = parentPathOf(info.getPath());
                if (key.isEmpty()) key = null;
                List<VideoInfo> group = map.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    map.put(key, group);
                }
                group.add(info);
            }
            List<VideoFolder> folders = new ArrayList<>();
            for (Map.Entry<String, List<VideoInfo>> e : map.entrySet()) {
                String dirPath = e.getKey();
                List<VideoInfo> list = e.getValue();
                String name;
                if (dirPath != null && !dirPath.isEmpty()) {
                    name = new File(dirPath).getName();
                    if (name.isEmpty()) name = dirPath;
                } else {
                    VideoInfo first = list.isEmpty() ? null : list.get(0);
                    name = (first != null && first.getBucketDisplayName() != null)
                            ? first.getBucketDisplayName()
                            : getString(R.string.drive_localvideo_unknown_folder);
                }
                folders.add(new VideoFolder(name, dirPath == null ? "" : dirPath, list));
            }
            mMainHandler.post(() -> {
                mFolders = folders;
                mFolderAdapter.setNewData(folders);
                // 重新扫描后回到原目录（若还在），否则回到第一项
                int pos = indexOfFolder(mCurrentFolderPath);
                if (pos < 0) {
                    pos = 0;
                    mCurrentFolderPath = "";
                }
                mFolderPos = pos;
                showVideos(folders.isEmpty() ? null : folders.get(pos), false);
                if (getCurrentFocus() == null) focusFolderList();
            });
        }, "lv-scan-folders").start();
    }

    private int indexOfFolder(String path) {
        if (path == null || path.isEmpty()) return -1;
        for (int i = 0; i < mFolders.size(); i++) {
            VideoFolder f = mFolders.get(i);
            if (f != null && path.equals(f.getPath())) return i;
        }
        return -1;
    }

    // ==================== 标题栏副标题：完整路径 ====================

    /**
     * 副标题优先显示当前目录的完整路径；
     * 还没有选中任何目录时，显示扫描根目录 + 文件夹/视频数量统计。
     */
    private void updateSubtitle() {
        String text;
        if (!mCurrentFolderPath.isEmpty()) {
            text = mCurrentFolderPath;
        } else {
            int total = 0;
            for (VideoFolder f : mFolders) total += f.getVideoList().size();
            String root = commonRoot(mFolders);
            if (root.isEmpty()) root = defaultRootPath();
            text = getString(R.string.drive_localvideo_root_info, root, mFolders.size(), total);
        }
        // 内容没变就不重设：TextView setText 会 requestLayout，
        // 在焦点移动时触发会顶掉左栏焦点
        if (mTitleBar.getSubtitleVisibility() == View.VISIBLE
                && text.equals(String.valueOf(mTitleBar.getSubtitle()))) {
            return;
        }
        mTitleBar.setSubtitle(text);
    }

    /** 扫描完成前占位显示：主共享存储根目录，例如 /storage/emulated/0 */
    private String defaultRootPath() {
        try {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 取所有视频目录的最长公共父路径作为扫描根目录。
     * 全部视频都在同一存储卷下时即精确的卷根目录（如 /storage/emulated/0）；
     * 跨 U 盘 / SD 卡时返回它们的公共父目录（如 /storage）。
     */
    private static String commonRoot(List<VideoFolder> folders) {
        List<String> dirs = new ArrayList<>();
        for (VideoFolder f : folders) {
            String p = f.getPath();
            if (p != null && !p.isEmpty()) dirs.add(p);
        }
        if (dirs.isEmpty()) return "";
        String prefix = dirs.get(0);
        for (int i = 1; i < dirs.size(); i++) {
            prefix = commonPrefix(prefix, dirs.get(i));
            if (prefix.isEmpty()) break;
        }
        return prefix.isEmpty() ? dirs.get(0) : prefix;
    }

    /** 目录级公共前缀（按 "/" 段比较，避免出现半个目录名） */
    private static String commonPrefix(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return "";
        String[] as = a.split("/");
        String[] bs = b.split("/");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            if (!as[i].equals(bs[i])) break;
            // 绝对路径 split 后首段为空，跳过它，后续每段前面补 "/"
            if (i == 0 && as[i].isEmpty()) continue;
            sb.append("/").append(as[i]);
        }
        return sb.toString();
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
        if (mVideoAdapter.isSelectMode()) {
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
