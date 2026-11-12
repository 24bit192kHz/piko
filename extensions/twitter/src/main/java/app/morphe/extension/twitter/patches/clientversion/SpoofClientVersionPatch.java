/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.clientversion;

import java.util.regex.Pattern;

@SuppressWarnings("unused")
public class SpoofClientVersionPatch {
    private static final Pattern USER_AGENT_VERSION = Pattern.compile("^TwitterAndroid/[^ ]+ \\(\\d+-");

    public static final String PREFS = "piko_settings";
    public static final String PREF_KEY = "xchat_reported_version";

    // "12.45", "12.45.0" or the full "12.45.0-prod.01".
    private static final Pattern VERSION_NAME = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d))?(?:-([a-z]+)\\.(\\d{1,3}))?$");

    // The patch replaces these values with its options.
    public static String defaultVersion() {
        return "12.41.0-prod.01";
    }

    private static int defaultVersionCode() {
        return 312410001;
    }

    private static final class Reported {
        final String raw;
        final String name;
        final int code;

        Reported(String raw, String name, int code) {
            this.raw = raw;
            this.name = name;
            this.code = code;
        }
    }

    private static volatile android.content.SharedPreferences prefs;
    private static volatile Reported reported;

    /**
     * The version X Chat is told: the one set in Piko settings (so it can be raised on the phone
     * when X starts refusing the built-in one), otherwise the patch's default.
     * This runs very early in the app's start, so it must not depend on the extension being set up.
     */
    private static Reported reported() {
        String raw = "";
        try {
            android.content.SharedPreferences p = prefs;
            if (p == null) {
                Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                if (app != null) {
                    prefs = p = ((android.content.Context) app).getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
                }
            }
            if (p != null) raw = p.getString(PREF_KEY, "");
        } catch (Throwable ignored) {
        }
        if (raw == null) raw = "";
        Reported r = reported;
        if (r == null || !r.raw.equals(raw)) reported = r = parse(raw);
        return r;
    }

    /** X's version code is 3, then major (2 digits), minor (2), patch (1) and build (3). */
    static Reported parse(String raw) {
        try {
            java.util.regex.Matcher m = VERSION_NAME.matcher(raw.trim());
            if (m.matches()) {
                String def = defaultVersion();
                String channel = m.group(4), build = m.group(5);
                if (channel == null) {
                    String suffix = def.substring(def.indexOf('-') + 1); // "prod.01"
                    channel = suffix.substring(0, suffix.indexOf('.'));
                    build = suffix.substring(suffix.indexOf('.') + 1);
                }
                int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
                int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                String name = major + "." + minor + "." + patch + "-" + channel + "." + build;
                int code = 300_000_000 + major * 1_000_000 + minor * 10_000 + patch * 1_000 + Integer.parseInt(build);
                return new Reported(raw, name, code);
            }
        } catch (Throwable ignored) {
        }
        return new Reported(raw, defaultVersion(), defaultVersionCode());
    }

    /** Injection point: X Chat's app info and client info. */
    public static String version() {
        return reported().name;
    }

    /** Injection point: X Chat's app info. */
    public static int versionCode() {
        return reported().code;
    }

    private static final ThreadLocal<java.net.URI> REQUEST = new ThreadLocal<>();

    /** Injection point: the legacy stack is about to add its client headers for this request. */
    public static void onRequest(java.net.URI uri) {
        REQUEST.set(uri);
        if (DEBUG && uri != null) android.util.Log.i("PikoSpoof", String.valueOf(uri.getPath()));
    }

    private static final boolean DEBUG = false;

    // The patch fills this with every X Chat GraphQL operation name of the app (comma separated).
    private static String xchatOperations() {
        return "";
    }

    private static java.util.Set<String> operations;

    private static boolean isXChatRequest() {
        java.net.URI uri = REQUEST.get();
        String path = uri == null ? null : uri.getPath();
        if (path == null) return false;
        if (path.toLowerCase(java.util.Locale.ROOT).contains("xchat")) return true;
        if (operations == null) {
            operations = new java.util.HashSet<>(java.util.Arrays.asList(xchatOperations().split(",")));
        }
        int slash = path.lastIndexOf('/');
        return path.contains("/graphql/") && operations.contains(path.substring(slash + 1));
    }

    /** Injection point: X-Twitter-Client-Version of the legacy stack. */
    public static String legacyVersion(String version) {
        return isXChatRequest() ? version() : version;
    }

    /** Injection point: User-Agent of the legacy stack. */
    public static String legacyUserAgent(String userAgent) {
        return isXChatRequest() ? userAgent(userAgent) : userAgent;
    }

    /**
     * Injection point: the X Chat (com.x) network stack's user agent.
     * The legacy stack keeps the app's real user agent.
     */
    public static String userAgent(String userAgent) {
        if (userAgent == null) return null;
        return USER_AGENT_VERSION.matcher(userAgent)
            .replaceFirst("TwitterAndroid/" + version() + " (" + versionCode() + "-");
    }
}
