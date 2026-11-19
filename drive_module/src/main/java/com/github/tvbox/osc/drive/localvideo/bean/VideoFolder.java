package com.github.tvbox.osc.drive.localvideo.bean;

import java.io.Serializable;
import java.util.List;

/**
 * 本地视频文件夹（MediaStore BUCKET 分组结果）。
 */
public class VideoFolder implements Serializable {

    private static final long serialVersionUID = 1L;

    String name;
    /** 文件夹完整绝对路径（由组内第一个视频的父目录推导） */
    String path;
    List<VideoInfo> videoList;

    public VideoFolder(String name, List<VideoInfo> videoList) {
        this(name, "", videoList);
    }

    public VideoFolder(String name, String path, List<VideoInfo> videoList) {
        this.name = name;
        this.path = path;
        this.videoList = videoList;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public List<VideoInfo> getVideoList() { return videoList; }
    public void setVideoList(List<VideoInfo> videoList) { this.videoList = videoList; }
}
