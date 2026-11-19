package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.MoreSourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.LineSelectAdapter;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.ConfigFetcher;
import com.github.tvbox.osc.util.ConfigRepository;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.ClipboardUtil;
import com.github.tvbox.osc.util.OkGoHelper;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.owen.tvrecyclerview.widget.TvRecyclerView;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 多仓配置对话框（参照ysc的h21，补齐所有缺失功能）
 *
 * 本次新增功能（对照ysc）：
 * - ItemTouchHelper拖拽排序
 * - ItemTouchHelper拖拽排序（长按拖动，参照hj0）
 * - 自动加载第一个仓库线路（参照h21.OooO00o）
 * - 标题长按清空所有仓库（参照cn0 case 1 + y81确认）
 * - 删除确认对话框（参照y81/b21: 删除前弹确认"确定要删除？"）
 * - 选中项前显示ic_select_fill图标 + requestFocus（参照c21）
 * - 宽度自适应（参照h21: isTablet→720mm，否则760mm）
 * - postDelayed标题获取焦点（参照id case 16）
 * - 删除时清除线路缓存+.zip残留（参照b21.OooO0Oo）
 */
public class MoreSourceDialog extends BaseDialog {
    private EditText inputSourceName;
    private EditText inputSourceUrl;
    private ProgressBar playLoading;
    private com.owen.tvrecyclerview.widget.TvRecyclerView storeListView;
    private StoreListAdapter storeAdapter;
    private ArrayList<MoreSourceBean> storeList = new ArrayList<>();
    private MoreSourceBean selectedStore = null;
    private boolean isFetching = false;
    private OnStoreSelectedListener onStoreSelectedListener;
    private boolean storeWasSelected = false;
    private ItemTouchHelper itemTouchHelper;
    private boolean isDragging = false;
    private ArrayList<MoreSourceBean> pendingRefreshList;
    /** 本次会话是否有过仓库列表变更：dismiss只在置位时才落库 */
    private boolean storeListDirty = false;

    /**
     * 本地文件选择回调接口（参照ApiDialog.OnListener.onLocalConfig）
     */
    public interface OnLocalFileListener {
        void onLocalFileSelected();
    }

    private OnLocalFileListener onLocalFileListener;

    public void setOnLocalFileListener(OnLocalFileListener listener) {
        this.onLocalFileListener = listener;
    }

    /** 批量导入py接口源回调（导入后由宿主直接生效） */
    public interface OnBatchImportPyListener {
        void onBatchImportPy();
    }

    private OnBatchImportPyListener onBatchImportPyListener;

    public void setOnBatchImportPyListener(OnBatchImportPyListener listener) {
        this.onBatchImportPyListener = listener;
    }

    /**
     * 仓库选中回调接口（参照ysc a21: 选中仓库后直接触发线路切换）
     */
    public interface OnStoreSelectedListener {
        void onStoreSelected(MoreSourceBean bean);
    }

    public void setOnStoreSelectedListener(OnStoreSelectedListener listener) {
        this.onStoreSelectedListener = listener;
    }

    /**
     * 线路加载回调接口（v1.2新增）
     * 对话框完成线路持久化并关闭后，通知宿主（设置页）执行"返回首页"重启。
     * 重启动作统一由宿主执行，对话框自身不再做任何Activity跳转——
     * 与"配置地址"入口的 restartAppAfterConfigChanged 链路保持一致
     * （该链路实测可正确返回主页，修复v1.0/v1.1"提示返回首页但未能返回"）
     */
    public interface OnLineLoadListener {
        void onLoadLine(String lineUrl, String lineName);
    }

    private OnLineLoadListener onLineLoadListener;

    public void setOnLineLoadListener(OnLineLoadListener listener) {
        this.onLineLoadListener = listener;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPushStore(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_STORE) {
            String url = (String) event.obj;
            String name = event.obj2 != null ? (String) event.obj2 : "";
            if (url != null && !url.isEmpty()) {
                addStoreToLayout(url, name);
            }
        }
    }

