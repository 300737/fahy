package com.github.tvbox.osc.player.thirdparty;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.util.HashMap;

/**
 * 外部 Nova 播放器（Nova Video Player / org.courville.nova）
 * <p>
 * Nova 是 Archos Video Player Community Edition 的 fork，开源仓库:
 * <ul>
 *     <li>主仓: https://github.com/nova-video-player/nova</li>
 *     <li>实际源码分支: https://github.com/nova-video-player/aos-Video/tree/v6.4</li>
 * </ul>
 * <p>
 * Nova 的播放 Activity 为 {@code com.archos.mediacenter.video.player.PlayerActivity}
 * （applicationId 为 {@code org.courville.nova}，但 Java 包名仍为 Archos 时代的
 * {@code com.archos.mediacenter.video}），它在 AndroidManifest.xml 中声明了针对
 * {@link Intent#ACTION_VIEW} + {@code video/*} 的 intent-filter，支持
 * file/content/http/https/smb/ftp/webdav/mms/rtsp 等多种 scheme。
 * <p>
 * 本类支持的 Intent extras（参考 aos-Video v6.4 源码）:
 * <ul>
 *     <li>{@code title} (String): 媒体标题；Nova 还兼容 {@link Intent#EXTRA_TITLE}
 *         与 {@code extra_name}，这里仅写入 {@code title} 即可</li>
 *     <li>{@code position} (long, 毫秒): 起始播放位置（断点续播）；
 *         Nova 的 {@code ExternalResumeIntent.readPosition()} 同时接受 int/long，
 *         单位为毫秒</li>
 *     <li>{@code headers} (Bundle&lt;String,String&gt;): HTTP 请求头，采用 MX Player /
 *         Stremio 约定（即 Bundle 的 key 是 header 名，value 是 header 值）；
 *         Nova 在 {@code PlayerActivity.postOnPlayerServiceBind()} 中读取该 Bundle</li>
 *     <li>{@code return_result} (boolean): 是否要求 Nova 返回播放结果
 *         （position / extra_position / duration 等），默认 false</li>
 * </ul>
 * <p>
 * 注意事项:
 * <ul>
 *     <li>Nova 不支持通过 Intent 传递字幕 URI（其 SubtitleManager 仅从同名 sidecar 文件、
 *         Subs/ 目录、OpenSubtitles 等自动发现字幕），因此 {@code subtitle} 参数在本类中
 *         实际不会被传递给 Nova；保留参数仅为对齐其他外部播放器接口</li>
 *     <li>与 Kodi / MXPlayer 不同，Nova 直接接受标准 HTTP URL，无需将 headers 以 "|"
 *         拼接到 URL 后面；headers 应通过 {@code Bundle} extra 传递</li>
 *     <li>本地路径（如 /storage/emulated/0/xxx.mp4）会被自动转换为 file:// URI
 *         （Nova 的 resolveUri() 只接受带 scheme 的 URI），并临时绕过 Android 7.0+ 的
 *         FileUriExposedException 检查</li>
 * </ul>
 */
public class NovaPlayer {
    public static final String TAG = "ThirdParty.Nova";

    private static final String PACKAGE_NAME = "org.courville.nova";
    // Nova 的 applicationId 为 org.courville.nova，但 Java 包名仍为 Archos 时代的
    // com.archos.mediacenter.video，PlayerActivity 的全限定名如下（v6.4 验证）。
    private static final String PLAYBACK_ACTIVITY = "com.archos.mediacenter.video.player.PlayerActivity";

    private static class NovaPackageInfo {
        final String packageName;
        final String activityName;

        NovaPackageInfo(String packageName, String activityName) {
            this.packageName = packageName;
            this.activityName = activityName;
        }
    }

