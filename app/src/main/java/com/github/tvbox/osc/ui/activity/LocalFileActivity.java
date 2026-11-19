package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.ui.adapter.LocalFileAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class LocalFileActivity extends BaseActivity {
    public static final String EXTRA_LIVE = "live";
    /** 批量导入 py 接口源模式：只列出 .py 文件，可多选，返回 EXTRA_PY_PATHS 字符串数组 */
    public static final String EXTRA_PY_MULTI = "py_multi";
    public static final String EXTRA_PY_PATHS = "py_paths";

    private TvRecyclerView fileList;
    private TextView tvTitle;
    private TextView tvPath;
    private TextView tvEmpty;
    private TextView tvPyConfirm;
    private LocalFileAdapter adapter;
    /** 当前目录；null 表示处于存储根列表页（内置存储 / U盘 等入口） */
    private File currentDir;
    private List<File> storageRoots;
    /** 批量导入 py 模式状态 */
    private boolean pyMulti;
    /** 已选中的 py 绝对路径（去重、保持选择顺序） */
    private final LinkedHashSet<String> selectedPyPaths = new LinkedHashSet<String>();

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_local_file;
    }

    @Override
    protected void init() {
        tvTitle = findViewById(R.id.tvTitle);
        tvPath = findViewById(R.id.tvPath);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvPyConfirm = findViewById(R.id.tvPyConfirm);
        fileList = findViewById(R.id.fileList);
        adapter = new LocalFileAdapter();
        fileList.setAdapter(adapter);
        fileList.setLayoutManager(new V7LinearLayoutManager(this, 1, false));
        fileList.setSpacingWithMargins(0, AutoSizeUtils.mm2px(this, 8));
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                File item = (File) adapter.getItem(position);
                if (item != null) open(item);
            }
        });
        pyMulti = getIntent() != null && getIntent().getBooleanExtra(EXTRA_PY_MULTI, false);
        boolean live = getIntent() != null && getIntent().getBooleanExtra(EXTRA_LIVE, false);
        if (pyMulti) {
            tvTitle.setText("批量导入py接口源（点选多个py，选完按确定）");
            adapter.setPyMulti(true);
            tvPyConfirm.setVisibility(View.VISIBLE);
            tvPyConfirm.setText("确定导入(0)");
            tvPyConfirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selectedPyPaths.isEmpty()) {
                        Toast.makeText(LocalFileActivity.this, "请至少选择一个py文件", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent data = new Intent();
                    data.putStringArrayListExtra(EXTRA_PY_PATHS, new ArrayList<String>(selectedPyPaths));
                    setResult(Activity.RESULT_OK, data);
                    finish();
                }
            });
        } else {
            tvTitle.setText(live ? "选择直播配置/视频文件" : "选择点播配置/视频文件");
        }
        initStorageRoots();
        loadRoots();
    }

    /**
     * 带显示名的存储根：根列表页展示"内置存储/U盘名"，而不是底层目录名（0、sda1 等）。
     * File.getName() 可被覆盖，适配器直接展示该名字。
     */
    private static class StorageRoot extends File {
        private final String label;

        StorageRoot(String path, String label) {
            super(path);
            this.label = label;
        }

        @Override
        public String getName() {
            return label;
        }
    }

    private void initStorageRoots() {
        storageRoots = new ArrayList<File>();
        File builtIn = Environment.getExternalStorageDirectory();
        if (builtIn != null) {
            storageRoots.add(new StorageRoot(builtIn.getAbsolutePath(), "内置存储"));
        }
        for (String path : getExternalStoragePaths()) {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                String name = f.getName();
                storageRoots.add(new StorageRoot(path, name == null || name.isEmpty() ? path : "U盘 " + name));
            }
        }
    }

    private void loadRoots() {
        currentDir = null;
        tvPath.setText("存储根目录");
        List<File> files = new ArrayList<File>(storageRoots);
        adapter.setParentDir(null);
        if (pyMulti) adapter.setSelectedPyPaths(selectedPyPaths);
        adapter.setNewData(files);
        tvEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        fileList.postDelayed(new Runnable() {
            @Override
            public void run() {
                fileList.requestFocus();
            }
        }, 100);
    }

    private void loadDir(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "无法打开目录", Toast.LENGTH_SHORT).show();
            return;
        }
        currentDir = dir;
        tvPath.setText(dir.getAbsolutePath());
        List<File> files = new ArrayList<File>();
        boolean atRoot = isStorageRoot(dir);
        if (!atRoot && dir.getParentFile() != null) files.add(dir.getParentFile());
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                // 批量导入模式：目录仍显示（可继续进入），普通文件只留 .py
                if (pyMulti && child.isFile()
                        && !child.getName().toLowerCase(Locale.ROOT).endsWith(".py")) {
                    continue;
                }
                files.add(child);
            }
        }
        adapter.setParentDir(atRoot ? null : dir.getParentFile());
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                if (left.equals(currentDir.getParentFile())) return -1;
                if (right.equals(currentDir.getParentFile())) return 1;
                if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        adapter.setNewData(files);
        tvEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        fileList.postDelayed(new Runnable() {
            @Override
            public void run() {
                fileList.requestFocus();
            }
        }, 100);
    }

    private void open(File item) {
        if (item.isDirectory()) {
            loadDir(item);
            return;
        }
        if (pyMulti) {
            // 批量导入模式：只接受 .py，点选切换选中态，不立即返回
            if (!item.getName().toLowerCase(Locale.ROOT).endsWith(".py")) {
                Toast.makeText(this, "批量导入仅支持py文件", Toast.LENGTH_SHORT).show();
                return;
            }
            String path = item.getAbsolutePath();
            if (selectedPyPaths.contains(path)) {
                selectedPyPaths.remove(path);
            } else {
                selectedPyPaths.add(path);
            }
            adapter.setSelectedPyPaths(selectedPyPaths);
            adapter.notifyDataSetChanged();
            updatePyConfirmText();
            return;
        }
        Intent data = new Intent();
        data.setData(Uri.fromFile(item));
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private void updatePyConfirmText() {
        if (tvPyConfirm != null) {
            tvPyConfirm.setText("确定导入(" + selectedPyPaths.size() + ")");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // 根列表页 → 退出；存储根 → 回根列表；普通目录 → 上一级
            if (currentDir == null) {
                return super.dispatchKeyEvent(event);
            }
            if (!isStorageRoot(currentDir) && currentDir.getParentFile() != null) {
                loadDir(currentDir.getParentFile());
                return true;
            }
            loadRoots();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isStorageRoot(File dir) {
        try {
            String canonical = dir.getCanonicalPath();
            for (File root : storageRoots) {
                if (canonical.equals(root.getCanonicalPath())) return true;
            }
            return false;
        } catch (Throwable ignored) {
            for (File root : storageRoots) {
                if (dir.getAbsolutePath().equals(root.getAbsolutePath())) return true;
            }
            return false;
        }
    }

    // ===================== 外部存储（U盘）枚举，参照本地文件爬虫的通用做法 =====================

    /**
     * 枚举可移动存储挂载点：优先 StorageManager（Android M+，含 OTG U盘），
     * 取不到再解析 /proc/mounts 兜底。
     */
    private List<String> getExternalStoragePaths() {
        Set<String> pathSet = new HashSet<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    List<StorageVolume> volumes = sm.getStorageVolumes();
                    for (StorageVolume volume : volumes) {
                        if (!volume.isRemovable()) continue;
                        File volumeFile = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            volumeFile = volume.getDirectory();
                        } else {
                            // 反射获取路径（兼容旧版本）
                            try {
                                Method getPath = StorageVolume.class.getMethod("getPath");
                                volumeFile = new File((String) getPath.invoke(volume));
                            } catch (Exception ignored) {
                            }
                        }
                        if (volumeFile != null && volumeFile.exists()) {
                            pathSet.add(volumeFile.getAbsolutePath());
                        }
                    }
                }
            } catch (Exception ignored) {
                // 降级到 /proc/mounts
            }
        }
        if (pathSet.isEmpty()) {
            parseProcMounts(pathSet);
        }
        return new ArrayList<String>(pathSet);
    }

    private void parseProcMounts(Set<String> pathSet) {
        String builtInPath = "";
        try {
            builtInPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        } catch (Throwable ignored) {
        }
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 2) continue;
                String device = parts[0];
                String mountPoint = parts[1];
                boolean isExternal = device.contains("/dev/block/vold/")
                        || mountPoint.startsWith("/storage/")
                        || mountPoint.startsWith("/mnt/media_rw/")
                        || mountPoint.startsWith("/mnt/usb/")
                        || mountPoint.startsWith("/mnt/sda");
                if (!isExternal) continue;
                File mountDir = new File(mountPoint);
                if (mountDir.exists() && mountDir.canRead()
                        && !mountPoint.equals(builtInPath)
                        && !mountPoint.startsWith(builtInPath + File.separator)) {
                    pathSet.add(mountPoint);
                }
            }
        } catch (Throwable ignored) {
            // 静默处理，避免崩溃
        }
    }
}
