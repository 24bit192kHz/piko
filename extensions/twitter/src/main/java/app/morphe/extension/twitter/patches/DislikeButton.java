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
    private static final int TAG_DISMISSED = 0x70696b04, TAG_LIST_WATCH = 0x70696b05;
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
            dismissMethod.invoke(handler, itemTweet, item, action);
            if (DEBUG) debug("dismissed " + id + " via " + dismissMethod.getName());
            if (list != null) pickNotRelevant(list, handle, rowTop, 0);
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

    /**
     * Polls the list (every 100 ms, up to 3 s) for the feedback row X puts where the post was: a
     * row without a post header that names the post's author ("Show fewer posts from …"), or,
     * when the author isn't known, the row in the post's place. Nothing else is ever touched.
     */
    private static void pickNotRelevant(ViewGroup list, String handle, int rowTop, int attempt) {
        // Without the author (the country filter's header hook is off) the row can't be told
        // apart safely: X's own row stays, with its Undo.
        if (handle == null) return;
        Pattern author = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(handle) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        int slot = Math.max(8, list.getResources().getDisplayMetrics().densityDpi / 10);
        main().postDelayed(() -> {
            View target = null;
            for (int i = 0, n = list.getChildCount(); i < n && target == null; i++) {
                View child = list.getChildAt(i);
                if (dismissedRows.containsKey(child) || hasPostHeader(child)) continue;
                // X's row in the post's place that names the author ("Show fewer posts from …").
                if (Math.abs(child.getTop() - rowTop) <= slot && author.matcher(textOf(child)).find()) target = child;
            }
            if (target == null) {
                if (attempt < 30) {
                    pickNotRelevant(list, handle, rowTop, attempt + 1);
                } else {
                    if (DEBUG) debug("feedback row not found");
                    rememberAuthor(handle);
                }
                return;
            }
            View reason = notRelevantOption(target, handle);
            if (reason != null) {
                boolean clicked = reason.performClick();
                if (DEBUG) debug("picked not relevant: " + clicked + " after " + (attempt * 100) + " ms");
                View row = target;
                // X swaps the reasons for its "Thanks" line in the same row; hide it once it settles.
                // Only then is the author remembered, so its row of this dismissal is left to us.
                main().postDelayed(() -> {
                    hideRow(list, row, handle);
                    rememberAuthor(handle);
                    showUndo(list, row, handle);
                }, 400);
            } else {
                if (DEBUG) debug("no reasons offered, hiding the row");
                hideRow(list, target, handle);
                rememberAuthor(handle);
                showUndo(list, target, handle);
            }
        }, 100);
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

    private static void collectClickables(View view, View row, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view != row && view.isClickable() && findText(view, null) != null) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) collectClickables(g.getChildAt(i), row, out);
        }
    }

    private static View findText(View view, Pattern pattern) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0 && (pattern == null || pattern.matcher(text).find())) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View found = findText(g.getChildAt(i), pattern);
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
        try {
            android.app.Activity activity = activityOf(list.getContext());
            if (activity == null) return;
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof android.widget.FrameLayout)) return;
            android.widget.FrameLayout frame = (android.widget.FrameLayout) content;
            View old = frame.findViewWithTag(UNDO_TAG);
            if (old != null) frame.removeView(old);
            android.content.Context c = activity;
            float d = c.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout bar = new android.widget.LinearLayout(c);
            bar.setTag(UNDO_TAG);
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            bar.setPadding(Math.round(16 * d), 0, Math.round(4 * d), 0);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xF0202327);
            bg.setCornerRadius(8 * d);
            bar.setBackground(bg);
            bar.setElevation(6 * d);
            TextView label = new TextView(c);
            label.setText(AdvancedSearch.t(c, "piko_ni_hidden", "Post hidden"));
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            bar.addView(label, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView undo = new TextView(c);
            undo.setText(AdvancedSearch.t(c, "piko_ni_undo", "Undo"));
            undo.setTextColor(0xFF1D9BF0);
            undo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            undo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            undo.setGravity(android.view.Gravity.CENTER);
            undo.setMinHeight(Math.round(48 * d));
            undo.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
            undo.setClickable(true);
            undo.setFocusable(true);
            undo.setOnClickListener(v -> {
                frame.removeView(bar);
                undo(list, row, handle);
            });
            bar.addView(undo);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
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
            } else if (android.os.Build.VERSION.SDK_INT >= 23 && frame.getRootWindowInsets() != null) {
                bottom += frame.getRootWindowInsets().getSystemWindowInsetBottom();
            }
            lp.setMargins(side, 0, side, bottom);
            frame.addView(bar, lp);
            main().postDelayed(() -> {
                if (bar.getParent() == frame) frame.removeView(bar);
            }, 6000);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static android.app.Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Shows X's row again, forgets the author and presses X's own Undo (which also tells X). */
    private static void undo(ViewGroup list, View row, String handle) {
        try {
            forgetAuthor(handle);
            if (row.getParent() == list && row.getTag(TAG_DISMISSED) != null) {
                Object height = row.getTag(TAG_DISMISSED);
                row.setTag(TAG_DISMISSED, null);
                dismissedRows.remove(row);
                ViewGroup.LayoutParams lp = row.getLayoutParams();
                if (lp != null && height instanceof Integer) {
                    lp.height = (Integer) height;
                    row.setLayoutParams(lp);
                }
                row.setVisibility(View.VISIBLE);
            }
            // X's Undo works once the row is laid out again: press it then, and if X's row is still
            // there a moment later, tap it like a finger would.
            main().postDelayed(() -> pressXUndo(row, 0), 250);
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

    private static void pressXUndo(View row, int attempt) {
        try {
            if (!row.isAttachedToWindow() || row.getVisibility() != View.VISIBLE) return;
            View undo = xUndoOf(row);
            if (undo == null) return;
            if (attempt == 0) {
                undo.performClick();
            } else {
                long t = android.os.SystemClock.uptimeMillis();
                float x = undo.getWidth() / 2f, y = undo.getHeight() / 2f;
                android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_UP, x, y, 0);
                undo.dispatchTouchEvent(down);
                undo.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
            }
            if (attempt < 1) main().postDelayed(() -> {
                // Still X's feedback row (not a post again): try the tap.
                if (row.isAttachedToWindow() && !hasPostHeader(row) && xUndoOf(row) != null) pressXUndo(row, attempt + 1);
            }, 700);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
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
        if (row.getParent() != list || row.getTag(TAG_DISMISSED) != null || hasPostHeader(row)) return;
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return;
        row.setTag(TAG_DISMISSED, lp.height);
        String text = textOf(row);
        // Identify the row by the author's handle when X names it, else by all of its text.
        String signature = handle != null && text.contains(handle) ? handle : text;
        dismissedRows.put(row, signature);
        lp.height = 0;
        row.setLayoutParams(lp);
        row.setVisibility(View.GONE);
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
                String now = textOf(row);
                if (was != null && (was.equals(now) || now.contains(was))) continue;
                Object height = row.getTag(TAG_DISMISSED);
                row.setTag(TAG_DISMISSED, null);
                dismissedRows.remove(row);
                ViewGroup.LayoutParams lp = row.getLayoutParams();
                if (lp != null && height instanceof Integer) {
                    lp.height = (Integer) height;
                    row.setLayoutParams(lp);
                }
                row.setVisibility(View.VISIBLE);
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
            if (child.getTag(TAG_DISMISSED) != null || child.getTag(0x70696b07) != null) continue; // hidden, or a post row
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
    private static View rowOf(View view) {
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
