package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

/**
 * 文件选择器（SAF / file://）返回 URI 的路径解析，
 * 供本地配置转换（ConfigFlow）与本地视频分流（LocalVideoLauncher）共用。
 */
public final class UriUtils {

    private UriUtils() {
    }

    /** 剥掉外部存储根路径前缀，转成 TVBox 可识别的 clan://localhost/... 地址 */
    public static String toClanOrFileApi(String path) {
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length()).replaceFirst("^/+", "");
        }
        return "file://" + path;
    }

    /**
     * 取 URI 对应的真实文件路径；
     * file:// 直接返回，外部存储 / Download 的 document URI 尽力转换，取不到返回 null。
     */
    public static String getPathFromUri(Context context, Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath();
            }
            if (DocumentsContract.isDocumentUri(context, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    String[] split = docId.split(":");
                    if (split.length > 1 && "primary".equalsIgnoreCase(split[0])) {
                        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + split[1];
                    }
                }
                if ("com.android.providers.downloads.documents".equals(uri.getAuthority())
                        && docId.startsWith("raw:")) {
                    return docId.substring(4);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
