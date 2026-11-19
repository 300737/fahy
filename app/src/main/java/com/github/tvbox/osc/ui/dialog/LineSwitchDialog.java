package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.MoreSourceBean;
import com.github.tvbox.osc.ui.adapter.LineSelectAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.ConfigFetcher;
import com.github.tvbox.osc.util.ConfigRepository;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 线路切换对话框（从主界面直接打开，参照配置对话框的模式）
 * <p>
 * 将 ModelSettingFragment 中的线路切换逻辑（showLineSwitchDialog / fetchStoreLinesAndShow /
 * showLineSelectDialog / showLineSelectDialogWithCacheInfo 等方法）提取为独立 Dialog，
 * 使主界面的"线路"按钮可以像"配置"按钮一样直接弹出小对话框，而无需跳转设置页。
 */
public class LineSwitchDialog extends BaseDialog {

    private Activity mActivity;
    private long currentLineCacheTime = -1;
    private String currentLineCacheKey = "";

    /** 线路切换完成后的回调（可选），用于通知宿主刷新UI */
    public interface OnLineSwitchedListener {
        void onLineSwitched(String newApiUrl);
    }

    private OnLineSwitchedListener mListener;
    private MoreSourceDialog.OnLocalFileListener onLocalFileListener;
    /** 内部创建的MoreSourceDialog引用，用于透传setLocalFileUrl */
    private MoreSourceDialog innerMoreSourceDialog;

    public LineSwitchDialog(@NonNull Activity activity) {
        super(activity);
        mActivity = activity;
    }

    public void setOnLineSwitchedListener(OnLineSwitchedListener listener) {
        mListener = listener;
    }

    /** 设置本地文件选择监听器，透传给内部创建的MoreSourceDialog */
    public void setOnLocalFileListener(MoreSourceDialog.OnLocalFileListener listener) {
        this.onLocalFileListener = listener;
    }

    /** 将本地文件选择结果回填到内部MoreSourceDialog的URL输入框 */
    public void setLocalFileUrl(String url) {
        if (innerMoreSourceDialog != null) {
            innerMoreSourceDialog.setLocalFileUrl(url);
        }
    }

