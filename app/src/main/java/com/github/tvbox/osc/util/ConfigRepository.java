package com.github.tvbox.osc.util;

import android.content.Context;

import com.github.tvbox.osc.bean.MoreSourceBean;
import com.google.gson.Gson;
import com.orhanobut.hawk.Hawk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 多仓/线路配置的唯一数据层（Single Source of Truth）。
 *
 * <p>本项目此前把同一套 Hawk 读写逻辑复制了四份：
 * {@code MoreSourceDialog}、{@code LineSwitchDialog}、{@code ModelSettingFragment}、
 * {@code UserFragment}，且实现逐渐分叉（例如只有 LineSwitchDialog 版本做了
 * R8 混淆残留坏元素的清洗）。这里统一收口，四个调用方全部改为调本类。</p>
 *
 * <p>注意：本类只做持久化，不含任何 UI 与网络操作。</p>
 */
public final class ConfigRepository {

    private ConfigRepository() {
    }

    // ===================== 仓库列表（custom_store_house） =====================

    /**
     * 读取仓库列表。
     * 读取时在内存中过滤 R8 混淆时代写入 Hawk 的坏元素（按旧混淆类名反序列化出的
     * 无关对象），避免调用方遍历强转时 ClassCastException 闪退。
     * 只在内存过滤、不回写不删除：反序列化瞬时异常或类型不匹配不应销毁用户数据，
     * 脏数据由下一次正常保存自然覆盖。
     */
    public static ArrayList<MoreSourceBean> getStoreHouseList() {
        return readBeanList(HawkConfig.CUSTOM_STORE_HOUSE);
    }

    public static void saveStoreHouseList(List<MoreSourceBean> list) {
        Hawk.put(HawkConfig.CUSTOM_STORE_HOUSE, list == null ? new ArrayList<MoreSourceBean>() : list);
    }

    /** 该键是否被持久化过（用于区分"从未保存"与"用户已全部删除"） */
    public static boolean hasStoreHouseList() {
        return Hawk.contains(HawkConfig.CUSTOM_STORE_HOUSE);
    }

    /**
     * 追加一个仓库到仓库列表（按url去重，置顶）。扫码推送等外部入口必须走这里
     * 直接写列表——只写旧键STORE_API时，列表一旦存在过，迁移分支永不触发，推送的仓库不可见。
     *
     * @return 是否新增（已存在同url仓库时返回false）
     */
    public static boolean addStoreHouse(String url, String name) {
        if (url == null || url.trim().isEmpty()) return false;
        ArrayList<MoreSourceBean> list = getStoreHouseList();
        for (MoreSourceBean bean : list) {
            if (url.equals(bean.getSourceUrl())) return false;
        }
        MoreSourceBean bean = new MoreSourceBean();
        bean.setSourceName(name == null || name.trim().isEmpty() ? "推送仓库" : name.trim());
        bean.setSourceUrl(url);
        bean.setShowDelete(true);
        list.add(0, bean);
        saveStoreHouseList(list);
        return true;
    }

    // ===================== 选中的仓库（custom_store_house_selected） =====================

    public static MoreSourceBean getSelectedStoreHouse() {
        try {
            String json = Hawk.get(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED, "");
            if (json == null || json.isEmpty()) return null;
            return new Gson().fromJson(json, MoreSourceBean.class);
        } catch (Exception e) {
            // 只读不删：反序列化异常不应销毁用户数据（与readBeanList口径一致）
            return null;
        }
    }

    public static void saveSelectedStoreHouse(MoreSourceBean bean) {
        if (bean == null) {
            clearSelectedStoreHouse();
            return;
        }
        Hawk.put(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED, new Gson().toJson(bean));
    }

    public static void clearSelectedStoreHouse() {
        Hawk.delete(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED);
    }

    // ===================== 历史线路（api_history_list） =====================

    public static ArrayList<MoreSourceBean> getApiHistoryList() {
        return readBeanList(HawkConfig.API_HISTORY_LIST);
    }

    public static void saveApiHistoryList(List<MoreSourceBean> list) {
        Hawk.put(HawkConfig.API_HISTORY_LIST, list == null ? new ArrayList<MoreSourceBean>() : list);
    }

