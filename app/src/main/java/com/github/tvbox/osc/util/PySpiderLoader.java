package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 本地 py 爬虫的自动装载。
 *
 * <p>点选任意一个 .py 爬虫文件时，扫描同目录下的全部 .py 爬虫，
 * 自动包装成本地站点配置（每个文件一个 type=3 站点，api 用 file:// 绝对路径直读磁盘），
 * 生成的配置地址交给调用方回填对话框，后续走正常配置加载流程。
 * 由 ConfigFlow.localConfigToApi 在 .py 分流处调用，多仓/线路/设置页各入口共用。</p>
 */
public final class PySpiderLoader {

    private PySpiderLoader() {
    }

    /**
     * .py 入口分流：点选任意一个 .py 爬虫时，扫描同目录下的全部 .py 爬虫并包装成本地站点配置。
     *
     * @return 生成的配置地址；非 .py 文件或生成失败时返回 ""，调用方回落原有单文件逻辑
     */
    public static String localPyDirConfigToApi(Context context, Uri uri) {
        String path = UriUtils.getPathFromUri(context, uri);
        if (path == null || path.isEmpty() || !path.toLowerCase(Locale.ROOT).endsWith(".py")) {
            return "";
        }
        return buildPyDirConfig(context, new File(path));
    }

    /** 判定 .py 是否实现爬虫回调（区别于被依赖的纯工具/库文件） */
    private static final String[] PY_SPIDER_MARKS = {
            "def homeContent", "def homeVideoContent", "def categoryContent",
            "def detailContent", "def searchContent", "def playerContent"
    };

    /**
     * 批量导入：把用户多选的一批 py 文件各自生成一个 type=3 站点，合并成本地站点配置，
     * 返回 clan:// 地址交给配置页直接生效。与 {@link #buildPyDirConfig} 站点结构完全一致，
     * 区别仅在文件来源是显式多选列表而非"同目录扫描"。
     *
     * @param pyPaths 选中的 py 绝对路径列表
     * @return 配置地址；空列表或生成失败返回 ""
     */
    public static String batchPyListConfigToApi(Context context, List<String> pyPaths) {
        if (pyPaths == null || pyPaths.isEmpty()) return "";
        List<File> spiders = new ArrayList<File>();
        for (String p : pyPaths) {
            if (p == null || p.isEmpty()) continue;
            File f = new File(p);
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".py")) {
                spiders.add(f);
            }
        }
        if (spiders.isEmpty()) return "";
        // 种子加文件数与首个文件路径，避免与其他来源的配置互相覆盖
        String seed = "batch_" + spiders.size() + "_" + spiders.get(0).getAbsolutePath();
        return buildSitesConfig(context, spiders, seed);
    }

    private static String buildPyDirConfig(Context context, File picked) {
        try {
            File pyDir = picked.getParentFile();
            List<File> spiders = new ArrayList<>();
            File[] children = pyDir == null ? null : pyDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    String name = f.getName();
                    if (!f.isFile() || !name.toLowerCase(Locale.ROOT).endsWith(".py")) continue;
                    // 跳过隐藏文件与 __init__ 之类的包文件
                    if (name.startsWith(".") || name.startsWith("_")) continue;
                    spiders.add(f);
                }
            }
            Collections.sort(spiders, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            // 只保留实现爬虫回调的文件；一个都筛不出来时（内容特征不匹配）退回全量
            List<File> matched = new ArrayList<>();
            for (File f : spiders) {
                if (looksLikePySpider(f)) matched.add(f);
            }
            if (!matched.isEmpty()) {
                spiders = matched;
            } else if (!spiders.contains(picked)) {
                spiders.add(picked);
            }
            if (spiders.isEmpty()) return "";

            String seed = pyDir == null ? picked.getAbsolutePath() : pyDir.getAbsolutePath();
            String api = buildSitesConfig(context, spiders, seed);
            if (!api.isEmpty()) {
                Toast.makeText(context, "已自动装载同目录 " + spiders.size() + " 个py爬虫", Toast.LENGTH_SHORT).show();
            }
            return api;
        } catch (Throwable th) {
            th.printStackTrace();
            return "";
        }
    }

    /**
     * 把给定 py 文件列表包装成本地站点配置 json 并写入缓存，返回 clan:// 地址。
     *
     * @param spiders 要装载的 py 文件
     * @param seed    生成配置文件名用的种子（同一来源复用同一文件）
     */
    private static String buildSitesConfig(Context context, List<File> spiders, String seed) {
        try {
            JSONArray sites = new JSONArray();
            for (File f : spiders) {
                String name = f.getName();
                String stem = name.substring(0, name.length() - 3);
                JSONObject site = new JSONObject();
                site.put("key", "py_" + stem);
                // name 保留 .py 扩展名，与 JsSpiderLoader 一致：首页数据源处显示的就是它
                // （HomeActivity tvName 显示 home.getName()），key 仍用不带扩展名的 stem 作内部标识
                site.put("name", name);
                site.put("type", 3);
                // file:// 直读磁盘：pyLoader 的 downloadPlugin 对 file:// 不走网络下载，
                // 爬虫声明的同目录依赖也能按相对路径解析到本目录
                site.put("api", "file://" + f.getAbsolutePath());
                site.put("searchable", 1);
                site.put("quickSearch", 1);
                site.put("filterable", 1);
                sites.put(site);
            }
            JSONObject config = new JSONObject();
            config.put("spider", "");
            config.put("sites", sites);

            File outDir = new File(context.getExternalCacheDir(), "config");
            if (!outDir.exists()) outDir.mkdirs();
            File out = new File(outDir, "py_spiders_" + MD5.encode(seed).substring(0, 12) + ".json");
            FileOutputStream output = new FileOutputStream(out);
            try {
                output.write(config.toString().getBytes("UTF-8"));
            } finally {
                output.close();
            }
            return UriUtils.toClanOrFileApi(out.getAbsolutePath());
        } catch (Throwable th) {
            th.printStackTrace();
            return "";
        }
    }

    private static boolean looksLikePySpider(File f) {
        FileInputStream input = null;
        try {
            input = new FileInputStream(f);
            byte[] buffer = new byte[256 * 1024];
            int length = input.read(buffer);
            if (length <= 0) return false;
            String content = new String(buffer, 0, length, "UTF-8");
            for (String mark : PY_SPIDER_MARKS) {
                if (content.contains(mark)) return true;
            }
            return false;
        } catch (Throwable th) {
            return false;
        } finally {
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
