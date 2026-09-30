/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.view.Menu;
import android.view.MenuItem;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.entity.TwitterUser;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Hides posts that are ads without being "promoted" timeline entries (paid partnerships and
 * posts that label themselves as ads), and keeps an allow-list of accounts whose ads stay visible.
 *
 * <p>Timeline entries are parsed on a single thread, and every post an entry carries is parsed
 * inside it. Parse depth tells the entry's own post (depth 0) apart from the posts nested in it
 * (depth 1: a quoted post, or the original of a repost).
 */
@SuppressWarnings("unused")
public class SponsoredPosts {
    private static final boolean hidePaidPartnership, hideAdLabel;

    // A line that is nothing but an ad label, e.g. "Ad", "#ad", "(إعلان)". The decoration around
    // the word must be a non-letter in any script, so a sentence that merely contains the word is
    // never matched.
    private static final Pattern AD_LABEL = Pattern.compile(
            "^[^\\p{L}\\p{N}_]*(?:ad|promoted|إعلان)[^\\p{L}\\p{N}_]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /** Menu item id of "Allow ads from account" in the profile overflow menu ("piko"). */
    public static final int PROFILE_MENU_ITEM_ID = 0x70696b6f;

    private static final class ParseState {
        int depth;
        Object outer;
        Object child;
    }

    private static final ThreadLocal<ParseState> parseState = new ThreadLocal<ParseState>() {
        @Override
        protected ParseState initialValue() {
            return new ParseState();
        }
    };
    private static final Map<String, Field> fieldCache = new HashMap<>();

    private static String allowListRaw = null;
    private static Set<String> allowList = new LinkedHashSet<>();

    static {
        hidePaidPartnership = (Pref.hidePaidPartnership() && SettingsStatus.hideSponsoredPosts);
        hideAdLabel = (Pref.hideAdLabel() && SettingsStatus.hideSponsoredPosts);
    }

    // Obfuscated JSON model field names. Each placeholder is replaced during patching with the
    // field X's own JSON mapper assigns for that key.
    private static String contentDisclosureField() { return "content_disclosure"; }
    private static String advertisingDisclosureField() { return "advertising_disclosure"; }
    private static String isPaidPromotionField() { return "is_paid_promotion"; }
    private static String coreField() { return "core"; }
    // X's own queries use user_result (the user model itself); user_results wraps it in "result".
    private static String userResultField() { return "user_result"; }
    private static String userResultsField() { return "user_results"; }
    private static String userResultsResultField() { return "result"; }
    private static String legacyField() { return "legacy"; }
    private static String fullTextField() { return "full_text"; }
    private static String quotedResultField() { return "quoted_status_result"; }
    private static String retweetedResultField() { return "retweeted_status_result"; }
    // Long posts: NoteTweetData -> NoteTweetResults -> NoteTweetAvailableResult -> NoteTweet.text
    private static String noteTweetField() { return "note_tweet"; }
    private static String noteTweetResultsField() { return "noteTweetResults"; }
    private static String noteTweetResultField() { return "result"; }
    private static String availableNoteTweetField() { return "noteTweet"; }
    private static String noteTweetTextField() { return "text"; }

    // region Parse hooks

    /** Called when a timeline entry or module item starts parsing. */
    public static void onEntryParseStart() {
        ParseState state = parseState.get();
        state.depth = 0;
        state.outer = null;
        state.child = null;
    }

    /** Called when the JSON mapper starts parsing a post. */
    public static void onTweetParseStart() {
        ParseState state = parseState.get();
        if (state.depth == 0) state.child = null;
        state.depth++;
    }

    /** Called with every post the JSON mapper finishes parsing. */
    public static void onTweetParsed(Object jsonApiTweet) {
        ParseState state = parseState.get();
        if (state.depth > 0) state.depth--;
        if (jsonApiTweet == null) return;
        if (state.depth == 0) state.outer = jsonApiTweet;
        else if (state.depth == 1) state.child = jsonApiTweet;
    }

    /**
     * Returns the post the entry that is finishing parsing shows, and forgets it. For a repost
     * that is the original post, since the repost wrapper carries no ad metadata of its own.
     */
    public static Object takeEntryTweet() {
        ParseState state = parseState.get();
        Object tweet = state.outer;
        Object child = state.child;
        state.outer = null;
        state.child = null;
        if (tweet == null || child == null) return tweet;
        try {
            boolean isRepost = getField(getField(tweet, legacyField()), retweetedResultField()) != null;
            // With no quote on the wrapper, the only nested post is the reposted original.
            if (isRepost && getField(tweet, quotedResultField()) == null) return child;
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
        return tweet;
    }

    // endregion

    /** Whether a post should be removed from the timeline. Ads from allowed accounts are kept. */
    public static boolean shouldHide(Object jsonApiTweet) {
        if (jsonApiTweet == null || !(hidePaidPartnership || hideAdLabel)) return false;
        try {
            if (isAdAllowed(jsonApiTweet)) return false;
            if (hidePaidPartnership && isPaidPartnership(jsonApiTweet)) {
                logHidden("Paid partnership", jsonApiTweet);
                return true;
            }
            if (hideAdLabel && hasAdLabel(jsonApiTweet)) {
                logHidden("Ad label", jsonApiTweet);
                return true;
            }
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
        return false;
    }

    /** Whether the post's author is on the "Allow ads from" list. */
    public static boolean isAdAllowed(Object jsonApiTweet) {
        if (jsonApiTweet == null) return false;
        Set<String> allowed = getAllowList();
        if (allowed.isEmpty()) return false;
        String username = getUsername(jsonApiTweet);
        return username != null && allowed.contains(username.toLowerCase());
    }

    private static boolean isPaidPartnership(Object tweet) throws Exception {
        Object disclosure = getField(tweet, contentDisclosureField());
        Object advertising = getField(disclosure, advertisingDisclosureField());
        Object paid = getField(advertising, isPaidPromotionField());
        return Boolean.TRUE.equals(paid);
    }

    /** Adds a hidden post to the "Recently hidden posts" log. */
    public static void logHidden(String reason, Object jsonApiTweet) {
        String text = null;
        try {
            text = getText(jsonApiTweet);
        } catch (Exception ignored) {
        }
        HiddenPostsLog.record(reason, jsonApiTweet == null ? null : getUsername(jsonApiTweet), text);
    }

    private static String getText(Object tweet) throws Exception {
        if (tweet == null) return null;
        // Long posts only carry their first ~280 characters in legacy.full_text.
        Object text = getField(getField(getField(getField(getField(
                tweet, noteTweetField()), noteTweetResultsField()), noteTweetResultField()),
                availableNoteTweetField()), noteTweetTextField());
        if (!(text instanceof String)) text = getField(getField(tweet, legacyField()), fullTextField());
        if (!(text instanceof String)) text = getField(tweet, fullTextField());
        return text instanceof String ? (String) text : null;
    }

    private static boolean hasAdLabel(Object tweet) throws Exception {
        String text = getText(tweet);
        if (text == null) return false;
        for (String line : text.split("\n")) {
            if (AD_LABEL.matcher(line.trim()).matches()) return true;
        }
        return false;
    }

    private static String getUsername(Object tweet) {
        try {
            Object core = getField(tweet, coreField());
            Object user = getField(core, userResultField());
            if (user == null) user = getField(getField(core, userResultsField()), userResultsResultField());
            return user == null ? null : new TwitterUser(user).getUsername();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Reads a field declared on the object's class or any superclass; null-safe. */
    private static Object getField(Object obj, String name) throws Exception {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        String key = cls.getName() + '#' + name;
        Field field;
        synchronized (fieldCache) {
            field = fieldCache.get(key);
            if (field == null && !fieldCache.containsKey(key)) {
                for (Class<?> c = cls; c != null && field == null; c = c.getSuperclass()) {
                    try {
                        field = c.getDeclaredField(name);
                        field.setAccessible(true);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                fieldCache.put(key, field);
            }
        }
        return field == null ? null : field.get(obj);
    }

    // region Allow list

    private static synchronized Set<String> getAllowList() {
        String raw = Pref.getAdsAllowedAccounts();
        if (!raw.equals(allowListRaw)) {
            allowListRaw = raw;
            allowList = parseAllowList(raw);
        }
        return allowList;
    }

    private static Set<String> parseAllowList(String raw) {
        Set<String> set = new LinkedHashSet<>();
        for (String handle : raw.split(",")) {
            handle = handle.trim().replaceFirst("^@", "").toLowerCase();
            if (!handle.isEmpty()) set.add(handle);
        }
        return set;
    }

    /** Share menu action: add the post's author to the allow list, or remove them. */
    public static void toggleAllowAds(Object tweetObject) {
        try {
            toggleAllowAdsFor(new Tweet(tweetObject).getTweetUsername());
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    /** Profile overflow menu: adds the "Allow ads from account" item. */
    public static void addProfileMenuItem(Menu menu) {
        if (menu == null || !Pref.enableAllowAdsButton() || menu.findItem(PROFILE_MENU_ITEM_ID) != null) return;
        menu.add(Menu.NONE, PROFILE_MENU_ITEM_ID, Menu.CATEGORY_SECONDARY, str("piko_allow_ads_from_account"));
    }

    /** Profile overflow menu click; returns true when the item was ours. */
    public static boolean onProfileMenuItemSelected(Object user, MenuItem item) {
        if (item == null || item.getItemId() != PROFILE_MENU_ITEM_ID) return false;
        try {
            toggleAllowAdsFor(user == null ? null : new TwitterUser(user).getUsername());
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
        return true;
    }

    private static void toggleAllowAdsFor(String username) {
        try {
            if (username == null || username.isEmpty()) return;

            Set<String> allowed = new LinkedHashSet<>(getAllowList());
            boolean nowAllowed = allowed.add(username.toLowerCase());
            if (!nowAllowed) allowed.remove(username.toLowerCase());
            Pref.setAdsAllowedAccounts(String.join(", ", allowed));

            Utils.showToastShort(str(
                    nowAllowed ? "piko_ads_allowed_account_added" : "piko_ads_allowed_account_removed",
                    "@" + username
            ));
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    // endregion
}
