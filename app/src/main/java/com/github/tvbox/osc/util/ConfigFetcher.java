package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.MoreSourceBean;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 配置内容（仓库文件 / 线路文件）的唯一抓取与解析层。
 *
 * <p>支持三类地址，走完全相同的解析管线，因此本地文件与网络文件行为一致：</p>
 * <ul>
 *   <li>{@code clan://localhost/tvboxqq/000.json} —— 设备本地（经本地服务转发）</li>
 *   <li>{@code file://tvboxqq/001.json} —— 设备本地（configUrl 内部转成 clan://）</li>
 *   <li>{@code http(s)://...} —— 网络</li>
 * </ul>
 *
 * <p>此前这段"取内容 → 解密 → 路径修复 → 判格式"的代码在多仓对话框里有三份
 * 近似实现（自动加载第一个仓库、添加仓库、拉取线路），且客户端构造方式不一致
 * （有的用 OkGoHelper 全局客户端、有的自建裸客户端），导致同一地址在不同入口
 * 拉取结果不同。这里统一收口。</p>
 */
public final class ConfigFetcher {

    private ConfigFetcher() {
    }

    public interface Callback {
        void onSuccess(String body);

        void onError(String msg);
    }

    /**
     * URL规范化 + 密钥提取的原子操作。
     *
     * <p>ApiConfig.TempKey 是单例字段：configUrl() 写、getTempKey() 读。
     * 原项目 loadConfig 只在主线程调用没有并发问题；本项目的抓取都在后台线程，
     * 两步分开调用存在竞态——A线程执行完 configUrl 后 B线程抢先执行了自己的
     * configUrl 覆盖 TempKey，A 再读密钥就读到 B 的，AES-ECB 解出乱码、
     * JSON 解析静默失败。锁统一用 ApiConfig 单例实例——loadConfig/loadLiveConfig
     * 内部的原子段也 synchronized(this)，两个写入口互斥。</p>
     *
     * @return [0]=规范化后的请求URL（尚未做百分号编码），[1]=;pk;解密密钥（可能为null）
     */
    public static String[] resolve(String rawUrl) {
        ApiConfig apiConfig = ApiConfig.get();
        String fetchUrl;
        String tempKey;
        synchronized (apiConfig) {
            fetchUrl = apiConfig.configUrl(rawUrl);
            tempKey = apiConfig.getTempKey();
        }
        return new String[]{fetchUrl, tempKey};
    }

    /**
     * 异步抓取配置内容。子线程请求，主线程回调。
     *
     * @param rawUrl 用户填写的原始地址（本地或网络均可）
     */
    public static void fetch(final String rawUrl, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String body;
                try {
                    body = fetchSync(rawUrl);
                } catch (final Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    final String msg = e.getMessage() == null ? "网络请求失败" : e.getMessage();
                    post(callback, null, msg);
                    return;
                }
                post(callback, body, null);
            }
        }, "cfg-fetch").start();
    }

    /**
     * 同步抓取配置内容（调用方需自行保证在子线程），不读写线路缓存。
     */
    public static String fetchSync(String rawUrl) throws Exception {
        return fetchSync(rawUrl, null);
    }

    /**
     * 同步抓取配置内容（调用方需自行保证在子线程）。
     * 完整管线：URL 规范化 → 编码 → 缓存检查 → HTTP GET → 解密(FindResult) → 本地路径修复。
     * 传入context时读写线路缓存（键与LineSwitchDialog一致："LINE_KEY"+fetchUrl），
     * 使多仓对话框与线路切换两个入口对同一地址的时效性一致；此前每次实发请求。
     */
    public static String fetchSync(String rawUrl, android.content.Context context) throws Exception {
        ApiConfig apiConfig = ApiConfig.get();
        String[] resolved = resolve(rawUrl);
        String fetchUrl = encodeUnsafeUrlChars(resolved[0]);
        String configKey = resolved[1];

        String cacheKey = lineCacheKey(fetchUrl, configKey);
        int cacheDays = com.orhanobut.hawk.Hawk.get(com.github.tvbox.osc.util.HawkConfig.CACHE_TIME, 1);
        long cacheTimeMs = cacheDays == -1 ? -1L : (long) cacheDays * 24 * 60 * 60 * 1000;
        boolean useCache = context != null
                && !fetchUrl.startsWith("http://127.0.0.1")
                && cacheDays != 0;
        if (useCache) {
            String cached = com.github.tvbox.osc.util.ConfigRepository.readLineCache(context, cacheKey);
            if (cached != null && !cached.isEmpty()
                    && com.github.tvbox.osc.util.ConfigRepository.isLineCacheValid(context, cacheKey, cacheTimeMs)) {
                // 缓存命中且有效：缓存存的是原始响应，走与网络响应完全相同的后处理管线
                String body = ApiConfig.FindResult(cached, configKey);
                if (rawUrl.startsWith("clan://") || rawUrl.startsWith("file://")) {
                    body = apiConfig.clanContentFix(fetchUrl, body);
                }
                body = apiConfig.fixContentPath(rawUrl, body);
                return body;
            }
        }

        okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
        if (client == null) {
            client = new okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().url(fetchUrl);
        if (fetchUrl.startsWith("https://gitcode")) {
            requestBuilder.header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        } else {
            requestBuilder.header("User-Agent", "Mozilla/5.0");
        }
        String body;
        try (okhttp3.Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String errBody = "";
                try {
                    if (response.body() != null) errBody = response.body().string();
                } catch (Exception ignored) {
                }
                String hint = errBody == null ? "" : errBody.trim().replaceAll("\\s+", " ");
                if (hint.length() > 80) hint = hint.substring(0, 80);
                throw new IllegalStateException("HTTP " + response.code() + (hint.isEmpty() ? "" : "：" + hint));
            }
            if (response.body() == null) {
                throw new IllegalStateException("响应内容为空");
            }
            body = response.body().string();
        }

        // 缓存原始响应（解密后缓存无意义，与LineSwitchDialog同规则）
        if (useCache) {
            com.github.tvbox.osc.util.ConfigRepository.writeLineCache(context, cacheKey, body, cacheTimeMs);
        }
        body = ApiConfig.FindResult(body, configKey);
        if (rawUrl.startsWith("clan://") || rawUrl.startsWith("file://")) {
            body = apiConfig.clanContentFix(fetchUrl, body);
        }
        body = apiConfig.fixContentPath(rawUrl, body);
        return body;
    }

    /**
     * 计算与 fetchSync(url, context) 写入时完全一致的线路缓存key（含URL规范化与编码）。
     * 删除单个仓库时定向清理缓存用：key对不上就删不掉目标文件。
     */
    public static String lineCacheKey(String rawUrl) {
        String[] resolved = resolve(rawUrl);
        return lineCacheKey(encodeUnsafeUrlChars(resolved[0]), resolved[1]);
    }

    /**
     * 线路缓存key：规范化后的fetchUrl + 密钥哈希。
     * 密钥必须并入key：fetchUrl已剥掉;pk;，明文版与pk版同一地址若共用缓存文件，
     * 先明文缓存、后带pk请求会命中明文缓存再拿pk解密，解出乱码。
     * 明文（configKey为空）不加后缀，与历史缓存文件保持兼容。
     */
    public static String lineCacheKey(String fetchUrl, String configKey) {
        String key = "LINE_KEY" + fetchUrl;
        if (configKey != null && !configKey.isEmpty()) {
            key += "#PK" + MD5.encode(configKey);
        }
        return key;
    }

    private static void post(final Callback callback, final String body, final String error) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (error == null) {
                    callback.onSuccess(body);
                } else {
                    callback.onError(error);
                }
            }
        });
    }

    // ===================== 解析 =====================

    /** 内容是否是"直接配置"（含站点/爬虫/直播/壁纸任一段），区别于"仓库索引" */
    public static boolean isDirectConfig(String body) {
        try {
            org.json.JSONObject jo = new org.json.JSONObject(extractJsonObject(body));
            return jo.has("sites") || jo.has("spider") || jo.has("lives") || jo.has("wallpaper");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static class Payload {
        /** storeHouse 段落解析出的子仓库（本地/网络仓库文件都可能带） */
        public final ArrayList<MoreSourceBean> stores = new ArrayList<>();
        /** urls 段落解析出的线路 */
        public final ArrayList<MoreSourceBean> lines = new ArrayList<>();
        /** 是否本身就是一份可直接使用的配置 */
        public boolean directConfig;

        public boolean isEmpty() {
            return stores.isEmpty() && lines.isEmpty() && !directConfig;
        }
    }

    /**
     * 解析配置内容：识别 storeHouse / urls / 直接配置 三种形态。
     * 解析失败返回空 Payload。
     */
    public static Payload parse(String body) {
        Payload payload = new Payload();
        if (body == null || body.trim().isEmpty()) return payload;
        try {
            org.json.JSONObject jo = new org.json.JSONObject(extractJsonObject(body));
            payload.directConfig = jo.has("sites") || jo.has("spider")
                    || jo.has("lives") || jo.has("wallpaper");
            payload.stores.addAll(parseStoreHouse(jo));
            payload.lines.addAll(parseUrls(jo));
        } catch (Exception ignored) {
        }
        return payload;
    }

    /** 解析 storeHouse 段（按 sourceUrl 去重，保留首次出现顺序） */
    public static ArrayList<MoreSourceBean> parseStoreHouse(org.json.JSONObject jo) {
        ArrayList<MoreSourceBean> out = new ArrayList<>();
        try {
            if (!jo.has("storeHouse")) return out;
            Object obj = jo.get("storeHouse");
            if (!(obj instanceof org.json.JSONArray)) return out;
            org.json.JSONArray array = (org.json.JSONArray) obj;
            LinkedHashMap<String, MoreSourceBean> map = new LinkedHashMap<>();
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject item = array.getJSONObject(i);
                String name = item.optString("sourceName", "");
                String url = item.optString("sourceUrl", "");
                if (url == null || url.isEmpty()) continue;
                MoreSourceBean exist = map.get(url);
                if (exist == null) {
                    MoreSourceBean bean = new MoreSourceBean();
                    bean.setSourceName(name == null ? "" : name);
                    bean.setSourceUrl(url);
                    bean.setShowDelete(true);
                    map.put(url, bean);
                } else if (name != null && !name.isEmpty()) {
                    exist.setSourceName(name);
                }
            }
            out.addAll(map.values());
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 解析 urls 段（支持对象数组与字符串数组两种写法）。
     * 按url去重、保留首次出现：url是唯一身份（getUniKey只取url），
     * 同url不同名的两条记录在后续LinkedHashMap去重里会互相覆盖，但列表会重复渲染。
     */
    public static ArrayList<MoreSourceBean> parseUrls(org.json.JSONObject jo) {
        ArrayList<MoreSourceBean> out = new ArrayList<>();
        try {
            if (!jo.has("urls")) return out;
            Object obj = jo.get("urls");
            if (!(obj instanceof org.json.JSONArray)) return out;
            org.json.JSONArray array = (org.json.JSONArray) obj;
            LinkedHashMap<String, MoreSourceBean> map = new LinkedHashMap<>();
            for (int i = 0; i < array.length(); i++) {
                Object element = array.get(i);
                MoreSourceBean bean = new MoreSourceBean();
                if (element instanceof org.json.JSONObject) {
                    org.json.JSONObject item = (org.json.JSONObject) element;
                    String url = item.optString("url", "");
                    if (url.isEmpty()) url = item.optString("api", "");
                    bean.setSourceUrl(url);
                    bean.setSourceName(item.optString("name", ""));
                } else if (element instanceof String) {
                    bean.setSourceUrl((String) element);
                } else {
                    continue;
                }
                if (bean.getSourceUrl() == null || bean.getSourceUrl().isEmpty()) continue;
                bean.setShowDelete(true);
                if (!map.containsKey(bean.getSourceUrl())) {
                    map.put(bean.getSourceUrl(), bean);
                }
            }
            out.addAll(map.values());
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 截取首尾花括号之间的内容，容忍配置前后的干扰字符/注释 */
    public static String extractJsonObject(String body) {
        if (body == null) return "";
        String json = body.trim();
        int start = json.indexOf("{");
        int end = json.lastIndexOf("}");
        if (start < 0 || end <= start) return json;
        return json.substring(start, end + 1);
    }

    /**
     * 规范化请求URL：对非ASCII字符（如中文"宜优精选.json"）与空格做 UTF-8 百分号编码，
     * 保留 URL 结构字符原样，正确处理 emoji 等代理对。
     */
    public static String encodeUnsafeUrlChars(String url) {
        if (url == null) return url;
        boolean need = false;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c > 127 || c == ' ') {
                need = true;
                break;
            }
        }
        if (!need) return url;
        StringBuilder sb = new StringBuilder(url.length() + 48);
        int i = 0;
        final int n = url.length();
        while (i < n) {
            char c = url.charAt(i);
            if (c == ' ') {
                sb.append("%20");
                i++;
            } else if (c <= 127) {
                sb.append(c);
                i++;
            } else {
                int j = i;
                while (j < n && url.charAt(j) > 127) j++;
                try {
                    byte[] bytes = url.substring(i, j).getBytes("UTF-8");
                    for (byte b : bytes) {
                        sb.append('%').append(String.format("%02X", b));
                    }
                } catch (Exception e) {
                    sb.append(url.substring(i, j));
                }
                i = j;
            }
        }
        return sb.toString();
    }
}
