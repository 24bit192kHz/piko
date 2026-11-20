/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Behaviour follows Control Panel for Twitter's country cache
 * (https://github.com/insin/control-panel-for-twitter), MIT License,
 * Copyright (c) 2019 Jonny Buchanan.
 */

package app.morphe.extension.twitter.patches.country;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Local "account based in" database: lowercased handle → country, where it came from and when
 * the shared cache and X were last asked. Misses are remembered too (cache 3 days, X 30 days),
 * so nobody is asked twice. The most recently used records also stay in memory.
 */
public final class CountryStore {
    // The shared cache grows all the time: an account it did not know is asked about again after an hour.
    public static final long CLOUD_RETRY_MS = 3_600_000L;
    public static final long X_RETRY_MS = 30L * 86_400_000L;
    private static final int MEMORY_CAP = 5_000;

    /** One account. {@code country} is null while unknown. */
    public static final class Record {
        public String country;
        public String source; // "x" (X said so), "cloud" (shared cache)
        public boolean accurate = true;
        public long cloudAskedAt;
        public long xAskedAt;
        public long xAnsweredAt;
        public long contributedAt;
        public String contributedCountry;
    }

    private static final Map<String, Record> memory = new LinkedHashMap<String, Record>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Record> eldest) {
            return size() > MEMORY_CAP;
        }
    };

    // Handles the database doesn't have, so a post that is bound again doesn't query it again.
    private static final Map<String, Boolean> misses = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MISS_CAP;
        }
    };
    private static final int MISS_CAP = 4_000;

    private static Helper helper;

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, "piko_countries.db", null, 2);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE countries (h TEXT PRIMARY KEY, co TEXT, src TEXT, acc INTEGER, "
                    + "cq INTEGER, xq INTEGER, xa INTEGER, ct INTEGER, cco TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // Before version 2 every X lookup went to a client that answers 404, and each failure counted as
                // "X knows nothing" for 30 days: ask X and the shared cache again about every unknown account.
                db.execSQL("UPDATE countries SET xq = 0, cq = 0 WHERE co IS NULL");
            }
        }
    }

    private CountryStore() {
    }

    private static synchronized SQLiteDatabase db() {
        if (helper == null) {
            Context context = app.morphe.extension.shared.Utils.getContext();
            if (context == null) return null;
            helper = new Helper(context);
        }
        return helper.getWritableDatabase();
    }

    public static String normalize(String handle) {
        if (handle == null) return null;
        String h = handle.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("@")) h = h.substring(1);
        // A handle: 1 to 20 letters, digits or underscores (checked without a regex: this runs per post).
        int n = h.length();
        if (n == 0 || n > 20) return null;
        for (int i = 0; i < n; i++) {
            char c = h.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return null;
        }
        return h;
    }

    /** The record for a handle (memory, then the database), or null if it was never seen. */
    public static synchronized Record get(String handle) {
        String h = normalize(handle);
        if (h == null) return null;
        Record rec = memory.get(h);
        if (rec != null) return rec;
        if (misses.containsKey(h)) return null;
        SQLiteDatabase db = db();
        if (db == null) return null;
        try (Cursor c = db.query("countries", null, "h=?", new String[]{h}, null, null, null)) {
            if (!c.moveToFirst()) {
                misses.put(h, Boolean.TRUE);
                return null;
            }
            rec = new Record();
            rec.country = c.getString(c.getColumnIndexOrThrow("co"));
            rec.source = c.getString(c.getColumnIndexOrThrow("src"));
            rec.accurate = c.getInt(c.getColumnIndexOrThrow("acc")) != 0;
            rec.cloudAskedAt = c.getLong(c.getColumnIndexOrThrow("cq"));
            rec.xAskedAt = c.getLong(c.getColumnIndexOrThrow("xq"));
            rec.xAnsweredAt = c.getLong(c.getColumnIndexOrThrow("xa"));
            rec.contributedAt = c.getLong(c.getColumnIndexOrThrow("ct"));
            rec.contributedCountry = c.getString(c.getColumnIndexOrThrow("cco"));
        }
        memory.put(h, rec);
        return rec;
    }

    /** The known country of a handle, or null. Never touches the network. */
    public static String country(String handle) {
        Record rec = get(handle);
        return rec == null ? null : rec.country;
    }

    private static synchronized Record edit(String h) {
        Record rec = get(h);
        if (rec == null) {
            rec = new Record();
            misses.remove(h);
            memory.put(h, rec);
        }
        return rec;
    }

    private static synchronized void save(String h, Record rec) {
        SQLiteDatabase db = db();
        if (db == null) return;
        ContentValues v = new ContentValues();
        v.put("h", h);
        v.put("co", rec.country);
        v.put("src", rec.source);
        v.put("acc", rec.accurate ? 1 : 0);
        v.put("cq", rec.cloudAskedAt);
        v.put("xq", rec.xAskedAt);
        v.put("xa", rec.xAnsweredAt);
        v.put("ct", rec.contributedAt);
        v.put("cco", rec.contributedCountry);
        db.insertWithOnConflict("countries", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** X's own answer (a lookup, or about_profile in a response the app received). */
    public static synchronized boolean putFromX(String handle, String country, boolean accurate) {
        String h = normalize(handle);
        if (h == null) return false;
        Record rec = edit(h);
        long now = System.currentTimeMillis();
        rec.xAskedAt = now;
        boolean changed = false;
        if (country != null && !country.isEmpty()) {
            changed = !country.equals(rec.country);
            rec.country = country;
            rec.source = "x";
            rec.accurate = accurate;
            rec.xAnsweredAt = now;
        }
        save(h, rec);
        return changed;
    }

    /** The shared cache's answer; null means it had nothing. A known country is never replaced by "unknown". */
    public static synchronized boolean putFromCloud(String handle, String country, boolean accurate) {
        String h = normalize(handle);
        if (h == null) return false;
        Record rec = edit(h);
        rec.cloudAskedAt = System.currentTimeMillis();
        boolean changed = false;
        if (country != null && !country.isEmpty() && !"x".equals(rec.source)) {
            changed = !country.equals(rec.country);
            rec.country = country;
            rec.source = "cloud";
            rec.accurate = accurate;
        }
        save(h, rec);
        return changed;
    }

    public static synchronized void markContributed(String handle, String country) {
        String h = normalize(handle);
        if (h == null) return;
        Record rec = edit(h);
        rec.contributedAt = System.currentTimeMillis();
        rec.contributedCountry = country;
        save(h, rec);
    }

    public static synchronized void markXAsked(String handle) {
        markXAsked(handle, System.currentTimeMillis());
    }

    /** When X counts as asked: an error answer is retried after an hour instead of in 30 days. */
    public static synchronized void markXAsked(String handle, long at) {
        String h = normalize(handle);
        if (h == null) return;
        Record rec = edit(h);
        rec.xAskedAt = at;
        save(h, rec);
    }

    /** Whether the shared cache should be asked about a handle now. */
    public static boolean needsCloud(String handle, long now) {
        Record rec = get(handle);
        return rec == null || (rec.country == null && now - rec.cloudAskedAt >= CLOUD_RETRY_MS);
    }

    /** Whether X should be asked about a handle now (the shared cache had nothing). */
    public static boolean needsX(String handle, long now) {
        Record rec = get(handle);
        return rec != null && rec.country == null && rec.cloudAskedAt > 0 && now - rec.xAskedAt >= X_RETRY_MS;
    }

    public static synchronized int count() {
        SQLiteDatabase db = db();
        if (db == null) return 0;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM countries WHERE co IS NOT NULL", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public static synchronized void clear() {
        memory.clear();
        misses.clear();
        SQLiteDatabase db = db();
        if (db != null) db.delete("countries", null, null);
    }
}
