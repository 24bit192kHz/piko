/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    /** The search tab's box ("Search Twitter") is in the top bar, on screen. */
    private static boolean searchBoxShown(View view) {
        int id = id(view.getContext(), "id", "query_view");
        if (id == 0) return false;
        View box = view.getRootView().findViewById(id);
        return box != null && box.isShown() && box.getGlobalVisibleRect(RECT_BOX);
    }

    private static final android.graphics.Rect RECT_BOX = new android.graphics.Rect();

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
        // Only on the search tab: a page of its tab pager (Explore, Trending…) inside the bottom
        // tabs, or (accounts without those tabs) while the search box shows in the top bar.
        if (!onSearchTab(empty) && !searchBoxShown(empty)) {
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
        int language;
        /** 0 include, 1 exclude, 2 only. */
        int replies, links;
        String since, until;
    }

    private static final int ACCENT = 0xFF1D9BF0;

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static GradientDrawable box(Context c, int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(dp(c, 1.5f), stroke);
        return d;
    }

    /** The filled box every input uses (like the search box above it), outlined in blue while focused. */
    private static StateListDrawable inputBackground(Context c, int textColor) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, box(c, alpha(textColor, 0x1C), ACCENT, 8));
        s.addState(new int[]{android.R.attr.state_pressed}, box(c, alpha(textColor, 0x2C), 0, 8));
        s.addState(new int[0], box(c, alpha(textColor, 0x1C), 0, 8));
        return s;
    }

    private static View build(Context c, int textColor) {
        Fields f = new Fields();
        int pad = dp(c, 16);

        // Title and Search stay at the top while the form scrolls (the compose button covers the bottom).
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag(FORM_TAG);
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, dp(c, 8), pad, dp(c, 8));
        TextView title = text(c, "Advanced search", 20, true, textColor);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView search = text(c, "Search", 15, true, 0xFFFFFFFF);
        search.setGravity(Gravity.CENTER);
        search.setMinHeight(dp(c, 40));
        search.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        search.setBackground(box(c, ACCENT, 0, 20));
        search.setFocusable(true);
        header.addView(search);
        root.addView(header);
        View rule = new View(c);
        rule.setBackgroundColor(alpha(textColor, 0x26));
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f))));

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        // Room at the end for the compose button and the bottom tabs (the page reaches under them).
        col.setPadding(pad, 0, pad, dp(c, 240));
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        section(col, "Words", textColor);
        f.all = field(col, "All of these words", "what's happening", textColor);
        f.exact = field(col, "This exact phrase", "happy hour", textColor);
        f.any = field(col, "Any of these words", "cats dogs", textColor);
        f.none = field(col, "None of these words", "cats dogs", textColor);
        f.hashtags = field(col, "These hashtags", "#ThrowbackThursday", textColor);
        String[] names = new String[LANGUAGES.length];
        for (int i = 0; i < LANGUAGES.length; i++) {
            String code = LANGUAGES[i];
            names[i] = code.isEmpty() ? "Any language" : new Locale(code).getDisplayLanguage(Locale.ENGLISH);
        }
        TextView language = chooser(col, "Language", names[0], textColor);
        language.setOnClickListener(v -> new AlertDialog.Builder(c)
                .setTitle("Language")
                .setSingleChoiceItems(names, f.language, (d, which) -> {
                    f.language = which;
                    language.setText(names[which]);
                    d.dismiss();
                })
                .show());

        section(col, "Accounts", textColor);
        f.from = field(col, "From these accounts", "@username", textColor);
        f.to = field(col, "In reply to these accounts", "@username", textColor);
        f.mentioning = field(col, "Mentioning these accounts", "@one @another", textColor);

        section(col, "Filters", textColor);
        segments(col, "Replies", textColor, i -> f.replies = i);
        segments(col, "Links", textColor, i -> f.links = i);

        section(col, "Engagement", textColor);
        LinearLayout counts = row(col);
        f.minReplies = number(counts, "Min. replies", textColor);
        f.minLikes = number(counts, "Min. likes", textColor);
        f.minReposts = number(counts, "Min. reposts", textColor);

        section(col, "Dates", textColor);
        LinearLayout dates = row(col);
        TextView since = chooser(dates, "From", "Any date", textColor);
        TextView until = chooser(dates, "To", "Any date", textColor);
        since.setOnClickListener(v -> pickDate(c, f.since, d -> {
            f.since = d;
            since.setText(d == null ? "Any date" : d);
        }));
        until.setOnClickListener(v -> pickDate(c, f.until, d -> {
            f.until = d;
            until.setText(d == null ? "Any date" : d);
        }));

        Runnable run = () -> {
            String q = query(f);
            if (q.isEmpty()) {
                app.morphe.extension.shared.Utils.showToastShort("Fill in at least one field to search");
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
        return root;
    }

    private static TextView text(Context c, String s, float sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private static void section(LinearLayout col, String s, int color) {
        Context c = col.getContext();
        TextView t = text(c, s, 17, true, color);
        t.setPadding(0, dp(c, 24), 0, 0);
        col.addView(t);
    }

    /** Several inputs side by side, sharing the width. */
    private static LinearLayout row(LinearLayout col) {
        LinearLayout r = new LinearLayout(col.getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** A label above its input (it stays visible while typing); in a row, the cells share the width. */
    private static LinearLayout cell(LinearLayout parent, String label, int color) {
        Context c = parent.getContext();
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(c, label, 13, false, alpha(color, 0xB0));
        l.setPadding(0, dp(c, 12), 0, dp(c, 6));
        cell.addView(l);
        LinearLayout.LayoutParams lp;
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (parent.getChildCount() > 0) lp.setMarginStart(dp(c, 8));
        } else {
            lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        parent.addView(cell, lp);
        return cell;
    }

    private static void styleInput(TextView t, int color) {
        Context c = t.getContext();
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(color);
        t.setHintTextColor(alpha(color, 0x70));
        t.setBackground(inputBackground(c, color));
        t.setMinHeight(dp(c, 48));
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.setPadding(dp(c, 12), 0, dp(c, 12), 0);
    }

    private static EditText field(LinearLayout parent, String label, String hint, int color) {
        LinearLayout cell = cell(parent, label, color);
        EditText e = new EditText(parent.getContext());
        e.setHint(hint);
        e.setSingleLine(true);
        styleInput(e, color);
        cell.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private static EditText number(LinearLayout parent, String label, int color) {
        EditText e = field(parent, label, "0", color);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    /** A box that opens a picker; it reads like the inputs, with an arrow. */
    private static TextView chooser(LinearLayout parent, String label, String value, int color) {
        LinearLayout cell = cell(parent, label, color);
        TextView t = new TextView(parent.getContext());
        t.setText(value);
        t.setSingleLine(true);
        styleInput(t, color);
        t.setFocusable(true);
        t.setClickable(true);
        android.graphics.drawable.Drawable arrow = t.getContext().getDrawable(android.R.drawable.arrow_down_float);
        if (arrow != null) {
            arrow = arrow.mutate();
            arrow.setTint(alpha(color, 0xB0));
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null);
        }
        cell.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private interface Choice {
        void set(int index);
    }

    /** Include / Exclude / Only, as one segmented control (the web form's two checkboxes). */
    private static void segments(LinearLayout col, String label, int color, Choice choice) {
        Context c = col.getContext();
        LinearLayout cell = cell(col, label, color);
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(box(c, alpha(color, 0x1C), 0, 8));
        int inset = dp(c, 3);
        bar.setPadding(inset, inset, inset, inset);
        String[] options = {"Include", "Exclude", "Only " + label.toLowerCase(Locale.ROOT)};
        TextView[] views = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            TextView t = text(c, options[i], 14, false, color);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(c, 42));
            t.setFocusable(true);
            t.setClickable(true);
            views[i] = t;
            int index = i;
            t.setOnClickListener(v -> {
                for (int k = 0; k < views.length; k++) select(views[k], k == index, color);
                choice.set(index);
            });
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            select(t, i == 0, color);
        }
        cell.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void select(TextView t, boolean on, int color) {
        t.setSelected(on);
        t.setBackground(on ? box(t.getContext(), ACCENT, 0, 6) : null);
        t.setTextColor(on ? 0xFFFFFFFF : alpha(color, 0xD0));
        t.setTypeface(Typeface.create(t.getTypeface(), on ? Typeface.BOLD : Typeface.NORMAL));
    }

    private interface DateResult {
        void set(String date);
    }

    /** Opens on the chosen date (or today); "Any date" clears it. */
    private static void pickDate(Context c, String current, DateResult result) {
        Calendar at = Calendar.getInstance();
        if (current != null) {
            try {
                String[] p = current.split("-");
                at.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            } catch (Throwable ignored) {
            }
        }
        DatePickerDialog d = new DatePickerDialog(c, (view, y, m, day) ->
                result.set(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, day)),
                at.get(Calendar.YEAR), at.get(Calendar.MONTH), at.get(Calendar.DAY_OF_MONTH));
        d.getDatePicker().setMaxDate(System.currentTimeMillis());
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
        if (f.replies == 2) q.append(sep).append("filter:replies");
        else if (f.replies == 1) q.append(sep).append("-filter:replies");
        sep = q.length() > 0 ? " " : "";
        if (f.links == 2) q.append(sep).append("filter:links");
        else if (f.links == 1) q.append(sep).append("-filter:links");
        appendNumber(q, "min_replies:", f.minReplies);
        appendNumber(q, "min_faves:", f.minLikes);
        appendNumber(q, "min_retweets:", f.minReposts);
        String lang = LANGUAGES[Math.max(0, Math.min(LANGUAGES.length - 1, f.language))];
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
