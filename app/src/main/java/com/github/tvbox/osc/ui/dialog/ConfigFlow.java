package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.LocalFileActivity;
import com.github.tvbox.osc.util.JsSpiderLoader;
import com.github.tvbox.osc.util.LiveSourceLoader;
import com.github.tvbox.osc.util.LocalVideoLauncher;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.PySpiderLoader;
import com.github.tvbox.osc.util.UriUtils;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 「多仓配置 / 线路切换」的统一入口。
 *
 * <p>主界面（UserFragment）与设置页（ModelSettingFragment）的两个按钮，
 * 此前各自 new 对话框、各自实现权限申请 / 本地文件回填 / hideSysBar /
 * 重启宿主，实现已经分叉（主界面入口缺权限申请、缺 hideSysBar、
 * 且两个入口都没接入线路加载回调，只能走 System.exit 兜底）。
 * 现在两侧都调用这里，差异只体现在 {@link Host} 的实现上。</p>
 */
public final class ConfigFlow {

    public static final int REQUEST_LOCAL_STORE_FILE = 1002;
    /** 批量导入py接口源：多选本地py，导入即生效 */
    public static final int REQUEST_BATCH_IMPORT_PY = 1006;

    private ConfigFlow() {
    }

    /**
     * 宿主能力抽象：对话框只负责 UI 与业务，宿主相关的动作（权限、文件选择器、
     * 系统栏、重启）全部由宿主自己完成。
     */
    public interface Host {
        /**
         * 宿主 Activity。
         * 注意方法名不能叫 getActivity()，否则与 Fragment.getActivity() 的返回类型冲突。
         */
        Activity getHostActivity();

        /** 打开本地文件选择器（宿主自己 startActivityForResult） */
        void startLocalFilePicker(int requestCode);

        /** 打开py多选选择器（批量导入py接口源） */
        void startPyMultiPicker(int requestCode);

        /** 对话框关闭后恢复沉浸式系统栏 */
        void hideSysBar();

        /** 线路已持久化完成，宿主执行重启/返回首页 */
        void onConfigLoaded(String url, String name);
    }

    // 保存当前打开的对话框，供本地文件选择结果回填（打开新对话框/重启时会置空）。
    // 用WeakReference：LineSwitchDialog.show()不调super.show()、窗口不存在，
    // onDismiss永远不会触发，静态强引用会把Dialog连同Activity钉在内存里
    private static WeakReference<MoreSourceDialog> sStoreDialogRef;
    private static WeakReference<LineSwitchDialog> sLineDialogRef;

    // ===================== 打开多仓配置对话框 =====================

    public static void openStoreHouse(final Host host) {
        Activity activity = host == null ? null : host.getHostActivity();
        if (activity == null || activity.isFinishing()) return;
        final MoreSourceDialog dialog = new MoreSourceDialog(activity);
        sStoreDialogRef = new WeakReference<>(dialog);
        sLineDialogRef = null;
        dialog.setOnLocalFileListener(new MoreSourceDialog.OnLocalFileListener() {
            @Override
            public void onLocalFileSelected() {
                requestLocalFile(host);
            }
        });
        dialog.setOnBatchImportPyListener(new MoreSourceDialog.OnBatchImportPyListener() {
            @Override
            public void onBatchImportPy() {
                requestBatchImportPy(host);
            }
        });
        dialog.setOnLineLoadListener(new MoreSourceDialog.OnLineLoadListener() {
            @Override
            public void onLoadLine(String lineUrl, String lineName) {
                if (host != null) host.onConfigLoaded(lineUrl, lineName);
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                if (host != null) host.hideSysBar();
                if (sStoreDialogRef != null) sStoreDialogRef.clear();
            }
        });
        dialog.show();
    }

    // ===================== 打开线路切换对话框 =====================

