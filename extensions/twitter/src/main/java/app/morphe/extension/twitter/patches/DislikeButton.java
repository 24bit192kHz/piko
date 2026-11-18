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
                registry.put(id, new Bound(handler, item));
            }
            debug("bound " + id);
            refreshBars(id);
        } catch (Throwable t) {
            broken = true;
            debug("bind error " + t);
            PikoUtils.logger(t);
        }
    }

    static final boolean DEBUG = true;

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
            debug("no entry " + id);
            return false;
        }
        Object item = e.item.get();
        try {
            boolean ok = item != null && dontLikeAction(item) != null;
            debug("available " + id + " " + ok);
            return ok;
        } catch (Throwable t) {
            debug("available error " + t);
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
                app.morphe.extension.shared.Utils.showToastShort("X doesn't offer \"Not interested\" for this post");
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
            Object handle = row == null ? null : findTag(row, 0x70696b01);
            pendingSignature = handle instanceof String ? (String) handle : null;
            dismissMethod.invoke(handler, itemTweet, item, action);
            debug("dismissed " + id + " via " + dismissMethod.getName());
            if (list != null) pickNotRelevant(list, rowTop, 0);
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

    /** Polls the list (every 100 ms, up to 3 s) for the feedback row X puts where the post was. */
    private static void pickNotRelevant(ViewGroup list, int rowTop, int attempt) {
        main().postDelayed(() -> {
            View reason = null, reasonRow = null;
            int best = Integer.MAX_VALUE;
            for (int i = 0, n = list.getChildCount(); i < n; i++) {
                View child = list.getChildAt(i);
                if (dismissedRows.containsKey(child)) continue;
                View match = findText(child, NOT_RELEVANT);
                if (match == null) continue;
                int d = Math.abs(child.getTop() - rowTop);
                if (d < best) {
                    best = d;
                    reason = match;
                    reasonRow = child;
                }
            }
            if (reason != null) {
                View clickable = reason;
                while (clickable != reasonRow && !clickable.isClickable() && clickable.getParent() instanceof View) {
                    clickable = (View) clickable.getParent();
                }
                boolean clicked = clickable.performClick();
                debug("picked not relevant: " + clicked + " after " + (attempt * 100) + " ms");
                View row = reasonRow;
                // X swaps the reasons for its "Thanks" line in the same row; hide it once it settles.
                main().postDelayed(() -> hideRow(list, row), 400);
            } else if (attempt < 30) {
                pickNotRelevant(list, rowTop, attempt + 1);
            } else {
                // No reasons offered (or another language): hide the row that replaced the post.
                View closest = null;
                best = Integer.MAX_VALUE;
                for (int i = 0, n = list.getChildCount(); i < n; i++) {
                    View child = list.getChildAt(i);
                    int d = Math.abs(child.getTop() - rowTop);
                    if (d < best && findText(child, null) != null && !hasPostHeader(child)) {
                        best = d;
                        closest = child;
                    }
                }
                debug("no reasons offered, hiding the row: " + (closest != null));
                if (closest != null) hideRow(list, closest);
            }
        }, 100);
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

    private static String pendingSignature;

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

    private static void hideRow(ViewGroup list, View row) {
        if (row.getParent() != list || row.getTag(TAG_DISMISSED) != null) return;
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return;
        row.setTag(TAG_DISMISSED, lp.height);
        String text = textOf(row);
        // Identify the row by the author's handle when X names it, else by all of its text.
        String signature = pendingSignature != null && text.contains(pendingSignature) ? pendingSignature : text;
        pendingSignature = null;
        dismissedRows.put(row, signature);
        lp.height = 0;
        row.setLayoutParams(lp);
        row.setVisibility(View.GONE);
        debug("hid feedback row, signature: " + signature.replace('\n', '|'));
        watch(list);
    }

    /**
     * The list may reuse a hidden row for another item: after each layout, a hidden row whose
     * text changed is shown again. Only hidden rows are looked at.
     */
    private static void watch(ViewGroup list) {
        if (list.getTag(TAG_LIST_WATCH) != null) return;
        list.setTag(TAG_LIST_WATCH, Boolean.TRUE);
        list.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
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
