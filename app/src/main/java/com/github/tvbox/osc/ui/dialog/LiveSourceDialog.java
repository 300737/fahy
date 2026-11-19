package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.LiveSourceBean;
import com.github.tvbox.osc.bean.MoreSourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.LiveSourceSelectAdapter;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ClipboardUtil;
import com.github.tvbox.osc.util.ConfigFetcher;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 直播源配置对话框
 * 左侧：二维码推送
 * 右侧：直播源分支列表（选择+删除）
 * 底部：手动添加输入框
 */
public class LiveSourceDialog extends BaseDialog {
    private EditText inputSourceName;
    private EditText inputSourceUrl;
    private ProgressBar playLoading;
    private TvRecyclerView recyclerView;
    private LiveSourceSelectAdapter adapter;
    private JsonArray livesGroups;
    // 手动添加/扫码推送的直播源（独立持久化，见 HawkConfig.LIVE_MANUAL_LIST）
    private JsonArray manualGroups = new JsonArray();
    private ArrayList<String> nameList = new ArrayList<>();
    private OnListener listener;
    private OnBranchSelectListener branchSelectListener;
    private boolean isAdding = false;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLivePush(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_LIVE_PUSH) {
            if (playLoading != null) {
                playLoading.setVisibility(View.GONE);
            }
            String address = (String) event.obj;
            if (address != null && !address.isEmpty()) {
                String name = event.obj2 != null ? (String) event.obj2 : "";
                // 与手动添加完全同一流程：拉取验证→保存→加载，解析失败不落库
                addLiveSourceRequest(name, address);
            }
        }
    }

    public LiveSourceDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.live_source_dialog_select);
        setCanceledOnTouchOutside(false);

        inputSourceName = findViewById(R.id.input_sourceName);
        inputSourceUrl = findViewById(R.id.input_source_url);
        playLoading = findViewById(R.id.play_loading);
        recyclerView = findViewById(R.id.list);

        // 初始化直播源分支列表
        initLiveBranchList();

        // 确定按钮（v1.4重写：拉取验证→加入列表→持久化→选中→立即加载→才提示已保存；
        // 原逻辑只回调宿主写LIVE_API_URL，不验证也不加入列表，
        // 表现为"提示直播源已保存但列表未显示"）
        findViewById(R.id.inputSubmit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String name = inputSourceName.getText().toString().trim();
                final String address = inputSourceUrl.getText().toString().trim();
                if (address.isEmpty()) {
                    Toast.makeText(getContext(), "请输入直播源地址！", Toast.LENGTH_SHORT).show();
                    return;
                }
                addLiveSourceRequest(name, address);
            }
        });

        // 存储权限按钮
        View permissionBtn = findViewById(R.id.permission_Submit);
        if (permissionBtn != null) {
            permissionBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (XXPermissions.isGranted(getContext(), Permission.Group.STORAGE)) {
                        Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                        v.setVisibility(View.GONE);
                    } else {
                        XXPermissions.with(getContext())
                                .permission(Permission.Group.STORAGE)
                                .request(new OnPermissionCallback() {
                                    @Override
                                    public void onGranted(java.util.List<String> permissions, boolean all) {
                                        if (all) {
                                            Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
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
                }
            });
        }

        // 刷新二维码
        refreshQRCode();

        // v1.4: 已授予存储权限时自动隐藏"存储权限"按钮（修复授权后字样不消失）
        hidePermissionBtnIfGranted();
    }

    /**
     * 初始化直播源分支列表
     */
    private void initLiveBranchList() {
        livesGroups = Hawk.get(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
        // v1.9自愈：v1.6~v1.8期间点击内置分支可能把相对路径（如 ./lib/interface.txt）
        // 写入直播地址，导致后续加载/解析失败。合法直播地址必带协议（://），
        // 发现无协议残留则自动清空，让系统回到正常回退/补全逻辑。
        String savedLiveUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
        if (!savedLiveUrl.isEmpty() && !savedLiveUrl.contains("://")) {
            Hawk.put(HawkConfig.LIVE_API_URL, "");
        }
        // 合并手动添加/推送的直播源（按url去重）：LIVE_GROUP_LIST每次配置加载
        // 都会被parseLiveText清空/parseLiveJson覆盖（ApiConfig原逻辑，不动），
        // 手动项以 LIVE_MANUAL_LIST 为准持久化，这里仅合并到内存展示列表
        loadManualGroups();
        for (int i = 0; i < manualGroups.size(); i++) {
            JsonObject m = manualGroups.get(i).getAsJsonObject();
            String mUrl = m.has("url") ? m.get("url").getAsString() : "";
            if (mUrl.isEmpty()) continue;
            boolean exists = false;
            for (int j = 0; j < livesGroups.size(); j++) {
                JsonObject e = livesGroups.get(j).getAsJsonObject();
                String eUrl = e.has("url") ? e.get("url").getAsString() : "";
                if (mUrl.equals(eUrl)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) livesGroups.add(m);
        }
        int currentIndex = ApiConfig.getLiveGroupIndex();
        if (currentIndex >= livesGroups.size()) currentIndex = 0;
        final int selectIdx = currentIndex;

        String storeName = getStoreName();

        // 构建显示名称列表
        nameList.clear();
        for (int i = 0; i < livesGroups.size(); i++) {
            JsonObject obj = livesGroups.get(i).getAsJsonObject();
            String entryName = obj.has("name") ? obj.get("name").getAsString().trim() : "";
            nameList.add(buildDisplayName(entryName, storeName, i));
        }

        // 设置适配器
        adapter = new LiveSourceSelectAdapter(
            new LiveSourceSelectAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(String name, int position) {
                    // v1.9: 回退到 v1.4 原版行为——只切序号+重新加载当前直播地址，
                    // 不把列表项地址写入直播地址（v1.6 的写地址方案会把内置分支的
                    // 相对路径 ./lib/xxx 写入直播地址，导致后续解析失败）。
                    // 直播地址为空时 loadLiveConfig 自动回退主配置，
                    // 由 parseLiveJson 按序号选中分支，切换同样生效。
                    if (position < 0 || position >= livesGroups.size()) {
                        return;
                    }
                    // 手动源：点击时把地址写入直播地址（手动源必带协议，不会触发
                    // v1.9自愈的相对路径问题），切换才能立即生效
                    if (isManualEntry(position)) {
                        JsonObject clicked = livesGroups.get(position).getAsJsonObject();
                        String clickedUrl = clicked.has("url") ? clicked.get("url").getAsString() : "";
                        if (!clickedUrl.isEmpty()) Hawk.put(HawkConfig.LIVE_API_URL, clickedUrl);
                    } else {
                        // 清掉手工源残留地址：LIVE_API_URL非空时loadLiveConfig优先按它
                        // 加载，不清空的话点内置分支仍加载手工源内容、却把序号设成内置
                        // 分支位置，分组错乱/越界。清空后回退"主配置+按序号选中"的
                        // v1.9路径。必须先清再设序号——序号的存储key取决于当前LIVE_API_URL
                        Hawk.put(HawkConfig.LIVE_API_URL, "");
                    }
                    ApiConfig.setLiveGroupIndex(position);
                    // 列表里已有的源：选中即生效，加载成功后关闭本窗口并提示。
                    // 加载期间显示进度条，失败时保留窗口并提示，方便用户重试或改选其它分支。
                    if (playLoading != null) playLoading.setVisibility(View.VISIBLE);
                    // 重新加载直播配置
                    ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
                        @Override
                        public void success() {
                            if (playLoading != null) playLoading.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "已切换到: " + name, Toast.LENGTH_SHORT).show();
                            if (branchSelectListener != null) {
                                branchSelectListener.onBranchSelected(name);
                            }
                            dismiss();
                        }

                        @Override
                        public void error(String error) {
                            if (playLoading != null) playLoading.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "直播源切换失败: " + error, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void notice(String msg) {
                        }
                    });
                }
            },
            new LiveSourceSelectAdapter.OnDeleteClickListener() {
                @Override
                public void onDeleteClick(int position) {
                    // v1.4修复删除问题：增加越界保护；
                    // 原逻辑持久化本身正常(Hawk.put LIVE_GROUP_LIST)，但删除当前选中源后
                    // LIVE_API_URL仍残留被删源地址，回首页仍会加载被删的源；
                    // 列表删空时也未清空直播地址。现同步修正：
                    if (position < 0 || position >= livesGroups.size()) {
                        return;
                    }
                    // 若删的是手动源，同步从独立持久化里移除
                    JsonObject delObj = livesGroups.get(position).getAsJsonObject();
                    String delUrl = delObj.has("url") ? delObj.get("url").getAsString() : "";
                    int manualIdx = findManualIndexByUrl(delUrl);
                    if (manualIdx >= 0) {
                        manualGroups.remove(manualIdx);
                        saveManualGroups();
                    }
                    int curIdx = ApiConfig.getLiveGroupIndex();
                    livesGroups.remove(position);
                    nameList.remove(position);
                    if (livesGroups.size() == 0) {
                        Hawk.delete(HawkConfig.LIVE_GROUP_LIST);
                        Hawk.delete(HawkConfig.LIVE_MANUAL_LIST);
                        manualGroups = new JsonArray();
                        Hawk.put(HawkConfig.LIVE_API_URL, "");
                        ApiConfig.setLiveGroupIndex(0);
                    } else {
                        Hawk.put(HawkConfig.LIVE_GROUP_LIST, livesGroups);
                        if (position == curIdx || curIdx >= livesGroups.size()) {
                            int newIdx = Math.min(curIdx, livesGroups.size() - 1);
                            if (newIdx < 0) newIdx = 0;
                            ApiConfig.setLiveGroupIndex(newIdx);
                            JsonObject obj = livesGroups.get(newIdx).getAsJsonObject();
                            String newUrl = obj.has("url") ? obj.get("url").getAsString() : "";
                            Hawk.put(HawkConfig.LIVE_API_URL, newUrl);
                        }
                    }
                    adapter.setData(nameList, ApiConfig.getLiveGroupIndex());
                    Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
                    // 同步刷新设置页"直播地址"显示文本
                    if (branchSelectListener != null) {
                        int idx = ApiConfig.getLiveGroupIndex();
                        if (!nameList.isEmpty() && idx >= 0 && idx < nameList.size()) {
                            branchSelectListener.onBranchSelected(nameList.get(idx));
                        } else {
                            branchSelectListener.onBranchSelected("");
                        }
                    }
                }
            }
        );

        // 新增：复制按钮回调（列表项只持有显示名，实际地址从livesGroups取）
        adapter.setOnCopyClickListener(new LiveSourceSelectAdapter.OnCopyClickListener() {
            @Override
            public void onCopyClick(int position) {
                if (position < 0 || position >= livesGroups.size()) {
                    return;
                }
                JsonObject obj = livesGroups.get(position).getAsJsonObject();
                String url = obj.has("url") ? obj.get("url").getAsString() : "";
                String name = obj.has("name") ? obj.get("name").getAsString() : "";
                String copyText = (url != null && !url.isEmpty()) ? url : name;
                ClipboardUtil.copyText(getContext(), "直播源地址", copyText);
            }
        });

        if (recyclerView != null) {
            recyclerView.setAdapter(adapter);
            adapter.setData(nameList, selectIdx);
            recyclerView.setSelectedPosition(selectIdx);
            if (selectIdx < 10) {
                recyclerView.setSelection(selectIdx);
            }
        }
    }

    /**
     * 手动添加直播源（v1.4核心修复）
     * 原逻辑只回调宿主写 LIVE_API_URL，既不验证也不加入列表（LIVE_GROUP_LIST），
     * 表现为"提示直播源已保存但列表未显示"。
     * 现流程：去重 → 子线程拉取地址内容验证（configUrl统一处理clan://等协议）
     * → 加入列表并持久化 → 选中新增项 → 立即加载直播配置（与分支切换同机制）
     * → 成功后才回调宿主（写LIVE_API_URL + 提示"直播源已保存"）。
     * 全程留在当前页面，不返回主页。
     */
    private void addLiveSourceRequest(final String name, final String address) {
        if (isAdding) {
            Toast.makeText(getContext(), "正在获取直播源，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        // 去重：地址已存在时直接选中该分支并使其生效（不重复添加）
        for (int i = 0; i < livesGroups.size(); i++) {
            JsonObject obj = livesGroups.get(i).getAsJsonObject();
            String existUrl = obj.has("url") ? obj.get("url").getAsString() : "";
            if (address.equals(existUrl)) {
                // 先写入直播地址再加载：loadLiveConfig 读的是 LIVE_API_URL，
                // 顺序反了会加载旧地址，新源要等下次进直播页才生效。
                // 加载失败会把直播地址还原，已存在的条目保持原样
                final String prevLiveUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
                Hawk.put(HawkConfig.LIVE_API_URL, address);
                ApiConfig.setLiveGroupIndex(i);
                rebuildNameList(i);
                inputSourceName.setText("");
                inputSourceUrl.setText("");
                isAdding = true;
                if (playLoading != null) playLoading.setVisibility(View.VISIBLE);
                // 列表里已有这条源：加载成功后直接关闭窗口并提示"已存在"，
                // 不再走新增保存链路（否则宿主会再弹一次"直播源已保存"，两条Toast打架）
                loadAndNotify(name, address, null, prevLiveUrl, true);
                return;
            }
        }
        isAdding = true;
        if (playLoading != null) playLoading.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String errMsg = null;
                String body = null;
                try {
                    ApiConfig apiConfig = ApiConfig.get();
                    // resolve() 原子化 configUrl+密钥提取，避免多线程TempKey竞态
                    String[] resolved = ConfigFetcher.resolve(address);
                    // v1.10: 中文等非ASCII字符百分号编码（与多仓对话框同步修复）
                    String fetchUrl = ConfigFetcher.encodeUnsafeUrlChars(resolved[0]);
                    final String configKey = resolved[1];
                    // v1.10: 改用全局客户端（与配置地址入口同栈，含DoH/重试等配置）
                    okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                    if (client == null) {
                        client = new okhttp3.OkHttpClient.Builder()
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build();
                    }
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(fetchUrl)
                            .header("User-Agent", "Mozilla/5.0")
                            .build();
                    try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            String errBody = "";
                            try {
                                if (response.body() != null) errBody = response.body().string();
                            } catch (Exception ignored) {
                            }
                            String hint = errBody == null ? "" : errBody.trim().replaceAll("\\s+", " ");
                            if (hint.length() > 80) hint = hint.substring(0, 80);
                            errMsg = "HTTP " + response.code() + (hint.isEmpty() ? "" : "：" + hint);
                        } else if (response.body() == null) {
                            errMsg = "响应内容为空";
                        } else {
                            body = response.body().string();
                            body = ApiConfig.FindResult(body, configKey);
                            if (address.startsWith("clan://") || address.startsWith("file://")) {
                                body = apiConfig.clanContentFix(fetchUrl, body);
                            }
                            body = apiConfig.fixContentPath(address, body);
                        }
                    }
                } catch (Exception e) {
                    errMsg = e.getMessage() == null ? "网络请求失败" : e.getMessage();
                }
                final String finErr = errMsg;
                final String finBody = body;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        isAdding = false;
                        if (playLoading != null) playLoading.setVisibility(View.GONE);
                        if (finErr != null) {
                            Toast.makeText(getContext(), "直播源获取失败(" + finErr + ")，未保存", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (finBody == null || finBody.trim().isEmpty()) {
                            Toast.makeText(getContext(), "直播源内容为空，未保存", Toast.LENGTH_LONG).show();
                            return;
                        }
                        // 宽松校验直播源格式：#genre#分组 / m3u / 名称,url 三者其一
                        String check = finBody.trim();
                        if (!(check.contains("#genre#") || check.contains("#EXTM3U") || check.contains(","))) {
                            Toast.makeText(getContext(), "内容不是有效的直播源格式，未保存", Toast.LENGTH_LONG).show();
                            return;
                        }
                        applyLiveSource(name, address);
                    }
                });
            }
        }).start();
    }

    /**
     * 验证通过：加入列表 → 持久化 → 选中新增项 → 刷新列表UI → 立即加载
     */
    /**
     * 验证通过：加入列表 → 持久化 → 选中新增项 → 刷新列表UI → 立即加载。
     * 只保存解析成功的：loadLiveConfig 真正解析出频道才算成功，
     * 加载失败由 loadAndNotify 回滚全部落库状态（含LIVE_MANUAL_LIST与直播地址）。
     */
    private void applyLiveSource(final String name, final String address) {
        JsonObject newObj = new JsonObject();
        newObj.addProperty("name", name);
        newObj.addProperty("url", address);
        livesGroups.add(newObj);
        Hawk.put(HawkConfig.LIVE_GROUP_LIST, livesGroups);
        // 手动源独立持久化：LIVE_GROUP_LIST会被配置加载覆盖（清空或替换），
        // 不单独存的话重启后列表就没了
        manualGroups.add(newObj);
        saveManualGroups();
        final int newPos = livesGroups.size() - 1;
        // 先写入直播地址再加载（顺序原先是反的，详见去重分支注释）
        final String prevLiveUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
        Hawk.put(HawkConfig.LIVE_API_URL, address);
        ApiConfig.setLiveGroupIndex(newPos);
        rebuildNameList(newPos);
        hidePermissionBtnIfGranted();
        inputSourceName.setText("");
        inputSourceUrl.setText("");
        // 加载期间保持"正在获取"状态，解析失败即回滚
        isAdding = true;
        if (playLoading != null) playLoading.setVisibility(View.VISIBLE);
        loadAndNotify(name, address, newObj, prevLiveUrl, false);
    }

    /**
     * 立即加载直播配置（与列表点击切换分支同机制）。
     * 只保存解析成功的：success 才回调宿主保存直播地址；
     * error 视为解析失败，回滚本次添加/切换的全部落库状态后提示，不保存。
     *
     * @param addedEntry 本次新增的列表条目（去重选中已有条目时传null，只还原直播地址）
     * @param prevLiveUrl 加载前的直播地址，失败时还原
     * @param closeOnSuccess true=加载成功后关闭本窗口（命中列表已有项时用），false=留在窗口
     */
    private void loadAndNotify(final String name, final String address,
                               final JsonObject addedEntry, final String prevLiveUrl,
                               final boolean closeOnSuccess) {
        ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
            @Override
            public void success() {
                isAdding = false;
                if (playLoading != null) playLoading.setVisibility(View.GONE);
                if (closeOnSuccess) {
                    // 已有项：只刷新设置页文本 + 提示 + 关闭，不走宿主的新增保存回调，
                    // 避免"该直播源已存在"与"直播源已保存"两条Toast连续弹出
                    notifyBranchSelected();
                    Toast.makeText(getContext(), "该直播源已存在，已为您选中", Toast.LENGTH_SHORT).show();
                    dismiss();
                } else {
                    notifyHostSaved(name, address);
                }
            }

            @Override
            public void error(String error) {
                isAdding = false;
                if (playLoading != null) playLoading.setVisibility(View.GONE);
                rollbackLiveSource(addedEntry, prevLiveUrl);
                Toast.makeText(getContext(), "直播源解析失败，未保存: " + error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void notice(String msg) {
            }
        });
    }

    /**
     * 加载/解析失败时还原落库状态：移除新增条目（分支列表+手动持久化）、
     * 还原直播地址并刷新列表。addedEntry为null时只还原直播地址。
     */
    private void rollbackLiveSource(JsonObject addedEntry, String prevLiveUrl) {
        if (addedEntry != null) {
            livesGroups.remove(addedEntry);
            manualGroups.remove(addedEntry);
            saveManualGroups();
            Hawk.put(HawkConfig.LIVE_GROUP_LIST, livesGroups);
        }
        Hawk.put(HawkConfig.LIVE_API_URL, prevLiveUrl == null ? "" : prevLiveUrl);
        int selectIdx = ApiConfig.getLiveGroupIndex();
        if (selectIdx >= livesGroups.size()) selectIdx = Math.max(0, livesGroups.size() - 1);
        rebuildNameList(selectIdx);
    }

    /**
     * 只通知宿主刷新设置页"直播地址"文本（按当前选中的分支显示名），
     * 不触发宿主的新增保存提示。用于"命中已有项"这类无需再次保存的场景。
     */
    private void notifyBranchSelected() {
        if (branchSelectListener == null) return;
        int idx = ApiConfig.getLiveGroupIndex();
        if (nameList != null && !nameList.isEmpty() && idx >= 0 && idx < nameList.size()) {
            branchSelectListener.onBranchSelected(nameList.get(idx));
        } else {
            branchSelectListener.onBranchSelected("");
        }
    }

    /**
     * 回调宿主保存直播地址（宿主：Hawk.put(LIVE_API_URL) + 提示"直播源已保存" + 刷新设置页文本）
     */
    private void notifyHostSaved(String name, String address) {
        LiveSourceBean bean = new LiveSourceBean();
        bean.setSourceName(name);
        bean.setSourceUrl(address);
        if (listener != null) {
            listener.onAdd(bean);
        }
    }

    /**
     * 按当前livesGroups重建显示名称列表并刷新适配器（选中指定项）
     */
    private void rebuildNameList(int selectIdx) {
        String storeName = getStoreName();
        nameList.clear();
        for (int i = 0; i < livesGroups.size(); i++) {
            JsonObject obj = livesGroups.get(i).getAsJsonObject();
            String entryName = obj.has("name") ? obj.get("name").getAsString().trim() : "";
            nameList.add(buildDisplayName(entryName, storeName, i));
        }
        if (adapter != null) {
            adapter.setData(nameList, selectIdx);
        }
        if (recyclerView != null) {
            recyclerView.setSelectedPosition(selectIdx);
            if (selectIdx < 10) {
                recyclerView.setSelection(selectIdx);
            }
        }
    }

    /**
     * 已授予存储权限时自动隐藏"存储权限"按钮
     */
    private void hidePermissionBtnIfGranted() {
        View permissionBtn = findViewById(R.id.permission_Submit);
        if (permissionBtn != null && permissionBtn.getVisibility() == View.VISIBLE
                && XXPermissions.isGranted(getContext(), Permission.Group.STORAGE)) {
            permissionBtn.setVisibility(View.GONE);
        }
    }

    // ===================== 手动直播源独立持久化 =====================

    private void loadManualGroups() {
        JsonArray saved = Hawk.get(HawkConfig.LIVE_MANUAL_LIST, new JsonArray());
        manualGroups = saved == null ? new JsonArray() : saved;
    }

    private void saveManualGroups() {
        Hawk.put(HawkConfig.LIVE_MANUAL_LIST, manualGroups == null ? new JsonArray() : manualGroups);
    }

    private int findManualIndexByUrl(String url) {
        if (manualGroups == null || url == null || url.isEmpty()) return -1;
        for (int i = 0; i < manualGroups.size(); i++) {
            JsonObject m = manualGroups.get(i).getAsJsonObject();
            String mUrl = m.has("url") ? m.get("url").getAsString() : "";
            if (url.equals(mUrl)) return i;
        }
        return -1;
    }

    private boolean isManualEntry(int position) {
        if (position < 0 || position >= livesGroups.size()) return false;
        JsonObject obj = livesGroups.get(position).getAsJsonObject();
        String url = obj.has("url") ? obj.get("url").getAsString() : "";
        return findManualIndexByUrl(url) >= 0;
    }

    // ===================== EventBus（与MoreSourceDialog同款自注册） =====================

    /**
     * 当前活跃实例：推送事件(TYPE_LIVE_PUSH)同时被本对话框与HomeActivity订阅，
     * 两边各自拉取验证并各写一次LIVE_MANUAL_LIST（对话框的去重只看打开时刻的
     * livesGroups快照，不看manual最新值），同一url会重复入库/回滚时丢项。
     * HomeActivity见isActive()为true时跳过持久化，持久化单写者=对话框；对话框
     * 不在时单写者=HomeActivity。
     */
    private static final java.lang.ref.WeakReference<LiveSourceDialog> EMPTY_REF =
            new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<LiveSourceDialog> sActiveRef = EMPTY_REF;

    public static boolean isActive() {
        LiveSourceDialog active = sActiveRef.get();
        return active != null && active.isShowing();
    }

    @Override
    public void show() {
        sActiveRef = new java.lang.ref.WeakReference<>(this);
        try {
            EventBus.getDefault().register(this);
        } catch (Exception ignored) {
        }
        super.show();
    }

    @Override
    public void dismiss() {
        if (sActiveRef.get() == this) sActiveRef = EMPTY_REF;
        try {
            EventBus.getDefault().unregister(this);
        } catch (Exception ignored) {
        }
        super.dismiss();
    }

    /**
     * 构建直播源显示名称（参照ysc o0oOo0O0.OooO0oO 第145-151行）
     */
    private String buildDisplayName(String entryName, String storeName, int index) {
        if (entryName == null) entryName = "";
        if (storeName == null || storeName.isEmpty()) storeName = "";
        // 分支名为空/http/clan → "仓库名直播"
        if (entryName.isEmpty() || entryName.startsWith("http") || entryName.startsWith("clan://")) {
            if (!storeName.isEmpty()) return storeName + "直播";
            return "线路" + (index + 1);
        }
        // 否则 → "分支名直播仓库名"
        if (!storeName.isEmpty()) return entryName + "直播" + storeName;
        return entryName;
    }

    /**
     * 获取当前仓库名
     */
    private String getStoreName() {
        String name = Hawk.get(HawkConfig.STORE_API_NAME, "");
        if (name != null && !name.isEmpty()) return name;
        try {
            String json = Hawk.get(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED, "");
            if (json != null && !json.isEmpty()) {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                MoreSourceBean selected = gson.fromJson(json, MoreSourceBean.class);
                if (selected != null && selected.getSourceName() != null) {
                    return selected.getSourceName();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
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

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }

    public void setOnBranchSelectListener(OnBranchSelectListener listener) {
        this.branchSelectListener = listener;
    }

    public interface OnListener {
        void onAdd(LiveSourceBean bean);
    }

    public interface OnBranchSelectListener {
        void onBranchSelected(String displayName);
    }
}
