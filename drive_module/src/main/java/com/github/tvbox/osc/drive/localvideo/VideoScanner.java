package com.github.tvbox.osc.drive.localvideo;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import com.github.tvbox.osc.drive.localvideo.bean.VideoInfo;
import com.github.tvbox.osc.drive.util.VideoFormats;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地视频扫描器：查询 MediaStore 中的指定格式视频，组装为 {@link VideoInfo} 列表。
 *
 * <p><b>查询层过滤（性能更优）</b>：通过构造 selection 参数
 * （{@code _DATA LIKE '%.mp4' OR _DATA LIKE '%.mkv' OR ...}，格式来自
 * {@link VideoFormats#dottedExtensions()}），让 MediaStore 数据库在查询时就
 * 只返回扩展名匹配的视频，避免把全部视频条目遍历一遍再逐行丢弃，
 * 显著减少 Cursor 遍历次数。SQLite LIKE 对 ASCII 默认不区分大小写，
 * ".MP4" 等大写扩展名同样能命中。</p>
 *
 * <p>仅在已授予读存储权限时能返回数据，权限申请由 Activity 负责。</p>
 */
public final class VideoScanner {

    private VideoScanner() { }

    /**
     * 扫描指定格式的本地视频（同步调用；调用方自行决定是否放到 IO 线程）。
     * 扩展名过滤在 MediaStore 查询层完成，只返回
     * {@link VideoFormats 白名单}内扩展名的视频。
     */
    public static List<VideoInfo> scan(Context context) {
        List<VideoInfo> videoList = new ArrayList<>();
        // 查询层过滤：_DATA LIKE '%.mp4' OR _DATA LIKE '%.mkv' OR ...
        // 让数据库在查询时就只返回扩展名匹配的视频，减少 Cursor 遍历次数
        List<String> dotted = VideoFormats.dottedExtensions();
        StringBuilder selection = new StringBuilder();
        String[] selectionArgs = new String[dotted.size()];
        for (int i = 0; i < dotted.size(); i++) {
            if (i > 0) selection.append(" OR ");
            selection.append(MediaStore.Video.Media.DATA).append(" LIKE ?");
            selectionArgs[i] = "%" + dotted.get(i);
        }
        // 查询列按系统版本分档：MediaStore 的列是各版本陆续加进来的，
        // 把设备数据库里还不存在的列写进 projection，query 会直接抛异常
        // （低版本上就是"no such column"）。IS_PRIVATE 是 API 29 才有的列，
        // 之前无条件写死，导致 API 19~28 的机器本地视频扫描直接失败。
        List<String> columns = new ArrayList<>();
        columns.add(MediaStore.Video.Media._ID);
        columns.add(MediaStore.Video.Media.DATA);
        columns.add(MediaStore.Video.Media.SIZE);
        columns.add(MediaStore.Video.Media.DISPLAY_NAME);
        columns.add(MediaStore.Video.Media.TITLE);
        columns.add(MediaStore.Video.Media.DURATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            columns.add(MediaStore.Video.Media.RESOLUTION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns.add(MediaStore.Video.Media.IS_PRIVATE);
        }
        columns.add(MediaStore.Video.Media.BUCKET_ID);
        columns.add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
        columns.add(MediaStore.Video.Media.BOOKMARK);

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    columns.toArray(new String[0]),
                    selection.toString(), selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    VideoInfo v = new VideoInfo();
                    v.setId(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)));
                    v.setPath(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)));
                    v.setSize(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)));
                    v.setDisplayName(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)));
                    v.setTitle(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)));
                    v.setDuration(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)));
                    // 分档列用 getColumnIndex 取，列不存在时是 -1，跳过即可
                    int idxResolution = cursor.getColumnIndex(MediaStore.Video.Media.RESOLUTION);
                    if (idxResolution >= 0) v.setResolution(cursor.getString(idxResolution));
                    int idxPrivate = cursor.getColumnIndex(MediaStore.Video.Media.IS_PRIVATE);
                    if (idxPrivate >= 0) v.setIsPrivate(cursor.getInt(idxPrivate));
                    v.setBucketId(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)));
                    v.setBucketDisplayName(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)));
                    v.setBookmark(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BOOKMARK)));
                    videoList.add(v);
                } while (cursor.moveToNext());
            }
        } catch (Throwable th) {
            // 无读存储权限、Provider 异常、或某台设备缺列都不该把异常抛给调用方，
            // 扫不到就返回已拿到的部分（空列表），由界面显示"暂无视频"
            th.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return videoList;
    }
}
