/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * "This Tweet has been deleted." in a post whose quoted post was deleted, as Twitter used to show it.
 *
 * <p>X now leaves a deleted quoted post out of its answer without a trace, apart from the quoted
 * post's id in the quoting post, and the old app then shows the post as if it quoted nothing. The
 * id is also all X sends for the post a quoted post quotes in turn (X never nests quotes), so only
 * posts that are not themselves inside a quote count.
 *
 * <p>Such posts are remembered (also across restarts: the timeline is shown from X's cache), X's
 * quote box is created and bound for them as for any quote, and the box shows the line instead of
 * a post.
 */
@SuppressWarnings("unused")
public final class DeletedQuotes {
    private static final String JSON_TWEET = "com.twitter.api.model.json.core.JsonApiTweet";
    private static final String JSON_MAPPER = "com.twitter.api.model.json.core.JsonApiTweet$$JsonObjectMapper";
    private static final int CAP = 500;
    private static final int TAG_DELETED = 0x70696b50;

    /** Quoting post id -> deleted quoted post id, newest last. */
    private static LinkedHashMap<Long, Long> marks;
    /** Posts deleted from this app (the delete X confirmed), newest last. */
    private static LinkedHashSet<Long> gone;
    /** Posts a post page showed since the app started: one X then answers with nothing for is gone, not withheld. */
    private static final Set<Long> held = Collections.synchronizedSet(new LinkedHashSet<>());
    private static Field restIdField, idField, legacyField, quotedIdField, tweetField, quotedTweetField, showQuoteField;
    private static Method tweetIdMethod;
    private static boolean broken;

    private DeletedQuotes() {
    }

    static boolean enabled() {
        try {
            return SettingsStatus.deletedQuotes && !broken && Boolean.TRUE.equals(Utils.getBooleanPref(Settings.DELETED_QUOTES));
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences store() {
        return app.morphe.extension.shared.Utils.getContext().getSharedPreferences("piko_deleted_quotes", Context.MODE_PRIVATE);
    }

    private static synchronized LinkedHashMap<Long, Long> marks() {
        if (marks == null) {
            marks = new LinkedHashMap<>();
            try {
                for (String pair : store().getString("marks", "").split(",")) {
                    int colon = pair.indexOf(':');
                    if (colon > 0) marks.put(Long.parseLong(pair.substring(0, colon)), Long.parseLong(pair.substring(colon + 1)));
                }
            } catch (Throwable ignored) {
            }
        }
        return marks;
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Long> e : marks.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        try {
            store().edit().putString("marks", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static synchronized LinkedHashSet<Long> gone() {
        if (gone == null) {
            gone = new LinkedHashSet<>();
            try {
                for (String id : store().getString("gone", "").split(",")) {
                    if (!id.isEmpty()) gone.add(Long.parseLong(id));
                }
            } catch (Throwable ignored) {
            }
        }
        return gone;
    }

    private static synchronized void addGone(long id) {
        LinkedHashSet<Long> g = gone();
        if (id <= 0 || !g.add(id)) return;
        while (g.size() > CAP) g.remove(g.iterator().next());
        StringBuilder sb = new StringBuilder();
        for (Long l : g) {
            if (sb.length() > 0) sb.append(',');
            sb.append(l);
        }
        try {
            store().edit().putString("gone", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    static synchronized boolean isGone(long id) {
        return gone().contains(id);
    }

    /** Injection point: X deleted these posts (one, or every version of an edited one) after its server did. */
    public static void deletedHere(List<Long> ids) {
        try {
            if (ids != null) for (Long id : ids) if (id != null) addGone(id);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static synchronized void mark(long tweet, long quoted) {
        LinkedHashMap<Long, Long> m = marks();
        Long old = m.remove(tweet);
        m.put(tweet, quoted);
        while (m.size() > CAP) m.remove(m.keySet().iterator().next());
        if (old == null || old != quoted) save();
    }

    private static synchronized void unmark(long tweet) {
        if (marks().remove(tweet) != null) save();
    }

    static synchronized boolean marked(long tweet) {
        return marks().containsKey(tweet);
    }

    // region parsing

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

    /**
     * Injection point: the quoted post of a post X is reading ({@code JsonApiTweet.v()}): its result
     * (null when X sent none). A top-level post with a quoted id but no quoted post is remembered.
     */
    public static Object quoted(Object json, Object result) {
        if (json == null || !enabled()) return result;
        try {
            if (restIdField == null) {
                Class<?> c = json.getClass();
                restIdField = field(c, "N");
                idField = field(c, "U");
                legacyField = field(c, "O");
                quotedIdField = field(c, "v");
            }
            long tweet = restIdField.getLong(json);
            if (tweet <= 0) tweet = idField.getLong(json);
            Object legacy = legacyField.get(json);
            long quoted = legacy != null ? quotedIdField.getLong(legacy) : quotedIdField.getLong(json);
            if (tweet <= 0) return result;
            if (quoted <= 0 || result != null) {
                if (marked(tweet)) unmark(tweet);
                return result;
            }
            if (insideQuote()) return result;
            mark(tweet, quoted);
            if (DislikeButton.DEBUG) DislikeButton.debug("deleted quote: " + tweet + " quoted " + quoted);
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
        return result;
    }

    /**
     * Whether the post being read is itself the quoted post of another one: X reads a quoted post
     * while reading the post that quotes it, so its reader appears twice on the stack. Only looked
     * at for the few posts whose quote is missing.
     */
    private static boolean insideQuote() {
        int readers = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (JSON_MAPPER.equals(e.getClassName()) && "parse".equals(e.getMethodName()) && ++readers > 1) return true;
        }
        return false;
    }

    // endregion

    // region showing

    private static long tweetId(Object tweet) throws Exception {
        if (tweetIdMethod == null) tweetIdMethod = tweet.getClass().getMethod("getId");
        Object v = tweetIdMethod.invoke(tweet);
        return v instanceof Long ? (Long) v : 0;
    }

    /** The post of a post view's state ({@code z.a}). */
    private static Object tweetOf(Object state) throws Exception {
        if (tweetField == null) {
            tweetField = field(state.getClass(), "a");
            showQuoteField = field(state.getClass(), "c");
        }
        return tweetField.get(state);
    }

    private static Object quotedTweetOf(Object tweet) throws Exception {
        if (quotedTweetField == null) quotedTweetField = field(tweet.getClass(), "c");
        return quotedTweetField.get(tweet);
    }

    /** Injection point: whether a post view shows a quote ({@code z.f()}): also for a deleted one. */
    public static boolean hasQuote(boolean has, Object state) {
        if (has || state == null || !enabled()) return has;
        try {
            Object tweet = tweetOf(state);
            if (tweet == null || quotedTweetOf(tweet) != null || !showQuoteField.getBoolean(state)) return false;
            return marked(tweetId(tweet));
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
            return false;
        }
    }

    /**
     * Whether the quote of {@code tweet} is a deleted post: X's server left it out (X may still fill it in from
     * its cache, as it does for posts you saw before), or it was deleted from this app.
     */
    private static boolean quoteGone(Object tweet) throws Exception {
        Object quoted = quotedTweetOf(tweet);
        Long mark;
        synchronized (DeletedQuotes.class) {
            mark = marks().get(tweetId(tweet));
        }
        if (quoted == null) return mark != null;
        long q = tweetId(quoted);
        return (mark != null && mark == q) || isGone(q);
    }

    /** Injection point: the post X's quote box is about to show for {@code tweet}: none for a deleted one. */
    public static Object quoteToShow(Object tweet, Object quoted) {
        if (tweet == null || quoted == null || !enabled()) return quoted;
        try {
            return quoteGone(tweet) ? null : quoted;
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
            return quoted;
        }
    }

    /** Injection point: X bound its quote box for {@code tweet} and made it visible. */
    public static void bound(View quoteView, Object tweet) {
        if (quoteView == null) return;
        try {
            boolean deleted = tweet != null && enabled() && quoteGone(tweet);
            boolean was = quoteView.getTag(TAG_DELETED) != null;
            quoteView.setTag(TAG_DELETED, deleted ? Boolean.TRUE : null);
            if (deleted) quoteView.setContentDescription(line(quoteView.getContext()));
            if (deleted != was) quoteView.requestLayout();
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
    }

    static String line(Context c) {
        return AdvancedSearch.t(c, "piko_deleted_quote", "This Tweet has been deleted.").toString();
    }

    /**
     * Injection point: the line X's quote box shows when it has no post to show ("This quoted Tweet
     * is unavailable."): for a deleted quote, the old Twitter line instead. X lays it out and draws
     * it itself, centred in the box.
     */
    public static String interstitial(View quoteView, String text) {
        try {
            if (quoteView != null && quoteView.getTag(TAG_DELETED) != null) return line(quoteView.getContext());
        } catch (Throwable ignored) {
        }
        return text;
    }

    // endregion

    // region post page

    private static Field pageIdField, httpCodeField;

    /** The posts pages showed last, to show a deleted one as it was (X drops it from its cache). */
    private static final Map<Long, Object> heldPosts = Collections.synchronizedMap(new LinkedHashMap<Long, Object>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
            return size() > 30;
        }
    });

    /** Injection point: a post page shows {@code tweet} (X has it, from its server or its cache). */
    public static void held(Object page, Object tweet) {
        try {
            if (tweet == null) return;
            long id = tweetId(tweet);
            held.add(id);
            heldPosts.put(id, tweet);
            Object activity = page != null ? valueOfType(page, "android.app.Activity") : null;
            if (activity instanceof android.app.Activity) DeletedPostCard.showing((android.app.Activity) activity, id);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Injection point: the message a post page shows when X could not load its post ({@code state}, null for X's
     * "Cannot retrieve Tweets"). X now answers a deleted post with nothing, exactly as it answers a post withheld
     * in your country, so the page says the post was deleted only when that is known: it was deleted from this
     * app, or the page showed it before and X's server now answers (successfully) without it.
     */
    public static Object pageState(Object state, Object handler, Object response) {
        if (state != null || handler == null || !enabled()) return state;
        try {
            if (pageIdField == null) pageIdField = field(handler.getClass(), "a");
            long id = pageIdField.getLong(handler);
            boolean deleted = isGone(id);
            if (!deleted && held.contains(id) && response != null) {
                if (httpCodeField == null) httpCodeField = field(response.getClass(), "c");
                deleted = httpCodeField.getInt(response) == 200;
                if (deleted) addGone(id);
            }
            if (!deleted) return null;
            DeletedPostCard.mark(pageActivity(handler));
            Object post = heldPosts.get(id);
            if (!DeletedPostCard.show(pageActivity(handler), post, id)) showOverList(handler);
            Context c = app.morphe.extension.shared.Utils.getContext();
            int res = c.getResources().getIdentifier("piko_deleted_quote", "string", c.getPackageName());
            if (res == 0) res = c.getResources().getIdentifier("deleted_tweet_title", "string", c.getPackageName());
            ClassLoader cl = handler.getClass().getClassLoader();
            Class<?> builderClass = Class.forName("com.twitter.ui.list.e$a", true, cl);
            Object builder = builderClass.getConstructor().newInstance();
            Object text = Class.forName("com.twitter.ui.text.x", true, cl).getConstructor(int.class).newInstance(res);
            field(builderClass, "b").set(builder, text);
            field(builderClass, "f").setInt(builder, 144); // X's own code for a deleted post
            return builderClass.getMethod("j").invoke(builder);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    private static Object valueOfType(Object owner, String typeName) throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName) || (typeName.equals("android.app.Activity") && android.app.Activity.class.isAssignableFrom(f.getType()))) {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null) return v;
                }
            }
        }
        return null;
    }

    private static android.app.Activity pageActivity(Object handler) {
        try {
            Object page = valueOfType(handler, "com.twitter.tweetdetail.a0");
            Object activity = page != null ? valueOfType(page, "android.app.Activity") : null;
            return activity instanceof android.app.Activity ? (android.app.Activity) activity : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A page that showed its post still holds X's (now empty) list of it, which hides X's message and finds
     * its empty state first: the line goes there, and the list is hidden.
     */
    private static void showOverList(Object handler) {
        try {
            Object page = valueOfType(handler, "com.twitter.tweetdetail.a0");
            Object activity = page != null ? valueOfType(page, "android.app.Activity") : null;
            if (!(activity instanceof android.app.Activity)) return;
            android.app.Activity a = (android.app.Activity) activity;
            View container = a.findViewById(id(a, "fragment_container"));
            if (!(container instanceof android.view.ViewGroup) || ((android.view.ViewGroup) container).getChildCount() == 0) return;
            container.post(() -> {
                try {
                    View list = container.findViewById(id(a, "pinned_header_container"));
                    View empty = container.findViewById(id(a, "empty_state"));
                    View desc = container.findViewById(id(a, "empty_desc"));
                    if (empty == null || !(desc instanceof android.widget.TextView)) return;
                    if (list != null) list.setVisibility(View.GONE);
                    for (String name : new String[]{"empty_container", "list_empty_text"}) {
                        View v = container.findViewById(id(a, name));
                        if (v != null) v.setVisibility(View.VISIBLE);
                    }
                    View progress = container.findViewById(id(a, "list_progress"));
                    if (progress != null) progress.setVisibility(View.GONE);
                    container.setTag(TAG_DELETED, Boolean.TRUE);
                    ((android.widget.TextView) desc).setText(line(a));
                    desc.setVisibility(View.VISIBLE);
                    empty.setVisibility(View.VISIBLE);
                    // X's own message box, laid over the list (it has no text here): hidden, so the line shows.
                    android.view.ViewParent parent = container.getParent();
                    if (parent instanceof android.view.ViewGroup) {
                        android.view.ViewGroup g = (android.view.ViewGroup) parent;
                        for (int i = 0; i < g.getChildCount(); i++) {
                            View v = g.getChildAt(i);
                            if (v != container && v.getId() == id(a, "empty_state")) v.setVisibility(View.GONE);
                        }
                    }
                } catch (Throwable t) {
                    PikoUtils.logger(t);
                }
            });
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /**
     * Injection point: the post page's list could not load (X then offers "Retry"): not said on a page that
     * shows its post was deleted, where there is nothing to load.
     */
    public static boolean quietError(Object list) {
        try {
            Object activity = list != null ? valueOfType(list, "android.app.Activity") : null;
            if (!(activity instanceof android.app.Activity)) return false;
            android.app.Activity a = (android.app.Activity) activity;
            if (DeletedPostCard.shown(a)) return true;
            View container = a.findViewById(id(a, "fragment_container"));
            return container != null && container.getTag(TAG_DELETED) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int id(Context c, String name) {
        return c.getResources().getIdentifier(name, "id", c.getPackageName());
    }

    // endregion
}
