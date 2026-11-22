/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Feature after the "Not interested" button of the user's Control Panel for Twitter fork
 * (https://github.com/insin/control-panel-for-twitter, MIT License, Copyright (c) 2019 Jonny Buchanan).
 */

package app.morphe.extension.twitter.patches;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * A "Not interested" button in the action bar of posts X offers that feedback for (the "For you"
 * timeline). It runs X's own "Not interested in this post" action, the one in the post's menu,
 * then picks "This post isn't relevant" in the row X shows instead and hides that row.
 *
 * <p>The post's timeline item and the handler of its menu actions are remembered when its
 * header menu is bound (by post id, weakly); the button only looks them up when tapped.
 */
@SuppressWarnings("unused")
public final class DislikeButton {
    private static final String DONT_LIKE = "DontLike";
    private static final Pattern NOT_RELEVANT = Pattern.compile("isn[’']?t relevant", Pattern.CASE_INSENSITIVE);
    private static final int TAG_LIST_WATCH = 0x70696b05;
    private static final int REGISTRY_CAP = 200;

    private static final class Bound {
        final WeakReference<Object> handler, item;

        Bound(Object handler, Object item) {
            this.handler = new WeakReference<>(handler);
            this.item = new WeakReference<>(item);
        }
    }

    private static final Map<Long, Bound> registry = new LinkedHashMap<Long, Bound>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Bound> eldest) {
            return size() > REGISTRY_CAP;
        }
    };
    /** Authors of posts dismissed with the button (newest last, at most 100), kept across restarts. */
    private static final int DISMISSED_CAP = 100;
    private static java.util.LinkedHashSet<String> dismissedAuthors;
    private static Pattern dismissedPattern;

    private static android.content.SharedPreferences store() {
        return app.morphe.extension.shared.Utils.getContext().getSharedPreferences("piko_not_interested", android.content.Context.MODE_PRIVATE);
    }

    private static synchronized java.util.LinkedHashSet<String> authors() {
        if (dismissedAuthors == null) {
            dismissedAuthors = new java.util.LinkedHashSet<>();
            try {
                String raw = store().getString("authors", "");
                for (String h : raw.split(",")) if (!h.isEmpty()) dismissedAuthors.add(h);
            } catch (Throwable ignored) {
            }
            dismissedPattern = null;
        }
        return dismissedAuthors;
    }

    /** Authors whose dismissal is still being handled (their new row is left to that flow). */
    private static final java.util.Set<String> inProgress = new java.util.HashSet<>();

    private static synchronized void rememberAuthor(String handle) {
        inProgress.remove(handle.toLowerCase(java.util.Locale.ROOT));
        java.util.LinkedHashSet<String> set = authors();
        set.remove(handle);
        set.add(handle);
        while (set.size() > DISMISSED_CAP) set.remove(set.iterator().next());
        dismissedPattern = null;
        try {
            store().edit().putString("authors", String.join(",", set)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Matches X's feedback row text naming any dismissed author (whole word), or null when none. */
    private static synchronized Pattern dismissedPattern() {
        java.util.LinkedHashSet<String> set = authors();
        if (set.isEmpty()) return null;
        if (dismissedPattern == null) {
            StringBuilder sb = new StringBuilder("(?<![A-Za-z0-9_])(?:");
            boolean first = true;
            for (String h : set) {
                if (!first) sb.append('|');
                sb.append(Pattern.quote(h));
                first = false;
            }
            dismissedPattern = Pattern.compile(sb.append(")(?![A-Za-z0-9_])").toString(), Pattern.CASE_INSENSITIVE);
        }
        return dismissedPattern;
    }

    /** Rows hidden after a dismissal, with the text they showed then. */
    private static final Map<View, String> dismissedRows = new WeakHashMap<>();
    private static Handler main;

    private static Field handlerField, tweetField, feedbackListField, actionTypeField;
    private static Field lambdaBinderField, stateTweetField, stateItemField;
    private static Method idMethod, feedbackInfoMethod, hasFeedbackMethod, dismissMethod;
    private static boolean broken;

    private DislikeButton() {
    }

    // region placeholders (the patch fills in the obfuscated names)

    private static String handlerFieldName() {
        return "a";
    }

    private static String lambdaBinderFieldName() {
        return "a";
    }

    private static String stateTweetFieldName() {
        return "a";
    }

    private static String stateItemFieldName() {
        return "f";
    }

    private static String tweetFieldName() {
        return "k";
    }

    private static String hasFeedbackMethodName() {
        return "j";
    }

    private static String feedbackInfoMethodName() {
        return "c";
    }

    private static String feedbackListFieldName() {
        return "s";
    }

    private static String actionTypeFieldName() {
        return "a";
    }

    private static String dismissMethodName() {
        return "q";
    }

    // endregion

    static boolean enabled() {
        try {
            return SettingsStatus.notInterestedButton && Boolean.TRUE.equals(Utils.getBooleanPref(Settings.NOT_INTERESTED_BUTTON));
        } catch (Throwable t) {
            return false;
        }
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

    private static Method method(Class<?> cls, String name, boolean returnsBoolean) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0
                        && (m.getReturnType() == boolean.class) == returnsBoolean && m.getReturnType() != void.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(cls.getName() + "." + name + "()");
    }

    /**
     * Injection point: a timeline post's menu got a state (its tweet and timeline item). Cheap:
     * a few field reads and a map put; nothing else happens until the button is tapped.
     */
    public static void onCurationState(Object lambda, Object state) {
        long t = app.morphe.extension.twitter.patches.country.Perf.start();
        curationState(lambda, state);
        app.morphe.extension.twitter.patches.country.Perf.end(2, t);
    }

    private static void curationState(Object lambda, Object state) {
        if (broken || lambda == null || state == null || !enabled()) return;
        try {
            if (lambdaBinderField == null) {
                lambdaBinderField = field(lambda.getClass(), lambdaBinderFieldName());
                stateTweetField = field(state.getClass(), stateTweetFieldName());
                stateItemField = field(state.getClass(), stateItemFieldName());
            }
            Object binder = lambdaBinderField.get(lambda);
            Object item = stateItemField.get(state);
            Object tweet = stateTweetField.get(state);
            if (binder == null || item == null || tweet == null) return;
            if (handlerField == null) {
                handlerField = field(binder.getClass(), handlerFieldName());
                tweetField = field(item.getClass(), tweetFieldName());
            }
            Object handler = handlerField.get(binder);
            Long id = tweetId(tweet);
            if (handler == null || id == null) return;
            synchronized (registry) {
                Bound old = registry.get(id);
                // The same post's menu re-emits its state often: only a new item needs work.
                if (old != null && old.item.get() == item && old.handler.get() == handler) return;
                registry.put(id, new Bound(handler, item));
            }
            if (DEBUG) debug("bound " + id);
            refreshBars(id);
        } catch (Throwable t) {
            broken = true;
            if (DEBUG) debug("bind error " + t);
            PikoUtils.logger(t);
        }
    }

    static final boolean DEBUG = false;

    static void debug(String msg) {
        if (DEBUG) android.util.Log.d("PikoDislike", msg);
    }

    /** Action bars on screen and the post each shows, so a late menu bind can show the button. */
    private static final Map<View, Long> bars = Collections.synchronizedMap(new WeakHashMap<>());

    /** The action bar now shows {@code tweet}. */
    static void onBar(View bar, Object tweet) {
        Long id = tweet == null ? null : tweetId(tweet);
        if (id == null) bars.remove(bar);
        else bars.put(bar, id);

        // The list shows X's rows of earlier dismissals again after a restart (timeline cache):
        // watch it so they are hidden too.
        if (bar.getTag(TAG_LIST_WATCH) == null && dismissedPattern() != null) {
            View row = rowOf(bar);
            if (row != null && row.getParent() instanceof ViewGroup) {
                bar.setTag(TAG_LIST_WATCH, Boolean.TRUE);
                watch((ViewGroup) row.getParent());
            }
        }
    }

    /** The action bars on screen with the post each shows. */
    static List<Map.Entry<View, Long>> barsSnapshot() {
        synchronized (bars) {
            return new ArrayList<>(bars.entrySet());
        }
    }

    private static void refreshBars(long id) {
        main().post(() -> {
            List<View> match = new ArrayList<>();
            synchronized (bars) {
                for (Map.Entry<View, Long> e : bars.entrySet()) {
                    if (e.getKey() != null && e.getValue() == id) match.add(e.getKey());
                }
            }
            for (View bar : match) InlineDownloadButton.refreshDislike((ViewGroup) bar);
        });
    }

    private static Long tweetId(Object tweet) {
        try {
            if (idMethod == null) idMethod = tweet.getClass().getMethod("getId");
            Object v = idMethod.invoke(tweet);
            return v instanceof Long ? (Long) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** X's "Not interested in this post" action of the item, or null when X doesn't offer it. */
    private static Object dontLikeAction(Object item) throws Exception {
        if (hasFeedbackMethod == null) hasFeedbackMethod = method(item.getClass(), hasFeedbackMethodName(), true);
        if (!Boolean.TRUE.equals(hasFeedbackMethod.invoke(item))) return null;
        if (feedbackInfoMethod == null) feedbackInfoMethod = method(item.getClass(), feedbackInfoMethodName(), false);
        Object info = feedbackInfoMethod.invoke(item);
        if (info == null) return null;
        if (feedbackListField == null) feedbackListField = field(info.getClass(), feedbackListFieldName());
        Object list = feedbackListField.get(info);
        if (!(list instanceof List)) return null;
        for (Object action : (List<?>) list) {
            if (action == null) continue;
            if (actionTypeField == null) actionTypeField = field(action.getClass(), actionTypeFieldName());
            if (DONT_LIKE.equalsIgnoreCase(String.valueOf(actionTypeField.get(action)))) return action;
        }
        return null;
    }

    /** Whether the post with this tweet model can be marked "Not interested" right now. */
    static boolean available(Object tweet) {
        if (broken || tweet == null || !enabled()) return false;
        Long id = tweetId(tweet);
        if (id == null) return false;
        Bound e;
        synchronized (registry) {
            e = registry.get(id);
        }
        if (e == null || e.handler.get() == null) {
            if (DEBUG) debug("no entry " + id);
            return false;
        }
        Object item = e.item.get();
        try {
            boolean ok = item != null && dontLikeAction(item) != null;
            if (DEBUG) debug("available " + id + " " + ok);
            return ok;
        } catch (Throwable t) {
            if (DEBUG) debug("available error " + t);
            return false;
        }
    }

    /** The button was tapped on the post shown by {@code actionBar}. */
    static void dismiss(View actionBar, Object tweet) {
        try {
            Long id = tweetId(tweet);
            Bound e;
            synchronized (registry) {
                e = id == null ? null : registry.get(id);
            }
            Object handler = e == null ? null : e.handler.get();
            Object item = e == null ? null : e.item.get();
            Object action = item == null ? null : dontLikeAction(item);
            if (handler == null || action == null) {
                app.morphe.extension.shared.Utils.showToastShort(AdvancedSearch.t(actionBar.getContext(), "piko_ni_unavailable", "X doesn't offer \"Not interested\" for this post"));
                return;
            }
            Object itemTweet = tweetField.get(item);
            if (dismissMethod == null) {
                for (Class<?> c = handler.getClass(); c != null && dismissMethod == null; c = c.getSuperclass()) {
                    dismissMethod = findDismiss(c);
                }
                if (dismissMethod == null) throw new NoSuchMethodException("dismiss on " + handler.getClass());
            }
            View row = rowOf(actionBar);
            ViewGroup list = row == null ? null : (ViewGroup) row.getParent();
            int rowTop = row == null ? 0 : row.getTop();
            // The author's handle (tagged on the post header by the country filter's hook): X's
            // feedback row names it ("Show fewer posts from …"), which identifies the row later.
            Object tag = row == null ? null : findTag(row, 0x70696b01);
            String handle = tag instanceof String ? (String) tag : null;
            if (handle != null) {
                synchronized (DislikeButton.class) {
                    inProgress.add(handle.toLowerCase(java.util.Locale.ROOT));
                }
            }
            // The post folds away at once; X's feedback row that replaces it is caught before it is drawn.
            boolean smooth = handle != null && row != null && list != null;
            FoldOverlay fold = smooth ? FoldOverlay.start(list, row) : null;
            dismissMethod.invoke(handler, itemTweet, item, action);
            if (DEBUG) debug("dismissed " + id + " via " + dismissMethod.getName());
            if (smooth) catchFeedbackRow(list, row, handle, rowTop, fold);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static Method findDismiss(Class<?> c) {
        for (Class<?> i : c.getInterfaces()) {
            for (Method m : i.getMethods()) {
                if (m.getName().equals(dismissMethodName()) && m.getParameterTypes().length == 3) return m;
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(dismissMethodName()) && m.getParameterTypes().length == 3) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // region after the action: pick "This post isn't relevant", then hide X's row

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    static final String FEEDBACK_REASON = "not-interested-feedback";
    private static final int TAG_HIDDEN_AT = 0x70696b25;
    private static final long CATCH_MS = 3000;
    private static int[] feedbackIds;

    /** X's feedback row (its Undo, its list of reasons, or its "Thanks" line), in any of its states. */
    private static boolean isFeedbackRow(View row) {
        if (feedbackIds == null) {
            android.content.Context c = row.getContext();
            String pkg = c.getPackageName();
            android.content.res.Resources r = c.getResources();
            feedbackIds = new int[]{r.getIdentifier("undo_feedback", "id", pkg), r.getIdentifier("feedback_items", "id", pkg),
                    r.getIdentifier("confirmation_text", "id", pkg)};
        }
        for (int id : feedbackIds) if (id != 0 && row.findViewById(id) != null) return true;
        return false;
    }

    /**
     * Watches the list's frames for 3 s for what X puts where the post was: its feedback row (a
     * row without a post header that names the post's author, "Show fewer posts from …"), then
     * the same row loading and saying "Thanks". Each is hidden before it is drawn. "This post
     * isn't relevant" is picked in the first. Rows elsewhere in the list are never touched.
     */
    private static void catchFeedbackRow(ViewGroup list, View postRow, String handle, int rowTop, FoldOverlay fold) {
        Pattern author = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(handle) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        int slot = Math.max(8, list.getResources().getDisplayMetrics().densityDpi / 10);
        long deadline = android.os.SystemClock.uptimeMillis() + CATCH_MS;
        ViewTreeObserver.OnPreDrawListener[] self = new ViewTreeObserver.OnPreDrawListener[1];
        boolean[] found = new boolean[1], done = new boolean[1];
        int[] caughtTop = new int[1];
        Runnable stop = () -> {
            if (done[0]) return;
            done[0] = true;
            list.getViewTreeObserver().removeOnPreDrawListener(self[0]);
            if (!found[0]) {
                if (DEBUG) debug("feedback row not found");
                if (fold != null) fold.settled();
                rememberAuthor(handle);
            }
        };
        self[0] = () -> {
            if (done[0]) return true;
            if (android.os.SystemClock.uptimeMillis() > deadline) {
                stop.run();
                return true;
            }
            boolean hid = false;
            for (int i = 0, n = list.getChildCount(); i < n; i++) {
                View child = list.getChildAt(i);
                if (child == postRow || RowHide.isHidden(child) || hasPostHeader(child)) continue;
                boolean feedback = isFeedbackRow(child);
                // X's row naming the author: where the post was, or anywhere when X re-anchored the
                // list (a post partly scrolled off the top); its later states take the same place.
                boolean first = !found[0] && author.matcher(textOf(child)).find()
                        && (feedback || Math.abs(child.getTop() - rowTop) <= slot);
                boolean later = found[0] && feedback && Math.abs(child.getTop() - caughtTop[0]) <= slot;
                if (!first && !later) continue;
                hideRow(list, child, handle);
                hid = true;
                if (first) {
                    found[0] = true;
                    caughtTop[0] = child.getTop();
                    View row = child;
                    if (DEBUG) debug("feedback row hidden before drawing");
                    main().post(() -> pickReason(list, row, handle));
                    if (fold != null) fold.settled();
                }
            }
            return !hid; // a frame that would show X's row is skipped: the next one is laid out without it
        };
        list.getViewTreeObserver().addOnPreDrawListener(self[0]);
        main().postDelayed(stop, CATCH_MS + 100);
    }

    private static void pickReason(ViewGroup list, View row, String handle) {
        View reason = notRelevantOption(row, handle);
        if (reason != null) {
            boolean clicked = reason.performClick();
            if (DEBUG) debug("picked not relevant: " + clicked);
        } else if (DEBUG) {
            debug("no reasons offered");
        }
        rememberAuthor(handle);
        showUndo(list, row, handle);
    }

    /**
     * "This post isn't relevant" in the feedback row: by its English text, else (another
     * language) the last of the row's options, the one that doesn't name the author ("Show
     * fewer posts from …" does). The row has at least three: Undo and the two reasons.
     */
    private static View notRelevantOption(View row, String handle) {
        View byText = findText(row, NOT_RELEVANT);
        if (byText != null) return clickableOf(byText, row);
        List<View> options = new ArrayList<>();
        collectClickables(row, row, options);
        if (options.size() < 3) return null;
        View last = options.get(options.size() - 1);
        return handle == null || !textOf(last).contains(handle) ? last : null;
    }

    private static View clickableOf(View view, View row) {
        View v = view;
        while (v != row && !v.isClickable() && v.getParent() instanceof View) v = (View) v.getParent();
        return v;
    }

    // The row itself may be hidden by us (it is read before and after): only what is inside it must be visible.

    private static void collectClickables(View view, View row, List<View> out) {
        if (view != row && view.isClickable() && findText(view, null) != null) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View child = g.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE) collectClickables(child, row, out);
            }
        }
    }

    private static View findText(View view, Pattern pattern) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0 && (pattern == null || pattern.matcher(text).find())) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View child = g.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                View found = findText(child, pattern);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object findTag(View view, int key) {
        Object tag = view.getTag(key);
        if (tag != null) return tag;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                Object found = findTag(g.getChildAt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasPostHeader(View view) {
        if (view.getTag(0x70696b01) != null) return true; // tagged by the country filter's header hook
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                if (hasPostHeader(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /** All the text a row shows, to recognize it again. */
    private static String textOf(View view) {
        StringBuilder sb = new StringBuilder();
        collectText(view, sb);
        return sb.toString();
    }

    private static void collectText(View view, StringBuilder sb) {
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null) sb.append(t).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) collectText(g.getChildAt(i), sb);
        }
    }

    // region Undo and forgetting

    private static final String UNDO_TAG = "piko_not_interested_undo";
    private static final Pattern UNDO = Pattern.compile("^\\s*Undo\\s*$", Pattern.CASE_INSENSITIVE);

    /** A small bar above the bottom tabs for 6 s: "Post hidden" with Undo. */
    private static void showUndo(ViewGroup list, View row, String handle) {
        android.app.Activity activity = UndoBar.activityOf(list.getContext());
        UndoBar.show(activity, AdvancedSearch.t(list.getContext(), "piko_ni_hidden", "Post hidden"), () -> undo(list, row, handle));
    }

    /**
     * Forgets the author and presses X's own Undo (which also tells X) until X shows the post
     * again: once from "Thanks" back to the reasons, once more back to the post. X's row stays
     * hidden meanwhile (each of its states is caught before it is drawn), so only the post
     * coming back is seen.
     */
    private static void undo(ViewGroup list, View row, String handle) {
        try {
            forgetAuthor(handle);
            // The row stays on the watch list (a recycled one is shown again there).
            row.setTag(TAG_HIDDEN_AT, android.os.SystemClock.uptimeMillis());
            int slotTop = row.getTop();
            hideFeedbackAt(list, row, slotTop, CATCH_MS);
            pressXUndo(list, row, slotTop, 0);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static View xUndoOf(View row) {
        View found = findText(row, UNDO);
        if (found != null) return clickableOf(found, row);
        List<View> options = new ArrayList<>();
        collectClickables(row, row, options);
        return options.isEmpty() ? null : options.get(0); // Undo is the row's first action
    }

    /** X's feedback row in the slot (hidden or not): the given one while it still is, else one at the slot's top. */
    private static View feedbackRowAt(ViewGroup list, View row, int slotTop) {
        if (row.getParent() == list && isFeedbackRow(row) && !hasPostHeader(row)) return row;
        int slot = Math.max(8, list.getResources().getDisplayMetrics().densityDpi / 10);
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            View child = list.getChildAt(i);
            if (Math.abs(child.getTop() - slotTop) <= slot && isFeedbackRow(child) && !hasPostHeader(child)) return child;
        }
        return null;
    }

    private static void pressXUndo(ViewGroup list, View row, int slotTop, int step) {
        try {
            View target = feedbackRowAt(list, row, slotTop);
            if (target == null) return; // the post is back
            View undo = xUndoOf(target);
            if (undo != null) undo.performClick();
            if (DEBUG) debug("undo step " + step + " pressed " + (undo != null));
            if (step < 4) main().postDelayed(() -> pressXUndo(list, row, slotTop, step + 1), 150);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** For a while, X's feedback rows that show up in the slot are hidden before they are drawn. */
    private static void hideFeedbackAt(ViewGroup list, View row, int slotTop, long ms) {
        int slot = Math.max(8, list.getResources().getDisplayMetrics().densityDpi / 10);
        long deadline = android.os.SystemClock.uptimeMillis() + ms;
        ViewTreeObserver.OnPreDrawListener[] self = new ViewTreeObserver.OnPreDrawListener[1];
        self[0] = () -> {
            if (android.os.SystemClock.uptimeMillis() > deadline) {
                list.getViewTreeObserver().removeOnPreDrawListener(self[0]);
                return true;
            }
            boolean hid = false;
            for (int i = 0, n = list.getChildCount(); i < n; i++) {
                View child = list.getChildAt(i);
                if (RowHide.isHidden(child) || hasPostHeader(child) || !isFeedbackRow(child)) continue;
                if (child != row && Math.abs(child.getTop() - slotTop) > slot) continue;
                hideRow(list, child, null);
                hid = true;
            }
            return !hid;
        };
        list.getViewTreeObserver().addOnPreDrawListener(self[0]);
    }

    private static synchronized void forgetAuthor(String handle) {
        if (handle == null) return;
        java.util.LinkedHashSet<String> set = authors();
        set.removeIf(h -> h.equalsIgnoreCase(handle));
        dismissedPattern = null;
        try {
            store().edit().putString("authors", String.join(",", set)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Settings button: forgets every remembered author; returns how many there were. */
    public static synchronized int clearRemembered() {
        int n = authors().size();
        authors().clear();
        dismissedPattern = null;
        try {
            store().edit().remove("authors").apply();
        } catch (Throwable ignored) {
        }
        return n;
    }

    // endregion

    private static void hideRow(ViewGroup list, View row, String handle) {
        if (row.getParent() != list || RowHide.isHiddenBy(row, FEEDBACK_REASON) || hasPostHeader(row)) return;
        String text = textOf(row);
        // Identify the row by the author's handle when X names it, else by all of its text.
        String signature = handle != null && text.contains(handle) ? handle : text;
        dismissedRows.put(row, signature);
        row.setTag(TAG_HIDDEN_AT, android.os.SystemClock.uptimeMillis());
        RowHide.hide(row, FEEDBACK_REASON, false);
        if (DEBUG) debug("hid feedback row, signature: " + signature.replace('\n', '|'));
        watch(list);
    }

    /**
     * The list may reuse a hidden row for another item: after each layout, a hidden row whose
     * text changed is shown again. Only hidden rows are looked at.
     */
    private static final int TAG_LEFTOVER_CHECK = 0x70696b0f;

    private static void watch(ViewGroup list) {
        if (list.getTag(TAG_LIST_WATCH) != null) return;
        list.setTag(TAG_LIST_WATCH, Boolean.TRUE);
        list.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            // Layouts come in bursts while rows attach: X's feedback rows are looked for at most every 150 ms.
            if (list.getTag(TAG_LEFTOVER_CHECK) == null) {
                list.setTag(TAG_LEFTOVER_CHECK, Boolean.TRUE);
                list.postDelayed(() -> {
                    list.setTag(TAG_LEFTOVER_CHECK, null);
                    hideLeftovers(list);
                }, 150);
            }
            if (dismissedRows.isEmpty()) return;
            List<View> rows = new ArrayList<>(dismissedRows.keySet());
            for (View row : rows) {
                if (row == null || row.getParent() != list) continue;
                String was = dismissedRows.get(row);
                // X swaps the reasons for its "Thanks" line in the same row (which no longer names
                // the author): the row stays hidden unless it now shows a post, or other text later on.
                // A row reused for another dismissal shows its reasons again (Undo + two).
                if (!hasPostHeader(row)) {
                    String now = textOf(row);
                    if (was != null && (was.equals(now) || now.contains(was))) continue;
                    Object at = row.getTag(TAG_HIDDEN_AT);
                    if (at instanceof Long && android.os.SystemClock.uptimeMillis() - (Long) at < CATCH_MS) continue;
                    if (options(row) < 3) continue;
                }
                dismissedRows.remove(row);
                RowHide.show(row, FEEDBACK_REASON);
            }
        });
    }

    /**
     * Hides X's feedback rows of posts dismissed with the button (in this or an earlier session).
     * Only rows that show no post are read, so post rows cost one tag lookup each.
     */
    private static void hideLeftovers(ViewGroup list) {
        Pattern p = dismissedPattern();
        if (p == null) return;
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            View child = list.getChildAt(i);
            if (RowHide.isHidden(child) || child.getTag(0x70696b07) != null) continue; // hidden, or a post row
            if (hasPostHeader(child)) continue;
            String text = textOf(child);
            java.util.regex.Matcher m = p.matcher(text);
            if (!m.find()) continue;
            String who = m.group();
            synchronized (DislikeButton.class) {
                if (inProgress.contains(who.toLowerCase(java.util.Locale.ROOT))) continue;
            }
            if (options(child) >= 3) hideRow(list, child, who);
        }
    }

    private static int options(View row) {
        List<View> options = new ArrayList<>();
        collectClickables(row, row, options);
        return options.size();
    }

    /** The list row (a direct child of a RecyclerView) that holds this view, or null. */
    static View rowOf(View view) {
        View child = view;
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            for (Class<?> c = parent.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
                if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) return child;
            }
            child = (View) parent;
            parent = parent.getParent();
        }
        return null;
    }

    // endregion
}
