/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.country;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;

final class CountrySettings {
    private static final String OLD_DEFAULT = "India, Israel, Yemen, any region";
    private static String blockedRaw;
    private static volatile boolean lastEnabled, lastEnabledKnown;
    private static List<String> blocked = Collections.emptyList();

    private CountrySettings() {
    }

    private static boolean bool(app.morphe.extension.crimera.settings.BooleanSetting setting) {
        try {
            return Boolean.TRUE.equals(Utils.getBooleanPref(setting));
        } catch (Throwable t) {
            return setting.defaultValue;
        }
    }

    private static volatile long readAt;
    private static volatile List<String> current = Collections.emptyList();

    // Held here: SharedPreferences only keeps a weak reference to its listeners.
    private static android.content.SharedPreferences.OnSharedPreferenceChangeListener listener;

    /** A changed country setting takes effect at once (rows on screen follow), not on the next read. */
    private static synchronized void listen() {
        if (listener != null) return;
        listener = (prefs, key) -> {
            if (key == null || key.startsWith("country_")) {
                readAt = 0;
                blocked();
            }
        };
        try {
            android.content.Context c = app.morphe.extension.shared.Utils.getContext();
            c.getSharedPreferences(Settings.SHARED_PREF_NAME, android.content.Context.MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(listener);
        } catch (Throwable ignored) {
        }
    }

    /** The blocked list in effect; the settings are read again when they change (or every half second). */
    static List<String> blocked() {
        if (listener == null) listen();
        long now = android.os.SystemClock.uptimeMillis();
        if (now - readAt < 500 && readAt != 0) return current;
        List<String> list = readBlocked();
        current = list;
        readAt = now;
        return list;
    }

    private static List<String> readBlocked() {
        boolean enabled = bool(Settings.COUNTRY_BLOCK_ENABLED);
        if (enabled != lastEnabled) {
            // Switched on or off: rows on screen follow (skipped on the first read).
            if (lastEnabledKnown) CountryFilter.countriesChanged();
            lastEnabled = enabled;
            lastEnabledKnown = true;
        }
        if (!enabled) return Collections.emptyList();
        String raw;
        try {
            raw = Utils.getStringPref(Settings.COUNTRY_BLOCKED);
        } catch (Throwable t) {
            raw = Settings.COUNTRY_BLOCKED.defaultValue;
        }
        // The old default ("any region" blocked every hidden-country region) becomes the new one,
        // in the setting too so the settings screen shows what is in effect.
        if (OLD_DEFAULT.equals(raw)) {
            raw = Settings.COUNTRY_BLOCKED.defaultValue;
            try {
                Utils.setStringPref(Settings.COUNTRY_BLOCKED.key, raw);
            } catch (Throwable ignored) {
            }
        }
        if (!raw.equals(blockedRaw)) {
            boolean changed = blockedRaw != null;
            blockedRaw = raw;
            blocked = Countries.parseList(raw);
            // Rows already on screen follow the new list (a post blocked or unblocked just now).
            if (changed) CountryFilter.countriesChanged();
        }
        return blocked;
    }

    static boolean showFlags() {
        return bool(Settings.COUNTRY_SHOW_FLAGS);
    }

    static boolean cloudLookup() {
        return bool(Settings.COUNTRY_CLOUD_LOOKUP);
    }

    static boolean xLookup() {
        return bool(Settings.COUNTRY_X_LOOKUP);
    }

    static boolean contribute() {
        return bool(Settings.COUNTRY_CONTRIBUTE);
    }

    /** Countries are only needed when something uses them. */
    static boolean active() {
        return showFlags() || !blocked().isEmpty();
    }
}
