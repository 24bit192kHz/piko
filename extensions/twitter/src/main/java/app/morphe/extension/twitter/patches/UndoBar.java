/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.crimera.PikoUtils;

/** A small bar above X's bottom tabs for 6 s: a label and "Undo" (one at a time). */
public final class UndoBar {
    private static final String TAG = "piko_not_interested_undo";
    private static final long SHOWN_MS = 6000;
    private static Handler main;

    private UndoBar() {
    }

    static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    public static void show(Activity activity, CharSequence text, Runnable onUndo) {
        try {
            if (activity == null || activity.isFinishing()) return;
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FrameLayout frame = (FrameLayout) content;
            View old = frame.findViewWithTag(TAG);
            if (old != null) frame.removeView(old);
            Context c = activity;
            float d = c.getResources().getDisplayMetrics().density;
            LinearLayout bar = new LinearLayout(c);
            bar.setTag(TAG);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(Math.round(16 * d), 0, Math.round(4 * d), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0202327);
            bg.setCornerRadius(8 * d);
            bar.setBackground(bg);
            bar.setElevation(6 * d);
            TextView label = new TextView(c);
            label.setText(text);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView undo = new TextView(c);
            undo.setText(AdvancedSearch.t(c, "piko_ni_undo", "Undo"));
            undo.setTextColor(0xFF1D9BF0);
            undo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            undo.setTypeface(Typeface.DEFAULT_BOLD);
            undo.setGravity(Gravity.CENTER);
            undo.setMinHeight(Math.round(48 * d));
            undo.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
            undo.setClickable(true);
            undo.setFocusable(true);
            undo.setOnClickListener(v -> {
                frame.removeView(bar);
                onUndo.run();
            });
            bar.addView(undo);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            int side = Math.round(12 * d);
            // Just above X's bottom tabs (or the screen's bottom edge when they are hidden).
            int bottom = Math.round(8 * d);
            int[] at = new int[2];
            frame.getLocationOnScreen(at);
            int frameBottom = at[1] + frame.getHeight();
            int tabsId = c.getResources().getIdentifier("bottom_bar", "id", c.getPackageName());
            View tabs = tabsId == 0 ? null : activity.findViewById(tabsId);
            if (tabs != null && tabs.isShown() && tabs.getHeight() > 0) {
                tabs.getLocationOnScreen(at);
                bottom += Math.max(0, frameBottom - at[1]);
            } else if (frame.getRootWindowInsets() != null) {
                bottom += frame.getRootWindowInsets().getSystemWindowInsetBottom();
            }
            lp.setMargins(side, 0, side, bottom);
            bar.setAlpha(0f);
            bar.setTranslationY(16 * d);
            frame.addView(bar, lp);
            bar.animate().alpha(1f).translationY(0f).setDuration(150).start();
            if (main == null) main = new Handler(Looper.getMainLooper());
            main.postDelayed(() -> {
                if (bar.getParent() == frame) frame.removeView(bar);
            }, SHOWN_MS);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }
}