    /**
     * 追加一条历史线路到 API_HISTORY_LIST（同url只保留一条并置顶，名字以新传入为准）。
     * 线路切换的统一落库入口之一，替代此前 handleDirectFormat 的内联写法。
     */
    public static void addApiHistory(String url, String name) {
        if (url == null || url.trim().isEmpty()) return;
        ArrayList<MoreSourceBean> list = getApiHistoryList();
        java.util.Iterator<MoreSourceBean> it = list.iterator();
        while (it.hasNext()) {
            if (url.equals(it.next().getSourceUrl())) {
                it.remove();
            }
        }
        MoreSourceBean bean = new MoreSourceBean();
        bean.setSourceUrl(url);
        bean.setSourceName(name == null || name.trim().isEmpty() ? "自定义配置线路" : name.trim());
        bean.setShowDelete(true);
        list.add(0, bean);
        saveApiHistoryList(list);
    }

    /**
     * 从所有仓库bean的localLineUrls里移除指定线路（按url匹配）。
     * 删除线路只清API_HISTORY_LIST/API_LINE_LIST的话，
     * 线路切换对话框每次打开会把仓库的localLineUrls合并回列表，被删线路会复活。
     */
    public static void removeLineFromAllStores(String lineUrl) {
        if (lineUrl == null || lineUrl.trim().isEmpty()) return;
        ArrayList<MoreSourceBean> stores = getStoreHouseList();
        boolean changed = false;
        for (MoreSourceBean store : stores) {
            List<MoreSourceBean> lines = store.getLocalLineUrls();
            if (lines == null || lines.isEmpty()) continue;
            boolean removed = false;
            java.util.Iterator<MoreSourceBean> it = lines.iterator();
            while (it.hasNext()) {
                MoreSourceBean line = it.next();
                if (lineUrl.equals(line.getSourceUrl())) {
                    it.remove();
                    removed = true;
                }
            }
            if (removed) {
                store.setLocalLineUrls(lines);
                changed = true;
            }
        }
        if (changed) saveStoreHouseList(stores);
    }

    // ===================== 当前仓库地址（store_api / store_api_name） =====================

    public static String getStoreApi() {
        return Hawk.get(HawkConfig.STORE_API, "");
    }

    public static String getStoreApiName() {
        return Hawk.get(HawkConfig.STORE_API_NAME, "");
    }

    public static void saveStoreApi(String url, String name) {
        if (url != null) {
            Hawk.put(HawkConfig.STORE_API, url);
        }
        if (name != null && !name.isEmpty()) {
            Hawk.put(HawkConfig.STORE_API_NAME, name);
        }
    }

    public static void clearStoreApi() {
        Hawk.delete(HawkConfig.STORE_API);
        Hawk.delete(HawkConfig.STORE_API_NAME);
    }

    // ===================== 线路列表（api_line_list） =====================

    public static ArrayList<String> getApiLineList() {
        try {
            Object raw = Hawk.<Object>get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
            if (raw instanceof List) {
                ArrayList<String> out = new ArrayList<>();
                for (Object item : (List<?>) raw) {
                    if (item instanceof String) out.add((String) item);
                }
                return out;
            }
        } catch (Exception e) {
            // 只读不删：反序列化异常不应销毁用户数据（与readBeanList口径一致）
        }
        return new ArrayList<>();
    }

    public static void saveApiLineList(List<String> list) {
        Hawk.put(HawkConfig.API_LINE_LIST, list == null ? new ArrayList<String>() : list);
    }

    public static String getCurrentApiUrl() {
        return Hawk.get(HawkConfig.API_URL, "");
    }

    // ===================== 线路缓存（参照 ysc zj0/x1 缓存核心） =====================

    private static final String CACHE_SPLIT = "---CACHE_SPLIT---";

