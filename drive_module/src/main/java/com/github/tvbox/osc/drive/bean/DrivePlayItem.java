package com.github.tvbox.osc.drive.bean;

import java.io.Serializable;

/**
 * 播放列表条目。
 * <p>
 * Drive 模块以列表形式向宿主回传播放信息时使用：
 * 与 Spider 的 {@code vod_play_url}（"名称1$地址1#名称2$地址2#..."）机制等价，
 * 只是改用结构化对象传递，避免文件名中包含 '#'/'$' 时被误切分。
 * <p>
 * 实现 Serializable 以便经 Intent 在 setResult 兜底模式下传递。
 */
public class DrivePlayItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 展示名（一般为文件名，显示在详情页选集列表中） */
    public final String name;
    /** 可直接被播放器播放的地址（本地文件为绝对路径，网络文件为完整 URL） */
    public final String url;

    public DrivePlayItem(String name, String url) {
        this.name = name;
        this.url = url;
    }

    @Override
    public String toString() {
        return name + "$" + url;
    }
}