    private static final NovaPackageInfo[] PACKAGES = {
            new NovaPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY),
    };

    /**
     * @return null if any Nova packages not exist.
     */
    public static NovaPackageInfo getPackageInfo() {
        for (NovaPackageInfo pkg : PACKAGES) {
            try {
                ApplicationInfo info = App.getInstance().getPackageManager().getApplicationInfo(pkg.packageName, 0);
                if (info.enabled)
                    return pkg;
                else
                    Log.v(TAG, "Nova package `" + pkg.packageName + "` is disabled.");
            } catch (PackageManager.NameNotFoundException ex) {
                Log.v(TAG, "Nova package `" + pkg.packageName + "` does not exist.");
            }
        }
        return null;
    }

    /**
     * 调起 Nova 播放器播放指定 URL。
     * <p>
     * 与 VlcPlayer / MpvPlayer 的 run(...) 签名兼容（接受断点进度），
     * 同时与 MXPlayer / Kodi 的签名兼容（接受 HTTP 请求头），
     * 因为 Nova 同时支持这两种能力。
     *
     * @param activity 调用方 Activity
     * @param url      视频 URL（网络或本地路径）
     * @param title    媒体标题
     * @param subtitle 字幕 URI（Nova 不支持通过 Intent 传递字幕，此参数会被忽略）
     * @param headers  HTTP 请求头（key=header 名，value=header 值），可为 null
     * @param progress 起始播放位置（毫秒），小于等于 0 时从头播放
     * @return true 表示成功调起 Nova；false 表示未安装或调用失败
     */
    public static boolean run(Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        NovaPackageInfo packageInfo = getPackageInfo();
        if (packageInfo == null)
            return false;

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setPackage(packageInfo.packageName);
        intent.setClassName(packageInfo.packageName, packageInfo.activityName);

        Uri dataUri = toNovaUri(url);
        if (dataUri == null) {
            Log.e(TAG, "Can't run Nova: invalid url `" + url + "`");
            return false;
        }
        // Nova 的 intent-filter 接受 video/* MIME 类型
        intent.setDataAndTypeAndNormalize(dataUri, "video/*");

        // 媒体标题（Nova 也会回退到 EXTRA_TITLE / extra_name，但写入 title 即可）
        if (title != null && !title.isEmpty()) {
            intent.putExtra("title", title);
        }

        // 字幕：Nova 不支持通过 Intent 传递字幕 URI，这里不写入以避免被忽略；
        // 如需字幕，Nova 会自动从同名 sidecar 文件 / Subs/ 目录 / OpenSubtitles 中发现。
        if (subtitle != null && !subtitle.isEmpty()) {
            Log.v(TAG, "Nova does not support subtitle URI via Intent; subtitle `" + subtitle + "` will be ignored.");
        }

        // HTTP 请求头（MX Player / Stremio 风格的 Bundle<String,String>）
        // Nova 在 PlayerActivity.postOnPlayerServiceBind() 中通过 getBundleExtra("headers") 读取
        if (headers != null && headers.size() > 0) {
            Bundle headersBundle = new Bundle();
            for (String key : headers.keySet()) {
                String value = headers.get(key);
                if (key != null && value != null) {
                    headersBundle.putString(key, value);
                }
            }
            intent.putExtra("headers", headersBundle);
        }

        // 起始播放位置（断点续播）；Nova 同时接受 int / long，单位毫秒
        if (progress > 0) {
            intent.putExtra("position", progress);
        }

        try {
            if ("file".equals(dataUri.getScheme())) {
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
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "Nova Player activity not found", ex);
            return false;
        } catch (Exception ex) {
            Log.e(TAG, "Can't run Nova Player", ex);
            return false;
        }
    }

    /**
     * Nova 的 PlayerActivity.resolveUri() 只接受带 scheme 的 URI
     * （file/content/http/https/rtsp 等），不带 scheme 的本地路径会被判定为
     * "unknown scheme" 而直接退出；这里统一转换：
     * 网络地址（含 "://"）原样使用；本地路径转换为 file:// URI。
     */
    private static Uri toNovaUri(String url) {
        if (url == null || url.isEmpty())
            return null;
        if (url.contains("://"))
            return Uri.parse(url);
        return Uri.fromFile(new File(url));
    }
}
