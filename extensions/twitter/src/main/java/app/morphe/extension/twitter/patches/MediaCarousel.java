/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

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
 * the block is about as tall as X's media normally is (0.68 of the width), never so tall that the
 * post's header and actions don't fit on the screen with it, and no row thinner than 0.4 of the
 * width when that can be helped (four portrait photos become 2x2, not one row of thumbnails). Optionally posts with 2 to 4 items are arranged the
 * same way instead of the cropped grid.
 */
@SuppressWarnings("unused")
public final class MediaCarousel {
    /** Most items X puts in one post. */
    public static final int MAX_ITEMS = 20;

    private static Field slotsField, itemsField, itemObjectField, sizeField, sizeWidthField, sizeHeightField;
    private static Method layoutSlotMethod;
    private static boolean broken;

    private static final int TAG_BOX_CAP = 0x70696b40, TAG_BOX_FREED = 0x70696b41, TAG_BOX_X = 0x70696b42;
    /** Below the media on a post's page: its date, numbers and actions. */
    private static final float BELOW_DP = 200f;

    /**
     * X's own values of a media box this class changed, and the values it set: the box is shared by
     * whatever the list shows in that row next, so they go back before it shows other media.
     */
    private static final class BoxValues {
        final float ratio, minRatio, maxRatio;
        final int maxHeight;
        float setRatio = Float.NaN, setMinRatio = Float.NaN;
        int setMaxHeight = -1;

        BoxValues(float ratio, float minRatio, float maxRatio, int maxHeight) {
            this.ratio = ratio;
            this.minRatio = minRatio;
            this.maxRatio = maxRatio;
            this.maxHeight = maxHeight;
        }
    }

    private static Field boxMinRatioField;

    /** X's minimum ratio of the box (no getter): the float field the min setter writes; "b" in 12.19.1. */
    private static float minRatio(View box) throws Exception {
        if (boxMinRatioField == null) {
            Field f = field(box.getClass(), "b");
            if (f.getType() != float.class) throw new NoSuchFieldException("min ratio");
            boxMinRatioField = f;
        }
        return boxMinRatioField.getFloat(box);
    }

    private static Object call(View box, String name, Class<?> type, Object arg) throws Exception {
        return type == null ? box.getClass().getMethod(name).invoke(box) : box.getClass().getMethod(name, type).invoke(box, arg);
    }

