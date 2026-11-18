/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * The search screen's empty Explore page (trends hidden) shows X's web "Advanced search" as a
 * native form instead of "Nothing to see here — yet.", under the normal search box. It builds
 * the same query the web form does and opens the app's own search results for it.
 */
@SuppressWarnings("unused")
public final class AdvancedSearch {
    private static final String FORM_TAG = "piko_advanced_search";
    private static final String[] LANGUAGES = {"", "ar", "en", "fr", "es", "de", "it", "pt", "ru", "tr", "fa", "ur", "hi",
            "id", "ms", "ja", "ko", "zh", "nl", "pl", "sv", "uk", "he", "th", "vi"};

    private AdvancedSearch() {
    }

    private static boolean enabled() {
        try {
            return SettingsStatus.advancedSearch && Boolean.TRUE.equals(Utils.getBooleanPref(Settings.ADVANCED_SEARCH));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Injection point: a list's empty state is shown ({@code empty} holds its title). On the
     * search screen with the generic text, the form takes its place.
     */
    static final boolean DEBUG = false;

    static void log(String m) {
        if (DEBUG) android.util.Log.d("PikoAdvSearch", m);
    }

    public static void onEmptyShown(View empty) {
        if (DEBUG) log("shown " + System.identityHashCode(empty) + " enabled=" + enabled() + " watching=" + (empty != null && empty.getTag(0x70696b09) != null));
        if (empty == null || !enabled()) return;
        if (empty.getTag(TAG_WATCH) != null) return;
        // The search tab's pages are built in the background (while another tab shows) and its
        // search box only comes with the tab: check after layouts until the page is on screen.
        // A frame check, not a layout one: switching tabs scrolls the pager without a layout.
        ViewTreeObserver.OnPreDrawListener[] holder = new ViewTreeObserver.OnPreDrawListener[1];
        holder[0] = () -> {
            try {
                if (empty.getVisibility() != View.VISIBLE) return true; // hidden or replaced already
                if (!empty.getGlobalVisibleRect(RECT)) return true; // not on screen (another tab)
                boolean done = apply(empty);
                if (DEBUG) log("predraw " + System.identityHashCode(empty) + " done=" + done);
                if (done) stopWatching(empty, holder[0]);
                else retry(empty, holder[0], 0); // on screen, but the tab's search box isn't there yet
            } catch (Throwable t) {
                stopWatching(empty, holder[0]);
                PikoUtils.logger(t);
            }
            return true;
        };
        empty.setTag(TAG_WATCH, holder[0]);
        empty.getViewTreeObserver().addOnPreDrawListener(holder[0]);
    }

    private static final android.graphics.Rect RECT = new android.graphics.Rect();
    private static final int TAG_RETRY = 0x70696b0a;

    /** The screen may not draw again once still: look again every 300 ms for up to 3 s. */
    private static void retry(View empty, ViewTreeObserver.OnPreDrawListener watcher, int attempt) {
        if (attempt == 0) {
            if (empty.getTag(TAG_RETRY) != null) return;
            empty.setTag(TAG_RETRY, Boolean.TRUE);
        }
        empty.postDelayed(() -> {
            try {
                if (empty.getTag(TAG_WATCH) != watcher || empty.getVisibility() != View.VISIBLE) {
                    empty.setTag(TAG_RETRY, null);
                    return;
                }
                if (empty.getGlobalVisibleRect(RECT) && apply(empty)) {
                    stopWatching(empty, watcher);
                    empty.setTag(TAG_RETRY, null);
                } else if (attempt < 10) {
                    retry(empty, watcher, attempt + 1);
                } else {
                    empty.setTag(TAG_RETRY, null);
                }
            } catch (Throwable t) {
                empty.setTag(TAG_RETRY, null);
                PikoUtils.logger(t);
            }
        }, 300);
    }
    private static final int TAG_WATCH = 0x70696b09;

    private static void stopWatching(View empty, ViewTreeObserver.OnPreDrawListener l) {
        empty.setTag(TAG_WATCH, null);
        // Not from inside the callback's iteration: on the next loop.
        empty.post(() -> empty.getViewTreeObserver().removeOnPreDrawListener(l));
    }

    /** Injection point: the list got content; the form goes away with the empty state. */
    public static void onEmptyHidden(View empty) {
        if (DEBUG) log("hidden " + System.identityHashCode(empty));
        if (empty == null) return;
        Object watch = empty.getTag(TAG_WATCH);
        if (watch instanceof ViewTreeObserver.OnPreDrawListener) stopWatching(empty, (ViewTreeObserver.OnPreDrawListener) watch);
        View form = formFor(empty);
        if (form != null && form.getVisibility() != View.GONE) {
            form.setVisibility(View.GONE);
            empty.setAlpha(1f);
            Context c = empty.getContext();
            View title = empty.findViewById(id(c, "id", "empty_title"));
            if (title != null) title.setVisibility(View.VISIBLE);
            View desc = empty.findViewById(id(c, "id", "empty_desc"));
            if (desc != null) desc.setVisibility(View.VISIBLE);
        }
    }

    private static int id(Context c, String type, String name) {
        return c.getResources().getIdentifier(name, type, c.getPackageName());
    }

    /** The view sits in a page of a tab pager that is itself a page of the bottom tab pager. */
    private static boolean onSearchTab(View view) {
        boolean tabPager = false;
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            String name = p.getClass().getSimpleName();
            if (name.equals("BottomNavViewPager")) return tabPager;
            if (name.endsWith("ViewPager")) tabPager = true;
        }
        return false;
    }

    private static View formFor(View empty) {
        ViewParent p = empty.getParent();
        return p instanceof ViewGroup ? ((ViewGroup) p).findViewWithTag(FORM_TAG) : null;
    }

    /** Returns false while it can't tell yet (no search box found). */
    private static boolean apply(View empty) {
        if (empty.getVisibility() != View.VISIBLE) return true;
        Context c = empty.getContext();
        TextView title = empty.findViewById(id(c, "id", "empty_title"));
        int generic = id(c, "string", "empty_generic_timeline");
        if (title == null || generic == 0) return true;
        // The title may be set after the state is shown: wait for it; another text means another list.
        if (title.getText() == null || title.getText().length() == 0) return false;
        if (!c.getString(generic).contentEquals(title.getText())) {
            if (DEBUG) log("other title: " + title.getText());
            return true;
        }
        // Only on the search tab: a page of its tab pager (Explore, Trending…) inside the bottom tabs.
        if (!onSearchTab(empty)) {
            if (DEBUG) log("not on the search tab");
            return true;
        }
        ViewParent parent = empty.getParent();
        if (!(parent instanceof ViewGroup)) return true;
        ViewGroup container = (ViewGroup) parent;
        View form = container.findViewWithTag(FORM_TAG);
        if (form == null) {
            form = build(c, title.getCurrentTextColor());
            container.addView(form, container.indexOfChild(empty) + 1,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        form.setVisibility(View.VISIBLE);
        // The empty state stays (the list shows and hides it), just out of sight.
        empty.setAlpha(0f);
        title.setVisibility(View.INVISIBLE);
        View desc = empty.findViewById(id(c, "id", "empty_desc"));
        if (desc != null) desc.setVisibility(View.INVISIBLE);
        return true;
    }

    // region form

    private static final class Fields {
        EditText all, exact, any, none, hashtags, from, to, mentioning, minReplies, minLikes, minReposts;
        Spinner language;
        CheckBox replies, onlyReplies, links, onlyLinks;
        String since, until;
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static View build(Context c, int textColor) {
        Fields f = new Fields();
        ScrollView scroll = new ScrollView(c);
        scroll.setTag(FORM_TAG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 16);
        col.setPadding(pad, dp(c, 8), pad, dp(c, 96));
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = text(c, "Advanced search", 22, true, textColor);
        col.addView(heading);

        section(col, "Words", textColor);
        f.all = field(col, "All of these words", "Example: what's happening · contains both “what's” and “happening”", textColor);
        f.exact = field(col, "This exact phrase", "Example: happy hour · contains the exact phrase “happy hour”", textColor);
        f.any = field(col, "Any of these words", "Example: cats dogs · contains either “cats” or “dogs” (or both)", textColor);
        f.none = field(col, "None of these words", "Example: cats dogs · does not contain “cats” and does not contain “dogs”", textColor);
        f.hashtags = field(col, "These hashtags", "Example: #ThrowbackThursday · contains the hashtag #ThrowbackThursday", textColor);
        col.addView(text(c, "Language", 14, false, textColor));
        f.language = new Spinner(c);
        List<String> names = new ArrayList<>();
        for (String code : LANGUAGES) {
            names.add(code.isEmpty() ? "Any language" : new Locale(code).getDisplayLanguage(Locale.ENGLISH) + " (" + code + ")");
        }
        f.language.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, names));
        col.addView(f.language);

        section(col, "Accounts", textColor);
        f.from = field(col, "From these accounts", "Example: @X · posts sent from @X", textColor);
        f.to = field(col, "To these accounts", "Example: @X · posts sent in reply to @X", textColor);
        f.mentioning = field(col, "Mentioning these accounts", "Example: @SFBART @Caltrain · mentions @SFBART or mentions @Caltrain", textColor);

        section(col, "Filters", textColor);
        col.addView(text(c, "Replies", 14, true, textColor));
        f.replies = check(col, "Include replies and original posts", true, textColor);
        f.onlyReplies = check(col, "Only show replies", false, textColor);
        f.onlyReplies.setOnCheckedChangeListener((b, on) -> f.replies.setEnabled(!on));
        col.addView(text(c, "Links", 14, true, textColor));
        f.links = check(col, "Include posts with links", true, textColor);
        f.onlyLinks = check(col, "Only show posts with links", false, textColor);
        f.onlyLinks.setOnCheckedChangeListener((b, on) -> f.links.setEnabled(!on));

        section(col, "Engagement", textColor);
        f.minReplies = number(col, "Minimum replies", "Example: 280 · posts with at least 280 replies", textColor);
        f.minLikes = number(col, "Minimum likes", "Example: 280 · posts with at least 280 likes", textColor);
        f.minReposts = number(col, "Minimum reposts", "Example: 280 · posts with at least 280 reposts", textColor);

        section(col, "Dates", textColor);
        Button from = dateButton(c, "From: any date", textColor);
        Button until = dateButton(c, "To: any date", textColor);
        from.setOnClickListener(v -> pickDate(c, d -> {
            f.since = d;
            from.setText(d == null ? "From: any date" : "From: " + d);
        }));
        until.setOnClickListener(v -> pickDate(c, d -> {
            f.until = d;
            until.setText(d == null ? "To: any date" : "To: " + d);
        }));
        col.addView(from);
        col.addView(until);

        Button search = new Button(c);
        search.setText("Search");
        search.setAllCaps(false);
        search.setTextColor(0xFFFFFFFF);
        search.setBackgroundTintList(ColorStateList.valueOf(0xFF1D9BF0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48));
        lp.topMargin = dp(c, 24);
        col.addView(search, lp);
        Runnable run = () -> {
            String q = query(f);
            if (q.isEmpty()) {
                app.morphe.extension.shared.Utils.showToastShort("Fill in at least one field");
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("twitter://search?query=" + Uri.encode(q)));
            i.setPackage(c.getPackageName());
            c.startActivity(i);
        };
        search.setOnClickListener(v -> run.run());
        // The keyboard's action key searches too, from any field (like Enter on the web form).
        for (EditText e : new EditText[]{f.all, f.exact, f.any, f.none, f.hashtags, f.from, f.to, f.mentioning,
                f.minReplies, f.minLikes, f.minReposts}) {
            e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            e.setOnEditorActionListener((v, action, event) -> {
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                    run.run();
                    return true;
                }
                return false;
            });
        }
        return scroll;
    }

    private static TextView text(Context c, String s, float sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, dp(c, 8), 0, dp(c, 4));
        return t;
    }

