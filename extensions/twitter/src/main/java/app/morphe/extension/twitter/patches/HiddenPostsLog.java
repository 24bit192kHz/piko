/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;

/** Keeps a short list of the most recently hidden ad / sponsored posts. */
public class HiddenPostsLog {
    private static final int MAX_ENTRIES = 10;
    private static final int MAX_TEXT = 120;
    // Entry fields are joined with a unit separator and entries with a record separator.
    private static final String FIELD_SEP = "\u001f";
    private static final String ENTRY_SEP = "\u001e";

    private static List<String> entries = null;
    // The same post is parsed again on every timeline refresh; don't log it twice in a row.
    private static String lastKey = null;

    private static synchronized List<String> load() {
        if (entries == null) {
            entries = new ArrayList<>();
            String raw = Utils.getStringPref(Settings.ADS_HIDDEN_POSTS_LOG);
            if (raw != null && !raw.isEmpty()) entries.addAll(Arrays.asList(raw.split(ENTRY_SEP)));
        }
        return entries;
    }

    public static synchronized void record(String reason, String username, String text) {
        try {
            String key = reason + username + text;
            if (key.equals(lastKey)) return;
            lastKey = key;

            if (text == null) text = "";
            text = text.replace('\n', ' ').trim();
            if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT) + "…";
            String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            String entry = time + FIELD_SEP + reason + FIELD_SEP
                    + (username == null ? "?" : username) + FIELD_SEP + text;

            List<String> list = load();
            list.remove(entry);
            list.add(0, entry);
            while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
            Utils.setStringPref(Settings.ADS_HIDDEN_POSTS_LOG.key, String.join(ENTRY_SEP, list));
        } catch (Exception ignored) {
        }
    }

    public static void showDialog(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String entry : load()) {
            String[] f = entry.split(FIELD_SEP, -1);
            if (f.length < 4) continue;
            sb.append(f[0]).append("  ").append(f[1]).append("  @").append(f[2]).append('\n');
            if (!f[3].isEmpty()) sb.append(f[3]).append('\n');
            sb.append('\n');
        }
        String message = sb.length() == 0 ? str("piko_pref_hidden_posts_log_empty") : sb.toString().trim();

        new AlertDialog.Builder(context)
                .setTitle(str("piko_pref_hidden_posts_log"))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(str("piko_pref_hidden_posts_log_clear"), (d, w) -> clear())
                .show();
    }

    private static synchronized void clear() {
        load().clear();
        lastKey = null;
        Utils.setStringPref(Settings.ADS_HIDDEN_POSTS_LOG.key, "");
    }
}
