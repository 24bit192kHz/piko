/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Hiding by "account based in" country follows Control Panel for Twitter
 * (https://github.com/insin/control-panel-for-twitter), MIT License,
 * Copyright (c) 2019 Jonny Buchanan.
 */

package app.morphe.extension.twitter.patches.country;

import java.lang.reflect.Field;
import java.util.List;

import app.morphe.extension.crimera.PikoUtils;

/**
 * Decisions use only the local database, so timelines never wait on the network. An author whose
 * country is still unknown is shown and looked up; once the country is known, their posts are
 * hidden from the next loaded timeline (Control Panel holds a post for up to 3 s instead, which
 * an app timeline can't do).
 */
@SuppressWarnings("unused")
public final class CountryFilter {
    private static volatile int capturedCount;
    private static Field userNameField, userAboutField, aboutCountryField, aboutAccurateField;
    private static boolean fieldsBroken;

    private CountryFilter() {
    }

    // region placeholders (the patch fills in the obfuscated field names)

    private static String userNameFieldName() {
        return "";
    }

    private static String userAboutFieldName() {
        return "";
    }

    private static String aboutCountryFieldName() {
        return "";
    }

    private static String aboutAccurateFieldName() {
        return "";
    }

    // endregion

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * Injection point: a user was parsed from an X response. When X included the user's
     * "About this account" data, its country goes into the local database (and is shared with
     * the cache, since it is X's own answer).
     */
    public static void onUser(Object user) {
        if (user == null || fieldsBroken) return;
        try {
            if (userAboutField == null) {
                userAboutField = field(user.getClass(), userAboutFieldName());
                userNameField = field(user.getClass(), userNameFieldName());
            }
            Object about = userAboutField.get(user);
            if (about == null) return;
            if (aboutCountryField == null) {
                aboutCountryField = field(about.getClass(), aboutCountryFieldName());
                aboutAccurateField = field(about.getClass(), aboutAccurateFieldName());
            }
            String country = (String) aboutCountryField.get(about);
            if (country == null || country.isEmpty()) return;
            String handle = (String) userNameField.get(user);
            boolean accurate = !Boolean.FALSE.equals(aboutAccurateField.get(about));
            capturedCount++;
            CountryLookup.debug("captured: " + handle + " -> " + country);
            if (CountryStore.putFromX(handle, country, accurate)) countriesChanged();
            CountryLookup.contribute(handle, country, accurate);
        } catch (Throwable t) {
            fieldsBroken = true;
            PikoUtils.logger(t);
        }
    }

    /**
     * Why a post by this author should be hidden ("Account based in Yemen"), or null. Unknown
     * authors are queued for lookup and shown for now.
     */
    public static String hideReason(String handle) {
        List<String> blocked = CountrySettings.blocked();
        if (handle == null || blocked.isEmpty()) return null;
        try {
            String country = CountryStore.country(handle);
            if (country == null) {
                CountryLookup.request(handle);
                return null;
            }
            String reason = Countries.isBlocked(country, blocked) ? "Account based in " + country : null;
            if (reason != null) CountryLookup.debug("hide: " + handle + " (" + country + ")");
            return reason;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    /** The flag to show after an author's name, or null (unknown handles are queued for lookup). */
    public static String flagFor(String handle) {
        if (handle == null || !CountrySettings.showFlags()) return null;
        try {
            String country = CountryStore.country(handle);
            if (country == null) {
                CountryLookup.request(handle);
                return null;
            }
            return Countries.flagFor(country);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Injection point: a post header is about to show an author's name; adds the flag after it (display only). */
    public static String decorateName(String name, String handle) {
        if (name == null || handle == null) return name;
        String flag = flagFor(handle.startsWith("@") ? handle.substring(1) : handle);
        return flag == null ? name : name + " " + flag;
    }

    /** New countries arrived; timelines pick them up on their next load. */
    static void countriesChanged() {
    }

    /** For the settings screen: how many countries X has sent along with users since start. */
    public static int capturedCount() {
        return capturedCount;
    }
}
