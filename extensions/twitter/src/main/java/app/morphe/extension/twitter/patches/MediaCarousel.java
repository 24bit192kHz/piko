/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Posts with more than four photos or videos (X allows up to 20 now) in the old post view, which
 * only has a 2x2 grid. All items are shown at once, uncropped, in justified rows (each row fills
 * the width, its items at their own aspect ratio, 4 dp apart). The number of rows is picked so
 * the block is about as tall as X's media normally is (0.68 of the width) and never taller than
 * it is wide, however small the items get. Optionally posts with 2 to 4 items are arranged the
 * same way instead of the cropped grid.
 */
@SuppressWarnings("unused")
public final class MediaCarousel {
    /** Most items X puts in one post. */
    public static final int MAX_ITEMS = 20;

    private static Field slotsField, itemsField, itemObjectField, sizeField, sizeWidthField, sizeHeightField;
    private static Method layoutSlotMethod;
    private static boolean broken;

    /** Per view: its last arrangement (item boxes relative to the view). */
    private static final class State {
        int[] left = new int[0], top = new int[0], width = new int[0], height = new int[0];
        int total;
    }

    private static final java.util.WeakHashMap<View, State> states = new java.util.WeakHashMap<>();
    private static final float TARGET_HEIGHT_RATIO = 0.68f, MAX_HEIGHT_RATIO = 1.0f, SPACING = 4f;
    private static final int MAX_ROWS = 6;

    private MediaCarousel() {
    }

    // region placeholders (the patch fills in the obfuscated names)

    private static String slotsFieldName() {
        return "b";
    }

    private static String itemsFieldName() {
        return "B";
    }

    private static String itemObjectFieldName() {
        return "a";
    }

    private static String sizeFieldName() {
        return "q";
    }

    private static String sizeWidthFieldName() {
        return "a";
    }

    private static String sizeHeightFieldName() {
        return "b";
    }

    private static String layoutSlotMethodName() {
        return "m";
    }

    // endregion

