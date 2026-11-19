package com.github.tvbox.osc.drive.localvideo.util;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * 防快速连点工具（独立实现，绑定主线程 Looper）。
 */
public final class FastClickCheckUtil {

    private FastClickCheckUtil() { }

    /** 相同视图点击必须间隔 0.5s 才能有效 */
    public static void check(View view) { check(view, 500); }

    public static void check(final View view, int mills) {
        view.setClickable(false);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { view.setClickable(true); }
        }, mills);
    }
}
