package com.github.tvbox.osc.drive.util;

import android.content.Context;

import com.github.tvbox.osc.drive.R;

/**
 * 存储盘类型枚举及视频格式判断工具。
 * [P2修复] getTypeNames() 改为接受 Context 参数，从 strings.xml 读取，支持国际化。
 * <p>
 * [指定格式] {@link #isVideoType(String)} 改为按 {@link VideoFormats} 指定白名单判断
 * （.mp4/.mkv/.avi/.flv/.wmv/.mov/.ts/.webm），与 localvideo 的 MediaStore
 * 查询层过滤共用同一份格式表，两个入口行为完全一致；网盘浏览的列表图标、
 * 点击播放、播放列表收录、压缩包内播放均经此判断，一处生效。
 */
public class StorageDriveType {

    public enum TYPE {
        LOCAL,
        WEBDAV,
        ALISTWEB,
        FTP,
        FTP_SERVER,
        SMB,
    }

    // [P2修复] 从字符串资源读取，支持多语言
    public static String[] getTypeNames(Context context) {
        return new String[]{
                context.getString(R.string.drive_type_local),
                context.getString(R.string.drive_type_webdav),
                context.getString(R.string.drive_type_alist),
                context.getString(R.string.drive_type_ftp),
                context.getString(R.string.drive_type_ftp_server),
                context.getString(R.string.drive_type_smb),
        };
    }

    public static boolean isVideoType(String type) {
        // [指定格式] 统一走 VideoFormats 白名单（与 localvideo 查询层同一份格式表）
        return VideoFormats.isVideoExtension(type);
    }
}