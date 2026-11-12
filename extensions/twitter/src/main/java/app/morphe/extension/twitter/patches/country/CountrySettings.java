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
    private static String blockedRaw;
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
        if (!bool(Settings.COUNTRY_BLOCK_ENABLED)) return Collections.emptyList();
        String raw;
        try {
            raw = Utils.getStringPref(Settings.COUNTRY_BLOCKED);
        } catch (Throwable t) {
            raw = Settings.COUNTRY_BLOCKED.defaultValue;
        }
        if (!raw.equals(blockedRaw)) {
            blockedRaw = raw;
            blocked = Countries.parseList(raw);
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