    /**
     * Injection point: the media view gets new items (a post bound to its row). When they won't be
     * arranged here, the box around the view gets X's own values back before it is measured, so a
     * single photo or video never shows (for a frame) at the size of the rows of an earlier post.
     */
    public static void bind(ViewGroup view, List<?> items) {
        try {
            view.setTag(TAG_BOX_FREED, null);
            view.setTag(TAG_BOX_CAP, null);
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return;
            View box = (View) parent;
            Object tag = box.getTag(TAG_BOX_X);
            if (!(tag instanceof BoxValues)) return;
            int n = items == null ? 0 : Math.min(items.size(), MAX_ITEMS);
            // Arranged again: the new rows' ratio replaces the old one at the next layout (no jump in between).
            if (!broken && enabled() && (n > 4 || (n >= 2 && smallToo()))) return;
            restoreBox(box, (BoxValues) tag);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** A box this class changed still holds media it doesn't arrange (bound without new items): X's values back. */
    private static void unbox(ViewGroup view) {
        try {
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return;
            Object tag = ((View) parent).getTag(TAG_BOX_X);
            if (tag instanceof BoxValues) restoreBox((View) parent, (BoxValues) tag);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static void restoreBox(View box, BoxValues x) throws Exception {
        box.setTag(TAG_BOX_X, null);
        // Only values still as this class left them: X may have set its own since.
        if (!Float.isNaN(x.setMinRatio) && minRatio(box) == x.setMinRatio) call(box, "setMinAspectRatio", float.class, x.minRatio);
        if (!Float.isNaN(x.setRatio) && (Float) call(box, "getAspectRatio", null, null) == x.setRatio) {
            call(box, "setAspectRatio", float.class, x.ratio);
        }
        if (x.setMaxHeight >= 0 && (Integer) call(box, "getMaxHeight", null, null) == x.setMaxHeight) {
            call(box, "setMaxHeight", int.class, x.maxHeight);
        }
    }

    /**
     * The post's own page puts the media in a box that keeps X's 16:9 grid ratio: when the rows
     * don't fit in it, the box gets the rows' ratio; if it can't, the rows are arranged to fit in it.
     * Returns whether a new measure was asked for. Called while the view is laid out: the layout it
     * asks for runs again before anything is drawn (a second layout pass in the same frame), so the
     * media never shows at the old size first.
     */
    private static boolean fitBox(ViewGroup view, State s) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof View)) return false;
        View box = (View) parent;
        int shown = Math.min(view.getHeight(), box.getHeight());
        if (shown <= 0 || Math.abs(s.total - box.getHeight()) <= 1) return false;
        boolean tooTall = s.total > shown + 1;
        Object items;
        try {
            items = items(view);
        } catch (Throwable t) {
            return false;
        }
        // The post must stay whole on the screen: the media fits below where it starts, with room
        // left for the post's date, numbers and actions.
        android.util.DisplayMetrics dm = view.getResources().getDisplayMetrics();
        int[] at = new int[2];
        box.getLocationOnScreen(at);
        int room = Math.round(dm.heightPixels - at[1] - BELOW_DP * dm.density);
        Object capped = view.getTag(TAG_BOX_CAP);
        boolean hasCap = capped instanceof Object[] && ((Object[]) capped)[0] == items;
        if (tooTall && !hasCap && at[1] > 0 && s.total > room && room > shown) {
            view.setTag(TAG_BOX_CAP, new Object[]{items, room});
            relayout(view);
            return true;
        }
        if (view.getTag(TAG_BOX_FREED) != items) {
            view.setTag(TAG_BOX_FREED, items);
            // X's box keeps the media at 16:9 (AspectRatioFrameLayout): it gets the rows' own ratio
            // (taller for 2x2 portraits, shorter for a single row).
            try {
                float ratio = view.getWidth() / (float) s.total;
                Object tag = box.getTag(TAG_BOX_X);
                BoxValues x = tag instanceof BoxValues ? (BoxValues) tag : null;
                if (x == null) {
                    x = new BoxValues((Float) call(box, "getAspectRatio", null, null), minRatio(box),
                            (Float) call(box, "getMaxAspectRatio", null, null), (Integer) call(box, "getMaxHeight", null, null));
                    box.setTag(TAG_BOX_X, x);
                }
                call(box, "setMinAspectRatio", float.class, ratio);
                call(box, "setAspectRatio", float.class, ratio);
                x.setMinRatio = ratio;
                x.setRatio = ratio;
                Object maxHeight = call(box, "getMaxHeight", null, null);
                if (maxHeight instanceof Integer && (Integer) maxHeight > 0 && (Integer) maxHeight < s.total) {
                    call(box, "setMaxHeight", int.class, s.total);
                    x.setMaxHeight = s.total;
                }
                relayout(view);
                return true;
            } catch (Throwable ignored) {
                // Not a box with a ratio: the rows are fitted into it below.
            }
        }
        if (!tooTall) return false;
        Object cap = view.getTag(TAG_BOX_CAP);
        if (cap instanceof Object[] && ((Object[]) cap)[0] == items && (Integer) ((Object[]) cap)[1] == shown) return false;
        view.setTag(TAG_BOX_CAP, new Object[]{items, shown});
        relayout(view);
        return true;
    }

    /**
     * A new layout of the media view and its box. Asked for while they are laid out, it runs before
     * anything is drawn (a second layout pass in the same frame); the posted one makes sure it runs
     * when no second pass comes (the view's items are only placed by its own layout).
     */
    private static void relayout(ViewGroup view) {
        view.requestLayout();
        view.post(view::requestLayout);
    }

    /** Per view: its last arrangement (item boxes relative to the view). */
    private static final class State {
        int[] left = new int[0], top = new int[0], width = new int[0], height = new int[0];
        int total;
    }

    private static final java.util.WeakHashMap<View, State> states = new java.util.WeakHashMap<>();
    private static final float TARGET_HEIGHT_RATIO = 0.68f, MIN_ROW_RATIO = 0.4f, SPACING = 4f;
    /** Screen height kept for the app's bars and the rest of the post (header, two lines of text, actions). */
    private static final float RESERVED_DP = 330f;
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

    private static String requestMethodName() {
        return "n";
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

    private static float minRowHeight(float[] r, int[] starts, float width, float spacing) {
        float min = Float.MAX_VALUE;
        for (int k = 0; k < starts.length; k++) {
            int from = starts[k], to = k + 1 < starts.length ? starts[k + 1] : r.length;
            float sum = 0;
            for (int i = from; i < to; i++) sum += r[i];
            min = Math.min(min, (width - spacing * (to - from - 1)) / sum);
        }
        return min;
    }

    /**
     * The rows for these items: the number of rows whose total height is closest to the usual
     * media height (0.68 of the width), never taller than {@code maxHeight} (the screen minus room
     * for the rest of the post), and with no row so thin that its items are hard to see.
     */
    static int[] arrange(float[] r, float width, float spacing, float maxHeight) {
        int n = r.length;
        int[] best = null;
        float bestScore = Float.MAX_VALUE, target = TARGET_HEIGHT_RATIO * width, max = maxHeight,
                minRow = MIN_ROW_RATIO * width;
        for (int rows = 1; rows <= Math.min(n, MAX_ROWS); rows++) {
            int[] starts = partition(r, rows);
            float h = totalHeight(r, starts, width, spacing);
            // Rows thinner than 0.4 of the width make the items hard to see (four portrait photos
            // in one row): such a split loses to a taller one, e.g. 2x2.
            float thinnest = minRowHeight(r, starts, width, spacing);
            float score = Math.abs(h - target) + (h > max ? 10 * (h - max) : 0)
                    + (thinnest < minRow ? 5 * (minRow - thinnest) : 0);
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
            if (n == 0) unbox(view);
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
            android.util.DisplayMetrics dm = view.getResources().getDisplayMetrics();
            // The whole post stays on one screen: the media is never so tall that the post's
            // header and actions can't be seen with it.
            float maxHeight = Math.max(0.68f * width, dm.heightPixels - RESERVED_DP * dm.density);
            Object cap = view.getTag(TAG_BOX_CAP);
            if (cap instanceof Object[] && ((Object[]) cap)[0] == items) maxHeight = Math.min(maxHeight, (Integer) ((Object[]) cap)[1]);
            int[] starts = arrange(r, width, spacing, maxHeight);
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
        if (fitBox(view, s)) return;
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
                int top = s.top[i], right = left + s.width[i], bottom = top + s.height[i];
                // Already in its box (X only lays out its first 4 grid slots): skip X's rounding and image-scaling
                // setup, the expensive part, which every layout pass of a 20-image post would redo for all of them.
                if (slot.getLeft() == left && slot.getTop() == top && slot.getRight() == right && slot.getBottom() == bottom
                        && !slot.isLayoutRequested()) continue;
                // The view's own slot layout: image scaling, and rounding where a box meets the view's corners.
                layoutSlotMethod.invoke(view, slot, left, top, right, bottom);
            }
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
    }

    // region loading: in order, a couple at a time, with a spinner on each image still loading

    /*
     * X starts all images of a post at once. With 20 of them on a slow link each gets 1/20 of the
     * bandwidth, nothing finishes for a long time (or ever, when requests time out) and the block
     * stays empty: black in the dark theme. For the posts arranged here the images load in reading
     * order, WINDOW at a time, each finished one starting the next; failed ones are tried once more
     * at the end, and a watchdog moves on when one hangs.
     */
    private static final int WINDOW = 2, MAX_WINDOW = 6;
    /** An image that arrives this fast means a fast link: one more may load at the same time. */
    private static final long FAST_MS = 700;
    private static final long WATCHDOG_MS = 25_000;
    private static Method requestMethod;

    /** Per view: the images of its current items. */
    private static final class Queue {
        Object[] request;          // the request builder of each slot (null once started)
        boolean[] z, started, done, failed, retried;
        long[] startedAt;
        Runnable[] watchdog;
        int generation, window = WINDOW;
    }

    private static final java.util.WeakHashMap<View, Queue> queues = new java.util.WeakHashMap<>();

    private static boolean callRequest(ViewGroup slot, Object request, boolean z) {
        try {
            if (requestMethod == null) {
                for (Class<?> c = slot.getClass(); c != null && requestMethod == null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (m.getName().equals(requestMethodName()) && p.length == 2 && p[1] == boolean.class
                                && m.getReturnType() == boolean.class) {
                            m.setAccessible(true);
                            requestMethod = m;
                            break;
                        }
                    }
                }
            }
            return (Boolean) requestMethod.invoke(slot, request, z);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return false;
        }
    }

    private static int indexOf(ViewGroup view, View slot) {
        try {
            ViewGroup[] slots = slots(view);
            for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Injection point (the media view gives a slot its image): arranged posts queue their images,
     * everything else loads as X does.
     */
    public static boolean request(ViewGroup slot, Object request, boolean z) {
        ViewGroup view = slot.getParent() instanceof ViewGroup ? (ViewGroup) slot.getParent() : null;
        int n = view == null ? 0 : arrangedCount(view);
        int i = n == 0 ? -1 : indexOf(view, slot);
        if (i < 0 || i >= n || request == null) {
            if (view != null) showSpinner(slot, false);
            return callRequest(slot, request, z);
        }
        Queue q = queues.get(view);
        if (i == 0 || q == null || q.request.length != n) {   // new items: a new queue
            q = new Queue();
            q.request = new Object[n];
            q.z = new boolean[n];
            q.started = new boolean[n];
            q.done = new boolean[n];
            q.failed = new boolean[n];
            q.retried = new boolean[n];
            q.startedAt = new long[n];
            q.watchdog = new Runnable[n];
            Queue old = queues.put(view, q);
            q.generation = old == null ? 1 : old.generation + 1;
        }
        q.request[i] = request;
        q.z[i] = z;
        showSpinner(slot, true);   // waiting: a still ring; it spins once its image is on the way
        if (i < WINDOW) {
            start(view, q, i);
            return true;
        }
        callRequest(slot, null, true);   // clear what a reused slot showed before
        return false;
    }

    private static void start(ViewGroup view, Queue q, int i) {
        try {
            ViewGroup slot = slots(view)[i];
            Object request = q.request[i];
            q.started[i] = true;
            q.startedAt[i] = SystemClock.uptimeMillis();
            if (slot.getForeground() instanceof Spinner) ((Spinner) slot.getForeground()).setActive(true);
            callRequest(slot, request, q.z[i]);
            int generation = q.generation;
            Runnable watchdog = () -> {
                Queue now = queues.get(view);
                if (now == q && now.generation == generation && !q.done[i]) next(view, q);
            };
            q.watchdog[i] = watchdog;
            view.postDelayed(watchdog, WATCHDOG_MS);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** Starts the first image not started yet, else retries one that failed. */
    private static void next(ViewGroup view, Queue q) {
        for (int k = 0; k < q.request.length; k++) {
            if (!q.started[k] && q.request[k] != null) {
                start(view, q, k);
                return;
            }
        }
        for (int k = 0; k < q.request.length; k++) {
            if (q.failed[k] && !q.retried[k]) {
                q.retried[k] = true;
                q.failed[k] = false;
                q.done[k] = false;
                try {
                    showSpinner(slots(view)[k], true);
                } catch (Throwable ignored) {
                }
                start(view, q, k);
                return;
            }
        }
    }

    /** Injection point (a slot's image finished, or failed): spinner off, the next image starts. */
    public static void loaded(View slot, Object result) {
        try {
            ViewGroup view = slot.getParent() instanceof ViewGroup ? (ViewGroup) slot.getParent() : null;
            boolean ok = succeeded(result);
            if (ok || view == null) showSpinner(slot, false);
            if (view == null) return;
            Queue q = queues.get(view);
            int i = indexOf(view, slot);
            if (q == null || i < 0 || i >= q.request.length || !q.started[i] || q.done[i]) return;
            q.done[i] = true;
            q.failed[i] = !ok;
            if (q.watchdog[i] != null) {   // arrived: its watchdog (and what it holds) can go
                view.removeCallbacks(q.watchdog[i]);
                q.watchdog[i] = null;
            }
            if (!ok && q.retried[i]) showSpinner(slot, false);   // given up: X's placeholder stays
            if (ok && SystemClock.uptimeMillis() - q.startedAt[i] < FAST_MS) q.window = Math.min(MAX_WINDOW, q.window + 1);
            int loading = 0;
            for (int k = 0; k < q.request.length; k++) if (q.started[k] && !q.done[k]) loading++;
            for (int k = loading; k < q.window; k++) next(view, q);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static Method successMethod;

    /** com.twitter.media.request.d#a(): whether the image arrived. */
    private static boolean succeeded(Object result) {
        try {
            if (successMethod == null) {
                for (Class<?> c = result.getClass(); c != null && successMethod == null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals("a") && m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class) {
                            m.setAccessible(true);
                            successMethod = m;
                            break;
                        }
                    }
                }
            }
            return successMethod == null || (Boolean) successMethod.invoke(result);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void showSpinner(View slot, boolean on) {
        Drawable current = slot.getForeground();
        if (on) {
            if (current instanceof Spinner) return;
            Spinner spinner = new Spinner(slot.getResources().getDisplayMetrics().density);
            slot.setForegroundGravity(Gravity.FILL);
            slot.setForeground(spinner);
        } else if (current instanceof Spinner) {
            slot.setForeground(null);
        }
    }

    /**
     * A spinning arc on a dark disc (readable on light and dark placeholders), at most 36 dp and never wider than
     * the image; a still, fainter ring while the image waits for its turn.
     */
    static final class Spinner extends Drawable {
        private boolean active;

        void setActive(boolean on) {
            active = on;
            invalidateSelf();
        }

        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
                arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final RectF oval = new RectF();

        Spinner(float density) {
            this.density = density;
            disc.setColor(0x73000000);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(2.5f * density);
            track.setColor(0x40FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(2.5f * density);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(0xF2FFFFFF);
        }


        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float r = Math.min(18 * density, Math.min(b.width(), b.height()) * 0.36f);
            if (r < 6 * density) return;
            float stroke = Math.max(1.5f * density, r / 7.2f);
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            disc.setAlpha(active ? 0x73 : 0x40);
            canvas.drawCircle(cx, cy, r, disc);
            float ring = r * 0.5f;
            oval.set(cx - ring, cy - ring, cx + ring, cy + ring);
            canvas.drawOval(oval, track);
            if (!active) return;
            long t = SystemClock.uptimeMillis();
            float rotation = (t % 1100L) / 1100f * 360f;
            float sweep = 60f + 200f * (0.5f + 0.5f * (float) Math.sin(t / 700.0 * Math.PI));
            canvas.drawArc(oval, rotation, sweep, false, arc);
            invalidateSelf();   // keeps spinning while it is the slot's foreground
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // endregion
}
