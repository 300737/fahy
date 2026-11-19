package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Intent;

import com.github.tvbox.osc.drive.DriveModule;
import com.github.tvbox.osc.drive.bean.DrivePlayItem;
import com.github.tvbox.osc.drive.callback.DriveCallback;
import com.github.tvbox.osc.drive.ui.activity.DriveActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * drive_module 的宿主接入桥。
 *
 * <p>启动 {@link DriveActivity} 前注入 {@link DriveCallback}：模块把选中目录的
 * 全部可播视频组成播放列表（点击项已轮转到首位）经 {@link #onPlayFiles} 一次性回传，
 * 这里拆成名称/地址两个平行列表传给详情页，复用 push_agent 内置推送源走
 * 正常详情/播放流程（与 LocalVideoLauncher 本地视频整列播放同链路）。
 * 名称与路径分开传（而非拼"名称$地址"），文件名含 '#' 或 '$' 也不会被选集解析误切分。
 * 模块初始化由 DriveActivity.onCreate() 内部幂等完成，宿主无需额外调用。</p>
 */
public final class DrivePlayHelper {

    private DrivePlayHelper() {
    }

    /** 主界面/设置页入口：注入播放回调并打开存储空间浏览页 */
    public static void open(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        DriveModule.setDriveCallback(new DriveCallback() {
            @Override
            public void onPlayFile(String name, String url, String headers, String playFlag) {
                List<DrivePlayItem> items = new ArrayList<>();
                items.add(new DrivePlayItem(name == null ? "" : name, url == null ? "" : url));
                startPlaylist(activity, items, 0);
            }

            @Override
            public void onPlayFiles(List<DrivePlayItem> items, int startIndex, String headers, String playFlag) {
                startPlaylist(activity, items, startIndex);
            }

            @Override
            public int getThemeColor() {
                return 0; // 使用模块默认主题色
            }
        });
        activity.startActivity(new Intent(activity, DriveActivity.class));
    }

    private static void startPlaylist(Activity activity, List<DrivePlayItem> items, int startIndex) {
        if (activity == null || activity.isFinishing() || items == null || items.isEmpty()) return;
        int index = (startIndex >= 0 && startIndex < items.size()) ? startIndex : 0;
        DrivePlayItem clicked = items.get(index);
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> urls = new ArrayList<>();
        for (DrivePlayItem item : items) {
            names.add(item.name);
            urls.add(item.url);
        }
        Intent intent = new Intent(activity, DetailActivity.class);
        intent.putExtra("id", clicked.url);
        intent.putExtra("sourceKey", "push_agent");
        intent.putExtra("title", clicked.name);
        intent.putStringArrayListExtra(DetailActivity.EXTRA_PLAY_LIST_NAMES, names);
        intent.putStringArrayListExtra(DetailActivity.EXTRA_PLAY_LIST_URLS, urls);
        intent.putExtra(DetailActivity.EXTRA_PLAY_LIST_INDEX, index);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
    }
}
