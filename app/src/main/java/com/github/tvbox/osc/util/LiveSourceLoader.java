package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * 本地直播源的自动装载。
 *
 * <p>点选本地直播源文件（txt / m3u / m3u8）时，按内容特征确认是直播格式后，
 * 把文件地址写入 LIVE_API_URL 并记入直播源历史；直播页进入时经
 * ApiConfig.loadLiveConfig 加载（TxtSubscribe 同时解析 #genre# 分组与 m3u 两种格式），
 * 与手机推送直播源同链路。与 {@link PySpiderLoader} / {@link JsSpiderLoader}
 * 同一套入口分流模式，多仓/线路/设置页各入口共用。</p>
 */
public final class LiveSourceLoader {

    private static final String[] LIVE_SUFFIXES = {".txt", ".m3u", ".m3u8"};

    private LiveSourceLoader() {
    }

    /**
     * 本地直播源分流：点选的文件是直播格式时设置为当前直播源，返回 true 表示已消费。
     */
    public static boolean trySetLocalLiveSource(Context context, Uri fileUri) {
        if (context == null || fileUri == null) return false;
        String path = UriUtils.getPathFromUri(context, fileUri);
        if (path == null || path.isEmpty() || !isLiveFile(path)) return false;
        String content = readHead(new File(path));
        if (!looksLikeLiveContent(content)) return false;
        String address = resolveAddress(context, path);
        if (address == null || address.isEmpty()) return false;
        Hawk.put(HawkConfig.LIVE_API_URL, address);
        HistoryHelper.setLiveApiHistory(address);
        Toast.makeText(context, "本地直播源已设置，进入直播后生效", Toast.LENGTH_SHORT).show();
        return true;
    }

    private static boolean isLiveFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String suffix : LIVE_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * 直播格式特征：txt 分组标记（#genre#）或 m3u（#EXTM3U / #EXTINF）。
     * JSON / HTML 开头的一律不算——点播配置走原有配置解析，
     * 比推送链路"含逗号即认"的兜底更保守，避免把普通 txt 点播配置误吞成直播源。
     */
    private static boolean looksLikeLiveContent(String content) {
        if (content == null) return false;
        // UTF-8 BOM trim() 剥不掉，all.txt 等文件带 BOM
        String text = content.replace("\uFEFF", "").trim();
        if (text.startsWith("{") || text.startsWith("<")) return false;
        return text.contains("#genre#") || text.contains("#EXTM3U") || text.contains("#EXTINF");
    }

    private static String readHead(File file) {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] buffer = new byte[256 * 1024];
            int length = input.read(buffer);
            if (length <= 0) return null;
            return new String(buffer, 0, length, "UTF-8");
        } catch (Throwable th) {
            return null;
        } finally {
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 原文件在外部存储内直接引用；在外则复制到应用缓存再引用（缓存目录同在外部存储内，本地 /file/ 服务可读） */
    private static String resolveAddress(Context context, String path) {
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(storageRoot)) {
            return UriUtils.toClanOrFileApi(path);
        }
        try {
            File outDir = new File(context.getExternalCacheDir(), "config");
            if (!outDir.exists()) outDir.mkdirs();
            File out = new File(outDir, new File(path).getName());
            FileInputStream input = null;
            FileOutputStream output = null;
            try {
                input = new FileInputStream(path);
                output = new FileOutputStream(out);
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
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
            return UriUtils.toClanOrFileApi(out.getAbsolutePath());
        } catch (Throwable th) {
            th.printStackTrace();
            return "";
        }
    }
}