    private static boolean enabled() {
        try {
            return SettingsStatus.mediaCarousel && Boolean.TRUE.equals(Utils.getBooleanPref(Settings.MEDIA_CAROUSEL));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean smallToo() {
        try {
            return Boolean.TRUE.equals(Utils.getBooleanPref(Settings.MEDIA_CAROUSEL_SMALL));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Injection point: how many items the media view keeps (it had 4 slots). */
    public static int maxItems(int original) {
        return enabled() ? MAX_ITEMS : original;
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }

    private static ViewGroup[] slots(View view) throws Exception {
        if (slotsField == null) slotsField = field(view.getClass(), slotsFieldName());
        return (ViewGroup[]) slotsField.get(view);
    }

    private static List<?> items(View view) throws Exception {
        if (itemsField == null) itemsField = field(view.getClass(), itemsFieldName());
        return (List<?>) itemsField.get(view);
    }

    /** Width / height of an item from its media entity's original size (kept within 1:3 and 3:1), or 1. */
    private static float ratio(Object item) {
        try {
            if (itemObjectField == null) itemObjectField = field(item.getClass(), itemObjectFieldName());
            Object entity = itemObjectField.get(item);
            if (entity == null) return 1f;
            if (sizeField == null) sizeField = field(entity.getClass(), sizeFieldName());
            Object size = sizeField.get(entity);
            if (size == null) return 1f;
            if (sizeWidthField == null) {
                sizeWidthField = field(size.getClass(), sizeWidthFieldName());
                sizeHeightField = field(size.getClass(), sizeHeightFieldName());
            }
            int w = sizeWidthField.getInt(size), h = sizeHeightField.getInt(size);
            if (w <= 0 || h <= 0) return 1f;
            return Math.max(1f / 3f, Math.min(3f, (float) w / h));
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** How many items this view arranges in rows, or 0 (the grid stays). */
    private static int arrangedCount(View view) {
        if (broken || !enabled()) return 0;
        try {
            List<?> items = items(view);
            int n = items == null ? 0 : items.size();
            if (n > 4 || (n >= 2 && smallToo())) return n;
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
        return 0;
    }

    // region arrangement

    /**
     * Splits the items (in order) into {@code rows} rows whose aspect-ratio sums are as even as
     * possible (linear partition); returns the index each row starts at.
     */
    static int[] partition(float[] r, int rows) {
        int n = r.length;
        float[] prefix = new float[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + r[i];
        float ideal = prefix[n] / rows;
        // cost[k][i]: best cost of the first i items in k rows; the cost of a row is (sum - ideal)^2.
        float[][] cost = new float[rows + 1][n + 1];
        int[][] cut = new int[rows + 1][n + 1];
        for (float[] row : cost) java.util.Arrays.fill(row, Float.MAX_VALUE);
        cost[0][0] = 0;
        for (int k = 1; k <= rows; k++) {
            for (int i = k; i <= n; i++) {
                for (int j = k - 1; j < i; j++) {
                    if (cost[k - 1][j] == Float.MAX_VALUE) continue;
                    float d = prefix[i] - prefix[j] - ideal;
                    float c = cost[k - 1][j] + d * d;
                    if (c < cost[k][i]) {
                        cost[k][i] = c;
                        cut[k][i] = j;
                    }
                }
            }
        }
        int[] starts = new int[rows];
        for (int k = rows, i = n; k >= 1; k--) {
            starts[k - 1] = cut[k][i];
            i = cut[k][i];
        }
        return starts;
    }

    /** Total height of the rows for this split: each row is as tall as it must be to fill the width. */
    private static float totalHeight(float[] r, int[] starts, float width, float spacing) {
        float total = spacing * (starts.length - 1);
        for (int k = 0; k < starts.length; k++) {
            int from = starts[k], to = k + 1 < starts.length ? starts[k + 1] : r.length;
            float sum = 0;
            for (int i = from; i < to; i++) sum += r[i];
            total += (width - spacing * (to - from - 1)) / sum;
        }
        return total;
    }

    /**
     * The rows for these items: the number of rows whose total height is closest to the usual
     * media height (0.68 of the width) without being taller than the width.
     */
    static int[] arrange(float[] r, float width, float spacing) {
        int n = r.length;
        int[] best = null;
        float bestScore = Float.MAX_VALUE, target = TARGET_HEIGHT_RATIO * width, max = MAX_HEIGHT_RATIO * width;
        for (int rows = 1; rows <= Math.min(n, MAX_ROWS); rows++) {
            int[] starts = partition(r, rows);
            float h = totalHeight(r, starts, width, spacing);
            float score = Math.abs(h - target) + (h > max ? 10 * (h - max) : 0);
            if (score < bestScore) {
                bestScore = score;
                best = starts;
            }
        }
        return best;
    }

    // endregion

    /**
     * Injection point: the media view measured its items for a width; when it arranges them in
     * rows, measures them again at their boxes and returns the block's height (else -1).
     */
    public static int measure(ViewGroup view, int width, int height) {
        int n = arrangedCount(view);
        if (n == 0 || width <= 0) {
            states.remove(view);
            return -1;
        }
        try {
            State s = states.get(view);
            if (s == null) states.put(view, s = new State());
            float spacing = SPACING * view.getResources().getDisplayMetrics().density;
            List<?> items = items(view);
            ViewGroup[] slots = slots(view);
            float[] r = new float[n];
            for (int i = 0; i < n; i++) r[i] = ratio(items.get(i));
            int[] starts = arrange(r, width, spacing);
            if (s.left.length != n) {
                s.left = new int[n];
                s.top = new int[n];
                s.width = new int[n];
                s.height = new int[n];
            }
            float y = 0;
            for (int k = 0; k < starts.length; k++) {
                int from = starts[k], to = k + 1 < starts.length ? starts[k + 1] : n;
                float sum = 0;
                for (int i = from; i < to; i++) sum += r[i];
                float rowHeight = (width - spacing * (to - from - 1)) / sum;
                int top = Math.round(y), bottom = Math.round(y + rowHeight);
                float x = 0;
                for (int i = from; i < to; i++) {
                    int left = Math.round(x);
                    // The last item ends exactly at the edge, whatever the rounding.
                    int right = i == to - 1 ? width : Math.round(x + r[i] * rowHeight);
                    s.left[i] = left;
                    s.top[i] = top;
                    s.width[i] = Math.max(1, right - left);
                    s.height[i] = Math.max(1, bottom - top);
                    x += r[i] * rowHeight + spacing;
                    ViewGroup slot = i < slots.length ? slots[i] : null;
                    if (slot != null) {
                        slot.measure(View.MeasureSpec.makeMeasureSpec(s.width[i], View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(s.height[i], View.MeasureSpec.EXACTLY));
                    }
                }
                y += rowHeight + spacing;
            }
            s.total = Math.max(1, Math.round(y - spacing));
            return s.total;
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
            return -1;
        }
    }

    /** Injection point: the media view laid out its grid; arranged items go to their boxes instead. */
    public static void layout(ViewGroup view) {
        State s = states.get(view);
        if (s == null || arrangedCount(view) == 0) return;
        try {
            ViewGroup[] slots = slots(view);
            if (layoutSlotMethod == null) {
                layoutSlotMethod = view.getClass().getMethod(layoutSlotMethodName(), ViewGroup.class, int.class, int.class, int.class, int.class);
            }
            boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int width = view.getMeasuredWidth();
            for (int i = 0; i < s.left.length && i < slots.length; i++) {
                ViewGroup slot = slots[i];
                if (slot == null) continue;
                int left = rtl ? width - s.left[i] - s.width[i] : s.left[i];
                // The view's own slot layout: image scaling, and rounding where a box meets the view's corners.
                layoutSlotMethod.invoke(view, slot, left, s.top[i], left + s.width[i], s.top[i] + s.height[i]);
            }
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
    }
}