    /**
     * 写入线路缓存
     *
     * @param cacheKey    缓存Key（"LINE_KEY" + url）
     * @param data        缓存数据（JSON字符串）
     * @param cacheTimeMs 缓存时间(毫秒)，-1为永久
     */
    public static void writeLineCache(Context context, String cacheKey, String data, long cacheTimeMs) {
        if (context == null) return;
        try {
            File cacheDir = context.getCacheDir();
            File cacheFile = cacheFile(cacheDir, cacheKey);
            long expireTime = cacheTimeMs == -1 ? Long.MAX_VALUE : (System.currentTimeMillis() + cacheTimeMs);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write((data + "\n" + CACHE_SPLIT + "\n" + expireTime).getBytes("UTF-8"));
                fos.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 读取线路缓存，无缓存返回 null */
    public static String readLineCache(Context context, String cacheKey) {
        if (context == null) return null;
        try {
            File cacheFile = cacheFile(context.getCacheDir(), cacheKey);
            if (!cacheFile.exists()) return null;
            // 读取端显式UTF-8（写入端固定UTF-8，FileReader走平台默认编码有编码不一致风险）
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(cacheFile), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(CACHE_SPLIT)) break;
                    // 逐行间补回换行：直接append会把多行内容压成一行，
                    // 带注释的仓库文件或非JSON内容会被破坏
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isLineCacheValid(Context context, String cacheKey, long cacheTimeMs) {
        if (cacheTimeMs == -1) return true;
        if (cacheTimeMs <= 0) return false;
        long expire = getLineCacheExpireTime(context, cacheKey);
        if (expire <= 0) return false;
        long remaining = expire - System.currentTimeMillis();
        // 剩余时间不得超过当前配置的窗口：缓存文件里的expire是写入时刻按当时的
        // CACHE_TIME算的，用户把天数调小（如7天→1天）后，仅比expire会让旧缓存
        // 仍按旧天数生效。正常写入的缓存剩余时间不可能超过当前窗口，超出即判无效，
        // 重新拉取后按新窗口落盘，老缓存文件无需迁移
        return remaining > 0 && remaining <= cacheTimeMs;
    }

    /** 获取缓存过期时间戳，无缓存返回 0 */
    public static long getLineCacheExpireTime(Context context, String cacheKey) {
        if (context == null) return 0;
        try {
            File cacheFile = cacheFile(context.getCacheDir(), cacheKey);
            if (!cacheFile.exists()) return 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(cacheFile), "UTF-8"))) {
                String line;
                long expireTime = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(CACHE_SPLIT)) {
                        String nextLine = reader.readLine();
                        if (nextLine != null) {
                            expireTime = Long.parseLong(nextLine.trim());
                        }
                        break;
                    }
                }
                return expireTime;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 清除全部线路缓存（多仓删除/清空、切换配置时调用） */
    public static void clearAllLineCache(Context context) {
        if (context == null) return;
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir == null || !cacheDir.exists()) return;
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.getName() != null
                        && (file.getName().startsWith("LINE_KEY") || file.getName().endsWith(".cache"))) {
                    file.delete();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 删除单个地址对应的线路缓存文件（删除仓库时定向清理，其余仓库缓存保留） */
    public static void deleteLineCache(Context context, String cacheKey) {
        if (context == null || cacheKey == null || cacheKey.isEmpty()) return;
        try {
            File cacheFile = cacheFile(context.getCacheDir(), cacheKey);
            if (cacheFile.exists()) {
                cacheFile.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static File cacheFile(File cacheDir, String cacheKey) {
        // 修复：仅把非法字符替换成"_"会让不同URL塌缩成同名文件（"a/x/y.json"与"a/x_y.json"、
        // 仅query不同的两个地址），缓存互相覆盖、串台。这里在清洗后的前缀之外追加整个
        // cacheKey 的MD5——不同key必然不同文件名；截断前缀同时规避文件名255字节上限。
        String sanitized = cacheKey.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return new File(cacheDir, sanitized + "_" + md5(cacheKey) + ".cache");
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    // ===================== 内部工具 =====================

    private static ArrayList<MoreSourceBean> readBeanList(String hawkKey) {
        try {
            Object raw = Hawk.<Object>get(hawkKey, new ArrayList<MoreSourceBean>());
            if (!(raw instanceof List)) {
                return new ArrayList<MoreSourceBean>();
            }
            ArrayList<MoreSourceBean> cleaned = new ArrayList<MoreSourceBean>();
            for (Object item : (List<?>) raw) {
                if (item instanceof MoreSourceBean) {
                    cleaned.add((MoreSourceBean) item);
                }
            }
            return cleaned;
        } catch (Exception e) {
            // 读失败只返回空列表：反序列化瞬时异常不应销毁用户数据
            return new ArrayList<MoreSourceBean>();
        }
    }
}
