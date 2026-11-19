package com.github.tvbox.toolbar.ui.dialog;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.toolbar.R;
import com.github.tvbox.toolbar.bean.AppInfo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用属性弹窗 - 长按应用图标时展示版本、包名、SDK、安装时间、APK 大小、权限等信息，
 * 支持跳转系统应用详情页清除数据、复制属性文本
 */
public class AppInfoDialog extends Dialog {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /** 属性文本（与弹窗展示内容一致），供复制按钮使用 */
    private final StringBuilder mInfoText = new StringBuilder();

    public static void show(Context context, AppInfo appInfo) {
        try {
            new AppInfoDialog(context, appInfo).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private AppInfoDialog(Context context, AppInfo appInfo) {
        super(context, R.style.Theme_Toolbar_Dialog);
        setContentView(R.layout.toolbar_dialog_app_info);
        setCanceledOnTouchOutside(true);
        initView(appInfo);
        initBottomButtons(appInfo.getPack());
    }

    /** 清除数据需系统签名权限，普通应用无权直接执行，跳转系统应用详情页由用户手动清除 */
    private void initBottomButtons(final String pack) {
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + pack));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                    dismiss();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("app_info", mInfoText.toString().trim()));
                    Toast.makeText(getContext(), R.string.toolbar_info_copied, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void initView(AppInfo appInfo) {
        ((ImageView) findViewById(R.id.ivAppIcon)).setImageDrawable(appInfo.getIcon());
        ((TextView) findViewById(R.id.tvAppName)).setText(appInfo.getName());
        ((TextView) findViewById(R.id.tvAppPackage)).setText(appInfo.getPack());
        mInfoText.append(appInfo.getName()).append('\n').append(appInfo.getPack()).append('\n');

        LinearLayout container = findViewById(R.id.llInfoContainer);
        Context context = getContext();
        PackageManager pm = context.getPackageManager();
        PackageInfo pi = null;
        try {
            pi = pm.getPackageInfo(appInfo.getPack(), PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        if (pi == null) {
            addRow(container, context.getString(R.string.toolbar_info_package), appInfo.getPack());
            return;
        }
        addRow(container, context.getString(R.string.toolbar_info_version),
                (pi.versionName == null ? "-" : pi.versionName) + " (" + pi.versionCode + ")");
        addRow(container, context.getString(R.string.toolbar_info_package), pi.packageName);
        ApplicationInfo ai = pi.applicationInfo;
        // ApplicationInfo.minSdkVersion 字段 API 24 才加入框架，低版本系统上读取会 NoSuchFieldError
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && ai.minSdkVersion > 0) {
            addRow(container, context.getString(R.string.toolbar_info_min_sdk), sdkLabel(ai.minSdkVersion));
        }
        if (ai.targetSdkVersion > 0) {
            addRow(container, context.getString(R.string.toolbar_info_target_sdk), sdkLabel(ai.targetSdkVersion));
        }
        addRow(container, context.getString(R.string.toolbar_info_install_time),
                TIME_FORMAT.format(new Date(pi.firstInstallTime)));
        addRow(container, context.getString(R.string.toolbar_info_update_time),
                TIME_FORMAT.format(new Date(pi.lastUpdateTime)));
        String path = ai.sourceDir;
        if (!TextUtils.isEmpty(path)) {
            addRow(container, context.getString(R.string.toolbar_info_apk_size), formatSize(new File(path).length()));
            addRow(container, context.getString(R.string.toolbar_info_apk_path), path);
        }
        addRow(container, context.getString(R.string.toolbar_info_permissions), formatPermissions(context, pi));
    }

    private void addRow(LinearLayout container, String label, String value) {
        mInfoText.append(label).append(": ").append(value).append('\n');
        Context context = container.getContext();
        int padV = context.getResources().getDimensionPixelSize(R.dimen.toolbar_app_info_row_padding_v);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, padV, 0, padV);

        TextView tvLabel = new TextView(context);
        tvLabel.setText(label);
        tvLabel.setTextColor(context.getResources().getColor(R.color.toolbar_white_60));
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.toolbar_app_info_row_text_size));
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                context.getResources().getDimensionPixelSize(R.dimen.toolbar_app_info_label_width),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(tvLabel);

