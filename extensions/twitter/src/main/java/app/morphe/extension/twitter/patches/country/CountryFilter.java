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

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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
            if (CountryLookup.DEBUG) CountryLookup.debug("captured: " + handle + " -> " + country);
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
            if (CountryLookup.DEBUG && reason != null) CountryLookup.debug("hide: " + handle + " (" + country + ")");
            return reason;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    // region post headers and timeline rows

    // View tag keys (any id with a package byte of 0x70 is free for app use).
    private static final int TAG_HANDLE = 0x70696b01, TAG_HEIGHT = 0x70696b02, TAG_LISTENER = 0x70696b03,
            TAG_ROW = 0x70696b06, TAG_HEADERS = 0x70696b07, TAG_PENDING = 0x70696b08;
    /** Rows that show a post, re-checked when countries arrive. Weak: rows come and go with the list. */
    private static final Map<View, Boolean> rows = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>, Boolean> listClasses = new WeakHashMap<>();
    private static Handler main;
    private static boolean recheckQueued;

    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View header) {
            checkRowOf(header);
        }

        @Override
        public void onViewDetachedFromWindow(View header) {
        }
    };

    /**
     * Injection point: a post header (the author's, or a quoted post's) gets its name and handle.
     * Returns the name with the author's flag or region icon after it (display only), and hides
     * the row the header is in when the author is based in a blocked country. Timelines already
     * drop such posts while parsing, but a post whose country was unknown then, or one restored
     * from the timeline cache, is only caught here.
     */
    public static CharSequence onHeaderData(View header, String name, String handle) {
        long t = Perf.start();
        CharSequence result = headerData(header, name, handle);
        Perf.end(0, t);
        return result;
    }

    private static CharSequence headerData(View header, String name, String handle) {
        String h = handle == null ? null : (handle.startsWith("@") ? handle.substring(1) : handle);
        try {
            header.setTag(TAG_HANDLE, h);
            if (header.getTag(TAG_LISTENER) == null) {
                header.setTag(TAG_LISTENER, Boolean.TRUE);
                header.addOnAttachStateChangeListener(ATTACH);
            }
            // A recycled row is bound while attached; a new one is checked when it attaches.
            if (header.isAttachedToWindow()) checkRowOf(header);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return decorate(header, name, h);
    }

    private static CharSequence decorate(View header, String name, String handle) {
        if (name == null || handle == null || !CountrySettings.showFlags()) return name;
        try {
            String country = CountryStore.country(handle);
            if (country == null) {
                CountryLookup.request(handle);
                return name;
            }
            String flag = Countries.flagFor(country);
            if (flag != null) return name + " " + flag;
            String kind = Countries.regionKind(country);
            if (kind == null) return name;
            CharSequence withIcon = RegionIcons.append(header.getContext(), name, kind);
            return withIcon == null ? name : withIcon;
        } catch (Throwable t) {
            return name;
        }
    }

    /** The list row (a direct child of a RecyclerView) that holds this view, or null. */
    private static View rowOf(View view) {
        View child = view;
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (isList(parent.getClass())) return child;
            child = (View) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private static boolean isList(Class<?> cls) {
        Boolean cached = listClasses.get(cls);
        if (cached != null) return cached;
        boolean list = false;
        for (Class<?> c = cls; c != null && c != View.class; c = c.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                list = true;
                break;
            }
        }
        listClasses.put(cls, list);
        return list;
    }

    private static void checkRowOf(View header) {
        // A header stays in the row it was inflated in, so the row is looked up once.
        Object cached = header.getTag(TAG_ROW);
        View row = cached instanceof View ? (View) cached : rowOf(header);
        if (row == null) return;
        if (cached == null) header.setTag(TAG_ROW, row);
        @SuppressWarnings("unchecked")
        ArrayList<View> headers = (ArrayList<View>) row.getTag(TAG_HEADERS);
        if (headers == null) row.setTag(TAG_HEADERS, headers = new ArrayList<>(2));
        if (!headers.contains(header)) headers.add(header);
        // Nothing blocked: only a row hidden earlier needs work (it is shown again).
        if (CountrySettings.blocked().isEmpty() && row.getTag(TAG_HEIGHT) == null) return;
        rows.put(row, Boolean.TRUE);
        // Now, so a hidden row is never drawn (or measured, while the list adds it) at full height,
        // and once more after the rest of the row is bound (its quote may be hidden or replaced).
        applyRow(row);
        if (row.getTag(TAG_PENDING) == null) {
            row.setTag(TAG_PENDING, Boolean.TRUE);
            row.post(() -> {
                row.setTag(TAG_PENDING, null);
                applyRow(row);
            });
        }
    }

    /** Hides or restores a row: hidden while any shown header in it belongs to a blocked author. */
    private static boolean applyRow(View row) {
        long t = Perf.start();
        boolean changed = applyRow0(row);
        Perf.end(1, t);
        return changed;
    }

    /** Returns whether the row was hidden or shown just now. */
    private static boolean applyRow0(View row) {
        String reason = CountrySettings.blocked().isEmpty() ? null : blockedHeader(row);
        boolean hidden = row.getTag(TAG_HEIGHT) != null;
        if (reason != null && !hidden) {
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp == null) return false;
            row.setTag(TAG_HEIGHT, lp.height);
            lp.height = 0;
            row.setLayoutParams(lp);
            row.setVisibility(View.GONE);
            if (CountryLookup.DEBUG) CountryLookup.debug("row hidden: " + reason);
            return true;
        } else if (reason == null && hidden) {
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            Object height = row.getTag(TAG_HEIGHT);
            row.setTag(TAG_HEIGHT, null);
            if (lp != null) {
                lp.height = (Integer) height;
                row.setLayoutParams(lp);
            }
            row.setVisibility(View.VISIBLE);
            return true;
        }
        return false;
    }

    /** The hide reason of the first shown header in the row whose author is blocked, or null. */
    private static String blockedHeader(View row) {
        @SuppressWarnings("unchecked")
        ArrayList<View> headers = (ArrayList<View>) row.getTag(TAG_HEADERS);
        if (headers == null) return null;
        for (int i = 0, n = headers.size(); i < n; i++) {
            View header = headers.get(i);
            Object handle = header.getTag(TAG_HANDLE);
            if (!(handle instanceof String) || !shownIn(header, row)) continue;
            String reason = hideReason((String) handle);
            if (reason != null) return reason;
        }
        return null;
    }

    /** Whether the header and everything between it and the row is visible (a quote may be hidden). */
    private static boolean shownIn(View view, View row) {
        for (View v = view; v != row; ) {
            if (v.getVisibility() != View.VISIBLE) return false;
            ViewParent p = v.getParent();
            if (!(p instanceof View)) return false;
            v = (View) p;
        }
        return true;
    }

    /** New countries arrived: rows already on screen are checked again (once per burst). */
    static void countriesChanged() {
        synchronized (CountryFilter.class) {
            if (recheckQueued) return;
            recheckQueued = true;
            if (main == null) main = new Handler(Looper.getMainLooper());
        }
        main.postDelayed(() -> {
            synchronized (CountryFilter.class) {
                recheckQueued = false;
            }
            List<View> snapshot;
            synchronized (rows) {
                snapshot = new ArrayList<>(rows.keySet());
            }
            int changed = 0;
            for (View row : snapshot) {
                if (row != null && row.isAttachedToWindow() && applyRow(row)) changed++;
            }
            if (CountryLookup.DEBUG && changed > 0) CountryLookup.debug("recheck after new countries or settings: " + changed + " rows changed");
        }, 250);
    }

    // endregion

    /** For the settings screen: how many countries X has sent along with users since start. */
    public static int capturedCount() {
        return capturedCount;
    }
}