    private static void section(LinearLayout col, String s, int color) {
        TextView t = text(col.getContext(), s, 18, true, color);
        t.setPadding(0, dp(col.getContext(), 20), 0, dp(col.getContext(), 4));
        col.addView(t);
    }

    private static EditText field(LinearLayout col, String label, String help, int color) {
        Context c = col.getContext();
        EditText e = new EditText(c);
        e.setHint(label);
        e.setSingleLine(true);
        e.setTextColor(color);
        e.setHintTextColor((color & 0x00FFFFFF) | 0x88000000);
        col.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView h = text(c, help, 12, false, (color & 0x00FFFFFF) | 0x99000000);
        h.setPadding(0, 0, 0, dp(c, 6));
        col.addView(h);
        return e;
    }

    private static EditText number(LinearLayout col, String label, String help, int color) {
        EditText e = field(col, label, help, color);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private static CheckBox check(LinearLayout col, String label, boolean on, int color) {
        CheckBox b = new CheckBox(col.getContext());
        b.setText(label);
        b.setTextColor(color);
        b.setChecked(on);
        col.addView(b);
        return b;
    }

    private static Button dateButton(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTextColor(color);
        b.setBackgroundTintList(ColorStateList.valueOf((color & 0x00FFFFFF) | 0x22000000));
        return b;
    }

    private interface DateResult {
        void set(String date);
    }

    private static void pickDate(Context c, DateResult result) {
        Calendar now = Calendar.getInstance();
        DatePickerDialog d = new DatePickerDialog(c, (view, y, m, day) ->
                result.set(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, day)),
                now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        d.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Any date", (dialog, which) -> result.set(null));
        d.show();
    }

    // endregion

    // region query (the same operators the web form writes)

    private static String[] words(EditText e) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? new String[0] : s.split("[\\s,]+");
    }

