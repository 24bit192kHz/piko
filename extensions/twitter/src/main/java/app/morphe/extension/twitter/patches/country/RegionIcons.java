/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.country;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import java.util.HashMap;
import java.util.Map;

/**
 * Icons for the regions X shows instead of a country (vector drawables "piko_region_*"), drawn
 * after the author's name at the size of the name's text. One drawable per region kind is
 * loaded on first use and shared; the spans only hold a reference to it.
 */
final class RegionIcons {
    private static final Map<String, Drawable> DRAWABLES = new HashMap<>();
    private static final Map<String, Span> SPANS = new HashMap<>();

    private RegionIcons() {
    }

    /** The name followed by the region's icon, or null when the kind has no icon. Main thread only. */
    static CharSequence append(Context context, CharSequence name, String kind) {
        Span span = span(context, kind);
        if (span == null) return null;
        SpannableStringBuilder sb = new SpannableStringBuilder(name).append(" ￼");
        sb.setSpan(span, sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static Span span(Context context, String kind) {
        Span span = SPANS.get(kind);
        if (span != null || SPANS.containsKey(kind)) return span;
        Drawable d = null;
        try {
            String name = "piko_region_" + kind.replaceAll("[^a-z]", "_");
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id != 0) d = context.getResources().getDrawable(id, null);
        } catch (Throwable ignored) {
        }
        span = d == null ? null : new Span(d);
        SPANS.put(kind, span);
        return span;
    }

    /** Draws the icon 1.5:1 like a flag emoji, as tall as the text's cap height plus a little. */
    private static final class Span extends ReplacementSpan {
        private final Drawable drawable;

        Span(Drawable drawable) {
            this.drawable = drawable;
        }

        private static int height(Paint paint) {
            return Math.round(paint.getTextSize() * 0.78f);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                fm.ascent = pfm.ascent;
                fm.descent = pfm.descent;
                fm.top = pfm.top;
                fm.bottom = pfm.bottom;
            }
            return Math.round(height(paint) * 1.5f);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            int h = height(paint);
            int w = Math.round(h * 1.5f);
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            // Centre on the middle of the text (between baseline+ascent and baseline+descent).
            int cy = y + (fm.ascent + fm.descent) / 2;
            int l = Math.round(x), t = cy - h / 2;
            drawable.setBounds(l, t, l + w, t + h);
            drawable.draw(canvas);
        }
    }
}