    public MoreSourceDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.more_source_dialog_select);
        setCanceledOnTouchOutside(false);

        inputSourceName = findViewById(R.id.input_sourceName);
        inputSourceUrl = findViewById(R.id.input_source_url);
        playLoading = findViewById(R.id.play_loading);
        storeListView = findViewById(R.id.list);

        // 加载已保存的仓库列表
        loadStoreList();

        // 初始化仓库列表适配器
        storeAdapter = new StoreListAdapter();
        storeListView.setAdapter(storeAdapter);

        // ========== 功能: ItemTouchHelper拖拽（参照h21.OooO0OO末尾，只绑定一次） ==========
        attachItemTouchHelper();

        // ========== 功能: 自动加载第一个仓库（参照ysc h21.OooO00o） ==========
        // 对话框打开时，如果api_url为空，自动请求第一个仓库的线路
        autoLoadFirstStore();

        // 本地文件按钮（参照ApiDialog的"选择"按钮，打开本地文件选择器）
        findViewById(R.id.inputLocalFile).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onLocalFileListener != null) {
                    onLocalFileListener.onLocalFileSelected();
                }
            }
        });

        // 批量导入py接口源按钮
        findViewById(R.id.inputBatchImportPy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onBatchImportPyListener != null) {
                    onBatchImportPyListener.onBatchImportPy();
                }
            }
        });

        // 确定按钮 -> 添加新仓库
        findViewById(R.id.inputSubmit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isFetching) {
                    Toast.makeText(getContext(), "正在解析中，请稍候...", Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = inputSourceName.getText().toString().trim();
                String url = inputSourceUrl.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(getContext(), "请输入仓库地址！", Toast.LENGTH_SHORT).show();
                    return;
                }
                addStoreToLayout(url, name);
                inputSourceName.setText("");
                inputSourceUrl.setText("");
            }
        });

        // 存储权限按钮（参照ysc h21构造: 已有权限直接GONE，否则VISIBLE并设点击监听）
        View permissionBtn = findViewById(R.id.permission_Submit);
        if (permissionBtn != null) {
            if (XXPermissions.isGranted(getContext(), Permission.Group.STORAGE)) {
                permissionBtn.setVisibility(View.GONE);
            } else {
                permissionBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        XXPermissions.with(getContext())
                                .permission(Permission.Group.STORAGE)
                                .request(new OnPermissionCallback() {
                                    @Override
                                    public void onGranted(java.util.List<String> permissions, boolean all) {
                                        if (all) {
                                            Toast.makeText(getContext(), "已获得存储权限,按钮已经被隐藏", Toast.LENGTH_SHORT).show();
                                            v.setVisibility(View.GONE);
                                        }
                                    }

                                    @Override
                                    public void onDenied(java.util.List<String> permissions, boolean never) {
                                        if (never) {
                                            Toast.makeText(getContext(), "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                            XXPermissions.startPermissionActivity((Activity) getContext(), permissions);
                                        } else {
                                            Toast.makeText(getContext(), "获取存储权限失败", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                    }
                });
            }
        }

        // 标题相关功能
        TextView title = findViewById(R.id.title);
        if (title != null) {
            // ========== 功能: 标题点击 -> 选中第一个仓库并进入线路切换（参照ysc h21 oO0O0O00 case 1） ==========
            title.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!storeList.isEmpty()) {
                        MoreSourceBean firstStore = storeList.get(0);
                        saveSelectedAndNotify(firstStore);
                    }
                }
            });

            // ========== 功能: postDelayed获取焦点（参照ysc id case 16: title.setFocusable(true)） ==========
            title.setFocusable(true);
            title.postDelayed(new Runnable() {
                @Override
                public void run() {
                    title.setFocusable(true);
                }
            }, 1000L);

            // ========== 功能: 标题长按清空所有仓库（参照ysc cn0 case 1 → y81确认） ==========
            title.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    // 弹出确认对话框（参照ysc y81: "确定要删除？" + 取消/确定）
                    showConfirmDialog(getContext(), "确定要删除？", "取消", "确定",
                        new ConfirmCallback() {
                            @Override
                            public void onConfirm() {
                                clearAllStores();
                            }

                            @Override
                            public void onCancel() {
                            }
                        }
                    );
                    return true;
                }
            });
        }

        // 刷新二维码
        refreshQRCode();
    }

    // ===================== 自动加载第一个仓库（参照ysc h21.OooO00o） =====================
    /**
     * 对话框打开时，如果api_url为空，自动获取第一个仓库的线路
     * 参照ysc h21.OooO00o:
     * 1. 检查api_url是否为空
     * 2. 取custom_store_house第一个元素
     * 3. 保存为custom_store_house_selected
     * 4. clan://地址转换
     * 5. 非本地地址使用缓存模式 FIRST_CACHE_THEN_REQUEST
     * 6. gitcode地址使用特殊UA
     * 7. 发起GET请求（q90回调中会弹出线路选择）
     */
    private void autoLoadFirstStore() {
        String apiUrl = Hawk.get(HawkConfig.API_URL, "");
        if (apiUrl != null && !apiUrl.isEmpty()) {
            return; // api_url不为空，不需要自动加载
        }
        if (storeList.isEmpty()) {
            return; // 仓库列表为空，无法加载
        }
        MoreSourceBean firstStore = storeList.get(0);
        // 保存为选中状态（参照ysc: iu.Oooo00O(moreSourceBean, "custom_store_house_selected")）
        saveSelectedStoreHouse(firstStore);
        // 标记选中
        for (MoreSourceBean bean : storeList) {
            bean.setSelected(bean == firstStore);
        }
        selectedStore = firstStore;

        String rawUrl = firstStore.getSourceUrl();
        if (rawUrl == null || rawUrl.isEmpty()) return;
        final String finalRawUrl = rawUrl;

        // 在子线程中请求线路（与"添加仓库"/"拉取线路"共用同一条抓取管线 ConfigFetcher）
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = ConfigFetcher.fetchSync(finalRawUrl, getContext());
                    // 解析线路列表
                    parseAndShowLines(body, finalRawUrl, firstStore);
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnMainShowToast("自动加载失败: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 解析线路JSON并展示线路选择（参照ysc q90成功回调）
     *
     * 该方法会在autoLoadFirstStore()的裸线程中被直接调用，因此拆成两段：
     * 第一段只做JSON解析（纯计算，任意线程安全）；
     * 第二段涉及Hawk持久化与UI/后续线路加载，统一post回主线程执行，
     * 否则Toast会抛"Can't toast on a thread that has not called Looper.prepare()"。
     */
    private void parseAndShowLines(String body, String url, final MoreSourceBean store) {
        // ===== 第一段：解析（可在原线程执行），统一走 ConfigFetcher.parse：
        // storeHouse / urls（含字符串数组）/ 直接配置 三种形态都能识别。
        // 原先只认 urls，首个仓库内容是 storeHouse 时会静默无提示地什么都不做 =====
        final ConfigFetcher.Payload payload = ConfigFetcher.parse(body);

        // ===== 第二段：UI / 持久化 / 线路加载，切回主线程 =====
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!payload.stores.isEmpty()) {
                    // storeHouse 子仓库：展开合并到仓库列表（与添加仓库入口同一入口）
                    mergeStoreList(payload.stores);
                }
                if (!payload.lines.isEmpty()) {
                    // 保存本地线路到仓库bean（参照ysc: moreSourceBean.setLocalLineUrls），
                    // 然后弹线路选择对话框，由用户自己选择要加载的线路
                    store.setLocalLineUrls(payload.lines);
                    saveStoreList();
                    showInnerLineSelectDialog(payload.lines, url);
                    return;
                }
                if (payload.directConfig) {
                    // 仓库本身就是一份可用配置（sites/spider/lives/wallpaper）→ 直接加载自身
                    String name = store.getSourceName();
                    applyLineAutoLoad(url, name == null || name.isEmpty() ? "直接配置" : name, url);
                    return;
                }
                if (payload.stores.isEmpty()) {
                    Toast.makeText(getContext(), "自动加载失败：仓库内容不是有效配置", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ===================== 修复Bug: 自动加载线路（真正触发加载） =====================

    /** 无来源仓库上下文的入口（来源记为线路自身地址） */
    private void applyLineAutoLoad(final String lineUrl, final String lineName) {
        applyLineAutoLoad(lineUrl, lineName, null);
    }

    /**
     * 自动加载线路（修复"提示自动加载但未能实现自动加载"）
     * 与线路选择对话框点击线路、handleDirectFormat统一的完整流程：
     * 1. 持久化 API_URL / 配置历史 / 线路列表（C12统一落库语义）
     * 2. 直播地址仅在"跟随配置"状态下切换（为空或本就跟随旧配置地址），
     *    用户手工添加的直播源不被覆盖
     * 3. 提示后关闭对话框
     * 4. 通知宿主（设置页）执行"返回首页"重启（v1.2：restartAppAfterConfigChanged
     *    → SettingActivity.onBackPressed，与"配置地址"入口完全一致，详见notifyHostLoadLine）
     * 可在子线程或主线程调用，UI操作内部已切换到主线程
     *
     * @param sourceUrl 线路来源仓库地址（API_LINE_SOURCE语义统一为"来源仓库"，
     *                  与LineSwitchDialog/ApiConfig一致；直接配置场景传null则记线路自身）
     */
    private void applyLineAutoLoad(final String lineUrl, final String lineName, final String sourceUrl) {
        if (lineUrl == null || lineUrl.isEmpty()) return;
        // 1. 持久化线路选择
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        Hawk.put(HawkConfig.API_URL, lineUrl);
        HistoryHelper.setApiHistory(lineUrl);
        // 直播跟随但不覆盖手工直播源（新规格）
        HistoryHelper.followLiveApiOnLineSwitch(lineUrl, oldApi);
        ConfigRepository.addApiHistory(lineUrl, lineName);
        // 2. 合并进线路列表（不覆盖手工添加的线路）+ 记录来源仓库
        HistoryHelper.mergeApiLineList(lineName, lineUrl);
        Hawk.put(HawkConfig.API_LINE_SOURCE,
                sourceUrl != null && !sourceUrl.isEmpty() ? sourceUrl : lineUrl);
        // 3. 提示后关闭对话框，"返回首页"重启交由宿主执行（v1.2核心修复）
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(getContext(), "已自动加载线路: "
                            + (lineName == null || lineName.isEmpty() ? lineUrl : lineName), Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
                storeWasSelected = false;
                dismiss();
                notifyHostLoadLine(lineUrl, lineName);
            }
        }, 500);
    }

    /**
     * 通知宿主（设置页）执行"返回首页"重启（v1.2核心修复）
     * v1.0/v1.1由对话框自己finish旧HomeActivity+startActivity新HomeActivity，
     * 在对话框上下文中实测不可靠，表现为"提示正在返回首页但未能返回"。
     * 现改为通知宿主走 restartAppAfterConfigChanged → SettingActivity.onBackPressed
     * 成熟链路（与"配置地址"入口完全一致，实测可正确返回主页）。
     * 无宿主回调（onLineLoadListener == null）或回调异常时，兜底使用桌面启动Intent重启。
     */
    private void notifyHostLoadLine(final String lineUrl, final String lineName) {
        if (onLineLoadListener != null) {
            try {
                onLineLoadListener.onLoadLine(lineUrl, lineName);
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        restartToHome();
    }

    /**
     * 兜底重启方案（v1.2：仅在无宿主回调或宿主回调异常时使用，正常流程不会走到）
     * 采用与ModelSettingFragment.restartApp()一致的桌面启动Intent机制：
     * getLaunchIntentForPackage + FLAG_ACTIVITY_CLEAR_TOP + System.exit(0)，
     * 结束进程后由系统以全新进程启动应用，HomeActivity/ApiConfig全部按新配置初始化。
     */
    private void restartToHome() {
        ConfigFlow.restartApp(getContext());
    }

    // ===================== 标题长按清空所有仓库（参照ysc cn0 case 1 + y81确认） =====================

    /**
     * 设置本地文件选择后的URL（参照ApiDialog.setLocalApi）
     * 由ModelSettingFragment在onActivityResult中回调
     */
    public void setLocalFileUrl(String url) {
        if (url != null && !url.isEmpty()) {
            inputSourceUrl.setText(url);
        }
    }

    /**
     * 确认对话框回调接口（参照ysc x81）
     */
    public interface ConfirmCallback {
        void onConfirm();
        void onCancel();
    }

    /**
     * 通用确认对话框（参照ysc y81: 标题 + 取消/确定按钮）
     */
    private void showConfirmDialog(Context context, String message, String cancelText, String confirmText,
                                   final ConfirmCallback callback) {
        final android.app.Dialog dialog = new android.app.Dialog(context);
        dialog.setContentView(R.layout.dialog_tip);
        dialog.setCanceledOnTouchOutside(false);
        TextView tipInfo = dialog.findViewById(R.id.tipInfo);
        TextView leftBtn = dialog.findViewById(R.id.leftBtn);
        TextView rightBtn = dialog.findViewById(R.id.rightBtn);
        if (tipInfo != null) tipInfo.setText(message);
        if (leftBtn != null) {
            leftBtn.setText(cancelText);
            leftBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (callback != null) callback.onCancel();
                }
            });
        }
        if (rightBtn != null) {
            rightBtn.setText(confirmText);
            rightBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (callback != null) callback.onConfirm();
                }
            });
        }
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                if (callback != null) callback.onCancel();
            }
        });
        dialog.show();
    }

    /**
     * 清空所有仓库（参照ysc cn0 case 1确认后的操作）
     * 1. 清空仓库列表
     * 2. 清空选中状态
     * 3. 清除线路缓存
     * 4. 清理.zip残留文件
     */
    private void clearAllStores() {
        selectedStore = null;
        storeWasSelected = false;
        // 清除选中（参照ysc b21.OooO0Oo: iu.Oooo0o0("custom_store_house_selected")）
        Hawk.delete(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED);
        // 修复Bug: 同步清除STORE_API记录，避免下次打开时被旧数据迁移逻辑复活
        Hawk.delete(HawkConfig.STORE_API);
        Hawk.delete(HawkConfig.STORE_API_NAME);
        // 清除线路缓存（参照ysc b21.OooO0Oo: x1.OooO00o.OooO0o0(null, null)）
        clearLineCache();
        // 清理.zip残留文件（参照ysc b21.OooO0Oo: wp.OooO0Oo）
        cleanZipResiduals();
        // 清空为空列表并保存（拖拽中则挂起，拖拽结束由finishDragIfNeeded统一落库）
        if (refreshListWithDiffUtil(new ArrayList<MoreSourceBean>())) {
            saveStoreList();
        }
        Toast.makeText(getContext(), "已清空所有仓库", Toast.LENGTH_SHORT).show();
    }

    /**
     * 清除线路缓存（参照ysc b21: x1.OooO00o.OooO0o0(null, null)）
     * 这里通过清除OkGo的缓存目录中LINE_KEY开头的缓存来实现
     */
    private void clearLineCache() {
        ConfigRepository.clearAllLineCache(getContext());
    }

    /**
     * 清理.zip残留文件（参照ysc b21.OooO0Oo: wp.OooO0Oo）
     * 修复：getCacheDir是应用级共享目录，原先按后缀无差别删除——清理动作和
     * "多仓"没有绑定关系，其他模块下载到cache根目录的zip会被连坐。
     * 本流程自身也不会往cache根目录写zip（RemoteServer推送的zip当场解压到外存后即删），
     * 因此只清理确属残留的：超过24小时未修改的zip与_extracted目录
     */
    private void cleanZipResiduals() {
        try {
            File cacheDir = getContext().getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    long residualCutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
                    for (File file : files) {
                        if (file.lastModified() >= residualCutoff) {
                            continue; // 新近文件可能是正在进行的下载，不动
                        }
                        if (file.getName() != null && file.getName().endsWith(".zip")) {
                            file.delete();
                        }
                        // 也清理解压后的目录
                        if (file.isDirectory() && file.getName().contains("_extracted")) {
                            deleteDir(file);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        dir.delete();
    }

    // ===================== 列表刷新 =====================

    /**
     * 刷新列表（与原始TVBoxOS一致：直接clear/addAll/notifyDataSetChanged）
     * 修复：拖拽动画进行中notifyDataSetChanged会让ItemTouchHelper丢失拖拽位置
     * （后台抓取回调随时可能触发刷新），拖拽期间先挂起，拖拽结束再落库+刷新
     *
     * @return false=拖拽进行中，newList已挂起到pendingRefreshList（拖拽结束后由
     *         finishDragIfNeeded应用并统一落库）；此时调用方不得基于storeList落库，
     *         否则保存的是不含本次变更的旧列表，变更会整体丢失
     */
    private boolean refreshListWithDiffUtil(ArrayList<MoreSourceBean> newList) {
        if (isDragging) {
            pendingRefreshList = newList;
            return false;
        }
        storeList.clear();
        storeList.addAll(newList);
        storeAdapter.notifyDataSetChanged();
        return true;
    }

    /**
     * 拖拽挂起期间列表的最新期望状态：后台回调构建新列表必须以此为基础，
     * 而非已过期的storeList——否则新列表会覆盖掉先前挂起的变更
     */
    private ArrayList<MoreSourceBean> latestStoreList() {
        return (isDragging && pendingRefreshList != null) ? pendingRefreshList : storeList;
    }

    private void finishDragIfNeeded() {
        if (!isDragging) return;
        isDragging = false;
        // 先应用挂起的刷新再一次性落库：此前先落库后应用、且应用后不再落库，
        // 挂起期间后台写入的变更永远进不了存储
        if (pendingRefreshList != null) {
            ArrayList<MoreSourceBean> pending = pendingRefreshList;
            pendingRefreshList = null;
            storeList.clear();
            storeList.addAll(pending);
            storeAdapter.notifyDataSetChanged();
        }
        // 修复：拖拽排序原先只改内存（Collections.swap），不落库——
        // 进程在关对话框前被杀（TV盒子常见）顺序就丢了。拖拽结束立即持久化
        saveStoreList();
    }

    // ===================== ItemTouchHelper拖拽排序（参照ysc hj0） =====================

    /**
     * 绑定拖拽排序（参照ysc h21.OooO0OO末尾: new ItemTouchHelper(new hj0(list, adapter)).attachToRecyclerView）
     */
    private void attachItemTouchHelper() {
        if (itemTouchHelper != null) {
            itemTouchHelper.attachToRecyclerView(null);
        }
        itemTouchHelper = new ItemTouchHelper(new StoreItemTouchCallback(storeList, storeAdapter));
        itemTouchHelper.attachToRecyclerView(storeListView);
    }

    /**
     * ItemTouchHelper.Callback实现（参照ysc hj0）
     * 支持四个方向拖拽，不处理滑动删除
     */
    private class StoreItemTouchCallback extends ItemTouchHelper.Callback {
        private final List<MoreSourceBean> dataList;
        private final RecyclerView.Adapter adapter;

        StoreItemTouchCallback(List<MoreSourceBean> dataList, RecyclerView.Adapter adapter) {
            this.dataList = dataList;
            this.adapter = adapter;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            // 参照ysc hj0: makeMovementFlags(15, 0) → 支持上下左右拖拽，不处理滑动
            return makeMovementFlags(15, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            // 参照ysc hj0: return true → 长按触发拖拽
            return true;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            // 参照ysc hj0.onMove: 逐步swap实现拖拽排序
            recyclerView.getParent().requestDisallowInterceptTouchEvent(true);
            int fromPos = viewHolder.getAdapterPosition();
            int toPos = target.getAdapterPosition();
            if (fromPos < toPos) {
                for (int i = fromPos; i < toPos; i++) {
                    Collections.swap(dataList, i, i + 1);
                }
            } else {
                for (int i = fromPos; i > toPos; i--) {
                    Collections.swap(dataList, i, i - 1);
                }
            }
            adapter.notifyItemMoved(fromPos, toPos);
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            // 拖拽开始/结束打标：结束（含取消）时立即把新顺序落库
            if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                // 顺序不能反：finishDragIfNeeded以isDragging为门槛并负责置false，
                // 此前先置false导致其首行return，落库与pendingRefreshList消费成了死代码
                finishDragIfNeeded();
            } else {
                isDragging = true;
            }
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            // 参照ysc hj0.onSwiped: 空实现，不处理滑动删除
        }
    }

    // ===================== 添加仓库到列表 =====================

    /**
     * 添加仓库到列表
     * 如果地址返回 storeHouse 格式，自动解析并将子仓库展开到列表中
     */
    private void addStoreToLayout(final String url, final String name) {
        if (url.isEmpty()) return;
        // 检查是否已存在（参照ysc: arrayList.contains(moreSourceBean)，依赖BaseItem.equals）
        MoreSourceBean checkBean = new MoreSourceBean();
        checkBean.setSourceUrl(url);
        checkBean.setSourceName(name);
        if (storeList.contains(checkBean)) {
            Toast.makeText(getContext(), "该仓库已存在", Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示加载状态
        isFetching = true;
        if (playLoading != null) {
            playLoading.setVisibility(View.VISIBLE);
        }

        // 在子线程中请求并解析仓库地址
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 本地文件（clan:// / file://）与网络地址走同一条抓取+解析管线（ConfigFetcher）
                String strFindResult;
                try {
                    strFindResult = ConfigFetcher.fetchSync(url, getContext());
                } catch (final Exception e) {
                    e.printStackTrace();
                    // 只保存解析成功的：抓取失败不再兜底入库，无效仓库留着
                    // 会在点选时反复失败、拖累线路切换，这里仅提示
                    runOnMainShowToast("仓库获取失败(" + e.getMessage() + ")，未保存");
                    return;
                }
                if (strFindResult == null || strFindResult.isEmpty()) {
                    runOnMainShowToast("仓库内容为空");
                    return;
                }

                // 统一形态分发：ConfigFetcher.parse内部先截取{...}（容忍BOM/前后说明文字，
                // 此前裸new JSONObject(strFindResult)，带BOM的仓库文件直接报"接口无效"），
                // storeHouse / urls / 直接配置(sites|spider|lives|wallpaper) 三态识别，
                // 与点选仓库(loadStoreLinesInternal)、自动加载(parseAndShowLines)、
                // 线路切换(LineSwitchDialog)同一套判据。
                // 此前只认sites，纯lives/spider的配置在这里被误报"你的仓库格式不对"
                final ConfigFetcher.Payload payload = ConfigFetcher.parse(strFindResult);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        isFetching = false;
                        if (playLoading != null) playLoading.setVisibility(View.GONE);
                        if (payload.stores.isEmpty() && payload.lines.isEmpty() && !payload.directConfig) {
                            Toast.makeText(getContext(), "你的仓库格式不对", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // storeHouse子仓库：展开合并到仓库列表（混合文件两边都处理）
                        if (!payload.stores.isEmpty()) {
                            mergeStoreList(payload.stores);
                            if (payload.lines.isEmpty() && payload.directConfig) {
                                // 混合形态（storeHouse+直接配置、无urls）：此入口只展开子仓库，
                                // 配置自身不加载——此前完全静默，用户无从知道这部分被丢掉了
                                Toast.makeText(getContext(), "已展开" + payload.stores.size()
                                        + "个子仓库；该地址本身也是一份配置，未自动加载", Toast.LENGTH_LONG).show();
                            }
                        }
                        if (!payload.lines.isEmpty()) {
                            // urls线路：入库，然后弹线路选择对话框由用户自己选择加载
                            MoreSourceBean storeBean = new MoreSourceBean();
                            storeBean.setSourceUrl(url);
                            storeBean.setSourceName(name);
                            storeBean.setLocalLineUrls(payload.lines);
                            storeBean.setShowDelete(true);
                            // 去重检查（以挂起列表为基础：拖拽中先前挂起的添加也要参与去重）
                            ArrayList<MoreSourceBean> base = latestStoreList();
                            if (!base.contains(storeBean)) {
                                ArrayList<MoreSourceBean> newList = new ArrayList<>(base);
                                newList.add(0, storeBean);
                                // 挂起时不得落库：storeList还是旧列表，存了就把本次添加丢掉
                                if (refreshListWithDiffUtil(newList)) {
                                    saveStoreList();
                                }
                                if (storeListView != null) {
                                    storeListView.scrollToPosition(0);
                                }
                            }
                            // 纯urls仓库与storeHouse+urls混合文件都弹选择对话框：
                            // 混合文件刚把子仓库合并进列表，用户可以选线路直接加载，
                            // 也可以取消选择留在对话框里查看/点选刚添加的仓库
                            showInnerLineSelectDialog(payload.lines, url);
                        } else if (payload.directConfig && payload.stores.isEmpty()) {
                            // 仓库本身就是一份可用配置 → 直接加载自身
                            handleDirectFormat(url, name);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 处理直接配置（sites/spider/lives/wallpaper任一）：仓库地址本身就是一份可用配置，
     * 原先只叫handleSitesFormat且只被sites触发，纯lives/spider配置会被误报"格式不对"
     * C12修复：落库与applyLineAutoLoad统一（API_HISTORY_LIST/API_HISTORY/线路列表/
     * 直播跟随），此前缺LIVE_API_URL与线路列表，直播是否跟随、线路切换展示取决于
     * 用户从哪个形态进入
     */
    private void handleDirectFormat(String url, String name) {
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        Hawk.put(HawkConfig.API_URL, url);
        HistoryHelper.setApiHistory(url);
        // 直播跟随但不覆盖手工直播源（与applyLineAutoLoad同规则）
        HistoryHelper.followLiveApiOnLineSwitch(url, oldApi);
        ConfigRepository.addApiHistory(url, name);
        HistoryHelper.mergeApiLineList(name, url);
        Hawk.put(HawkConfig.API_LINE_SOURCE, url);
        Toast.makeText(getContext(), "系统识别到你推送的可能是线路，已经帮你保存", Toast.LENGTH_LONG).show();
        // v1.2: "返回首页"导航交给宿主执行（与applyLineAutoLoad一致），
        // 对话框自身不再做任何Activity跳转
        storeWasSelected = false;
        try {
            dismiss();
        } catch (Exception ignored) {
        }
        notifyHostLoadLine(url, name);
    }

    /**
     * 合并解析出的仓库到列表（参照ysc h21.OooO0OO: 用LinkedHashMap去重合并）
     * 刷新列表（与原始TVBoxOS一致）
     */
    private void mergeStoreList(ArrayList<MoreSourceBean> parsedStores) {
        if (parsedStores.isEmpty()) {
            Toast.makeText(getContext(), "解析结果为空", Toast.LENGTH_SHORT).show();
            return;
        }
        // 参照ysc h21.OooO0OO的合并逻辑：用LinkedHashMap去重
        // （以latestStoreList为基础：拖拽挂起期间storeList过期，直接合并会丢掉挂起的变更）
        LinkedHashMap<String, MoreSourceBean> existMap = new LinkedHashMap<>();
        for (MoreSourceBean bean : latestStoreList()) {
            existMap.put(bean.getUniKey(), bean);
        }
        LinkedHashMap<String, MoreSourceBean> newMap = new LinkedHashMap<>();
        for (MoreSourceBean bean : parsedStores) {
            newMap.put(bean.getUniKey(), bean);
        }
        int addedCount = 0;
        for (Map.Entry<String, MoreSourceBean> entry : newMap.entrySet()) {
            if (!existMap.containsKey(entry.getKey())) {
                existMap.put(entry.getKey(), entry.getValue());
                addedCount++;
            }
        }
        ArrayList<MoreSourceBean> mergedList = new ArrayList<>(existMap.values());
        // 恢复选中状态
        String selectedKey = selectedStore != null ? selectedStore.getUniKey() : null;
        for (MoreSourceBean bean : mergedList) {
            bean.setSelected(bean.getUniKey().equals(selectedKey));
        }
        // ========== 刷新列表 ==========
        if (refreshListWithDiffUtil(mergedList)) {
            // 在storeList被refreshListWithDiffUtil更新后再保存（挂起时由finishDragIfNeeded统一落库）
            saveStoreList();
        }
        if (storeListView != null) {
            storeListView.scrollToPosition(0);
        }
        Toast.makeText(getContext(), "解析成功，添加了" + addedCount + "个仓库", Toast.LENGTH_SHORT).show();
    }

    /**
     * 在主线程显示Toast
     */
    private void runOnMainShowToast(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                isFetching = false;
                if (playLoading != null) {
                    playLoading.setVisibility(View.GONE);
                }
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 子线程中调用：网络异常时切回主线程，以单个仓库形式添加（兜底）
     */
    private void runOnMainAddSingleStore(final String url, final String name, final String errorMsg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                isFetching = false;
                if (playLoading != null) {
                    playLoading.setVisibility(View.GONE);
                }
                if (errorMsg != null) {
                    Toast.makeText(getContext(), errorMsg, Toast.LENGTH_SHORT).show();
                }
                addSingleStoreDirect(url, name);
            }
        });
    }

    /**
     * 直接以单个仓库形式添加到列表（原始行为）
     */
    private void addSingleStoreDirect(String url, String name) {
        // 使用contains去重（依赖BaseItem.equals；含拖拽挂起列表，避免与挂起的添加重复）
        MoreSourceBean checkBean = new MoreSourceBean();
        checkBean.setSourceUrl(url);
        checkBean.setSourceName(name);
        if (latestStoreList().contains(checkBean)) {
            return;
        }
        MoreSourceBean newBean = new MoreSourceBean();
        newBean.setSourceUrl(url);
        if (name == null || name.isEmpty()) {
            name = "自用仓库" + storeList.size();
        }
        newBean.setSourceName(name);
        newBean.setShowDelete(true);
        // 构建新列表再刷新（不能先add到storeList）
        ArrayList<MoreSourceBean> newList = new ArrayList<>(latestStoreList());
        newList.add(0, newBean);
        if (refreshListWithDiffUtil(newList)) {
            saveStoreList();
        }
        if (storeListView != null) {
            storeListView.scrollToPosition(0);
        }
    }

    /**
     * 保存选中仓库并通知
     */
    private void saveSelectedAndNotify(MoreSourceBean bean) {
        for (MoreSourceBean item : storeList) {
            item.setSelected(false);
        }
        bean.setSelected(true);
        selectedStore = bean;
        saveSelectedStoreHouse(bean);
        Hawk.put(HawkConfig.STORE_API, bean.getSourceUrl());
        if (bean.getSourceName() != null && !bean.getSourceName().isEmpty()) {
            Hawk.put(HawkConfig.STORE_API_NAME, bean.getSourceName());
        }
        // 刷新列表显示选中状态（拖拽中跳过：此时刷新只会用过期storeList覆盖挂起的pendingRefreshList；
        // 选中状态已由saveSelectedStoreHouse/STORE_API单独持久化，不受影响）
        if (!isDragging) {
            refreshListWithDiffUtil(new ArrayList<>(storeList));
        }
        // 每次选择后立即保存列表到Hawk，防止外部代码（如LineSwitchDialog的storeHouse展开）覆盖CUSTOM_STORE_HOUSE导致列表丢失
        saveStoreList();
        Toast.makeText(getContext(), "已选择: " + bean.getSourceName() + "，正在加载...", Toast.LENGTH_SHORT).show();
        // ========== 修复Bug3: 由对话框自行完成“拉取仓库→解析线路→弹线路选择列表”全流程 ==========
        // 原先通过onStoreSelectedListener回调到外部（ModelSettingFragment旧逻辑）处理，
        // 该旧逻辑无HTTP状态码检查，仓库文件不存在时错误文本被当配置解析成空列表，
        // 表现为提示“正在加载”后毫无反应且无任何错误提示。
        // 现改为对话框内部处理，不依赖外部实现，同时保留回调接口兼容。
        loadStoreLinesInternal(bean, 0);
    }

    // ===================== 修复Bug2/Bug3: 对话框内部自包含的线路加载流程 =====================

    /**
     * 内容拉取回调（主线程回调）
     */
    private interface LineFetchCallback {
        void onSuccess(String body);
        void onError(String msg);
    }

    /**
     * 统一的仓库/线路内容拉取方法（子线程请求，主线程回调）
     * 修复点：
     * 1. 检查HTTP状态码，失败时返回包含状态码与错误体的明确信息（本地文件不存在时服务器返回500+"File xxx not found!"）
     * 2. 统一解密（FindResult）与clan://内容修复（clanContentFix/fixContentPath）
     */
    private void fetchUrlContent(final String rawUrl, final LineFetchCallback callback) {
        ConfigFetcher.fetch(rawUrl, new ConfigFetcher.Callback() {
            @Override
            public void onSuccess(String body) {
                callback.onSuccess(body);
            }

            @Override
            public void onError(String msg) {
                callback.onError(msg);
            }
        });
    }

    /**
     * 拉取仓库内容并解析线路（对话框内部流程，替代外部回调逻辑）
     * 支持格式：urls线路列表 / storeHouse子仓库展开 / 单条无名线路重定向(最多3层) / sites直接配置
     */
    private void loadStoreLinesInternal(final MoreSourceBean storeBean, final int depth) {
        if (depth >= 3) {
            Toast.makeText(getContext(), "仓库重定向层数过多，请检查仓库文件是否相互指向", Toast.LENGTH_LONG).show();
            return;
        }
        final String storeUrl = storeBean.getSourceUrl();
        if (storeUrl == null || storeUrl.isEmpty()) {
            Toast.makeText(getContext(), "仓库地址为空", Toast.LENGTH_SHORT).show();
            return;
        }
        fetchUrlContent(storeUrl, new LineFetchCallback() {
            @Override
            public void onSuccess(String body) {
                if (body == null || body.trim().isEmpty()) {
                    Toast.makeText(getContext(), "未获取到线路：仓库内容为空", Toast.LENGTH_LONG).show();
                    return;
                }
                // 统一形态分发（与添加仓库/自动加载/线路切换同一判据）：
                // ConfigFetcher.parse内部已截取{...}，storeHouse/urls/直接配置三态
                ConfigFetcher.Payload payload = ConfigFetcher.parse(body);
                // 格式1: storeHouse子仓库 → 展开到仓库列表
                if (!payload.stores.isEmpty()) {
                    mergeStoreList(payload.stores);
                    // 混合文件顺带带urls时继续走线路流程；纯storeHouse到此为止
                    if (payload.lines.isEmpty()) return;
                }
                // 格式2: urls线路列表
                if (!payload.lines.isEmpty()) {
                    if (payload.lines.size() == 1 && payload.lines.get(0).getSourceName().trim().isEmpty()) {
                        // 单条无名线路：视为重定向，继续解析指向的仓库（带深度保护）
                        MoreSourceBean redirect = new MoreSourceBean();
                        redirect.setSourceName(storeBean.getSourceName());
                        redirect.setSourceUrl(payload.lines.get(0).getSourceUrl());
                        loadStoreLinesInternal(redirect, depth + 1);
                        return;
                    }
                    storeBean.setLocalLineUrls(payload.lines);
                    saveStoreList();
                    showInnerLineSelectDialog(payload.lines, storeUrl);
                    return;
                }
                // 格式3: 直接就是配置（sites/spider/lives/wallpaper）→ 自动加载该仓库自身
                if (payload.directConfig) {
                    String name = storeBean.getSourceName();
                    applyLineAutoLoad(storeUrl, name == null || name.isEmpty() ? "直接配置" : name, storeUrl);
                    return;
                }
                Toast.makeText(getContext(), "未获取到线路：仓库内容不是有效配置格式", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String msg) {
                Toast.makeText(getContext(), "线路获取失败(" + msg + ")，请检查设备存储中仓库文件是否存在", Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 对话框内部的线路选择列表（SelectDialog + LineSelectAdapter）
     * 点击线路 → applyLineAutoLoad 自动加载并返回首页
     *
     * @param storeUrl 线路来源仓库地址（API_LINE_SOURCE语义统一用）
     */
    private void showInnerLineSelectDialog(final ArrayList<MoreSourceBean> lineBeans, final String storeUrl) {
        // 同步到API_LINE_LIST（兼容设置页"线路切换"展示）。
        // 修复：原先整体替换API_LINE_LIST——用户手工添加过的线路不在本仓库的
        // 线路里，每次点开一个仓库就从"线路切换"里消失。改为合并不覆盖
        try {
            ArrayList<String> finalLines = new ArrayList<>();
            for (MoreSourceBean bean : lineBeans) {
                if (bean.getSourceUrl() != null && !bean.getSourceUrl().isEmpty()) {
                    finalLines.add(HistoryHelper.buildApiLine(bean.getSourceName(), bean.getSourceUrl()));
                }
            }
            if (!finalLines.isEmpty()) {
                HistoryHelper.mergeApiLineListAll(finalLines);
            }
        } catch (Exception ignored) {
        }
        String current = Hawk.get(HawkConfig.API_URL, "");
        int idx = 0;
        for (int i = 0; i < lineBeans.size(); i++) {
            if (current.equals(lineBeans.get(i).getSourceUrl())) {
                idx = i;
                break;
            }
        }
        final int selectIdx = idx;
        final SelectDialog<MoreSourceBean> dialog = new SelectDialog<>(getContext());
        dialog.setTip("选择线路");
        // 新增：列表项增加复制按钮后行宽变大，同步增大对话框宽度
        dialog.setDialogWidthMm(720);
        final LineSelectAdapter[] adapterHolder = new LineSelectAdapter[1];
        adapterHolder[0] = new LineSelectAdapter(new LineSelectAdapter.OnLineClickListener() {
            @Override
            public void onLineClick(MoreSourceBean bean, int position) {
                dialog.dismiss();
                applyLineAutoLoad(bean.getSourceUrl(), bean.getSourceName(), storeUrl);
            }

            @Override
            public void onLineDelete(MoreSourceBean bean, int position) {
                ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
                for (int i = 0; i < apiLines.size(); i++) {
                    if (bean.getSourceUrl() != null
                            && bean.getSourceUrl().equals(HistoryHelper.getApiLineUrl(apiLines.get(i)))) {
                        apiLines.remove(i);
                        break;
                    }
                }
                Hawk.put(HawkConfig.API_LINE_LIST, apiLines);
                // 同步清理仓库bean里存的localLineUrls（与LineSwitchDialog.onLineDelete同规则）
                ConfigRepository.removeLineFromAllStores(bean.getSourceUrl());
                adapterHolder[0].getData().remove(position);
                adapterHolder[0].notifyItemRemoved(position);
            }
        });
        adapterHolder[0].setData(lineBeans, selectIdx);
        TvRecyclerView listView = dialog.findViewById(R.id.list);
        if (listView != null) {
            listView.setAdapter(adapterHolder[0]);
            listView.setSelectedPosition(selectIdx);
            if (selectIdx < 10) {
                listView.setSelection(selectIdx);
            }
        }
        dialog.show();
    }

    // ===================== 仓库列表适配器（参照ysc的c21，新增选中图标+焦点管理） =====================

    private class StoreListAdapter extends RecyclerView.Adapter<StoreListAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            ImageView ivDelete;
            ImageView ivCopy;
            View itemView;

            ViewHolder(View itemView) {
                super(itemView);
                this.itemView = itemView;
                tvName = itemView.findViewById(R.id.tvName);
                ivDelete = itemView.findViewById(R.id.ivDelete);
                ivCopy = itemView.findViewById(R.id.ivCopy);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_source_select, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MoreSourceBean bean = storeList.get(position);
            // ========== 参照ysc c21.OooOOOo: 优先显示sourceName，为空时显示sourceUrl ==========
            String displayName = bean.getSourceName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = bean.getSourceUrl();
            }
            // 内部类只能引用final或实际上的final局部变量，displayName被二次赋值后不再是实际最终变量，
            // 因此生成一个final副本供下方匿名内部类（复制按钮）引用
            final String finalDisplayName = displayName;
            holder.tvName.setText(displayName);

            // ========== 参照ysc c21.OooO0OO: 选中项显示ic_select_fill图标 + requestFocus ==========
            if (bean.isSelected()) {
                // 选中时：在文字前添加ic_select_fill图标（参照ysc c21: d31 + ImageSpan）
                Drawable selectIcon = ContextCompat.getDrawable(holder.tvName.getContext(), R.drawable.ic_select_fill);
                if (selectIcon != null) {
                    selectIcon.setBounds(0, 0, selectIcon.getIntrinsicWidth(), selectIcon.getIntrinsicHeight());
                    holder.tvName.setCompoundDrawables(selectIcon, null, null, null);
                    holder.tvName.setCompoundDrawablePadding(dpToPx(8));
                }
                // 选中项文字颜色
                holder.tvName.setTextColor(0xff02f8e1);
                // 参照ysc c21: textView.requestFocus() — 选中项获取焦点。
                // 修复：每次绑定选中项都强抢焦点，列表刷新时遥控器焦点会跳到该行；
                // 仅在焦点尚未落在列表上时（首次展示）请求
                if (storeListView == null || !storeListView.hasFocus()) {
                    holder.tvName.requestFocus();
                }
            } else {
                holder.tvName.setCompoundDrawables(null, null, null, null);
                holder.tvName.setTextColor(0xFFFFFFFF);
            }

            // 点击仓库名 -> 选中该仓库并进入线路切换
            holder.tvName.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveSelectedAndNotify(bean);
                }
            });

            // ========== 删除按钮（全部可删除，按用户要求） ==========
            // 但增加删除确认对话框（参照ysc b21: 删除前弹确认"确定要删除？"）
            if (holder.ivDelete != null) {
                holder.ivDelete.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final int pos = holder.getAdapterPosition();
                        if (pos >= 0 && pos < storeList.size()) {
                            // 确认前先定格删除目标：确认弹窗期间列表可能被后台变更
                            // （如扫码推送新增仓库），确认时仍用点击时的pos会删错行
                            final MoreSourceBean deleteTarget = storeList.get(pos);
                            // ========== 新增: 删除确认对话框（参照ysc y81/b21） ==========
                            showConfirmDialog(getContext(), "确定要删除？", "取消", "确定",
                                new ConfirmCallback() {
                                    @Override
                                    public void onConfirm() {
                                        // 与其它变更点同口径：以latestStoreList为基准定位，
                                        // 拖拽挂起期间storeList过期，按它定位会错行
                                        ArrayList<MoreSourceBean> base = latestStoreList();
                                        int curPos = -1;
                                        for (int i = 0; i < base.size(); i++) {
                                            MoreSourceBean item = base.get(i);
                                            if (item == deleteTarget
                                                    || (deleteTarget.getSourceUrl() != null
                                                    && deleteTarget.getSourceUrl().equals(item.getSourceUrl()))) {
                                                curPos = i;
                                                break;
                                            }
                                        }
                                        if (curPos >= 0) {
                                            deleteStoreAt(curPos);
                                        }
                                    }

                                    @Override
                                    public void onCancel() {
                                    }
                                }
                            );
                        }
                    }
                });
            }

            // ========== 复制按钮（新增：位于删除按钮右侧，复制仓库地址） ==========
            if (holder.ivCopy != null) {
                holder.ivCopy.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String url = bean.getSourceUrl();
                        if (url == null || url.isEmpty()) {
                            url = finalDisplayName;
                        }
                        ClipboardUtil.copyText(getContext(), "仓库地址", url);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return storeList.size();
        }

        private int dpToPx(int dp) {
            return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    /**
     * 删除指定位置的仓库（参照ysc b21.OooO0Oo）
     * 1. 从列表中移除
     * 2. 清除线路缓存
     * 3. 清理.zip残留
     * 4. 保存列表
     */
    private void deleteStoreAt(int pos) {
        // 与其它变更点同口径：以latestStoreList为基准（拖拽挂起期间storeList已过期，
        // 直接基于它删会把挂起的变更整体丢掉）
        ArrayList<MoreSourceBean> base = latestStoreList();
        if (pos < 0 || pos >= base.size()) return;
        MoreSourceBean removedBean = base.get(pos);
        // 如果删除的是当前选中的，清除选中状态
        if (removedBean.isSelected()) {
            selectedStore = null;
            storeWasSelected = false;
            Hawk.delete(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED);
        }
        // ========== 修复Bug: 删除后立即持久化，并同步清理选中仓库记录 ==========
        // 同步清理STORE_API记录，避免loadStoreList的旧数据迁移把已删除的仓库复活
        if (removedBean.getSourceUrl() != null
                && removedBean.getSourceUrl().equals(Hawk.get(HawkConfig.STORE_API, ""))) {
            Hawk.delete(HawkConfig.STORE_API);
            Hawk.delete(HawkConfig.STORE_API_NAME);
        }
        // 构建删除后的新列表（不能先remove再刷新）
        ArrayList<MoreSourceBean> newList = new ArrayList<>(base);
        newList.remove(pos);
        // 定向清除该仓库的线路缓存（此前是清空全部线路缓存，其余仓库下次打开都得重新下载）
        try {
            ConfigRepository.deleteLineCache(getContext(), ConfigFetcher.lineCacheKey(removedBean.getSourceUrl()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 清理.zip残留（参照ysc b21: 如果sourceName包含.zip，清理对应目录）
        if (removedBean.getSourceName() != null && removedBean.getSourceName().contains(".zip")) {
            String dirName = removedBean.getSourceName().substring(0,
                    removedBean.getSourceName().lastIndexOf(".zip"));
            try {
                File cacheDir = getContext().getCacheDir();
                if (cacheDir != null) {
                    File zipDir = new File(cacheDir, dirName);
                    if (zipDir.exists()) {
                        deleteDir(zipDir);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 刷新列表（会更新storeList）
        if (refreshListWithDiffUtil(newList)) {
            // ========== 修复Bug: 删除后立即保存列表，否则退出后删除不生效 ==========
            saveStoreList();
        }
        // 如果列表已全部删空，同步清空选中状态，避免残留记录把已删仓库带回来
        // （拖拽挂起时storeList未更新，以本次删除结果newList为准）
        if (newList.isEmpty()) {
            Hawk.delete(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED);
            Hawk.delete(HawkConfig.STORE_API);
            Hawk.delete(HawkConfig.STORE_API_NAME);
        }
    }

    // ===================== 数据存储方法 =====================

    private void loadStoreList() {
        boolean hasStoredList = ConfigRepository.hasStoreHouseList();
        ArrayList<MoreSourceBean> saved = ConfigRepository.getStoreHouseList();
        if (!saved.isEmpty()) {
            storeList.clear();
            storeList.addAll(saved);
        }
        // 仅当从未保存过仓库列表（首次使用）时，才从旧的 STORE_API 迁移。
        // 列表存在但为空说明用户已把仓库全部删除，不能再从 STORE_API 迁移复活。
        if (!hasStoredList && storeList.isEmpty()) {
            String oldStoreApi = Hawk.get(HawkConfig.STORE_API, "");
            if (oldStoreApi != null && !oldStoreApi.isEmpty()) {
                MoreSourceBean oldBean = new MoreSourceBean();
                oldBean.setSourceName(Hawk.get(HawkConfig.STORE_API_NAME, ""));
                oldBean.setSourceUrl(oldStoreApi);
                storeList.add(oldBean);
                saveStoreList();
            }
        }
        loadSelectedStore();
    }

    private void saveStoreList() {
        storeListDirty = true;
        ConfigRepository.saveStoreHouseList(storeList);
    }

    private void loadSelectedStore() {
        MoreSourceBean selected = ConfigRepository.getSelectedStoreHouse();
        selectedStore = selected;
        if (selected == null) return;
        for (MoreSourceBean bean : storeList) {
            bean.setSelected(selected.getUniKey().equals(bean.getUniKey()));
        }
    }

    private void saveSelectedStoreHouse(MoreSourceBean bean) {
        ConfigRepository.saveSelectedStoreHouse(bean);
    }

    private void refreshQRCode() {
        String address = ControlManager.get().getAddress(false);
        ImageView ivQRCode = findViewById(R.id.qrCode);
        TextView jumpWeb = findViewById(R.id.jump_web);
        if (ivQRCode != null) {
            ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, AutoSizeUtils.mm2px(getContext(), 200), AutoSizeUtils.mm2px(getContext(), 200)));
        }
        if (jumpWeb != null) {
            jumpWeb.setText("扫码远程推送\n" + address);
        }
    }

    @Override
    public void show() {
        // 参照ysc h21.show: lm.OooO0O0().OooOO0(this) — 注册EventBus
        // show()被连续调用时register会抛EventBusException，这里与dismiss的unregister同样容忍
        try {
            EventBus.getDefault().register(this);
        } catch (Exception ignored) {
        }
        super.show();
    }

    @Override
    public void dismiss() {
        // 参照ysc h21.dismiss: lm.OooO0O0().OooOO0o(this) — 反注册EventBus
        try {
            EventBus.getDefault().unregister(this);
        } catch (Exception ignored) {
        }
        // 修复Bug: 空列表也要保存（含清空语义）。原来仅在非空时保存，导致用户把仓库
        // 全部删除后，Hawk中仍保留旧列表，重新打开后已删除的线路全部复活。
        // 但只在本会话有过增删改时落库：读取侧反序列化拿到空列表（R8混淆残留、
        // 类型不匹配等脏数据）时对话框会以空列表呈现，此时按返回键若仍无条件保存，
        // 会把仓库列表整体清空且无备份
        if (storeListDirty) {
            saveStoreList();
        }
        // 修复Bug3: 移除dismiss时的onStoreSelected回调触发。
        // 线路加载已改为点击仓库时由对话框内部完成（loadStoreLinesInternal），
        // 不再依赖外部（ModelSettingFragment）的旧逻辑，避免双重加载或静默失败。
        super.dismiss();
    }

    public void setOnListener(OnListener listener) {
        // 保留接口兼容
    }

    public interface OnListener {
        void onAdd(MoreSourceBean bean);
    }
}