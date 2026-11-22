/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import android.widget.TextView;
import java.util.List;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Updates from the fork's GitHub releases: when the app comes to the front (at most every 6 hours)
 * it reads the latest release; when that is newer than this build, the APK for this phone's CPU is
 * downloaded on an unmetered network and installed with Android's package installer. The first
 * update asks once; after that Android lets the app update itself without asking (Android 12+).
 */
@SuppressWarnings("unused")
public final class Updates {
    private static final String REPO = "24bit192kHz/piko";
    private static final String APK_PREFIX = "X-12.19.1-piko-plus-";
    private static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final long RETRY_MS = 30 * 60 * 1000L;
    private static final String ACTION = "app.morphe.extension.twitter.UPDATE_STATUS";

    private static boolean installed, busy, receiverRegistered;
    private static WeakReference<Activity> resumed = new WeakReference<>(null);

    private Updates() {
    }

    /** This build's release (bumped with every release; the release kit checks it against the tag). */
    static String current() {
        return "3.11.1-plus.6";
    }

    private static boolean enabled() {
        try {
            return SettingsStatus.autoUpdate && Boolean.TRUE.equals(Utils.getBooleanPref(Settings.AUTO_UPDATE));
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences store(Context c) {
        return c.getSharedPreferences("piko_updates", Context.MODE_PRIVATE);
    }

    /** Injection point: Application.onCreate. */
    public static void init(Application app) {
        if (installed || app == null) return;
        installed = true;
        try {
            // A finished update's download is no longer needed.
            File old = apkFile(app);
            if (old.exists() && System.currentTimeMillis() - old.lastModified() > 60_000) old.delete();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity a) {
                    resumed = new WeakReference<>(a);
                    maybeCheck(a.getApplicationContext());
                }

                public void onActivityPaused(Activity a) {
                    if (resumed.get() == a) resumed = new WeakReference<>(null);
                }

                public void onActivityCreated(Activity a, Bundle b) {
                }

                public void onActivityStarted(Activity a) {
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

    private static File apkFile(Context c) {
        return new File(c.getCacheDir(), "piko-update.apk");
    }

    private static synchronized void maybeCheck(Context c) {
        if (busy || !enabled()) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = store(c);
        if (now - p.getLong("checked", 0) < CHECK_EVERY_MS || now - p.getLong("tried", 0) < RETRY_MS) return;
        busy = true;
        p.edit().putLong("tried", now).apply();
        new Thread(() -> {
            try {
                check(c);
            } catch (Throwable t) {
                PikoUtils.logger(t);
            } finally {
                synchronized (Updates.class) {
                    busy = false;
                }
            }
        }, "piko-update").start();
    }

    private static UpdateBar currentBar;

    private static final long SNOOZE_MS = 24 * 60 * 60 * 1000L;

    private static final class Release {
        final String tag, version, url;
        final long size;

        Release(String tag, String url, long size) {
            this.tag = tag;
            this.version = tag.startsWith("v") ? tag.substring(1) : tag;
            this.url = url;
            this.size = size;
        }
    }

    private static void check(Context c) throws Exception {
        JSONObject release = new JSONObject(get("https://api.github.com/repos/" + REPO + "/releases/latest"));
        String tag = release.optString("tag_name", "");
        store(c).edit().putLong("checked", System.currentTimeMillis()).apply();
        if (compare(tag, current()) <= 0) return;
        SharedPreferences p = store(c);
        if (tag.equals(p.getString("skipped", null))) return;
        if (System.currentTimeMillis() < p.getLong("snoozed_until", 0)) return;
        String url = null;
        long size = -1;
        JSONArray assets = release.optJSONArray("assets");
        for (String abi : candidates()) {
            for (int i = 0; assets != null && i < assets.length() && url == null; i++) {
                JSONObject a = assets.getJSONObject(i);
                if ((APK_PREFIX + abi + ".apk").equals(a.optString("name"))) {
                    url = a.optString("browser_download_url");
                    size = a.optLong("size", -1);
                }
            }
            if (url != null) break;
        }
        if (url == null) return;
        Release r = new Release(tag, url, size);
        new Handler(Looper.getMainLooper()).post(() -> prompt(r));
    }

    /** X's own dialog style (the activity's theme): Update, Not now (asks again tomorrow), Skip this version. */
    private static void prompt(Release r) {
        Activity a = resumed.get();
        if (a == null || a.isFinishing()) return;
        Context c = a.getApplicationContext();
        String mb = r.size > 0 ? " (" + Math.round(r.size / 1048576.0) + " MB)" : "";
        CharSequence title = AdvancedSearch.t(a, "piko_update_title", "Update available");
        CharSequence message = AdvancedSearch.t(a, "piko_update_message", "Piko %s is ready%s. The app restarts after the update.")
                .replaceFirst("%s", r.version).replace("%s", mb);
        android.content.DialogInterface.OnClickListener update = (d, w) -> start(a, r);
        android.content.DialogInterface.OnClickListener later = (d, w) ->
                store(c).edit().putLong("snoozed_until", System.currentTimeMillis() + SNOOZE_MS).apply();
        android.content.DialogInterface.OnClickListener skip = (d, w) -> store(c).edit().putString("skipped", r.tag).apply();
        android.content.DialogInterface.OnCancelListener cancel = d ->
                store(c).edit().putLong("snoozed_until", System.currentTimeMillis() + SNOOZE_MS).apply();
        CharSequence yes = AdvancedSearch.t(a, "piko_update_now", "Update"), no = AdvancedSearch.t(a, "piko_update_later", "Not now"),
                never = AdvancedSearch.t(a, "piko_update_skip", "Skip this version");
        try {
            android.app.Dialog d = xDialog(a, title, message, yes, update, no, later, never, skip, cancel);
            d.show();
            // X styles its positive and negative buttons only: the third one looks like "Not now".
            android.view.View third = d.findViewById(android.R.id.button3), second = d.findViewById(android.R.id.button2);
            if (third instanceof android.widget.Button && second instanceof android.widget.Button) {
                android.widget.Button b3 = (android.widget.Button) third, b2 = (android.widget.Button) second;
                b3.setAllCaps(b2.isAllCaps());
                b3.setTextColor(b2.getTextColors());
                b3.setTypeface(b2.getTypeface());
                b3.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, b2.getTextSize());
                b3.setLetterSpacing(b2.getLetterSpacing());
            }
        } catch (Throwable t) {
            // X's dialog builder not found: the platform's.
            try {
                new android.app.AlertDialog.Builder(a).setTitle(title).setMessage(message)
                        .setPositiveButton(yes, update).setNegativeButton(no, later).setNeutralButton(never, skip)
                        .setOnCancelListener(cancel).show();
            } catch (Throwable t2) {
                PikoUtils.logger(t2);
            }
        }
    }

    /**
     * The dialog the way X makes its own (Material dialog builder over the AppCompat alert params,
     * X 12.19.1 names): its colours, shape and button style.
     */
    private static android.app.Dialog xDialog(Activity a, CharSequence title, CharSequence message,
                                              CharSequence pos, android.content.DialogInterface.OnClickListener onPos,
                                              CharSequence neg, android.content.DialogInterface.OnClickListener onNeg,
                                              CharSequence neu, android.content.DialogInterface.OnClickListener onNeu,
                                              android.content.DialogInterface.OnCancelListener onCancel) throws Exception {
        Object builder = Class.forName("com.google.android.material.dialog.b").getConstructor(Context.class, int.class).newInstance(a, 0);
        java.lang.reflect.Field pf = null;
        for (Class<?> k = builder.getClass(); k != null && pf == null; k = k.getSuperclass()) {
            try {
                pf = k.getDeclaredField("a");
            } catch (NoSuchFieldException ignored) {
            }
        }
        if (pf == null) throw new NoSuchFieldException("alert params");
        pf.setAccessible(true);
        Object params = pf.get(builder);
        if (!params.getClass().getName().startsWith("androidx.appcompat.app.AlertController")) throw new IllegalStateException(params.getClass().getName());
        set(params, "e", title);
        set(params, "g", message);
        set(params, "h", pos);
        set(params, "i", onPos);
        set(params, "j", neg);
        set(params, "k", onNeg);
        set(params, "l", neu);
        set(params, "m", onNeu);
        set(params, "o", onCancel);
        Object dialog = builder.getClass().getMethod("create").invoke(builder);
        return (android.app.Dialog) dialog;
    }

    private static void set(Object o, String name, Object value) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (value != null && !f.getType().isInstance(value)) throw new IllegalStateException(name + " " + f.getType());
        f.set(o, value);
    }

    /** Update tapped: download (with a small progress bar), install, restart. */
    private static void start(Activity a, Release r) {
        Context c = a.getApplicationContext();
        UpdateBar bar = currentBar = UpdateBar.show(a, AdvancedSearch.t(a, "piko_update_downloading", "Downloading Piko %s").replace("%s", r.version));
        synchronized (Updates.class) {
            busy = true;
        }
        new Thread(() -> {
            try {
                File apk = apkFile(c);
                download(r.url, apk, r.size, pct -> bar.progress(pct));
                if (r.size > 0 && apk.length() != r.size) throw new java.io.IOException("size " + apk.length());
                bar.text(AdvancedSearch.t(c, "piko_update_installing", "Installing Piko %s").replace("%s", r.version));
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        install(c, apk);
                    } catch (Throwable t) {
                        failed(c, bar, t);
                    }
                });
            } catch (Throwable t) {
                failed(c, bar, t);
            } finally {
                synchronized (Updates.class) {
                    busy = false;
                }
            }
        }, "piko-update-download").start();
    }

    private static void failed(Context c, UpdateBar bar, Throwable t) {
        PikoUtils.logger(t);
        bar.close();
        new Handler(Looper.getMainLooper()).post(() -> app.morphe.extension.shared.Utils.showToastShort(
                AdvancedSearch.t(c, "piko_update_failed", "Piko update failed")));
    }

    /** The APK names to look for, best first: this phone's CPU, then the build for all of them. */
    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a") || abi.equals("armeabi-v7a") || abi.equals("x86_64")) {
                list.add(abi);
                break;
            }
        }
        list.add("universal");
        return list;
    }

    /** Compares versions like "v3.11.1-plus.6" by their numbers. */
    static int compare(String a, String b) {
        long[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static long[] numbers(String v) {
        List<Long> list = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(v == null ? "" : v);
        while (m.find()) list.add(Long.parseLong(m.group()));
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "piko-updater");
        con.setRequestProperty("Accept", "application/vnd.github+json");
        return con;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    interface Progress {
        void on(int percent);
    }

    private static void download(String url, File to, long size, Progress progress) throws Exception {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection con = open(url);
        con.setRequestProperty("Accept", "application/octet-stream");
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int last = -1;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                int pct = size > 0 ? (int) (done * 100 / size) : -1;
                if (pct != last) {
                    last = pct;
                    progress.on(pct);
                }
            }
        } finally {
            con.disconnect();
        }
        if (!part.renameTo(to)) throw new java.io.IOException("rename " + part);
    }

    private static void install(Context c, File apk) throws Exception {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }
            registerReceiver(c);
            // UpdateRestart opens the app again once the new version is installed.
            store(c).edit().putLong("restart_at", System.currentTimeMillis()).commit();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, id, new Intent(ACTION).setPackage(c.getPackageName()), flags);
            session.commit(pi.getIntentSender());
        }
    }

    private static void registerReceiver(Context c) {
        if (receiverRegistered) return;
        receiverRegistered = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // The first update (the app wasn't installed by itself yet): Android asks once.
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm == null) return;
                    Activity a = resumed.get();
                    if (a != null) a.startActivity(confirm);
                    else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else if (status != PackageInstaller.STATUS_SUCCESS) {
                    if (currentBar != null) currentBar.close();
                    context.getSharedPreferences("piko_updates", Context.MODE_PRIVATE).edit().remove("restart_at").apply();
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    app.morphe.extension.shared.Utils.showToastShort(AdvancedSearch.t(context, "piko_update_failed", "Piko update failed") + (msg == null ? "" : ": " + msg));
                }
            }
        };
        Context app = c.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, new IntentFilter(ACTION));
    }
}
