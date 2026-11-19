package com.github.tvbox.osc.drive.localvideo.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 存储读取权限工具（原生 API 实现）。
 *
 * <p><b>权限策略与 drive_module 原先保持一致</b>：仅申请
 * {@link Manifest.permission#READ_EXTERNAL_STORAGE}，
 * 不在 Android 13+ 切换到 READ_MEDIA_VIDEO。理由：</p>
 * <ul>
 *   <li>drive_module AndroidManifest 历来只声明 READ/WRITE_EXTERNAL_STORAGE 两项；</li>
 *   <li>宿主 TVBox 类应用通常已授予 MANAGE_EXTERNAL_STORAGE（所有文件访问），
 *       {@link Environment#isExternalStorageManager()} 直接通过；</li>
 *   <li>统一一份权限申请文案，避免在不同 Android 版本下用户看到不同弹窗。</li>
 * </ul>
 *
 * <p>满足其一即可扫描：</p>
 * <ul>
 *   <li>Android 11+：宿主已获得"所有文件访问"（MANAGE_EXTERNAL_STORAGE）授权；</li>
 *   <li>任意版本：已授予 READ_EXTERNAL_STORAGE。</li>
 * </ul>
 */
public final class StoragePermission {

    /** 与 DriveActivity 中 REQUEST_CODE_PERMISSION 错开，避免冲突 */
    public static final int REQUEST_CODE = 10086;

    private StoragePermission() { }

    public static boolean has(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Environment.isExternalStorageManager()) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void request(Activity activity) {
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                REQUEST_CODE);
    }
}
