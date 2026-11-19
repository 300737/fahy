package com.github.tvbox.osc.drive.localvideo.bean;

import java.io.Serializable;

/**
 * 本地视频信息（MediaStore 查询结果映射）。
 *
 * <p>实现 Serializable 以便经 Intent 传递给宿主。</p>
 */
public class VideoInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean checked = false;
    private int id = 0;
    private String path = null;
    private long size = 0;
    private String displayName = null;
    private String title = null;
    private long duration = 0;
    private String resolution = null;
    private int isPrivate = 0;
    private String bucketId = null;
    private String bucketDisplayName = null;
    private String bookmark = null;

    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public int getIsPrivate() { return isPrivate; }
    public void setIsPrivate(int isPrivate) { this.isPrivate = isPrivate; }

    public String getBucketId() { return bucketId; }
    public void setBucketId(String bucketId) { this.bucketId = bucketId; }

    public String getBucketDisplayName() { return bucketDisplayName; }
    public void setBucketDisplayName(String bucketDisplayName) { this.bucketDisplayName = bucketDisplayName; }

    public String getBookmark() { return bookmark; }
    public void setBookmark(String bookmark) { this.bookmark = bookmark; }
}
