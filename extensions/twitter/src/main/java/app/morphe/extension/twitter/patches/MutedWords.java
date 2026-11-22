/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * "Mute" in the text selection menu: select words in a post, tap Mute, and
 * <ul>
 * <li>the words go on Piko's own muted words list, which hides posts containing them at once:
 *     rows already on screen (also the timeline behind the post), rows restored from X's timeline
 *     cache, and posts in every timeline X loads from then on;</li>
 * <li>the post itself is hidden by its id (a selection may span links or emoji the post's text
 *     doesn't match literally);</li>
 * <li>X's own muted words get them too (X's "create muted word" request, like its composer:
 *     home timeline and notifications, from anyone, forever), so X stops sending such posts;</li>
 * <li>the post's screen closes, back to the timeline it was opened from, which shows an Undo bar.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class MutedWords {
    static final String REASON = "mute";
    private static final int MENU_ID = 0x70696b30;
    private static final int MENU_ORDER = 1; // after the assist item (0), before Cut/Copy (4/5)
    private static final int IDS_CAP = 500;

    private static Handler main;
    private static boolean installed;

    private MutedWords() {
    }

    static final boolean DEBUG = false;

    static void debug(String msg) {
        if (DEBUG) android.util.Log.d("PikoMute", msg);
    }

    // region placeholders (the patch fills in the obfuscated names)

    private static String createRequestClass() {
        return "com.twitter.api.legacy.request.safety.i";
    }

    private static String destroyRequestClass() {
        return "com.twitter.api.legacy.request.safety.j";
    }

    private static String keywordModelClass() {
        return "com.twitter.model.safety.f";
    }

    private static String surfaceEnumClass() {
        return "com.twitter.model.safety.e";
    }

    private static String requestResultFieldName() {
        return "G";
    }

    private static String requestErrorFieldName() {
        return "H";
    }

    private static String dispatcherClass() {
        return "com.twitter.async.http.g";
    }

    // endregion

    // region settings

    private static boolean bool(app.morphe.extension.crimera.settings.BooleanSetting setting) {
        try {
            return Boolean.TRUE.equals(Utils.getBooleanPref(setting));
        } catch (Throwable t) {
            return setting.defaultValue;
        }
    }

    public static boolean menuEnabled() {
        return SettingsStatus.muteSelection && bool(Settings.MUTE_SELECTION_MENU);
    }

    private static String wordsRaw;
    private static String[] words = new String[0];
    private static boolean[][] edges = new boolean[0][];

    /** Piko's muted words, normalized (see {@link #norm}). */
    private static synchronized String[] words() {
        String raw;
        try {
            raw = Utils.getStringPref(Settings.MUTE_MUTED_WORDS);
        } catch (Throwable t) {
            raw = "";
        }
        if (raw == null) raw = "";
        if (!raw.equals(wordsRaw)) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String w : raw.split("[,،\n]")) {
                String n = norm(w);
                if (!n.isEmpty()) set.add(n);
            }
            words = set.toArray(new String[0]);
            edges = new boolean[words.length][];
            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                // Latin-like words match whole words only; Arabic ones also inside a word, since
                // Arabic writes "and", "the", "to" as prefixes (والحوثية, بالحوثية).
                edges[i] = new boolean[]{needsBoundary(w.codePointAt(0)), needsBoundary(w.codePointBefore(w.length()))};
            }
            wordsRaw = raw;
        }
        return words;
    }

    private static boolean needsBoundary(int cp) {
        return Character.isLetterOrDigit(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.ARABIC;
    }

    public static boolean active() {
        return words().length > 0 || !ids().isEmpty();
    }

    /**
     * Lower case; Arabic without diacritics and tatweel, with one alef; no direction marks or
     * joiners; runs of spaces as one.
     */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if ((c >= 'ً' && c <= 'ٟ') || c == 'ٰ' || c == 'ـ'
                    || (c >= '​' && c <= '‏') || c == '؜' || c == '⁦' || c == '⁧'
                    || c == '⁨' || c == '⁩' || c == '‪' || c == '‫' || c == '‬'
                    || c == '‭' || c == '‮' || c == '﻿') continue;
            if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') c = 'ا';
            if (Character.isWhitespace(c)) {
                if (b.length() > 0) space = true;
                continue;
            }
            if (space) {
                b.append(' ');
                space = false;
            }
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** The muted word the text contains, or null. */
    static String matchIn(String text) {
        String[] ws = words();
        if (ws.length == 0 || text == null || text.isEmpty()) return null;
        String t = norm(text);
        boolean[][] e;
        synchronized (MutedWords.class) {
            e = edges;
            if (e.length != ws.length) return null;
        }
        for (int i = 0; i < ws.length; i++) {
            String w = ws[i];
            for (int at = t.indexOf(w); at >= 0; at = t.indexOf(w, at + 1)) {
                int end = at + w.length();
                if (e[i][0] && at > 0 && Character.isLetterOrDigit(t.codePointBefore(at))) continue;
                if (e[i][1] && end < t.length() && Character.isLetterOrDigit(t.codePointAt(end))) continue;
                return w;
            }
        }
        return null;
    }

    private static SharedPreferences store() {
        return app.morphe.extension.shared.Utils.getContext().getSharedPreferences("piko_muted_words", Context.MODE_PRIVATE);
    }

    private static LinkedHashSet<Long> ids;

    /** Ids of posts muted with the menu (newest last), hidden whatever their text. */
    private static synchronized LinkedHashSet<Long> ids() {
        if (ids == null) {
            ids = new LinkedHashSet<>();
            try {
                for (String s : store().getString("ids", "").split(",")) {
                    if (!s.isEmpty()) ids.add(Long.parseLong(s));
                }
            } catch (Throwable ignored) {
            }
        }
        return ids;
    }

    private static synchronized void saveIds() {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        try {
            store().edit().putString("ids", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void addId(long id) {
        LinkedHashSet<Long> set = ids();
        set.remove(id);
        set.add(id);
        while (set.size() > IDS_CAP) set.remove(set.iterator().next());
        saveIds();
    }

    private static synchronized void removeId(long id) {
        if (ids().remove(id)) saveIds();
    }

    static synchronized boolean isIdMuted(Long id) {
        return id != null && ids().contains(id);
    }

    private static void setWords(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String w : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(w);
        }
        Utils.setStringPref(Settings.MUTE_MUTED_WORDS.key, sb.toString());
    }

    private static List<String> wordList() {
        List<String> list = new ArrayList<>();
        String raw;
        try {
            raw = Utils.getStringPref(Settings.MUTE_MUTED_WORDS);
        } catch (Throwable t) {
            raw = "";
        }
        if (raw != null) for (String w : raw.split("[,،\n]")) if (!w.trim().isEmpty()) list.add(w.trim());
        return list;
    }

    /** Adds a word; returns false when it was already there. */
    private static boolean addWord(String word) {
        List<String> list = wordList();
        String n = norm(word);
        for (String w : list) if (norm(w).equals(n)) return false;
        list.add(word);
        setWords(list);
        return true;
    }

    private static void removeWord(String word) {
        List<String> list = wordList();
        String n = norm(word);
        list.removeIf(w -> norm(w).equals(n));
        setWords(list);
    }

    // endregion

    // region filtering

    /** Posts whose own page is open (their id from the page's intent): never hidden, there or anywhere. */
    private static final Map<Activity, Long> openPosts = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static boolean isOpen(Long id) {
        if (id == null) return false;
        synchronized (openPosts) {
            return openPosts.containsValue(id);
        }
    }

    private static final java.util.regex.Pattern STATUS = java.util.regex.Pattern.compile("/status(?:es)?/(\\d+)");

    /** The post a post page shows, from its intent: X's argument, or the link it was opened with. */
    private static Long postOfIntent(android.content.Intent intent) {
        if (intent == null) return null;
        try {
            long id = intent.getLongExtra("timeline_arg_tweet_id", -1L);
            if (id > 0) return id;
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object v = extras.get(key);
                    if (v instanceof Bundle) {
                        long nested = ((Bundle) v).getLong("timeline_arg_tweet_id", -1L);
                        if (nested > 0) return nested;
                    }
                }
            }
            if (intent.getData() != null) {
                java.util.regex.Matcher m = STATUS.matcher(String.valueOf(intent.getData()));
                if (m.find()) return Long.parseLong(m.group(1));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Parse time (SponsoredPosts): the hide reason of a post with this text and id, or null. */
    static String reasonFor(String text, Long id) {
        if (isOpen(id)) return null;
        if (isIdMuted(id)) return "Muted post";
        String w = matchIn(text);
        return w == null ? null : "Muted word: " + w;
    }

    /**
     * Injection point (via the action bar's bind): a row shows {@code tweet}. Hidden at once when
     * it is muted, shown again when a recycled row now shows another post. A post's own page
     * (the focal post) is never hidden.
     */
    static void onBar(ViewGroup bar, Object tweet, boolean focal) {
        boolean muted = !focal && tweet != null && active() && isMuted(tweet);
        bar.setTag(TAG_MUTED, muted ? Boolean.TRUE : null);
        if (bar.getTag(TAG_ATTACH) == null) {
            if (!muted) return; // nothing to do until a muted post shows up here
            bar.setTag(TAG_ATTACH, Boolean.TRUE);
            bar.addOnAttachStateChangeListener(ATTACH);
        }
        // A row being bound is not in the list yet: it is checked when it attaches (before it is drawn).
        if (bar.isAttachedToWindow()) apply(bar);
    }

    private static final int TAG_MUTED = 0x70696b31, TAG_ATTACH = 0x70696b32;

    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View bar) {
            apply(bar);
        }

        @Override
        public void onViewDetachedFromWindow(View bar) {
        }
    };

    private static void apply(View bar) {
        View row = DislikeButton.rowOf(bar);
        if (row == null) return;
        if (bar.getTag(TAG_MUTED) != null) RowHide.hide(row, REASON, false);
        else RowHide.show(row, REASON);
    }

    private static boolean isMuted(Object tweet) {
        try {
            Tweet t = new Tweet(tweet);
            Long id = t.getTweetId();
            if (isOpen(id)) return false;
            if (isIdMuted(id)) return true;
            if (words().length == 0) return false;
            String text = t.getLongText();
            if (text == null) text = t.getShortText();
            return matchIn(text) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Rows on screen (in every open screen) are checked again: after a mute, or its undo. */
    private static void recheckOnScreen(View skipRoot) {
        for (Map.Entry<View, Long> e : DislikeButton.barsSnapshot()) {
            View bar = e.getKey();
            if (!(bar instanceof ViewGroup) || !bar.isAttachedToWindow()) continue;
            if (skipRoot != null && bar.getRootView() == skipRoot) continue;
            ViewGroup g = (ViewGroup) bar;
            onBar(g, InlineDownloadButton.tweetOf(g), InlineDownloadButton.focal(g));
        }
    }

    // endregion

    // region the selection menu

    /** Injection point: Application.onCreate. Puts "Mute" into the text selection menu of X's screens. */
    public static void init(Application app) {
        if (installed || app == null) return;
        installed = true;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity a) {
                    wrap(a);
                    showPendingUndo(a);
                }

                public void onActivityCreated(Activity a, Bundle b) {
                    if (a.getClass().getName().endsWith("TweetDetailActivity")) {
                        Long id = postOfIntent(a.getIntent());
                        if (id != null) openPosts.put(a, id);
                    }
                }

                public void onActivityStarted(Activity a) {
                }

                public void onActivityPaused(Activity a) {
                }

                public void onActivityStopped(Activity a) {
                }

                public void onActivitySaveInstanceState(Activity a, Bundle b) {
                }

                public void onActivityDestroyed(Activity a) {
                    openPosts.remove(a);
                }
            });
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static final class WindowHook implements InvocationHandler {
        final Window.Callback target;
        final WeakReference<Activity> activity;

        WindowHook(Window.Callback target, Activity activity) {
            this.target = target;
            this.activity = new WeakReference<>(activity);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
            if (args != null && args.length == 1 && args[0] instanceof ActionMode && "onActionModeStarted".equals(method.getName())) {
                try {
                    onModeStarted(activity.get(), (ActionMode) args[0]);
                } catch (Throwable t) {
                    PikoUtils.logger(t);
                }
            }
            return result;
        }
    }

    private static void wrap(Activity a) {
        try {
            Window w = a.getWindow();
            if (w == null) return;
            Window.Callback cb = w.getCallback();
            if (cb == null) return;
            if (Proxy.isProxyClass(cb.getClass()) && Proxy.getInvocationHandler(cb) instanceof WindowHook) return;
            Object proxy = Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class}, new WindowHook(cb, a));
            w.setCallback((Window.Callback) proxy);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static void onModeStarted(Activity activity, ActionMode mode) {
        if (activity == null || mode.getType() != ActionMode.TYPE_FLOATING || !menuEnabled()) return;
        TextView view = selectedTextView(activity);
        if (view == null) return;
        Menu menu = mode.getMenu();
        if (menu.findItem(MENU_ID) != null) return;
        WeakReference<TextView> ref = new WeakReference<>(view);
        MenuItem item = menu.add(Menu.NONE, MENU_ID, MENU_ORDER, AdvancedSearch.t(activity, "piko_mute_selection", "Mute"));
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(i -> {
            try {
                TextView tv = ref.get();
                String text = tv == null ? null : selection(tv);
                mode.finish();
                if (text != null) mute(activity, tv, text);
            } catch (Throwable t) {
                debug("mute error " + android.util.Log.getStackTraceString(t));
                PikoUtils.logger(t);
            }
            return true;
        });
        mode.invalidate();
    }

    /** The read-only text view whose text is selected now (never a text field). */
    private static TextView selectedTextView(Activity activity) {
        View focus = activity.getCurrentFocus();
        if (focus instanceof TextView && selectable((TextView) focus)) return (TextView) focus;
        Window w = activity.getWindow();
        return w == null ? null : findSelected(w.getDecorView());
    }

    private static boolean selectable(TextView tv) {
        return tv.hasSelection() && !tv.onCheckIsTextEditor() && tv.isShown();
    }

    private static TextView findSelected(View v) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView) return selectable((TextView) v) ? (TextView) v : null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                TextView found = findSelected(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String selection(TextView tv) {
        CharSequence text = tv.getText();
        int a = Math.min(tv.getSelectionStart(), tv.getSelectionEnd());
        int b = Math.max(tv.getSelectionStart(), tv.getSelectionEnd());
        if (text == null || a < 0 || b <= a || b > text.length()) return null;
        return text.subSequence(a, b).toString();
    }

    /** The selection as a muted word: one line, no list separators, no punctuation around it. */
    static String clean(String s) {
        String t = s.replaceAll("[,،\\s]+", " ").trim();
        int a = 0, b = t.length();
        while (a < b && !keep(t.codePointAt(a))) a += Character.charCount(t.codePointAt(a));
        while (b > a && !keep(t.codePointBefore(b))) b -= Character.charCount(t.codePointBefore(b));
        t = t.substring(a, b);
        return t.length() > 100 ? t.substring(0, 100).trim() : t;
    }

    private static boolean keep(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '#' || cp == '@' || cp == '$' || Character.getType(cp) == Character.OTHER_SYMBOL;
    }

    /** The id of the post shown in the list row that holds {@code view}, or null. */
    private static Long postIdOf(View view) {
        View row = DislikeButton.rowOf(view);
        if (row == null) return null;
        for (Map.Entry<View, Long> e : DislikeButton.barsSnapshot()) {
            View bar = e.getKey();
            if (bar != null && bar.isAttachedToWindow() && DislikeButton.rowOf(bar) == row) return e.getValue();
        }
        return null;
    }

    private static void mute(Activity activity, TextView view, String selected) {
        String word = clean(selected);
        if (word.isEmpty()) return;
        boolean added = addWord(word);
        Long id = view == null ? null : postIdOf(view);
        debug("muted '" + word + "' added " + added + " post " + id + " words " + wordList());
        if (id != null) addId(id);
        boolean leave = id != null && !activity.isTaskRoot();
        // This page closes: its post is no longer exempt.
        if (leave) openPosts.remove(activity);
        // The timeline behind this screen is settled before it shows again; the post's own screen,
        // which closes now, is left as it is.
        recheckOnScreen(leave ? activity.getWindow().getDecorView() : null);
        Object[] xRequest = added ? xMute(word) : null;
        Runnable undo = () -> {
            if (added) removeWord(word);
            if (id != null) removeId(id);
            recheckOnScreen(null);
            if (xRequest != null) xUnmute(xRequest, word);
        };
        CharSequence label = AdvancedSearch.t(activity, "piko_mute_selection_done", "Muted “%s”").replace("%s", word);
        if (leave) {
            pendingUndo = new PendingUndo(label, undo);
            activity.finish();
        } else {
            UndoBar.show(activity, label, undo);
        }
    }

    private static final class PendingUndo {
        final CharSequence label;
        final Runnable undo;
        final long at = android.os.SystemClock.uptimeMillis();

        PendingUndo(CharSequence label, Runnable undo) {
            this.label = label;
            this.undo = undo;
        }
    }

    private static PendingUndo pendingUndo;

    private static void showPendingUndo(Activity a) {
        PendingUndo p = pendingUndo;
        if (p == null) return;
        pendingUndo = null;
        if (android.os.SystemClock.uptimeMillis() - p.at > 3000) return;
        if (main == null) main = new Handler(Looper.getMainLooper());
        // After the screen's first layout, so the bar finds X's bottom tabs.
        main.post(() -> UndoBar.show(a, p.label, p.undo));
    }

    // endregion

    // region X's muted words

    private static Object dispatcher() throws Exception {
        return Class.forName(dispatcherClass()).getMethod("d").invoke(null);
    }

    /** Sends {@code req} with X's request controller (asynchronously, like X does). */
    private static void send(Object req) throws Exception {
        Object controller = dispatcher();
        for (Class<?> c = controller.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && m.getReturnType() == p[0] && p[0].isAssignableFrom(req.getClass())
                        && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    m.invoke(controller, req);
                    return;
                }
            }
        }
        throw new NoSuchMethodException("request controller execute");
    }

    /** Tells when X refused the request (its error field is set once X answered). */
    private static void reportFailure(Object req) {
        new Thread(() -> {
            try {
                Field error = null, result = null;
                for (Class<?> c = req.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getName().equals(requestErrorFieldName()) && error == null) error = f;
                        if (f.getName().equals(requestResultFieldName()) && result == null) result = f;
                    }
                }
                if (error == null || result == null) return;
                error.setAccessible(true);
                result.setAccessible(true);
                for (int i = 0; i < 120; i++) {
                    Thread.sleep(250);
                    if (result.get(req) != null) return;
                    if (error.get(req) != null) {
                        debug("X refused the muted word");
                        new Handler(Looper.getMainLooper()).post(() -> app.morphe.extension.shared.Utils.showToastShort(
                                AdvancedSearch.t(app.morphe.extension.shared.Utils.getContext(), "piko_mute_selection_x_failed",
                                        "Muted in Piko only: X's muted words couldn't be updated")));
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "piko-mute-check").start();
    }

    private static Object currentUser() throws Exception {
        return Class.forName("com.twitter.util.user.UserIdentifier").getMethod("getCurrent").invoke(null);
    }

    /** X's "create muted word" request for {@code word}; returns it (to read X's id for Undo), or null. */
    private static Object[] xMute(String word) {
        try {
            Class<?> uid = Class.forName("com.twitter.util.user.UserIdentifier");
            Class<?> model = Class.forName(keywordModelClass());
            // As X's own composer with its defaults: home timeline (with replies) and notifications.
            Set<Object> surfaces = new LinkedHashSet<>();
            Object[] all = Class.forName(surfaceEnumClass()).getEnumConstants();
            for (String name : new String[]{"home_timeline", "tweet_replies", "notifications"}) {
                for (Object s : all) if (name.equals(String.valueOf(s))) surfaces.add(s);
            }
            if (surfaces.size() != 3) throw new IllegalStateException("mute surfaces " + surfaces);
            Constructor<?> mc = model.getConstructor(long.class, String.class, String.class, long.class, long.class, Set.class, Set.class);
            Object keyword = mc.newInstance(0L, "", word, 0L, 0L, surfaces, null);
            Constructor<?> rc = Class.forName(createRequestClass()).getConstructor(uid, String.class, model, long.class);
            Object req = rc.newInstance(currentUser(), "Couldn't add the muted word", keyword, -1L);   // -1: forever
            send(req);
            reportFailure(req);
            return new Object[]{req};
        } catch (Throwable t) {
            PikoUtils.logger(t);
            app.morphe.extension.shared.Utils.showToastShort(AdvancedSearch.t(app.morphe.extension.shared.Utils.getContext(),
                    "piko_mute_selection_x_failed", "Muted in Piko only: X's muted words couldn't be updated"));
            return null;
        }
    }

    /** Undo: X's "delete muted word" with the id X gave the word (once the create request is done). */
    private static void xUnmute(Object[] holder, String word) {
        new Thread(() -> {
            try {
                Object req = holder[0];
                Field result = null;
                for (Class<?> c = req.getClass(); c != null && result == null; c = c.getSuperclass()) {
                    try {
                        result = c.getDeclaredField(requestResultFieldName());
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (result == null) return;
                result.setAccessible(true);
                Object list = null;
                for (int i = 0; i < 100 && list == null; i++) {
                    Object g = result.get(req);
                    if (g != null) {
                        for (Field f : g.getClass().getDeclaredFields()) {
                            if (List.class.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                list = f.get(g);
                            }
                        }
                    }
                    if (list == null) Thread.sleep(100);
                }
                if (!(list instanceof List)) return;
                // X's entry for this word: its String fields are the id (digits) and the keyword.
                Class<?> model = Class.forName(keywordModelClass());
                List<String> ids = new ArrayList<>();
                for (Object kw : (List<?>) list) {
                    if (kw == null || !model.isInstance(kw)) continue;
                    String id = null;
                    boolean thisWord = false;
                    for (Field f : model.getDeclaredFields()) {
                        if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object v = f.get(kw);
                        if (word.equals(v)) thisWord = true;
                        else if (v instanceof String && ((String) v).matches("\\d+")) id = (String) v;
                    }
                    if (thisWord && id != null) ids.add(id);
                }
                debug("undo: X ids " + ids);
                if (ids.isEmpty()) return;
                Constructor<?> dc = Class.forName(destroyRequestClass()).getConstructor(
                        Class.forName("com.twitter.util.user.UserIdentifier"), String.class, String[].class);
                Object del = dc.newInstance(currentUser(), "Couldn't delete the muted word", ids.toArray(new String[0]));
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        send(del);
                    } catch (Throwable t) {
                        PikoUtils.logger(t);
                    }
                });
            } catch (Throwable t) {
                PikoUtils.logger(t);
            }
        }, "piko-unmute").start();
    }

    // endregion
}
