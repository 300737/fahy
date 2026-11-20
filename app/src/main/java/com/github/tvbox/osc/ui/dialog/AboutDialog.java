package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        TextView tvAbout = findViewById(R.id.tvAbout);
        if (tvAbout != null) {
            String info = "版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                    + "\n构建时间 " + BuildConfig.BUILD_TIME;
            tvAbout.setText(tvAbout.getText() + "\n\n" + info);
        }
    }
}