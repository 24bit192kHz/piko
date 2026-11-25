/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.country;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.patches.customise.font.UpdateFont;

/**
 * The author's flag (or region tag) in a thin frame, added to the post header's own badge list (where X keeps the
 * verified check). X makes room for its badges before it shortens the name, so the flag is never cut off by a long
 * name, and the frame keeps it apart from emoji the author put in their name.
 */
final class CountryBadge {
    // Set at patch time (TweetHeaderView's fields and X's badge classes).
    private static String badgeListFieldName() {
        return "";
    }

    private static String textSizeFieldName() {
        return "";
    }

    private static String paintFieldName() {
        return "";
    }

    private static String handleColorFieldName() {
        return "";
    }

    private static String handleColorListFieldName() {
        return "";
    }

    private static String nameTextFieldName() {
        return "";
    }

    private static String nameLayoutFieldName() {
        return "";
    }

    private static String badgeClassName() {
        return "";
    }

    private static String spanClassName() {
        return "";
    }

    private static Field badgeList, textSize, paint, handleColor, handleColorList, nameText, nameLayout;
    private static Constructor<?> spanCtor, badgeCtor;
    private static boolean ready, broken;
    private static final Map<String, Object[]> BADGES = new HashMap<>();

    private CountryBadge() {
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static boolean init(View header) {
        if (ready) return true;
        if (broken) return false;
        try {
            Class<?> cls = header.getClass();
            badgeList = field(cls, badgeListFieldName());
            textSize = field(cls, textSizeFieldName());
            paint = field(cls, paintFieldName());
            handleColor = field(cls, handleColorFieldName());
            handleColorList = field(cls, handleColorListFieldName());
            nameText = field(cls, nameTextFieldName());
            nameLayout = field(cls, nameLayoutFieldName());
            ClassLoader cl = cls.getClassLoader();
            Class<?> span = Class.forName(spanClassName(), false, cl);
            spanCtor = span.getConstructor(Drawable.class, float.class);
            badgeCtor = Class.forName(badgeClassName(), false, cl).getConstructor(span, String.class);
            ready = true;
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
        return ready;
    }

    /** The framed flag for this header's text size and colours, and X's badge holding it (both cached). */
    private static Object[] frame(View header, String flag, String kind) throws Exception {
        float ts = textSize.getFloat(header);
        int color = handleColor.getInt(header);
        if ((color >>> 24) == 0) {
            // Not drawn yet: the colour the header will use for the handle.
            android.content.res.ColorStateList list = (android.content.res.ColorStateList) handleColorList.get(header);
            if (list != null) color = list.getColorForState(header.getDrawableState(), list.getDefaultColor());
            if ((color >>> 24) == 0) color = 0xFF71767B;
        }
        String key = (flag != null ? flag : "region:" + kind) + "|" + ts + "|" + color;
        Object[] cached = BADGES.get(key);
        if (cached == null) {
            TextPaint p = new TextPaint((TextPaint) paint.get(header));
            p.setTextSize(ts);
            Frame d = new Frame(header, p, ts, color, flag, kind);
            if (d.getIntrinsicWidth() <= 0) return null;
            Object badge = badgeCtor.newInstance(spanCtor.newInstance(d, 1.0f), "piko_country");
            cached = new Object[]{d, badge};
            if (BADGES.size() > 256) BADGES.clear();
            BADGES.put(key, cached);
        }
        return cached;
    }

    /**
     * Puts the framed flag on the side of the name next to the profile picture, whatever the name's script:
     * before the name when it runs the same way as the layout (an English name in English, an Arabic name in
     * Arabic), else in X's badge list after it (where X keeps the ✓), which then is the side next to the picture.
     * Returns the name to show (prefixed, or as it was with the badge added), or null when this is unavailable.
     */
    static CharSequence place(View header, CharSequence name, String flag, String kind) {
        if (name == null || !init(header)) return null;
        try {
            Object[] f = frame(header, flag, kind);
            if (f == null) return null;
            name = withoutFlag(name, flag);
            // The app's language (X draws this header itself, so the view's own direction stays left-to-right).
            boolean layoutRtl = header.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean nameRtl = android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(name, 0, name.length());
            if (layoutRtl == nameRtl) {
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("\uFFFC ").append(name);
                sb.setSpan(new FrameSpan((Frame) f[0]), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return sb;
            }
            @SuppressWarnings("unchecked")
            ArrayList<Object> list = (ArrayList<Object>) badgeList.get(header);
            list.add(f[1]);
            return name;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    private static boolean regional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    /**
     * The name without the flag the frame already shows, so it is not there twice: every copy of exactly that
     * flag goes, with one space next to it. Flags are pairs of regional letters counted from the start of a run
     * (so 🇺🇸🇦🇪 holds no 🇸🇦). A name that is only the flag, or holds another flag, keeps it.
     */
    static CharSequence withoutFlag(CharSequence name, String flag) {
        if (flag == null || flag.length() != 4 || name.length() < 4) return name;
        String text = name.toString();
        if (!text.contains(flag)) return name;
        ArrayList<int[]> cut = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            if (!regional(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            // A pair of regional letters (an unpaired last letter is left alone).
            int second = i + 2 < n ? text.codePointAt(i + 2) : 0;
            if (!regional(second)) {
                i += 2;
                continue;
            }
            if (text.startsWith(flag, i)) {
                int start = i, end = i + 4;
                if (end < n && text.charAt(end) == ' ') end++;
                else if (start > 0 && text.charAt(start - 1) == ' ') start--;
                cut.add(new int[]{start, end});
            }
            i += 4;
        }
        if (cut.isEmpty()) return name;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
        for (int k = cut.size() - 1; k >= 0; k--) sb.delete(cut.get(k)[0], cut.get(k)[1]);
        int a = 0, b = sb.length();
        while (a < b && Character.isWhitespace(sb.charAt(a))) a++;
        while (b > a && Character.isWhitespace(sb.charAt(b - 1))) b--;
        if (a == b) return name;
        return sb.subSequence(a, b);
    }

    /** The country arrived after the header was bound: place the flag now and have X lay the name line out again. */
    static boolean placeLate(View header, String flag, String kind) {
        if (!init(header)) return false;
        try {
            CharSequence name = (CharSequence) nameText.get(header);
            CharSequence placed = place(header, name, flag, kind);
            if (placed == null) return false;
            if (placed != name) nameText.set(header, placed);
            nameLayout.set(header, null);
            header.requestLayout();
            header.invalidate();
            return true;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return false;
        }
    }

    /** The framed flag as the first character of the name (same drawing as the badge). */
    static final class FrameSpan extends android.text.style.ReplacementSpan {
        private final Frame frame;

        FrameSpan(Frame frame) {
            this.frame = frame;
        }

        @Override
        public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            if (fm != null) {
                android.graphics.Paint.FontMetricsInt p = paint.getFontMetricsInt();
                fm.ascent = p.ascent;
                fm.descent = p.descent;
                fm.top = p.top;
                fm.bottom = p.bottom;
            }
            return frame.getIntrinsicWidth();
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, android.graphics.Paint paint) {
            int t = Math.round(y + frame.ascent);
            frame.setBounds(Math.round(x), t, Math.round(x) + frame.getIntrinsicWidth(), t + frame.getIntrinsicHeight());
            frame.draw(canvas);
        }
    }

    /** A flag emoji drawn once with the app's emoji font, trimmed to its visible pixels. */
    static final class Glyph {
        private static final float SIZE = 96f;
        private static final Map<String, Glyph> CACHE = new HashMap<>();

        final Bitmap bitmap;
        // In units of the text size: the visible box relative to the pen (x) and the baseline (y).
        final float top, width, height;

        private Glyph(Bitmap bitmap, float top, float width, float height) {
            this.bitmap = bitmap;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        static synchronized Glyph of(String flag) {
            if (CACHE.containsKey(flag)) return CACHE.get(flag);
            Glyph g = null;
            try {
                TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                Typeface tf = UpdateFont.emojiTypeface();
                if (tf != null) p.setTypeface(tf);
                p.setTextSize(SIZE);
                Paint.FontMetrics fm = p.getFontMetrics();
                int m = 8;
                int w = (int) Math.ceil(p.measureText(flag)) + 2 * m, h = (int) Math.ceil(fm.bottom - fm.top) + 2 * m;
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                float base = m - fm.top;
                new Canvas(b).drawText(flag, m, base, p);
                int[] px = new int[w * h];
                b.getPixels(px, 0, w, 0, 0, w, h);
                int minX = w, minY = h, maxX = -1, maxY = -1;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        if ((px[y * w + x] >>> 24) > 10) {
                            if (x < minX) minX = x;
                            if (x > maxX) maxX = x;
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                if (maxX >= minX) {
                    g = new Glyph(Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1),
                            (minY - base) / SIZE, (maxX - minX + 1) / SIZE, (maxY - minY + 1) / SIZE);
                }
                b.recycle();
            } catch (Throwable t) {
                PikoUtils.logger(t);
            }
            CACHE.put(flag, g);
            return g;
        }
    }

    /** The flag or region tag in a rounded frame of the handle's colour, as tall as the header's text line. */
    static final class Frame extends Drawable {
        final float ascent;
        private final float ts, pad, strokeW;
        private final int w, h;
        private final Glyph glyph;
        private final Drawable region;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Frame(View header, TextPaint p, float ts, int color, String flag, String kind) {
            this.ts = ts;
            Paint.FontMetrics fm = p.getFontMetrics();
            ascent = fm.ascent;
            float lineH = fm.descent - fm.ascent;
            strokeW = Math.max(1f, header.getResources().getDisplayMetrics().density);
            pad = ts * 0.14f;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeW);
            stroke.setColor(color);
            glyph = flag != null ? Glyph.of(flag) : null;
            region = flag == null ? RegionIcons.drawable(header.getContext(), kind) : null;
            if (glyph != null) {
                w = Math.round(glyph.width * ts + 2 * (pad + strokeW));
                h = (int) Math.floor(lineH);
            } else if (region != null) {
                int rh = Math.round(ts * 0.78f);
                w = Math.round(rh * 1.5f + 2 * (pad + strokeW));
                h = Math.min((int) Math.floor(lineH), Math.round(rh + 2 * (pad + strokeW)));
            } else {
                w = h = 0;
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        /** Reused on every draw (badges draw on the UI thread only). */
        private final RectF glyphBox = new RectF(), frameBox = new RectF();

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float radius = ts * 0.22f;
            if (glyph != null) {
                // The flag where it would sit in the line of text, the frame around it.
                float baseline = b.top - ascent;
                float gw = glyph.width * ts, gh = glyph.height * ts;
                float cy = baseline + (glyph.top + glyph.height / 2f) * ts;
                float left = b.left + pad + strokeW;
                glyphBox.set(left, cy - gh / 2f, left + gw, cy + gh / 2f);
                c.drawBitmap(glyph.bitmap, null, glyphBox, bitmapPaint);
                frameBox.set(b.left + strokeW / 2f, cy - gh / 2f - pad, b.right - strokeW / 2f, cy + gh / 2f + pad);
                c.drawRoundRect(frameBox, radius, radius, stroke);
            } else if (region != null) {
                int rh = Math.round(ts * 0.78f), rw = Math.round(rh * 1.5f);
                int l = Math.round(b.left + pad + strokeW), t = Math.round(b.exactCenterY() - rh / 2f);
                region.setBounds(l, t, l + rw, t + rh);
                region.draw(c);
                frameBox.set(b.left + strokeW / 2f, t - pad, b.right - strokeW / 2f, t + rh + pad);
                c.drawRoundRect(frameBox, radius, radius, stroke);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
