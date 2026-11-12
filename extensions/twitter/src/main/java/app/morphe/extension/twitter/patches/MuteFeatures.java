/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Features after Control Panel for Twitter (https://github.com/insin/control-panel-for-twitter),
 * MIT License, Copyright (c) 2019 Jonny Buchanan: "Add muted word" menu item, muting quotes of a
 * post, and hiding replies to hidden posts.
 */

package app.morphe.extension.twitter.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.Intent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.settings.Settings;

@SuppressWarnings("unused")
public final class MuteFeatures {
    private static final String COMPOSER = "com.twitter.app.safety.mutedkeywords.composer.MutedKeywordComposerActivity";
    private static final String MUTED_LIST = "com.twitter.app.safety.mutedkeywords.list.MutedKeywordsListActivity";
    private static final int MEMORY = 20_000;

    // Post id -> the post it quotes, and ids of posts hidden here (most recent kept).
    private static final Map<Long, Long> quotes = Collections.synchronizedMap(lru());
    private static final Map<Long, Boolean> hidden = Collections.synchronizedMap(lru());

    private static String mutedRaw;
    private static Set<Long> muted = Collections.emptySet();

    private MuteFeatures() {
    }

    private static <V> Map<Long, V> lru() {
        return new LinkedHashMap<Long, V>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > MEMORY;
            }
        };
    }

    private static boolean bool(app.morphe.extension.crimera.settings.BooleanSetting setting) {
        try {
            return Boolean.TRUE.equals(Utils.getBooleanPref(setting));
        } catch (Throwable t) {
            return setting.defaultValue;
        }
    }

    public static boolean addMutedWordMenu() {
        return bool(Settings.MUTE_ADD_MUTED_WORD_MENU);
    }

    public static boolean hideRepliesToHidden() {
        return bool(Settings.MUTE_HIDE_REPLIES_TO_HIDDEN);
    }

    static void rememberQuote(long id, long quotedId) {
        quotes.put(id, quotedId);
    }

    static void rememberHidden(long id) {
        hidden.put(id, Boolean.TRUE);
    }

    static boolean isHidden(long id) {
        return hidden.containsKey(id);
    }

    private static Set<Long> mutedQuotes() {
        String raw;
        try {
            raw = Utils.getStringPref(Settings.MUTE_MUTED_QUOTES);
        } catch (Throwable t) {
            raw = "";
        }
        if (!raw.equals(mutedRaw)) {
            Set<Long> set = new LinkedHashSet<>();
            for (String s : raw.split(",")) {
                try {
                    set.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            mutedRaw = raw;
            muted = set;
        }
        return muted;
    }

    static boolean isQuoteMuted(long quotedId) {
        return mutedQuotes().contains(quotedId);
    }

    /**
     * Share menu action "Mute quotes of this post": on a quote, the quoted post; otherwise the
     * post itself. Posts quoting it are hidden from then on (Control Panel's "muted quotes").
     */
    public static void muteQuotes(Object tweetObject) {
        try {
            Long id = new Tweet(tweetObject).getTweetId();
            if (id == null) return;
            Long quoted = quotes.get(id);
            long target = quoted != null ? quoted : id;
            Set<Long> set = new LinkedHashSet<>(mutedQuotes());
            boolean added = set.add(target);
            if (!added) set.remove(target);
            StringBuilder sb = new StringBuilder();
            for (Long l : set) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(l);
            }
            Utils.setStringPref(Settings.MUTE_MUTED_QUOTES.key, sb.toString());
            app.morphe.extension.shared.Utils.showToastShort(str(added ? "piko_mute_quotes_added" : "piko_mute_quotes_removed"));
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    /** Share menu action "Add muted word": X's own muted word composer (the muted words list if that fails). */
    public static void addMutedWord(Context context) {
        if (context == null) return;
        for (String activity : new String[]{COMPOSER, MUTED_LIST}) {
            try {
                Intent intent = new Intent().setClassName(context.getPackageName(), activity);
                if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ex) {
                PikoUtils.logger(ex);
            }
        }
    }
}
