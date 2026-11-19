package com.github.tvbox.osc.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

/**
 * 剪贴板工具类（新增）
 * 用于各配置对话框列表的"复制"按钮：一键复制仓库地址/线路地址/直播源地址/历史配置地址
 */
public final class ClipboardUtil {

    private ClipboardUtil() {
    }

    /**
     * 复制文本到系统剪贴板，并给出Toast提示
     *
     * @param context 上下文
     * @param label   剪贴板内容标签（如"仓库地址"）
     * @param text    要复制的文本；为空时提示未复制
     */
    public static void copyText(Context context, String label, String text) {
        if (context == null) {
            return;
        }
        if (text == null || text.isEmpty()) {
            Toast.makeText(context, "内容为空，未复制", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show();
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(context, "已复制" + (label == null || label.isEmpty() ? "" : label), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}