        TextView tvValue = new TextView(context);
        tvValue.setText(value);
        tvValue.setTextColor(context.getResources().getColor(R.color.toolbar_white));
        tvValue.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.toolbar_app_info_row_text_size));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvValue.setLayoutParams(valueLp);
        row.addView(tvValue);

        container.addView(row);
    }

    private String formatPermissions(Context context, PackageInfo pi) {
        if (pi.requestedPermissions == null || pi.requestedPermissions.length == 0) {
            return context.getString(R.string.toolbar_info_none);
        }
        StringBuilder sb = new StringBuilder();
        for (String permission : pi.requestedPermissions) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(permission);
        }
        return sb.toString();
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "-";
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.CHINA, "%.2f GB", bytes / 1024f / 1024f / 1024f);
        }
        return String.format(Locale.CHINA, "%.2f MB", bytes / 1024f / 1024f);
    }

    private String sdkLabel(int sdk) {
        String name = androidVersionName(sdk);
        return name == null ? ("API " + sdk) : (name + " (API " + sdk + ")");
    }

    private static String androidVersionName(int sdk) {
        switch (sdk) {
            case 21: return "Android 5.0";
            case 22: return "Android 5.1";
            case 23: return "Android 6.0";
            case 24: return "Android 7.0";
            case 25: return "Android 7.1";
            case 26: return "Android 8.0";
            case 27: return "Android 8.1";
            case 28: return "Android 9";
            case 29: return "Android 10";
            case 30: return "Android 11";
            case 31: return "Android 12";
            case 32: return "Android 12L";
            case 33: return "Android 13";
            case 34: return "Android 14";
            case 35: return "Android 15";
            default: return null;
        }
    }

    @Override
    public void show() {
        super.show();
        // 弹窗内只有底部两个按钮可聚焦，初始焦点落在清除按钮上保证遥控器可直接操作
        findViewById(R.id.btnClear).requestFocus();
        // 内容构建完成后按实际内容高度定窗；内容超窗时压缩 ScrollView，
        // 保证底部按钮区始终留在窗口内（否则 LinearLayout 会把最后的按钮裁出屏幕）
        final LinearLayout root = findViewById(R.id.llRoot);
        root.post(new Runnable() {
            @Override
            public void run() {
                if (getWindow() == null) return;
                root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                Context context = getContext();
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                int width = Math.min(root.getMeasuredWidth(),
                        Math.min((int) (dm.density * 720), (int) (dm.widthPixels * 0.92f)));
                int fixedH = measureFixedHeight(root);
                int contentH = Math.max(root.getMeasuredHeight() - fixedH, 0);
                int maxWinH = (int) (dm.heightPixels * 0.85f);
                int minScrollH = (int) (dm.density * 100);
                int scrollH = Math.max(Math.min(contentH, maxWinH - fixedH), minScrollH);
                ScrollView svInfo = findViewById(R.id.svInfo);
                svInfo.getLayoutParams().height = scrollH;
                getWindow().setLayout(width, fixedH + scrollH);
                getWindow().setGravity(Gravity.CENTER);
            }
        });
    }

    /** 头部、分隔线、按钮区等固定部分的总高度（含 root 上下 padding 与相关 margin） */
    private int measureFixedHeight(LinearLayout root) {
        View header = root.findViewById(R.id.llHeader);
        View divider = root.findViewById(R.id.vDivider);
        View btns = root.findViewById(R.id.llBtns);
        int h = root.getPaddingTop() + root.getPaddingBottom();
        h += header.getMeasuredHeight();
        LinearLayout.LayoutParams dividerLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        h += divider.getMeasuredHeight() + dividerLp.topMargin + dividerLp.bottomMargin;
        LinearLayout.LayoutParams btnsLp = (LinearLayout.LayoutParams) btns.getLayoutParams();
        h += btns.getMeasuredHeight() + btnsLp.topMargin;
        return h;
    }
}
