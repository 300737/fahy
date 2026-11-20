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
            String text = "繁华幻影"
                    + "\n聚合全网资源 · 呈现纯净画质"
                    + "\n版本 " + BuildConfig.VERSION_NAME
                    + "\n构建时间 " + BuildConfig.BUILD_TIME
                    + "\n仅供学习参考，请勿用于商业用途。";
            tvAbout.setText(text);
        }
    }
}