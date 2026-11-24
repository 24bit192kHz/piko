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
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Injection point: X bound its quote box for {@code tweet} and made it visible. */
    public static void bound(View quoteView, Object tweet) {
        if (quoteView == null) return;
        try {
            boolean deleted = tweet != null && quotedTweetOf(tweet) == null && enabled() && marked(tweetId(tweet));
            boolean was = quoteView.getTag(TAG_DELETED) != null;
            quoteView.setTag(TAG_DELETED, deleted ? Boolean.TRUE : null);
            if (deleted) quoteView.setContentDescription(line(quoteView.getContext()));
            if (deleted != was) quoteView.requestLayout();
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
    }

    private static String line(Context c) {
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
}