    /**
     * 不显示自身窗口，直接触发内层对话框
     * <p>
     * LineSwitchDialog 本身没有 contentView，只是逻辑调度器，
     * 调用 super.show() 会创建空窗口导致需要按两次返回键才能关闭。
     */
    @Override
    public void show() {
        if (mActivity.isFinishing()
                || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1
                && mActivity.isDestroyed())) {
            return;
        }
        showLineSwitchDialog();
    }

    // ===================== 线路切换主入口（参照 ModelSettingFragment.showLineSwitchDialog） =====================

    /**
     * 线路切换对话框主逻辑
     * 1. 如果有多仓配置，先展示多仓选择列表
     * 2. 选中仓库后，从仓库URL动态获取线路列表
     * 3. 展示线路选择对话框
     */
    private void showLineSwitchDialog() {
        ArrayList<MoreSourceBean> storeList = getStoreHouseList();
        ArrayList<MoreSourceBean> lineHistory = getApiHistoryList();

        // 将仓库中本地保存的线路合并到历史列表（参照ysc y11.OooO0O0）
        // 注意：只合并线路用于展示，不删除仓库（仓库保留在列表中供用户切换）
        // 修复：原先直接addAll不做去重——历史里已有的线路再从仓库localLineUrls
        // 合并一次，同一条线路在列表里出现两次
        if (!storeList.isEmpty()) {
            LinkedHashMap<String, MoreSourceBean> uniq = new LinkedHashMap<>();
            for (MoreSourceBean bean : lineHistory) {
                if (bean.getSourceUrl() != null && !bean.getSourceUrl().isEmpty()) {
                    uniq.put(bean.getSourceUrl(), bean);
                }
            }
            for (MoreSourceBean store : storeList) {
                if (store.getLocalLineUrls() != null) {
                    for (MoreSourceBean line : store.getLocalLineUrls()) {
                        if (line.getSourceUrl() != null && !line.getSourceUrl().isEmpty()
                                && !uniq.containsKey(line.getSourceUrl())) {
                            uniq.put(line.getSourceUrl(), line);
                        }
                    }
                }
            }
            lineHistory = new ArrayList<>(uniq.values());
        }

        // 如果历史线路列表为空且没有仓库配置
        if (lineHistory.isEmpty() && storeList.isEmpty()) {
            ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
            if (!apiLines.isEmpty()) {
                ArrayList<MoreSourceBean> lineBeans = new ArrayList<>();
                for (String line : apiLines) {
                    MoreSourceBean bean = new MoreSourceBean();
                    bean.setSourceName(HistoryHelper.getApiLineName(line));
                    bean.setSourceUrl(HistoryHelper.getApiLineUrl(line));
                    bean.setShowDelete(true);
                    lineBeans.add(bean);
                }
                showLineSelectDialog(lineBeans);
                return;
            }
            MoreSourceBean selectedStore = getSelectedStoreHouse();
            if (selectedStore == null || selectedStore.getSourceUrl() == null || selectedStore.getSourceUrl().isEmpty()) {
                // 线路为空且没有选中仓库 → 弹出多仓配置对话框
                showStoreHouseDialog();
                return;
            }
        }

        // 获取当前选中的仓库
        MoreSourceBean selectedStore = getSelectedStoreHouse();

        // 如果有选中的仓库，直接从该仓库获取线路
        if (selectedStore != null && selectedStore.getSourceUrl() != null && !selectedStore.getSourceUrl().isEmpty()) {
            fetchStoreLinesAndShow(selectedStore);
            return;
        }

        // 如果有历史线路但没有选中仓库，直接展示历史线路
        if (!lineHistory.isEmpty()) {
            for (MoreSourceBean bean : lineHistory) {
                bean.setShowDelete(true);
            }
            showLineSelectDialog(lineHistory);
            return;
        }

        // 如果有仓库列表，显示仓库选择对话框
        if (!storeList.isEmpty()) {
            showStoreSelectDialog(storeList);
            return;
        }

        // 兜底：尝试从 STORE_API 获取
        String storeApi = Hawk.get(HawkConfig.STORE_API, "");
        if (!storeApi.isEmpty()) {
            MoreSourceBean storeBean = new MoreSourceBean();
            storeBean.setSourceName(Hawk.get(HawkConfig.STORE_API_NAME, ""));
            storeBean.setSourceUrl(storeApi);
            fetchStoreLinesAndShow(storeBean);
        } else {
            showStoreHouseDialog();
        }
    }

    // ===================== 仓库/线路选择对话框 =====================

    /**
     * 显示仓库选择对话框
     */
    private void showStoreSelectDialog(ArrayList<MoreSourceBean> storeList) {
        String selectedUrl = Hawk.get(HawkConfig.STORE_API, "");
        int idx = 0;
        for (int i = 0; i < storeList.size(); i++) {
            if (selectedUrl.equals(storeList.get(i).getSourceUrl())) {
                idx = i;
                break;
            }
        }
        SelectDialog<MoreSourceBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("选择仓库");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<MoreSourceBean>() {
            @Override
            public void click(MoreSourceBean value, int pos) {
                dialog.dismiss();
                saveSelectedStoreHouse(value);
                Hawk.put(HawkConfig.STORE_API, value.getSourceUrl());
                if (value.getSourceName() != null && !value.getSourceName().isEmpty()) {
                    Hawk.put(HawkConfig.STORE_API_NAME, value.getSourceName());
                }
                Toast.makeText(mActivity, "正在获取线路，请稍候...", Toast.LENGTH_SHORT).show();
                fetchStoreLinesAndShow(value);
            }

            @Override
            public String getDisplay(MoreSourceBean val) {
                String name = val.getSourceName();
                if (name == null || name.isEmpty()) {
                    name = val.getSourceUrl();
                }
                return name;
            }
        }, new DiffUtil.ItemCallback<MoreSourceBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull MoreSourceBean oldItem, @NonNull @NotNull MoreSourceBean newItem) {
                return oldItem.getUniKey().equals(newItem.getUniKey());
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull MoreSourceBean oldItem, @NonNull @NotNull MoreSourceBean newItem) {
                return oldItem.getUniKey().equals(newItem.getUniKey());
            }
        }, storeList, idx);
        dialog.show();
    }

    /**
     * 显示多仓配置对话框（回退到 MoreSourceDialog）
     */
    private void showStoreHouseDialog() {
        try {
            MoreSourceDialog dialog = new MoreSourceDialog(mActivity);
            innerMoreSourceDialog = dialog;
            dialog.setOnStoreSelectedListener(new MoreSourceDialog.OnStoreSelectedListener() {
                @Override
                public void onStoreSelected(MoreSourceBean bean) {
                    // 选中仓库后，获取线路并显示
                    Toast.makeText(mActivity, "正在获取线路，请稍候...", Toast.LENGTH_SHORT).show();
                    fetchStoreLinesAndShow(bean);
                }
            });
            // 透传本地文件选择监听器（与UserFragment的设置页模式一致）
            if (onLocalFileListener != null) {
                dialog.setOnLocalFileListener(onLocalFileListener);
            }
            dialog.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 显示线路选择对话框（参照 ModelSettingFragment.showLineSelectDialog）
     */
    private void showLineSelectDialog(ArrayList<MoreSourceBean> lineBeans) {
        String current = Hawk.get(HawkConfig.API_URL, "");
        int idx = 0;
        for (int i = 0; i < lineBeans.size(); i++) {
            if (current.equals(lineBeans.get(i).getSourceUrl())) {
                idx = i;
                break;
            }
        }
        final int selectIdx = idx;
        SelectDialog<MoreSourceBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("选择线路");
        // 新增：列表项增加复制按钮后行宽变大，同步增大对话框宽度
        dialog.setDialogWidthMm(720);
        final LineSelectAdapter[] adapterHolder = new LineSelectAdapter[1];
        adapterHolder[0] = new LineSelectAdapter(new LineSelectAdapter.OnLineClickListener() {
            @Override
            public void onLineClick(MoreSourceBean bean, int position) {
                String newApi = bean.getSourceUrl();
                String oldApi = Hawk.get(HawkConfig.API_URL, "");
                if (newApi == null || newApi.isEmpty()) return;
                Hawk.put(HawkConfig.API_URL, newApi);
                // 直播地址仅在跟随配置状态下切换，用户手工添加的直播源不被覆盖
                HistoryHelper.followLiveApiOnLineSwitch(newApi, oldApi);
                HistoryHelper.setApiHistory(newApi);
                dialog.dismiss();
                if (!oldApi.equals(newApi) && mListener == null) {
                    // 无宿主回调时兜底：对话框自己重启（如今统一由 ConfigFlow 提供宿主）
                    restartAppAfterConfigChanged();
                }
                if (mListener != null) {
                    // 由宿主（设置页/主界面）决定如何重新加载，避免对话框与宿主各重启一次
                    mListener.onLineSwitched(newApi);
                }
            }

            @Override
            public void onLineDelete(MoreSourceBean bean, int position) {
                ArrayList<MoreSourceBean> historyList = getApiHistoryList();
                for (int i = 0; i < historyList.size(); i++) {
                    if (bean.getSourceUrl() != null && bean.getSourceUrl().equals(historyList.get(i).getSourceUrl())) {
                        historyList.remove(i);
                        break;
                    }
                }
                saveApiHistoryList(historyList);
                ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
                for (int i = 0; i < apiLines.size(); i++) {
                    if (bean.getSourceUrl() != null && bean.getSourceUrl().equals(HistoryHelper.getApiLineUrl(apiLines.get(i)))) {
                        apiLines.remove(i);
                        break;
                    }
                }
                Hawk.put(HawkConfig.API_LINE_LIST, apiLines);
                // 同步清理仓库bean里存的localLineUrls：否则下次打开线路切换时
                // showLineSwitchDialog把仓库的localLineUrls合并回列表，被删线路复活
                ConfigRepository.removeLineFromAllStores(bean.getSourceUrl());
                adapterHolder[0].getData().remove(position);
                adapterHolder[0].notifyItemRemoved(position);
            }
        });
        LineSelectAdapter adapter = adapterHolder[0];
        adapter.setData(lineBeans, selectIdx);
        TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
        tvRecyclerView.setAdapter(adapter);
        tvRecyclerView.setSelectedPosition(selectIdx);
        if (selectIdx < 10) {
            tvRecyclerView.setSelection(selectIdx);
        }
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                if (selectIdx >= 10) {
                    tvRecyclerView.smoothScrollToPosition(selectIdx);
                    tvRecyclerView.setSelectionWithSmooth(selectIdx);
                }
            }
        });
        dialog.show();
    }

    /**
     * 显示带缓存信息的线路选择对话框
     */
    private void showLineSelectDialogWithCacheInfo(ArrayList<MoreSourceBean> lineBeans) {
        showLineSelectDialogWithCacheInfo(lineBeans, null);
    }

    /**
     * 显示带缓存信息的线路选择对话框
     *
     * @param onLineConfirmed 用户点击某条线路确认加载后、执行加载前的回调（可为null）。
     *                        用于"先确认后落库"场景：持久化必须等用户确认再做，
     *                        按返回取消时不留脏数据
     */
    private void showLineSelectDialogWithCacheInfo(ArrayList<MoreSourceBean> lineBeans,
                                                   final Runnable onLineConfirmed) {
        String current = Hawk.get(HawkConfig.API_URL, "");
        int idx = 0;
        for (int i = 0; i < lineBeans.size(); i++) {
            if (current.equals(lineBeans.get(i).getSourceUrl())) {
                idx = i;
                break;
            }
        }
        final int selectIdx = idx;
        SelectDialog<MoreSourceBean> dialog = new SelectDialog<>(mActivity);
        String cacheInfoTitle = buildCacheInfoTitle();
        dialog.setTip(cacheInfoTitle);
        // 新增：列表项增加复制按钮后行宽变大，同步增大对话框宽度
        dialog.setDialogWidthMm(720);
        final LineSelectAdapter[] adapterHolder = new LineSelectAdapter[1];
        adapterHolder[0] = new LineSelectAdapter(new LineSelectAdapter.OnLineClickListener() {
            @Override
            public void onLineClick(MoreSourceBean bean, int position) {
                String newApi = bean.getSourceUrl();
                String oldApi = Hawk.get(HawkConfig.API_URL, "");
                if (newApi == null || newApi.isEmpty()) return;
                Hawk.put(HawkConfig.API_URL, newApi);
                // 直播地址仅在跟随配置状态下切换，用户手工添加的直播源不被覆盖
                HistoryHelper.followLiveApiOnLineSwitch(newApi, oldApi);
                HistoryHelper.setApiHistory(newApi);
                if (onLineConfirmed != null) {
                    onLineConfirmed.run();
                }
                dialog.dismiss();
                if (!oldApi.equals(newApi) && mListener == null) {
                    // 无宿主回调时兜底：对话框自己重启（如今统一由 ConfigFlow 提供宿主）
                    restartAppAfterConfigChanged();
                }
                if (mListener != null) {
                    // 由宿主（设置页/主界面）决定如何重新加载，避免对话框与宿主各重启一次
                    mListener.onLineSwitched(newApi);
                }
            }

            @Override
            public void onLineDelete(MoreSourceBean bean, int position) {
                ArrayList<MoreSourceBean> historyList = getApiHistoryList();
                for (int i = 0; i < historyList.size(); i++) {
                    if (bean.getSourceUrl() != null && bean.getSourceUrl().equals(historyList.get(i).getSourceUrl())) {
                        historyList.remove(i);
                        break;
                    }
                }
                saveApiHistoryList(historyList);
                ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
                for (int i = 0; i < apiLines.size(); i++) {
                    if (bean.getSourceUrl() != null && bean.getSourceUrl().equals(HistoryHelper.getApiLineUrl(apiLines.get(i)))) {
                        apiLines.remove(i);
                        break;
                    }
                }
                Hawk.put(HawkConfig.API_LINE_LIST, apiLines);
                // 同步清理仓库bean里存的localLineUrls（原因同上）
                ConfigRepository.removeLineFromAllStores(bean.getSourceUrl());
                adapterHolder[0].getData().remove(position);
                adapterHolder[0].notifyItemRemoved(position);
            }
        });
        LineSelectAdapter adapter = adapterHolder[0];
        adapter.setData(lineBeans, selectIdx);
        TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
        tvRecyclerView.setAdapter(adapter);
        tvRecyclerView.setSelectedPosition(selectIdx);
        if (selectIdx < 10) {
            tvRecyclerView.setSelection(selectIdx);
        }
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                if (selectIdx >= 10) {
                    tvRecyclerView.smoothScrollToPosition(selectIdx);
                    tvRecyclerView.setSelectionWithSmooth(selectIdx);
                }
            }
        });
        dialog.show();
    }

    // ===================== 网络获取线路 =====================

    /**
     * 从仓库URL获取线路并显示线路选择对话框
     */
    private void fetchStoreLinesAndShow(MoreSourceBean storeBean) {
        fetchStoreLinesAndShow(storeBean, 0);
    }

    /**
     * @param depth 仓库重定向深度（storeHouse形态重定向第一个仓库时递增，防止互相指向无限递归）
     */
    private void fetchStoreLinesAndShow(MoreSourceBean storeBean, final int depth) {
        String storeUrl = storeBean.getSourceUrl();
        if (storeUrl == null || storeUrl.isEmpty()) {
            Toast.makeText(mActivity, "仓库地址为空", Toast.LENGTH_SHORT).show();
            return;
        }
        // 复用 ConfigFetcher.resolve() 统一处理URL（file://转换、;pk;提取、clan://转换、http补全），
        // 原子化取回密钥避免TempKey竞态；与 MoreSourceDialog/ConfigFetcher 保持同一预处理
        final String rawUrl = storeUrl;
        String[] resolved = ConfigFetcher.resolve(storeUrl);
        String fetchUrl = ConfigFetcher.encodeUnsafeUrlChars(resolved[0]);
        final String configKey = resolved[1];

        final String finalFetchUrl = fetchUrl;

        // 缓存key并入密钥哈希：fetchUrl已剥掉;pk;，明文版与pk版同地址若共用
        // 缓存文件，先明文缓存、后带pk请求会命中明文缓存再拿pk解密出乱码
        String cacheKey = ConfigFetcher.lineCacheKey(fetchUrl, configKey);
        currentLineCacheKey = cacheKey;

        int cacheDays = Hawk.get(HawkConfig.CACHE_TIME, 1);
        if (cacheDays == -1) {
            currentLineCacheTime = -1L;
        } else {
            currentLineCacheTime = (long) cacheDays * 24 * 60 * 60 * 1000;
        }

        // -1 URL特殊处理：仅当请求路径末段以"-1"结尾时才走特殊管线。
        // 原先 contains("-1") 全文匹配，普通地址里任意位置的"-1"
        // （如 xxx-1.0.5.json、line-1.json、?t=-1）都会被劫持进该管线
        if (isMinusOneUrl(finalFetchUrl)) {
            handleMinusOneUrl(storeBean, finalFetchUrl, configKey, rawUrl, depth, cacheKey, currentLineCacheTime);
            return;
        }

        // ========== 检查本地缓存 ==========
        // 缓存有效直接使用、不发后台请求：原实现缓存有效时仍发一次
        // isBackgroundUpdate=true 的请求，结果只回写localLineUrls、不刷新已弹出的
        // 对话框，且每次writeLineCache重写expire把缓存无限续期——服务器每次打开
        // 线路切换都挨一次请求。现在只在无缓存/缓存过期时请求，localLineUrls随
        // 之降频为每个缓存周期刷新一次
        // 本地地址(127.0.0.1)不使用缓存（参照ysc: !b51.OooooOO(strClanToAddress, "http://127.0.0.1"））
        boolean useCache = !finalFetchUrl.startsWith("http://127.0.0.1") && cacheDays != 0;

        if (useCache) {
            String cachedBody = readLineCache(cacheKey);
            if (cachedBody != null && !cachedBody.isEmpty()
                    && isLineCacheValid(cacheKey, currentLineCacheTime)) {
                // 缓存有效，解密后再使用（缓存存的是原始响应）
                String decryptedCache = ApiConfig.FindResult(cachedBody, configKey);
                if (rawUrl != null && (rawUrl.startsWith("clan://") || rawUrl.startsWith("file://"))) {
                    decryptedCache = ApiConfig.get().clanContentFix(finalFetchUrl, decryptedCache);
                }
                decryptedCache = ApiConfig.get().fixContentPath(rawUrl != null ? rawUrl : finalFetchUrl, decryptedCache);
                parseAndShowLinesFromJson(decryptedCache, finalFetchUrl, storeBean, configKey, rawUrl, depth);
                return;
            }
        }

        // 无缓存或缓存无效，直接请求网络
        fetchLineFromNetwork(finalFetchUrl, storeBean, cacheKey, currentLineCacheTime, configKey, rawUrl, depth);
    }

    /**
     * 从网络获取线路
     */
    private void fetchLineFromNetwork(final String fetchUrl, final MoreSourceBean storeBean,
                                        final String cacheKey, final long cacheTimeMs,
                                        final String configKey, final String rawUrl, final int depth) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 与配置地址入口同栈：使用全局客户端（含DoH/超时配置），裸客户端会绕过DoH
                    okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                    if (client == null) {
                        client = new okhttp3.OkHttpClient.Builder()
                                .followRedirects(true).followSslRedirects(true).build();
                    }
                    okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().url(fetchUrl);
                    if (fetchUrl.startsWith("https://gitcode")) {
                        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                        requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    } else {
                        requestBuilder.header("User-Agent", "Mozilla/5.0");
                    }
                    String body;
                    try (okhttp3.Response response = client.newCall(requestBuilder.build()).execute()) {
                        // 检查HTTP状态码：404/500的错误页不能当线路内容解析，更不能写入缓存
                        if (!response.isSuccessful()) {
                            throw new IllegalStateException("HTTP " + response.code());
                        }
                        if (response.body() == null) {
                            throw new IllegalStateException("响应内容为空");
                        }
                        body = response.body().string();
                    }
                    // 写入缓存（缓存原始响应，解密后缓存无意义）
                    if (cacheTimeMs != 0 && !fetchUrl.startsWith("http://127.0.0.1")) {
                        writeLineCache(cacheKey, body, cacheTimeMs);
                    }
                    // 复用 ApiConfig.FindResult 解密 + 后处理（与 MoreSourceDialog.addStoreToLayout 完全一致）
                    ApiConfig apiConfig = ApiConfig.get();
                    body = ApiConfig.FindResult(body, configKey);
                    if (rawUrl != null && (rawUrl.startsWith("clan://") || rawUrl.startsWith("file://"))) {
                        body = apiConfig.clanContentFix(fetchUrl, body);
                    }
                    body = apiConfig.fixContentPath(rawUrl != null ? rawUrl : fetchUrl, body);
                    parseAndShowLinesFromJson(body, fetchUrl, storeBean, configKey, rawUrl, depth);
                } catch (final Exception e) {
                    e.printStackTrace();
                    String cachedBody = readLineCache(cacheKey);
                    if (cachedBody != null && !cachedBody.isEmpty()) {
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(mActivity, "网络异常，使用缓存数据", Toast.LENGTH_SHORT).show();
                            }
                        });
                        String decrypted = ApiConfig.FindResult(cachedBody, configKey);
                        if (rawUrl != null && (rawUrl.startsWith("clan://") || rawUrl.startsWith("file://"))) {
                            decrypted = ApiConfig.get().clanContentFix(fetchUrl, decrypted);
                        }
                        decrypted = ApiConfig.get().fixContentPath(rawUrl != null ? rawUrl : fetchUrl, decrypted);
                        parseAndShowLinesFromJson(decrypted, fetchUrl, storeBean, configKey, rawUrl, depth);
                    } else {
                        showLineFetchError(e);
                    }
                }
            }
        }).start();
    }

    /**
     * 判断是否为"-1"特殊地址：去掉query/fragment后，路径末段以"-1"结尾。
     * 替代原先的 contains("-1") 全文匹配，避免恰好含"-1"的普通地址被误判进特殊管线。
     */
    private static boolean isMinusOneUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        int cut = url.indexOf('?');
        int hash = url.indexOf('#');
        if (hash >= 0 && (cut < 0 || hash < cut)) cut = hash;
        String path = cut >= 0 ? url.substring(0, cut) : url;
        int slash = path.lastIndexOf('/');
        String lastSegment = slash >= 0 ? path.substring(slash + 1) : path;
        return lastSegment.endsWith("-1");
    }

    /**
     * -1 URL特殊处理
     */
    private void handleMinusOneUrl(final MoreSourceBean storeBean, final String fetchUrl, final String configKey, final String rawUrl, final int depth,
                                   final String cacheKey, final long cacheTimeMs) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 与配置地址入口同栈：使用全局客户端（含DoH/超时配置），裸客户端会绕过DoH
                    okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                    if (client == null) {
                        client = new okhttp3.OkHttpClient.Builder()
                                .followRedirects(true).followSslRedirects(true).build();
                    }
                    okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().url(fetchUrl);
                    if (fetchUrl.startsWith("https://gitcode")) {
                        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                        requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    } else {
                        requestBuilder.header("User-Agent", "Mozilla/5.0");
                    }
                    String body;
                    try (okhttp3.Response response = client.newCall(requestBuilder.build()).execute()) {
                        // 检查HTTP状态码：非2xx（本地文件不存在时服务器返回500）不能当线路内容解析
                        if (!response.isSuccessful()) {
                            throw new IllegalStateException("HTTP " + response.code());
                        }
                        if (response.body() == null) {
                            throw new IllegalStateException("响应内容为空");
                        }
                        body = response.body().string();
                    }
                    // 写入缓存（缓存原始响应）。此前-1管线从不写缓存，异常路径
                    // "网络异常时回退缓存"永远是死代码；写缓存规则与fetchLineFromNetwork一致
                    if (cacheTimeMs != 0 && !fetchUrl.startsWith("http://127.0.0.1")) {
                        writeLineCache(cacheKey, body, cacheTimeMs);
                    }
                    if (body != null && !body.isEmpty()) {
                        // 复用 ApiConfig.FindResult 解密
                        body = ApiConfig.FindResult(body, configKey);
                        if (rawUrl != null && (rawUrl.startsWith("clan://") || rawUrl.startsWith("file://"))) {
                            body = ApiConfig.get().clanContentFix(fetchUrl, body);
                        }
                        body = ApiConfig.get().fixContentPath(rawUrl != null ? rawUrl : fetchUrl, body);
                        try {
                            final String finalBody = body;
                            org.json.JSONObject jsonObject = new org.json.JSONObject(finalBody);
                            if (jsonObject.has("urls")) {
                                mActivity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        showLineSelectDialogWithCacheInfo(parseUrlsFromJson(finalBody));
                                    }
                                });
                                return;
                            }
                        } catch (Exception ignored) {}
                        // 无 urls（storeHouse/直接配置等其它形态）：回退通用解析管线兜底，
                        // 原先直接报"线路获取失败"，其它格式的仓库在这个入口完全无法展开
                        parseAndShowLinesFromJson(body, fetchUrl, storeBean, configKey, rawUrl, depth);
                        return;
                    }
                    if (!mActivity.isFinishing()) {
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(mActivity, "线路获取失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                    String cachedBody = readLineCache(cacheKey);
                    if (cachedBody != null && !cachedBody.isEmpty()) {
                        String decrypted = ApiConfig.FindResult(cachedBody, configKey);
                        final String finalDecrypted = decrypted;
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                currentLineCacheTime = 0;
                                showLineSelectDialogWithCacheInfo(parseUrlsFromJson(finalDecrypted));
                            }
                        });
                    } else {
                        showLineFetchError(e);
                    }
                }
            }
        }).start();
    }

    /**
     * 解析JSON并显示线路选择
     */
    private void parseAndShowLinesFromJson(String body, String fetchUrl, MoreSourceBean storeBean, String configKey, String rawUrl) {
        parseAndShowLinesFromJson(body, fetchUrl, storeBean, configKey, rawUrl, 0);
    }

    /**
     * 统一形态分发（识别层=ConfigFetcher.parse的三态Payload，与多仓对话框/自动加载同判据）：
     * 1. storeHouse子仓库 → 合并进仓库列表；无线路时重定向第一个仓库继续取线路（递归，depth≤3）
     * 2. urls线路 → 合并历史线路后弹选择
     * 3. 直接配置(sites/spider/lives/wallpaper) → 该URL本身就是一条线路，自动加载
     * <p>
     * 原先靠「单条无名线路=重定向」隐式信号判断storeHouse，但parseStoreLines用
     * HistoryHelper.buildApiLine会把空名字回填成url，信号永假 → 死代码，
     * storeHouse形态掉进「未获取到线路」；现改为显式按形态分发。
     */
    private void parseAndShowLinesFromJson(String body, String fetchUrl, MoreSourceBean storeBean, String configKey, String rawUrl, final int depth) {
        final ConfigFetcher.Payload payload = ConfigFetcher.parse(body);

        // storeHouse形态：合并子仓库。多个子仓库时弹仓库选择对话框让用户自己选
        // （不再自动重定向第一个子仓库）；只有一个时仍直接继续，省一次点击
        if (!payload.stores.isEmpty()) {
            mergeStoreBeans(payload.stores);
            if (payload.lines.isEmpty()) {
                if (payload.stores.size() > 1) {
                    final ArrayList<MoreSourceBean> candidates = new ArrayList<>(payload.stores);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!mActivity.isFinishing()) {
                                showStoreSelectDialog(candidates);
                            }
                        }
                    });
                    return;
                }
                if (depth >= 3) {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!mActivity.isFinishing()) {
                                Toast.makeText(mActivity, "仓库重定向层数过多，请检查仓库文件是否相互指向", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                    return;
                }
                final MoreSourceBean firstStore = payload.stores.get(0);
                if (firstStore.getSourceUrl() != null && !firstStore.getSourceUrl().isEmpty()) {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!mActivity.isFinishing()) {
                                fetchStoreLinesAndShow(firstStore, depth + 1);
                            }
                        }
                    });
                }
                return;
            }
        }

        // urls形态：合并历史线路中不重复的
        ArrayList<MoreSourceBean> historyList = getApiHistoryList();
        ArrayList<MoreSourceBean> allLines = new ArrayList<>(payload.lines);
        // 合并历史线路中不重复的
        for (MoreSourceBean historyBean : historyList) {
            boolean found = false;
            for (MoreSourceBean lineBean : allLines) {
                if (historyBean.getSourceUrl() != null && historyBean.getSourceUrl().equals(lineBean.getSourceUrl())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (historyBean.getSourceName() == null || historyBean.getSourceName().isEmpty()) {
                    historyBean.setSourceName("自定义配置地址" + (allLines.size() + 1));
                }
                historyBean.setShowDelete(true);
                allLines.add(historyBean);
            }
        }
        // 保存localLineUrls到storeBean
        ArrayList<MoreSourceBean> lineUrlsOnly = new ArrayList<>();
        for (MoreSourceBean line : allLines) {
            if (line.getSourceUrl() != null && !line.getSourceUrl().isEmpty()) {
                MoreSourceBean copy = new MoreSourceBean();
                copy.setSourceUrl(line.getSourceUrl());
                copy.setSourceName(line.getSourceName());
                lineUrlsOnly.add(copy);
            }
        }
        storeBean.setLocalLineUrls(lineUrlsOnly);
        // 更新仓库列表中的localLineUrls
        ArrayList<MoreSourceBean> storeList = getStoreHouseList();
        for (int i = 0; i < storeList.size(); i++) {
            if (storeList.get(i).getUniKey().equals(storeBean.getUniKey())) {
                storeList.set(i, storeBean);
                break;
            }
        }
        saveStoreHouseList(storeList);
        // 保存到API_LINE_LIST（合并不覆盖：用户手工添加的线路条目保留）
        ArrayList<String> finalLines = new ArrayList<>();
        for (MoreSourceBean bean : allLines) {
            if (bean.getSourceUrl() != null && !bean.getSourceUrl().isEmpty()) {
                finalLines.add(HistoryHelper.buildApiLine(bean.getSourceName(), bean.getSourceUrl()));
            }
        }
        if (!finalLines.isEmpty()) {
            HistoryHelper.mergeApiLineListAll(finalLines);
            Hawk.put(HawkConfig.API_LINE_SOURCE, fetchUrl);
        }
        // 在主线程显示对话框
        if (mActivity != null && !mActivity.isFinishing()) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (allLines.isEmpty()) {
                        // urls和storeHouse都没解析出来：该URL本身可能就是一份直接配置
                        if (payload.directConfig) {
                            // 该URL是直接配置线路：作为唯一候选弹选择框，用户确认后才落库加载
                            // （原先不问用户直接写API_URL并重启，与"让用户自己选择"不符）
                            final MoreSourceBean directLine = new MoreSourceBean();
                            directLine.setSourceUrl(rawUrl != null ? rawUrl : fetchUrl);
                            directLine.setSourceName(storeBean.getSourceName() != null && !storeBean.getSourceName().isEmpty()
                                    ? storeBean.getSourceName() : "直接配置");
                            directLine.setShowDelete(true);
                            allLines.add(directLine);
                            // 修复：原先确认前就把线路写进API_LINE_LIST/API_LINE_SOURCE、
                            // 把localLineUrls写进仓库列表，用户按返回取消后脏线路残留。
                            // 现在把这些持久化挪进确认回调，取消则什么都不写
                            showLineSelectDialogWithCacheInfo(allLines, new Runnable() {
                                @Override
                                public void run() {
                                    storeBean.setLocalLineUrls(new ArrayList<>(allLines));
                                    ArrayList<MoreSourceBean> storeList = getStoreHouseList();
                                    for (int i = 0; i < storeList.size(); i++) {
                                        if (storeList.get(i).getUniKey().equals(storeBean.getUniKey())) {
                                            storeList.set(i, storeBean);
                                            break;
                                        }
                                    }
                                    saveStoreHouseList(storeList);
                                    ArrayList<String> confirmLines = new ArrayList<>();
                                    confirmLines.add(HistoryHelper.buildApiLine(
                                            directLine.getSourceName(), directLine.getSourceUrl()));
                                    HistoryHelper.mergeApiLineListAll(confirmLines);
                                    Hawk.put(HawkConfig.API_LINE_SOURCE, fetchUrl);
                                }
                            });
                            return;
                        }
                        Toast.makeText(mActivity, "未获取到线路，请检查仓库地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showLineSelectDialogWithCacheInfo(allLines);
                }
            });
        }
    }

    /**
     * 合并解析出的storeHouse子仓库到仓库列表（只增不覆盖，按sourceUrl去重，
     * 与MoreSourceDialog.mergeStoreList语义一致）
     */
    private void mergeStoreBeans(ArrayList<MoreSourceBean> stores) {
        if (stores == null || stores.isEmpty()) return;
        ArrayList<MoreSourceBean> merged = getStoreHouseList();
        for (MoreSourceBean incoming : stores) {
            boolean exists = false;
            for (MoreSourceBean existing : merged) {
                if (incoming.getSourceUrl() != null
                        && incoming.getSourceUrl().equals(existing.getSourceUrl())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) merged.add(incoming);
        }
        saveStoreHouseList(merged);
    }

    // ===================== JSON解析辅助 =====================

    /**
     * 从JSON解析urls为MoreSourceBean列表
     */
    private ArrayList<MoreSourceBean> parseUrlsFromJson(String body) {
        try {
            org.json.JSONObject jsonObject = new org.json.JSONObject(body);
            // 统一走 ConfigFetcher.parseUrls：兼容对象数组与字符串数组两种写法
            return ConfigFetcher.parseUrls(jsonObject);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    // ===================== 数据存储辅助方法 =====================

    private ArrayList<MoreSourceBean> getStoreHouseList() {
        return ConfigRepository.getStoreHouseList();
    }

    private void saveStoreHouseList(ArrayList<MoreSourceBean> list) {
        ConfigRepository.saveStoreHouseList(list);
    }

    private MoreSourceBean getSelectedStoreHouse() {
        return ConfigRepository.getSelectedStoreHouse();
    }

    private void saveSelectedStoreHouse(MoreSourceBean bean) {
        ConfigRepository.saveSelectedStoreHouse(bean);
    }

    private ArrayList<MoreSourceBean> getApiHistoryList() {
        return ConfigRepository.getApiHistoryList();
    }

    private void saveApiHistoryList(ArrayList<MoreSourceBean> list) {
        ConfigRepository.saveApiHistoryList(list);
    }

    // ===================== 缓存方法 =====================

    private void writeLineCache(String cacheKey, String data, long cacheTimeMs) {
        ConfigRepository.writeLineCache(mActivity, cacheKey, data, cacheTimeMs);
    }

    private String readLineCache(String cacheKey) {
        return ConfigRepository.readLineCache(mActivity, cacheKey);
    }

    private boolean isLineCacheValid(String cacheKey, long cacheTimeMs) {
        return ConfigRepository.isLineCacheValid(mActivity, cacheKey, cacheTimeMs);
    }

    private long getLineCacheExpireTime(String cacheKey) {
        return ConfigRepository.getLineCacheExpireTime(mActivity, cacheKey);
    }

    // ===================== UI辅助 =====================

    private void showLineFetchError(final Exception e) {
        if (mActivity != null && !mActivity.isFinishing()) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mActivity, "线路获取失败" + (e != null ? e.getMessage() : ""), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private String buildCacheInfoTitle() {
        if (currentLineCacheTime == -1) {
            return "选择线路\n线路缓存永久生效";
        }
        long localExpire = getLineCacheExpireTime(currentLineCacheKey);
        if (localExpire <= 0) {
            return "选择线路\n当前使用接口数据";
        }
        // 剩余时间按当前CACHE_TIME封顶：旧缓存文件里的expire是按旧天数写的，
        // 用户调小天数后展示值不应超过新窗口（与isLineCacheValid的口径一致）
        long remaining = Math.min(localExpire - System.currentTimeMillis(), currentLineCacheTime);
        if (remaining <= 0) {
            return "选择线路\n当前使用接口数据";
        }
        return "选择线路\n线路缓存将于" + formatCacheTime(remaining) + "后更新";
    }

    private String formatCacheTime(long millis) {
        String[] units = {"天", "小时", "分钟", "秒", "毫秒"};
        int[] divisors = {86400000, 3600000, 60000, 1000, 1};
        int maxUnits = Math.min(2, 5);
        StringBuilder sb = new StringBuilder();
        if (millis == 0) {
            return "0" + units[0];
        }
        if (millis < 0) {
            sb.append("-");
            millis = -millis;
        }
        for (int i = 0; i < maxUnits; i++) {
            long div = divisors[i];
            if (millis >= div) {
                long count = millis / div;
                millis -= div * count;
                sb.append(count).append(units[i]);
            }
        }
        return sb.toString();
    }

    /**
     * 配置切换后重启应用（对话框版本：直接重启，不需要返回设置页）
     */
    private void restartAppAfterConfigChanged() {
        Toast.makeText(mActivity, "配置已切换,即将重新加载!", Toast.LENGTH_SHORT).show();
        try {
            SourceViewModel.clearRuntimeCache();
            LOG.i("echo-clear-config-switch-cache");
        } catch (Exception e) {
            LOG.i("echo-clear-config-switch-cache-error:" + e.getMessage());
            e.printStackTrace();
        }
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mActivity != null && !mActivity.isFinishing()) {
                    // 直接重启应用
                    Intent intent = mActivity.getPackageManager().getLaunchIntentForPackage(mActivity.getPackageName());
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        mActivity.startActivity(intent);
                        System.exit(0);
                    }
                }
            }
        }, 2500);
    }
}