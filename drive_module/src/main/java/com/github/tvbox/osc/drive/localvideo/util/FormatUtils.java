package com.github.tvbox.osc.drive.localvideo.util;

import java.util.Formatter;
import java.util.Locale;

/**
 * 展示格式化工具：时长、文件大小。
 *
 * <p>stringForTime 移植自原工程 Utils.stringForTime；
 * byte2FitSize 等价替代 blankj ConvertUtils.byte2FitMemorySize。</p>
 */
public final class FormatUtils {

    private FormatUtils() { }

    /** 毫秒 → "mm:ss" 或 "h:mm:ss"。 */
    public static String stringForTime(long timeMs) {
        long totalSeconds = timeMs / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb, Locale.getDefault());
        if (hours > 0) {
            return formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return formatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    /** 字节数 → "1.25GB" / "222.50MB" / "12.50KB" / "500B"（两位小数，去尾零）。 */
    public static String byte2FitSize(long byteSize) {
        if (byteSize < 0) return "0B";
        if (byteSize < 1024) return byteSize + "B";
        double kb = byteSize / 1024.0;
        if (kb < 1024) return trimZero(String.format(Locale.getDefault(), "%.2f", kb)) + "KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return trimZero(String.format(Locale.getDefault(), "%.2f", mb)) + "MB";
        double gb = mb / 1024.0;
        return trimZero(String.format(Locale.getDefault(), "%.2f", gb)) + "GB";
    }

    private static String trimZero(String value) {
        if (value.endsWith(".00")) return value.substring(0, value.length() - 3);
        if (value.endsWith("0")) return value.substring(0, value.length() - 1);
        return value;
    }
}
