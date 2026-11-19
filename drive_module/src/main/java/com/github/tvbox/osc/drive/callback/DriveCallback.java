package com.github.tvbox.osc.drive.callback;

import com.github.tvbox.osc.drive.bean.DrivePlayItem;

import java.util.List;

/**
 * 宿主与 Drive 模块之间的解耦回调。
 * <p>
 * 宿主在启动 {@code DriveActivity} 之前通过
 * {@code DriveModule.setDriveCallback(callback)} 注入实现，
 * Drive 模块在用户选中视频文件后回调 {@link #onPlayFile} 把播放信息回传给宿主，
 * 由宿主用自己的播放器播放。模块本身不依赖任何宿主类，
 * 这样可以做到 Drive 模块作为子模块被任意宿主接入。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>模块只通过此接口向宿主回传数据，不持有宿主 Activity 引用；</li>
 *   <li>{@link #getThemeColor()} 用于让宿主控制删除模式按钮的高亮色等主题色，
 *       返回 0 表示使用模块默认色；</li>
 *   <li>回调方法均在主线程被调用，宿主可直接启动播放 Activity；</li>
 *   <li>{@link #onPlayFiles} 为播放列表回调：模块把当前目录/文件夹的全部可播视频
 *       组成列表（从用户点击的那个开始）一次性回传，宿主可实现该方法让详情页
 *       展示完整选集并支持上/下集切换。未实现时自动退回 {@link #onPlayFile} 单文件回调。</li>
 * </ul>
 */
public interface DriveCallback {

    /**
     * 用户在 Drive 模块中选中了一个可播放文件时回调（单文件模式）。
     *
     * @param name     文件展示名（一般为文件名）
     * @param url      可直接被播放器播放的 URL（本地文件为绝对路径，
     *                 网络文件为完整 URL，例如 ftp://、smb://、http:// 等）
     * @param headers  播放所需的 HTTP Headers（JSON 字符串，可为 null）
     * @param playFlag 播放标识，预留字段，目前恒为 null
     */
    void onPlayFile(String name, String url, String headers, String playFlag);

    /**
     * 用户选中文件时以播放列表形式回调（列表模式）。
     * <p>
     * 列表顺序与本地文件 Spider（LocalFile）的 {@code vod_play_url} 一致：
     * 从用户点击的文件开始、按当前目录排序依次排列（环形轮转）。
     * 宿主未实现该方法时（Java 8 default），自动取点击项回退为 {@link #onPlayFile}。
     *
     * @param items     播放列表，非空；条目为 {@link DrivePlayItem}
     * @param startIndex 用户点击的文件在 items 中的下标（一般为 0，因模块已把点击项轮转到首位）
     * @param headers   播放所需的 HTTP Headers（JSON 字符串，可为 null，对整个列表生效）
     * @param playFlag  播放标识，预留字段，目前恒为 null
     */
    default void onPlayFiles(List<DrivePlayItem> items, int startIndex, String headers, String playFlag) {
        if (items == null || items.isEmpty()) return;
        int idx = (startIndex >= 0 && startIndex < items.size()) ? startIndex : 0;
        DrivePlayItem first = items.get(idx);
        onPlayFile(first.name, first.url, headers, playFlag);
    }

    /**
     * 宿主返回主题色，模块可用它做删除模式按钮高亮等。
     *
     * @return ARGB 颜色值，返回 0 表示使用模块默认色
     */
    int getThemeColor();
}
