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

    static List<String> blocked() {
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