    private static String handle(String w) {
        return w.startsWith("@") ? w.substring(1) : w;
    }

    /** "(a OR b)" for several, "a" for one. */
    private static void anyOf(StringBuilder q, List<String> terms) {
        if (terms.isEmpty()) return;
        if (q.length() > 0) q.append(' ');
        if (terms.size() == 1) {
            q.append(terms.get(0));
            return;
        }
        q.append('(');
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) q.append(" OR ");
            q.append(terms.get(i));
        }
        q.append(')');
    }

    static String query(Fields f) {
        StringBuilder q = new StringBuilder();
        for (String w : words(f.all)) q.append(q.length() > 0 ? " " : "").append(w);
        String exact = f.exact.getText().toString().trim().replace("\"", "");
        if (!exact.isEmpty()) q.append(q.length() > 0 ? " " : "").append('"').append(exact).append('"');
        List<String> any = new ArrayList<>();
        for (String w : words(f.any)) any.add(w);
        anyOf(q, any);
        for (String w : words(f.none)) q.append(q.length() > 0 ? " " : "").append('-').append(w);
        List<String> tags = new ArrayList<>();
        for (String w : words(f.hashtags)) tags.add(w.startsWith("#") ? w : "#" + w);
        anyOf(q, tags);
        List<String> from = new ArrayList<>(), to = new ArrayList<>(), mention = new ArrayList<>();
        for (String w : words(f.from)) from.add("from:" + handle(w));
        for (String w : words(f.to)) to.add("to:" + handle(w));
        for (String w : words(f.mentioning)) mention.add("@" + handle(w));
        anyOf(q, from);
        anyOf(q, to);
        anyOf(q, mention);
        String sep = q.length() > 0 ? " " : "";
        if (f.onlyReplies.isChecked()) q.append(sep).append("filter:replies");
        else if (!f.replies.isChecked()) q.append(sep).append("-filter:replies");
        sep = q.length() > 0 ? " " : "";
        if (f.onlyLinks.isChecked()) q.append(sep).append("filter:links");
        else if (!f.links.isChecked()) q.append(sep).append("-filter:links");
        appendNumber(q, "min_replies:", f.minReplies);
        appendNumber(q, "min_faves:", f.minLikes);
        appendNumber(q, "min_retweets:", f.minReposts);
        String lang = LANGUAGES[Math.max(0, f.language.getSelectedItemPosition())];
        if (!lang.isEmpty()) q.append(q.length() > 0 ? " " : "").append("lang:").append(lang);
        if (f.until != null) q.append(q.length() > 0 ? " " : "").append("until:").append(f.until);
        if (f.since != null) q.append(q.length() > 0 ? " " : "").append("since:").append(f.since);
        return q.toString().trim();
    }

    private static void appendNumber(StringBuilder q, String op, EditText e) {
        String s = e.getText().toString().trim();
        if (!s.isEmpty()) q.append(q.length() > 0 ? " " : "").append(op).append(s);
    }

    // endregion
}
