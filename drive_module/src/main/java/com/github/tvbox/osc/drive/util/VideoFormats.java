package com.github.tvbox.osc.drive.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 指定视频格式白名单的唯一来源（drive_module 与 localvideo 共用）。
 *
 * <p>仅下列扩展名的文件被视为可播视频：</p>
 * <ul>
 *   <li>localvideo：{@link com.github.tvbox.osc.drive.localvideo.VideoScanner}
 *       在 MediaStore 查询层用该列表构造 selection（{@code _DATA LIKE '%.xxx'}），
 *       让数据库在查询时就过滤，减少 Cursor 遍历次数；</li>
 *   <li>网盘/本地存储浏览（LOCAL/WEBDAV/FTP/SMB/Alist/压缩包）：
 *       {@link StorageDriveType#isVideoType(String)} 按该列表判断，
 *       列表图标、点击播放、播放列表收录、压缩包内播放统一生效。</li>
 * </ul>
 *
 * <p>如需增删格式，只改 {@link #EXTENSIONS_WITH_DOT} 一处即可。</p>
 */
public final class VideoFormats {

    /** 指定的视频扩展名（带点，小写）。SQLite LIKE 对 ASCII 默认不区分大小写，".MP4" 同样能命中。 */
    private static final String[] EXTENSIONS_WITH_DOT = {
            ".mp4", ".mkv", ".avi", ".flv", ".wmv", ".mov", ".ts", ".webm"
    };

    private static final List<String> DOTTED = new ArrayList<>(Arrays.asList(EXTENSIONS_WITH_DOT));

    /** 无点小写扩展名集合（mp4 / mkv / ...），供按扩展名判断的场景使用。 */
    private static final List<String> BARE;
    static {
        BARE = new ArrayList<>(DOTTED.size());
        for (String ext : DOTTED) {
            BARE.add(ext.substring(1));
        }
    }

    private VideoFormats() { }

    /**
     * 带点扩展名列表（".mp4"、".mkv"...），用于 MediaStore selection 参数
     * （{@code _DATA LIKE '%.mp4'}）或文件名 endsWith 判断。
     */
    public static List<String> dottedExtensions() {
        return new ArrayList<>(DOTTED);
    }

    /**
     * 按无点扩展名判断是否为指定视频格式（大小写不敏感，兼容带点传入）。
     *
     * @param extension 扩展名，如 "mp4"、"MP4"、".mp4"（可为 null）
     */
    public static boolean isVideoExtension(String extension) {
        if (extension == null) return false;
        String ext = extension.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) ext = ext.substring(1);
        return BARE.contains(ext);
    }

    /**
     * 按文件名判断是否为指定视频格式（大小写不敏感）。
     *
     * @param fileName 文件全名，如 "movie.MP4"（可为 null）
     */
    public static boolean isVideoFileName(String fileName) {
        if (fileName == null) return false;
        String name = fileName.trim().toLowerCase(Locale.ROOT);
        for (String ext : DOTTED) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }
}
