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
 * 本地 js 爬虫的自动装载。
 *
 * <p>点选任意一个 .js 爬虫文件时，扫描同目录下的全部 .js 爬虫，
 * 自动包装成本地站点配置（每个文件一个 type=3 站点，api 用 file:// 绝对路径；
 * JsLoader/JsSpider 经 FileUtils.loadModule 读取 file:// 走本地文件服务，
 * 爬虫的相对 import 由 QuickJS moduleNormalizeName 按 api 所在目录解析），
 * 生成的配置地址交给调用方回填对话框，后续走正常配置加载流程。
 * 与 {@link PySpiderLoader} 同一套模式，多仓/线路/设置页各入口共用。</p>
 */
public final class JsSpiderLoader {

    private JsSpiderLoader() {
    }

    /**
     * .js 入口分流：点选任意一个 .js 爬虫时，扫描同目录下的全部 .js 爬虫并包装成本地站点配置。
     *
     * @return 生成的配置地址；非 .js 文件或生成失败时返回 ""，调用方回落原有单文件逻辑
     */
    public static String localJsDirConfigToApi(Context context, Uri uri) {
        String path = UriUtils.getPathFromUri(context, uri);
        if (path == null || path.isEmpty() || !path.toLowerCase(Locale.ROOT).endsWith(".js")) {
            return "";
        }
        return buildJsDirConfig(context, new File(path));
    }

    /**
     * FileUtils.loadModule 对这几个文件名会劫持到 assets 内置库（js/lib），
     * 包装成站点必然加载失败，扫描时直接跳过。
     */
    private static final String[] JS_LIB_NAMES = {"cat.js", "模板.js", "gbk.js"};

    /**
     * 判定 .js 是否实现爬虫回调（区别于被依赖的纯工具/库文件）：
     * drpy 风格含 __jsEvalReturn，ES module 风格含 export default，
     * 或直接定义了 init/home/category/detail/search/play 等回调函数（含 async function 写法）。
     */
    private static final String[] JS_SPIDER_MARKS = {
            "__jsEvalReturn", "export default",
            "function init", "function home", "function homeVod", "function category",
            "function detail", "function search", "function play", "function proxy"
    };

    private static String buildJsDirConfig(Context context, File picked) {
        try {
            File jsDir = picked.getParentFile();
            List<File> spiders = new ArrayList<>();
            File[] children = jsDir == null ? null : jsDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    String name = f.getName();
                    if (!f.isFile() || !name.toLowerCase(Locale.ROOT).endsWith(".js")) continue;
                    // 跳过隐藏文件与内置库名
                    if (name.startsWith(".") || isLibName(name)) continue;
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
                if (looksLikeJsSpider(f)) matched.add(f);
            }
            if (!matched.isEmpty()) {
                spiders = matched;
            } else if (!spiders.contains(picked)) {
                spiders.add(picked);
            }
            if (spiders.isEmpty()) return "";

            int outside = 0;
            JSONArray sites = new JSONArray();
            for (File f : spiders) {
                String name = f.getName();
                String stem = name.substring(0, name.length() - 3);
                // 站点 api 必须是 clan://localhost/<外置存储相对路径>。
                // 不能写 "file://" + 绝对路径：js 爬虫由 JsSpider 经 FileUtils.loadModule 读取，
                // 它对 file:// 会剥掉全部斜杠拼成 <服务地址>/file/<路径>，而服务端 /file/ 又
                // 把路径拼到 Environment.getExternalStorageDirectory() 之后，
                // 绝对路径会被拼成 /storage/emulated/0/storage/emulated/0/... 而取不到文件。
                // clan://localhost/ 分支是剥掉前缀后直接相对外置存储根取，才是正确语义。
                String api = UriUtils.toClanOrFileApi(f.getAbsolutePath());
                if (api.startsWith("file://")) {
                    // 不在外置存储根下（SD 卡 / U 盘等），本地文件服务读不到，装载了必然失败
                    outside++;
                    continue;
                }
                JSONObject site = new JSONObject();
                site.put("key", "js_" + stem);
                // name 保留 .js 扩展名：首页数据源处显示的就是它（HomeActivity tvName 显示 home.getName()），
                // 同一目录下 js / py 爬虫混放时靠扩展名区分。key 仍用不带扩展名的 stem 作内部标识。
                site.put("name", name);
                site.put("type", 3);
                site.put("api", api);
                site.put("searchable", 1);
                site.put("quickSearch", 1);
                site.put("filterable", 1);
                sites.put(site);
            }
            if (sites.length() == 0) {
                Toast.makeText(context, "js爬虫不在外置存储目录，本地文件服务读不到", Toast.LENGTH_SHORT).show();
                return "";
            }

            JSONObject config = new JSONObject();
            config.put("spider", "");
            config.put("sites", sites);

            File outDir = new File(context.getExternalCacheDir(), "config");
            if (!outDir.exists()) outDir.mkdirs();
            File out = new File(outDir, "js_spiders_"
                    + MD5.encode(jsDir == null ? picked.getAbsolutePath() : jsDir.getAbsolutePath()).substring(0, 12) + ".json");
            FileOutputStream output = new FileOutputStream(out);
            try {
                output.write(config.toString().getBytes("UTF-8"));
            } finally {
                output.close();
            }
            String msg = "已自动装载同目录 " + sites.length() + " 个js爬虫";
            if (outside > 0) msg += "，" + outside + " 个不在外置存储已跳过";
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            return UriUtils.toClanOrFileApi(out.getAbsolutePath());
        } catch (Throwable th) {
            th.printStackTrace();
            return "";
        }
    }

    private static boolean isLibName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String lib : JS_LIB_NAMES) {
            if (lower.equals(lib)) return true;
        }
        return false;
    }

    private static boolean looksLikeJsSpider(File f) {
        FileInputStream input = null;
        try {
            input = new FileInputStream(f);
            byte[] buffer = new byte[256 * 1024];
            int length = input.read(buffer);
            if (length <= 0) return false;
            String content = new String(buffer, 0, length, "UTF-8");
            for (String mark : JS_SPIDER_MARKS) {
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
