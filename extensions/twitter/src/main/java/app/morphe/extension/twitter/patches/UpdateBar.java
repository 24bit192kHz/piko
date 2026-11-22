/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** A small bar above X's bottom tabs while an update downloads and installs (the Undo bar's look). */
final class UpdateBar {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private final TextView label;
    private final ProgressBar progress;
    private final View root;
    private final String base;

    private UpdateBar(View root, TextView label, ProgressBar progress, String base) {
        this.root = root;
        this.label = label;
        this.progress = progress;
        this.base = base;
    }

    static UpdateBar show(Activity a, String text) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Math.round(16 * d), Math.round(12 * d), Math.round(16 * d), Math.round(12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202327);
        bg.setCornerRadius(8 * d);
        bar.setBackground(bg);
        bar.setElevation(6 * d);
        TextView label = new TextView(a);
        label.setText(text);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.addView(label);
        ProgressBar p = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setIndeterminate(true);
        p.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF1D9BF0));
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF1D9BF0));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(4 * d));
        plp.topMargin = Math.round(8 * d);
        bar.addView(p, plp);
        View content = a.findViewById(android.R.id.content);
        if (content instanceof FrameLayout) {
            FrameLayout frame = (FrameLayout) content;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            int side = Math.round(12 * d), bottom = Math.round(8 * d);
            int tabsId = a.getResources().getIdentifier("bottom_bar", "id", a.getPackageName());
            View tabs = tabsId == 0 ? null : a.findViewById(tabsId);
            if (tabs != null && tabs.isShown() && tabs.getHeight() > 0) {
                int[] at = new int[2];
                frame.getLocationOnScreen(at);
                int frameBottom = at[1] + frame.getHeight();
                tabs.getLocationOnScreen(at);
                bottom += Math.max(0, frameBottom - at[1]);
            } else if (frame.getRootWindowInsets() != null) {
                bottom += frame.getRootWindowInsets().getSystemWindowInsetBottom();
            }
            lp.setMargins(side, 0, side, bottom);
            frame.addView(bar, lp);
        }
        return new UpdateBar(bar, label, p, text);
    }

    void progress(int pct) {
        main.post(() -> {
            if (pct < 0) return;
            progress.setIndeterminate(false);
            progress.setProgress(pct);
            label.setText(base + " · " + pct + "%");
        });
    }

    void text(String text) {
        main.post(() -> {
            label.setText(text);
            progress.setIndeterminate(true);
        });
    }

    void close() {
        main.post(() -> {
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        });
    }
}