    public static void openLineSwitch(final Host host) {
        Activity activity = host == null ? null : host.getHostActivity();
        if (activity == null || activity.isFinishing()) return;
        final LineSwitchDialog dialog = new LineSwitchDialog(activity);
        sLineDialogRef = new WeakReference<>(dialog);
        sStoreDialogRef = null;
        dialog.setOnLocalFileListener(new MoreSourceDialog.OnLocalFileListener() {
            @Override
            public void onLocalFileSelected() {
                requestLocalFile(host);
            }
        });
        dialog.setOnLineSwitchedListener(new LineSwitchDialog.OnLineSwitchedListener() {
            @Override
            public void onLineSwitched(String newApiUrl) {
                // LineSwitchDialog没有自身窗口、dismiss监听不会触发，
                // 线路切换完成即其生命周期终点，这里清引用防泄漏
                if (sLineDialogRef != null) sLineDialogRef.clear();
                if (host != null) host.onConfigLoaded(newApiUrl, null);
            }
        });
        // 与openStore对齐：用户返回键取消对话框时恢复沉浸模式，
        // 并让设置页hideSysBar顺带刷新"多仓地址"右侧文本
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                if (host != null) host.hideSysBar();
                if (sLineDialogRef != null) sLineDialogRef.clear();
            }
        });
        dialog.show();
    }

    // ===================== 本地文件选择（权限 → 宿主选择器 → 结果回填） =====================

    private static void requestLocalFile(final Host host) {
        Activity activity = host == null ? null : host.getHostActivity();
        if (activity == null) return;
        if (!XXPermissions.isGranted(activity, Permission.Group.STORAGE)) {
            Toast.makeText(activity, "请选择文件前需要先授予存储权限", Toast.LENGTH_SHORT).show();
            XXPermissions.with(activity)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(List<String> permissions, boolean all) {
                            if (all) {
                                Toast.makeText(activity, "已获得存储权限", Toast.LENGTH_SHORT).show();
                                host.startLocalFilePicker(REQUEST_LOCAL_STORE_FILE);
                            }
                        }

                        @Override
                        public void onDenied(List<String> permissions, boolean never) {
                            if (never) {
                                Toast.makeText(activity, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                XXPermissions.startPermissionActivity(activity, permissions);
                            } else {
                                Toast.makeText(activity, "获取存储权限失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return;
        }
        host.startLocalFilePicker(REQUEST_LOCAL_STORE_FILE);
    }

    /** 批量导入py：申请存储权限后打开py多选页面 */
    private static void requestBatchImportPy(final Host host) {
        Activity activity = host == null ? null : host.getHostActivity();
        if (activity == null) return;
        if (!XXPermissions.isGranted(activity, Permission.Group.STORAGE)) {
            Toast.makeText(activity, "选择py文件前需要先授予存储权限", Toast.LENGTH_SHORT).show();
            XXPermissions.with(activity)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(List<String> permissions, boolean all) {
                            if (all && host != null) host.startPyMultiPicker(REQUEST_BATCH_IMPORT_PY);
                        }

                        @Override
                        public void onDenied(List<String> permissions, boolean never) {
                            if (never) {
                                Toast.makeText(activity, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                XXPermissions.startPermissionActivity(activity, permissions);
                            } else {
                                Toast.makeText(activity, "获取存储权限失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return;
        }
        host.startPyMultiPicker(REQUEST_BATCH_IMPORT_PY);
    }

    /**
     * 宿主的 onActivityResult 转发到这里。
     *
     * @return true 表示该请求码已被本流程消费
     */
    public static boolean handleActivityResult(Host host, int requestCode, int resultCode, Intent data) {
        // 批量导入py结果：单独处理，不占用单文件选择流程
        if (requestCode == REQUEST_BATCH_IMPORT_PY) {
            handleBatchImportPyResult(host, resultCode, data);
            return true;
        }
        if (requestCode != REQUEST_LOCAL_STORE_FILE) return false;
        Activity activity = host == null ? null : host.getHostActivity();
        if (activity == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        // LocalFileActivity 返回 file:// 真实路径；视频文件走"同文件夹整列播放"，
        // 其余（配置json/txt等）仍走原有配置解析分发
        if (LocalVideoLauncher.tryStartLocalVideoFromResult(activity, data.getData())) {
            return true;
        }
        // 本地直播源分流：txt/m3u/m3u8 且内容为直播格式时写入 LIVE_API_URL（与推送直播源同链路）
        if (LiveSourceLoader.trySetLocalLiveSource(activity, data.getData())) {
            return true;
        }
        String api = localConfigToApi(activity, data.getData());
        if (api.isEmpty()) {
            Toast.makeText(activity, "读取本地配置失败", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (sStoreDialogRef != null) {
            MoreSourceDialog storeDialog = sStoreDialogRef.get();
            if (storeDialog != null) storeDialog.setLocalFileUrl(api);
        }
        if (sLineDialogRef != null) {
            LineSwitchDialog lineDialog = sLineDialogRef.get();
            if (lineDialog != null) lineDialog.setLocalFileUrl(api);
        }
        return true;
    }

    /**
     * 批量导入py结果：打包成本地站点配置地址，交给宿主 onConfigLoaded 直接生效。
     */
    private static void handleBatchImportPyResult(Host host, int resultCode, Intent data) {
        Activity activity = host == null ? null : host.getHostActivity();
        if (activity == null) return;
        if (resultCode != Activity.RESULT_OK || data == null) return;
        ArrayList<String> pyPaths = data.getStringArrayListExtra(LocalFileActivity.EXTRA_PY_PATHS);
        if (pyPaths == null || pyPaths.isEmpty()) {
            Toast.makeText(activity, "未选择py文件", Toast.LENGTH_SHORT).show();
            return;
        }
        String api = PySpiderLoader.batchPyListConfigToApi(activity, pyPaths);
        if (api == null || api.isEmpty()) {
            Toast.makeText(activity, "批量导入失败", Toast.LENGTH_SHORT).show();
            return;
        }
        // 持久化：与正常线路加载同一套（写配置地址/历史、解除线路来源关系）
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        Hawk.put(HawkConfig.API_URL, api);
        HistoryHelper.setApiHistory(api);
        if (!HistoryHelper.isApiLineHistory(api)) {
            HistoryHelper.clearApiLineSource();
        }
        Toast.makeText(activity, "已导入 " + pyPaths.size() + " 个py接口源，正在生效", Toast.LENGTH_SHORT).show();
        // 关闭当前对话框（宿主会重启/刷新）
        if (sStoreDialogRef != null) {
            MoreSourceDialog d = sStoreDialogRef.get();
            if (d != null) d.dismiss();
        }
        if (host != null) host.onConfigLoaded(api, null);
    }

    /** 宿主的 startLocalFilePicker 可直接复用这个 Intent 构造 */
    public static Intent buildLocalFileIntent(Context context) {
        Intent intent = new Intent(context, LocalFileActivity.class);
        intent.putExtra(LocalFileActivity.EXTRA_LIVE, false);
        return intent;
    }

    // ===================== URI → clan:// 地址 =====================

    /**
     * 把文件选择器返回的 URI 转成 TVBox 可识别的配置地址。
     * 与主界面/设置页原先两份实现行为一致（绝对路径优先，取不到就复制到本应用缓存目录）。
     * 点选 .py 爬虫文件时走 {@link PySpiderLoader} 自动装载同目录全部爬虫为本地站点配置，
     * 点选 .js 爬虫文件时走 {@link JsSpiderLoader} 同样处理。
     */
    public static String localConfigToApi(Context context, Uri uri) {
        // .py 爬虫分流：生成"同目录整包装载"的本地站点配置地址；非 .py 或生成失败回落原有单文件逻辑
        String pyApi = PySpiderLoader.localPyDirConfigToApi(context, uri);
        if (!pyApi.isEmpty()) return pyApi;
        // .js 爬虫分流：与 .py 同一套模式，扫描同目录全部 .js 爬虫包装成本地站点配置
        String jsApi = JsSpiderLoader.localJsDirConfigToApi(context, uri);
        if (!jsApi.isEmpty()) return jsApi;
        String path = UriUtils.getPathFromUri(context, uri);
        boolean copied = false;
        if (path == null || path.isEmpty()) {
            path = copyUriToLocalConfig(context, uri);
            copied = true;
        }
        if (path == null || path.isEmpty()) {
            return "";
        }
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(storageRoot)) {
            return UriUtils.toClanOrFileApi(path);
        }
        // 原始路径在外部存储之外时复制一份再转；已是复制产物则再复制落到同一路径，徒劳无益
        if (!copied) {
            path = copyUriToLocalConfig(context, uri);
            if (path != null && path.startsWith(storageRoot)) {
                return UriUtils.toClanOrFileApi(path);
            }
        }
        return "";
    }

    private static String copyUriToLocalConfig(Context context, Uri uri) {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = context.getContentResolver().openInputStream(uri);
            if (input == null) return "";
            File dir = new File(context.getExternalCacheDir(), "config");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, getDisplayName(context, uri));
            output = new FileOutputStream(file);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return file.getAbsolutePath();
        } catch (Throwable th) {
            th.printStackTrace();
            return "";
        } finally {
            try {
                if (output != null) output.close();
            } catch (Throwable ignored) {
            }
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String getDisplayName(Context context, Uri uri) {
        String name = "local_config.json";
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String displayName = cursor.getString(index);
                    if (displayName != null && !displayName.isEmpty()) {
                        name = displayName;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }

    // ===================== 兜底重启（仅无宿主回调时使用） =====================

    /**
     * 与 ModelSettingFragment.restartApp 一致的桌面启动 Intent 重启：
     * 结束进程后由系统以全新进程启动应用，HomeActivity/ApiConfig 全部按新配置初始化。
     */
    public static void restartApp(Context context) {
        try {
            if (sStoreDialogRef != null) sStoreDialogRef.clear();
            if (sLineDialogRef != null) sLineDialogRef.clear();
            if (context == null) return;
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(intent);
                if (context instanceof Activity) {
                    ((Activity) context).finish();
                }
                System.exit(0);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
