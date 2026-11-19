package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.github.tvbox.osc.ui.activity.DetailActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;


/**
 * 本地视频播放入口：浏览选中视频文件后，把同文件夹的全部视频
 * （按名称排序、以选中项为起点旋转）拆成名称/路径两个平行列表传给详情页，
 * 复用 push_agent 内置推送源走正常详情/播放流程（与手机推送本地文件同链路）。
 *
 * <p>供各浏览入口共用：多仓/线路对话框、设置页"选择"按钮的选择结果先经
 * {@link #tryStartLocalVideoFromResult} 分流，视频文件在此消费，其余仍按配置解析分发。</p>
 */
public final class LocalVideoLauncher {

    /**
     * 本地视频播放支持的视频扩展名（预置于程序，不做持久化配置）。
     * 文件浏览入口选中视频文件时按此表分流，并按它筛选同文件夹视频生成播放列表。
     * m3u,m3u8直播流不要加入这个播放列表。
     */
    public static final String[] MEDIA_SUFFIXES = {
        // 视频格式
        ".mp4", ".mkv", ".avi", ".flv", ".wmv", ".mov", ".ts", ".webm",
        ".m4v", ".mpg", ".mpeg", ".mpe", ".m1v", ".m2v", ".mpv",
        ".3gp", ".3g2", ".ogv", ".ogm", ".rm", ".rmvb", ".vob", ".asf",
        ".f4v", ".mxf", ".mts", ".m2ts", ".m2t", ".divx", ".xvid",
        ".dat", ".amv", ".qt", ".y4m", ".h264", ".h265", ".hevc",
        ".dv", ".mod", ".tod", ".wtv", ".dvr-ms", ".nsv", ".bik",
        ".smk", ".roq",

        // 音频格式
        ".mp3", ".wav", ".flac", ".aac", ".m4a", ".m4b", ".m4p", ".m4r",
        ".wma", ".ogg", ".oga", ".opus", ".ape", ".alac", ".aiff", ".aif",
        ".aifc", ".au", ".snd", ".amr", ".awb", ".ac3", ".eac3", ".ec3",
        ".dts", ".dtshd", ".mka", ".mid", ".midi", ".rmi", ".ra", ".ram",
        ".wv", ".tak", ".tta", ".caf", ".mp2", ".mp1", ".mpa", ".3ga",
        ".gsm", ".vox", ".voc", ".w64", ".dsf", ".dff", ".cda", ".spx",
        ".thd", ".mlp"
    };

    /** 播放列表集数上限，防止 Intent 超限（TransactionTooLarge） */
    private static final int LOCAL_PLAYLIST_MAX_ITEMS = 200;

    private LocalVideoLauncher() {
    }

    public static boolean isVideoFile(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String suffix : MEDIA_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * 本地视频分流：选择结果为视频文件时启动整列播放，返回 true 表示已消费。
     */
    public static boolean tryStartLocalVideoFromResult(Activity activity, Uri fileUri) {
        if (activity == null || fileUri == null) return false;
        String pickedPath = UriUtils.getPathFromUri(activity, fileUri);
        if (pickedPath == null || pickedPath.isEmpty()) return false;
        File picked = new File(pickedPath);
        if (!isVideoFile(picked.getName())) return false;
        startLocalVideoPlaylist(activity, picked);
        return true;
    }

    /**
     * 名称与路径分开传（而非拼"名称$地址"），文件名含 '#' 或 '$' 也不会被选集解析误切分。
     */
    private static void startLocalVideoPlaylist(Activity activity, File clicked) {
        File dir = clicked.getParentFile();
        File[] children = dir == null ? null : dir.listFiles();
        ArrayList<File> videos = new ArrayList<>();
        if (children != null) {
            for (File f : children) {
                if (f.isFile() && isVideoFile(f.getName())) videos.add(f);
            }
        }
        if (videos.isEmpty()) {
            Toast.makeText(activity, "该文件夹没有可播放的视频文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(videos, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        int startIndex = videos.indexOf(clicked);
        if (startIndex < 0) startIndex = 0;
        // 以选中项为起点旋转，点击项排到首位
        Collections.rotate(videos, -startIndex);

        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> urls = new ArrayList<>();
        for (File f : videos) {
            if (names.size() >= LOCAL_PLAYLIST_MAX_ITEMS) break;
            names.add(f.getName());
            urls.add(f.getAbsolutePath());
        }

        Intent intent = new Intent(activity, DetailActivity.class);
        intent.putExtra("id", clicked.getAbsolutePath());
        intent.putExtra("sourceKey", "push_agent");
        intent.putExtra("title", clicked.getName());
        intent.putStringArrayListExtra(DetailActivity.EXTRA_PLAY_LIST_NAMES, names);
        intent.putStringArrayListExtra(DetailActivity.EXTRA_PLAY_LIST_URLS, urls);
        // 旋转后点击项恒在首位
        intent.putExtra(DetailActivity.EXTRA_PLAY_LIST_INDEX, 0);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
    }
}
