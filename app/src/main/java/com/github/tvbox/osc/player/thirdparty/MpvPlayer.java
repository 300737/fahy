package com.github.tvbox.osc.player.thirdparty;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Parcelable;
import android.os.StrictMode;
import android.util.Log;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.util.HashMap;

/**
 * 外部 MPV 播放器（mpv-android）
 * <p>
 * Intent 规范参考 mpv-android 官方开源文档与源码:
 * <ul>
 *     <li>文档: http://mpv-android.github.io/mpv-android/intent.html</li>
 *     <li>源码: https://github.com/mpv-android/mpv-android</li>
 * </ul>
 * 支持的 extras（均为可选）:
 * <ul>
 *     <li>decode_mode (Byte): 2 表示禁用硬件解码</li>
 *     <li>subs (ParcelableArray of Uri): 额外字幕轨道列表</li>
 *     <li>subs.enable (ParcelableArray of Uri): 默认选中的字幕（subs 的子集）</li>
 *     <li>position (Int): 起始播放位置，单位毫秒</li>
 *     <li>title (String): 媒体标题（force-media-title）</li>
 * </ul>
 * 注意: mpv-android 暂不支持通过 Intent 传递 HTTP 请求头（源码中 headers 仍为 TODO），
 * 因此这里不能像 Kodi/MXPlayer 那样把 headers 以 "|" 拼接到 URL 后面，否则会破坏 URL 导致无法播放。
 * 本地路径（如 /storage/emulated/0/xxx.mp4）会自动转换为 file:// URI（mpv 的 resolveUri()
 * 只接受带 scheme 的 URI），并在启动时临时绕过 Android 7.0+ 的 FileUriExposedException 检查。
 */
public class MpvPlayer {
    public static final String TAG = "ThirdParty.MPV";

    private static final String PACKAGE_NAME = "is.xyz.mpv";
    private static final String PLAYBACK_ACTIVITY = "is.xyz.mpv.MPVActivity";

    private static class MpvPackageInfo {
        final String packageName;
        final String activityName;

        MpvPackageInfo(String packageName, String activityName) {
            this.packageName = packageName;
            this.activityName = activityName;
        }
    }

    private static final MpvPackageInfo[] PACKAGES = {
            new MpvPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY),
    };

    /**
     * @return null if any MPV packages not exist.
     */
    public static MpvPackageInfo getPackageInfo() {
        for (MpvPackageInfo pkg : PACKAGES) {
            try {
                ApplicationInfo info = App.getInstance().getPackageManager().getApplicationInfo(pkg.packageName, 0);
                if (info.enabled)
                    return pkg;
                else
                    Log.v(TAG, "MPV package `" + pkg.packageName + "` is disabled.");
            } catch (PackageManager.NameNotFoundException ex) {
                Log.v(TAG, "MPV package `" + pkg.packageName + "` does not exist.");
            }
        }
        return null;
    }

    public static boolean run(Activity activity, String url, String title, String subtitle, long progress) {
        MpvPackageInfo packageInfo = getPackageInfo();
        if (packageInfo == null)
            return false;

        // https://mpv-android.github.io/mpv-android/intent.html
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setPackage(packageInfo.packageName);
        intent.setClassName(packageInfo.packageName, packageInfo.activityName);
        // TVBox 的播放链接多无文件扩展名，官方文档要求设置 MIME 为 video/any 才能强制 mpv 接管打开该 URL
        Uri dataUri = toMpvUri(url);
        intent.setDataAndTypeAndNormalize(dataUri, "video/any");

        if (title != null && !title.isEmpty()) {
            intent.putExtra("title", title);
        }

        if (subtitle != null && !subtitle.isEmpty()) {
            Parcelable[] parcels = new Parcelable[1];
            parcels[0] = toMpvUri(subtitle);
            intent.putExtra("subs", parcels);
            intent.putExtra("subs.enable", parcels);
        }

        if (progress > 0) {
            // mpv-android 的 position 为 Int 类型，单位毫秒
            intent.putExtra("position", (int) progress);
        }

        try {
            if (dataUri != null && "file".equals(dataUri.getScheme())) {
                // targetSdk >= 24 时 Android 默认禁止跨应用传递 file:// URI（StrictMode 检查，
                // 会抛 FileUriExposedException 导致闪退），这里临时放开该检查，启动完成后立即恢复
                StrictMode.VmPolicy oldPolicy = StrictMode.getVmPolicy();
                StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX);
                try {
                    activity.startActivity(intent);
                } finally {
                    StrictMode.setVmPolicy(oldPolicy);
                }
            } else {
                activity.startActivity(intent);
            }
            return true;
        } catch (Exception ex) {
            Log.e(TAG, "Can't run MPV Player", ex);
            return false;
        }
    }

    /**
     * mpv-android 的 resolveUri() 只接受带 scheme 的 URI（file/content/http/https/rtsp 等），
     * 不带 scheme 的本地路径（如 /storage/emulated/0/xxx.mp4）会被判定为
     * "unknown scheme: null" 而直接退出（No file given），因此这里统一转换：
     * 网络地址（含 "://"）原样使用；本地路径转换为 file:// URI。
     */
    private static Uri toMpvUri(String url) {
        if (url == null || url.isEmpty())
            return null;
        if (url.contains("://"))
            return Uri.parse(url);
        return Uri.fromFile(new File(url));
    }
}
