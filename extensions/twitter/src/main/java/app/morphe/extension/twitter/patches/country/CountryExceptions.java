/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.country;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;

/**
 * Accounts and screens the country filter leaves alone:
 * <ul>
 * <li>accounts on the "Always show" list (Piko settings, or "Always show this account" in a profile's menu);</li>
 * <li>accounts that follow you: X says so in every user it sends ({@code followed_by}), so they are remembered as
 * X's answers arrive, with no request of their own, and stay remembered;</li>
 * <li>an account's own posts on its profile (you went there to see them);</li>
 * <li>your bookmarks (you saved them).</li>
 * </ul>
 */
public final class CountryExceptions {
    private static final String STORE = "piko_country_exceptions";
    private static final int FOLLOWS_YOU = 2; // X's friendship flag for followed_by
    private static final int ID_CAP = 2000;

    private static Set<String> followers;
    private static volatile String allowedRaw;
    private static volatile Set<String> allowed = Collections.emptySet();
    /** User id -> handle, as X sends them: a profile opened by id is matched to its posts' handle. */
    private static final Map<Long, String> handles = Collections.synchronizedMap(new LinkedHashMap<Long, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > ID_CAP;
        }
    });
    private static volatile WeakReference<Activity> top = new WeakReference<>(null);
    private static boolean tracking;

    private CountryExceptions() {
    }

    private static boolean bool(app.morphe.extension.crimera.settings.BooleanSetting setting) {
        try {
            return Boolean.TRUE.equals(Utils.getBooleanPref(setting));
        } catch (Throwable t) {
            return setting.defaultValue;
        }
    }

    private static SharedPreferences store() {
        return app.morphe.extension.shared.Utils.getContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static String norm(String handle) {
        if (handle == null) return null;
        String h = handle.trim();
        if (h.startsWith("@")) h = h.substring(1);
        return h.isEmpty() ? null : h.toLowerCase(java.util.Locale.ROOT);
    }

    // region followers

    private static synchronized Set<String> followers() {
        if (followers == null) {
            followers = new HashSet<>();
            try {
                for (String h : store().getString("followers", "").split(",")) if (!h.isEmpty()) followers.add(h);
            } catch (Throwable ignored) {
            }
        }
        return followers;
    }

    private static synchronized void addFollower(String h) {
        Set<String> f = followers();
        if (!f.add(h)) return;
        try {
            store().edit().putString("followers", String.join(",", f)).apply();
        } catch (Throwable ignored) {
        }
        CountryFilter.countriesChanged(); // rows already on screen are shown again
    }

    /** For the settings screen. */
    public static synchronized int followerCount() {
        return followers().size();
    }

    /**
     * Injection point (by way of the timeline filter): the author of a post X is reading, with X's friendship
     * flags for you and them.
     */
    public static void noteAuthor(String handle, long userId, Integer friendship) {
        String h = norm(handle);
        if (h == null) return;
        try {
            if (userId > 0) handles.put(userId, h);
            if (friendship != null && (friendship & FOLLOWS_YOU) != 0) addFollower(h);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    // endregion

    // region always shown

    private static Set<String> allowed() {
        String raw;
        try {
            raw = Utils.getStringPref(Settings.COUNTRY_ALLOWED);
        } catch (Throwable t) {
            raw = "";
        }
        if (raw == null) raw = "";
        if (!raw.equals(allowedRaw)) {
            Set<String> set = new LinkedHashSet<>();
            for (String part : raw.split("[,\\s]+")) {
                String h = norm(part);
                if (h != null) set.add(h);
            }
            allowed = set;
            allowedRaw = raw;
        }
        return allowed;
    }

    public static boolean isAllowed(String handle) {
        String h = norm(handle);
        return h != null && allowed().contains(h);
    }

    /** Adds the account to the "Always show" list, or takes it off. Returns whether it is on the list now. */
    public static boolean toggleAllowed(String handle) {
        String h = norm(handle);
        if (h == null) return false;
        Set<String> set = new LinkedHashSet<>(allowed());
        boolean now = set.add(h);
        if (!now) set.remove(h);
        Utils.setStringPref(Settings.COUNTRY_ALLOWED.key, String.join(", ", set));
        CountryFilter.countriesChanged();
        return now;
    }

    // endregion

    // region screens

    private static synchronized void track() {
        if (tracking) return;
        tracking = true;
        try {
            Context c = app.morphe.extension.shared.Utils.getContext();
            ((Application) c.getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                // Created, not only resumed: a screen's first timeline is read before it is on screen.
                public void onActivityCreated(Activity a, Bundle b) {
                    top = new WeakReference<>(a);
                }

                public void onActivityResumed(Activity a) {
                    top = new WeakReference<>(a);
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
                }
            });
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** The screen a timeline is read for: the one on top (or just opened). */
    private static Activity current() {
        track();
        return top.get();
    }

    private static boolean isBookmarks(Activity a) {
        String n = a.getClass().getName();
        return n.equals("com.twitter.app.bookmarks.legacy.BookmarkActivity") || n.equals("com.twitter.app.bookmarks.folders.BookmarkFolderActivity");
    }

    /** Whether the bookmarks are on screen (or being opened): nothing you saved is hidden there. */
    public static boolean onBookmarks() {
        Activity a = current();
        return a != null && isBookmarks(a);
    }

    /** The handle of the profile this screen shows, or null when it is not a profile. */
    private static String profileOwner(Activity a) {
        if (a == null || !a.getClass().getName().equals("com.twitter.app.profiles.ProfileActivity")) return null;
        try {
            Intent i = a.getIntent();
            if (i == null) return null;
            CharSequence name = i.getCharSequenceExtra("screen_name");
            if (name != null && name.length() > 0) return norm(name.toString());
            Uri data = i.getData();
            if (data != null) {
                String q = data.getQueryParameter("screen_name");
                if (q != null) return norm(q);
                String host = data.getHost();
                if (host != null && (host.endsWith("x.com") || host.endsWith("twitter.com")) && !data.getPathSegments().isEmpty()) {
                    return norm(data.getPathSegments().get(0));
                }
                String id = data.getQueryParameter("user_id");
                if (id != null) return handles.get(Long.parseLong(id));
            }
            long id = i.getLongExtra("user_id", 0L);
            return id > 0 ? handles.get(id) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether this author's profile is the screen (the one given, else the one on top). */
    public static boolean ownProfile(String handle, Context screen) {
        String h = norm(handle);
        if (h == null) return false;
        Activity a = screen != null ? activityOf(screen) : current();
        return h.equals(profileOwner(a));
    }

    // endregion

    /**
     * Whether the country filter leaves this author's posts alone on this screen ({@code screen} null: the screen
     * on top, for a timeline being read).
     */
    static boolean exempt(String handle, Context screen) {
        String h = norm(handle);
        if (h == null) return false;
        if (allowed().contains(h)) return true;
        if (bool(Settings.COUNTRY_ALLOW_FOLLOWERS) && followers().contains(h)) return true;
        Activity a = screen != null ? activityOf(screen) : current();
        if (a == null) return false;
        return isBookmarks(a) || h.equals(profileOwner(a));
    }
}
