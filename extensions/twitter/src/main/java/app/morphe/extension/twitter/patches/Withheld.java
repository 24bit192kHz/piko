/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Accounts withheld in the viewer's country ("@x has been withheld in SA in response to a legal
 * demand") are left out of lists (search, followers, who to follow) instead of shown as an empty
 * row with that notice: X sends nothing of them anyway. X marks them with a withheld scope of
 * "user" (only for the viewer's own country: an account withheld elsewhere has no scope).
 */
@SuppressWarnings("unused")
public final class Withheld {
    /** X's withheld scope for a whole account ("user"; "status" is 1). */
    private static final int SCOPE_USER = 2;
    private static final int CAP = 2000;
    private static final Pattern USER_ENTRY = Pattern.compile("(?:^|-)user-(\\d+)(?:-|$)");

    private static final Set<Long> withheld = Collections.synchronizedSet(new LinkedHashSet<>());
    private static Field idField, scopeField, scopeValueField;
    private static boolean broken;

    private Withheld() {
    }

    // Placeholders: the patch fills in the obfuscated names of X 12.19.1's user reader.
    private static String idFieldName() {
        return "a";
    }

    private static String scopeFieldName() {
        return "d0";
    }

    static boolean enabled() {
        try {
            return SettingsStatus.hideWithheld && !broken && Boolean.TRUE.equals(Utils.getBooleanPref(Settings.HIDE_WITHHELD));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Injection point: X read a user's withheld scope. */
    public static void scoped(Object user) {
        if (user == null || broken) return;
        try {
            if (idField == null) {
                idField = field(user.getClass(), idFieldName());
                scopeField = field(user.getClass(), scopeFieldName());
            }
            Object scope = scopeField.get(user);
            if (scope == null) return;
            if (scopeValueField == null) scopeValueField = field(scope.getClass(), "a");
            if (scopeValueField.getInt(scope) != SCOPE_USER) return;
            long id = idField.getLong(user);
            if (id <= 0) return;
            synchronized (withheld) {
                withheld.remove(id);
                withheld.add(id);
                if (withheld.size() > CAP) withheld.remove(withheld.iterator().next());
            }
        } catch (Throwable t) {
            broken = true;
            PikoUtils.logger(t);
        }
    }

    /** Whether a timeline entry is the row of a withheld account. */
    static boolean hideEntry(String entryId) {
        if (entryId == null || withheld.isEmpty() || !entryId.contains("user-") || !enabled()) return false;
        Matcher m = USER_ENTRY.matcher(entryId);
        while (m.find()) {
            try {
                if (withheld.contains(Long.parseLong(m.group(1)))) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
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
}
