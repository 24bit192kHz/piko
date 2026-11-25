/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.entity.Tweet;

/**
 * A deleted post's page as Twitter used to show it: the post as it was (picture, name, handle, text, time) with
 * "This Tweet has been deleted." under it, the screenshot the "This Tweet has been deleted" meme is made of. Drawn in
 * the page's own font and colours, with the line in the grey box X uses for a quote it can't show.
 */
final class DeletedPostCard {
    private static final int TAG_CARD = 0x70696b51;

    private DeletedPostCard() {
    }

    private static int id(Context c, String name) {
        return c.getResources().getIdentifier(name, "id", c.getPackageName());
    }

    private static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Pages that show a deleted post (marked at once: X may report its list's failed load before the card is drawn). */
    private static final java.util.Map<Activity, Boolean> pages = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static void mark(Activity a) {
        if (a != null) pages.put(a, Boolean.TRUE);
    }

    static boolean shown(Activity a) {
        return a != null && pages.containsKey(a);
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** The author's picture URL: the string of the post's author (X 12.19.1: post.a.F) that is one. */
    private static String avatarUrl(Object post) {
        try {
            Object author = field(field(post, "a"), "F");
            if (author == null) return null;
            for (Field f : author.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(author);
                if (v instanceof String && ((String) v).contains("/profile_images/")) {
                    return ((String) v).replace("_normal.", "_x96.");
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static long createdAt(Object post) {
        try {
            Object v = field(field(post, "a"), "m");
            return v instanceof Long ? (Long) v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The page's look, taken from its own toolbar title (font, text colour) and theme (background). */
    private static final class Look {
        Typeface typeface = Typeface.DEFAULT;
        int text = Color.WHITE, secondary = 0xFF71767B, background = Color.BLACK, line, box;

        Look(Activity a) {
            View toolbar = a.findViewById(id(a, "toolbar"));
            TextView title = toolbar instanceof ViewGroup ? firstText((ViewGroup) toolbar) : null;
            if (title != null) {
                if (title.getTypeface() != null) typeface = title.getTypeface();
                text = title.getCurrentTextColor();
            }
            background = pageBackground(a, toolbar);
            boolean dark = luminance(background) < 0.5;
            // X's own greys: secondary text, separators, and the box of a quote it can't show.
            secondary = dark ? 0xFF71767B : 0xFF536471;
            line = dark ? 0xFF2F3336 : 0xFFEFF3F4;
            box = dark ? 0xFF16181C : 0xFFF7F9F9;
        }

        /** The colour X paints behind the page (its theme's "colorBackground" is not the one it uses). */
        static int pageBackground(Activity a, View from) {
            for (View v = from; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                Drawable d = v.getBackground();
                if (d instanceof ColorDrawable && Color.alpha(((ColorDrawable) d).getColor()) == 255) return ((ColorDrawable) d).getColor();
            }
            Drawable w = a.getWindow().getDecorView().getBackground();
            if (w instanceof ColorDrawable) return ((ColorDrawable) w).getColor();
            TypedArray t = a.getTheme().obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
            int c = t.getColor(0, Color.BLACK);
            t.recycle();
            return c;
        }

        private static double luminance(int c) {
            return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
        }

        private static TextView firstText(ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (v instanceof TextView && ((TextView) v).getText().length() > 0) return (TextView) v;
                if (v instanceof ViewGroup) {
                    TextView t = firstText((ViewGroup) v);
                    if (t != null) return t;
                }
            }
            return null;
        }
    }

    private static TextView text(Context c, Look look, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(look.typeface, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * Shows the post as it was, with the line under it, over the page's (empty) list and X's message. Returns false
     * when it could not (the page then shows the line alone).
     */
    static boolean show(Activity a, Object post, long postId) {
        if (a == null) return false;
        Bitmap snapshot = postId > 0 ? SNAPSHOTS.get(postId) : null;
        if (snapshot == null && post == null) return false;
        try {
            View rootView = a.findViewById(id(a, "root_layout"));
            View toolbar = a.findViewById(id(a, "toolbar"));
            if (!(rootView instanceof RelativeLayout) || toolbar == null) return false;
            RelativeLayout root = (RelativeLayout) rootView;
            if (snapshot != null) {
                mark(a);
                root.post(() -> buildSnapshot(a, root, toolbar, snapshot));
                return true;
            }
            Tweet tweet = new Tweet(post);
            String name = tweet.getTweetProfileName();
            String handle = tweet.getTweetUsername();
            String body = tweet.getText();
            if (handle == null) return false;
            mark(a);
            long created = createdAt(post);
            String avatar = avatarUrl(post);
            root.post(() -> build(a, root, toolbar, name, handle, body, created, avatar));
            return true;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return false;
        }
    }

    // region snapshots

    /** X's own drawing of the posts pages showed last (header to action buttons), by post id. */
    private static final java.util.Map<Long, Bitmap> SNAPSHOTS = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Long, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Bitmap> eldest) {
            return size() > 6;
        }
    });
    /** The post each open post page shows. */
    private static final java.util.Map<Activity, Long> pagePosts = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static boolean tracking;

    /** A post page shows {@code postId}: X's drawing of it is kept once it is laid out, and again when the page is left. */
    static void showing(Activity a, long postId) {
        if (a == null || postId <= 0) return;
        pagePosts.put(a, postId);
        track(a);
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> capture(a, postId), 1500);
    }

    private static synchronized void track(Activity a) {
        if (tracking) return;
        tracking = true;
        a.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            public void onActivityPaused(Activity p) {
                Long id = pagePosts.get(p);
                if (id != null && !shown(p)) capture(p, id);
            }

            public void onActivityCreated(Activity p, android.os.Bundle b) {
            }

            public void onActivityStarted(Activity p) {
            }

            public void onActivityResumed(Activity p) {
            }

            public void onActivityStopped(Activity p) {
            }

            public void onActivitySaveInstanceState(Activity p, android.os.Bundle b) {
            }

            public void onActivityDestroyed(Activity p) {
                pagePosts.remove(p);
            }
        });
    }

    /** Draws the page's main post (the row with X's "focal" post views), from its header to its action buttons. */
    private static void capture(Activity a, long postId) {
        try {
            View container = a.findViewById(id(a, "fragment_container"));
            if (container == null) return;
            View focal = container.findViewById(id(a, "focal_tweet_social_context_badge_barrier"));
            View post = focal;
            int rowId = id(a, "row");
            while (post != null && post.getId() != rowId) post = post.getParent() instanceof View ? (View) post.getParent() : null;
            if (post == null || post.getWidth() <= 0 || post.getHeight() <= 0 || !post.isShown()) return;
            Bitmap b = Bitmap.createBitmap(post.getWidth(), post.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Look.pageBackground(a, a.findViewById(id(a, "toolbar"))));
            post.draw(c);
            SNAPSHOTS.put(postId, b);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** The post as X drew it, and the line under its buttons, as Twitter showed a post deleted while you looked at it. */
    private static void buildSnapshot(Activity a, RelativeLayout root, View toolbar, Bitmap snapshot) {
        try {
            View old = root.findViewWithTag(TAG_CARD);
            if (old != null) root.removeView(old);
            Look look = new Look(a);
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            ImageView post = new ImageView(a);
            post.setImageBitmap(snapshot);
            post.setAdjustViewBounds(true);
            post.setScaleType(ImageView.ScaleType.FIT_START);
            card.addView(post, new LinearLayout.LayoutParams(-1, -2));
            card.addView(deletedLine(a, look), lineParams(a));
            addCard(a, root, toolbar, card, look);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    // endregion

    /** "This Tweet has been deleted." centred in bold under the post, as Twitter drew it. */
    private static TextView deletedLine(Activity a, Look look) {
        TextView line = text(a, look, DeletedQuotes.line(a), 17, look.text, true);
        line.setGravity(Gravity.CENTER);
        return line;
    }

    private static LinearLayout.LayoutParams lineParams(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(dp(a, 28));
        lp.bottomMargin = Math.round(dp(a, 28));
        return lp;
    }

    private static void addCard(Activity a, RelativeLayout root, View toolbar, LinearLayout card, Look look) {
        ScrollView scroll = new ScrollView(a);
        scroll.setTag(TAG_CARD);
        scroll.setBackground(new ColorDrawable(look.background));
        scroll.setClickable(true); // nothing underneath reacts to touches
        scroll.addView(card, new FrameLayout.LayoutParams(-1, -2));
        RelativeLayout.LayoutParams rp = new RelativeLayout.LayoutParams(-1, -1);
        rp.addRule(RelativeLayout.BELOW, toolbar.getId());
        root.addView(scroll, rp);
    }

    private static void build(Activity a, RelativeLayout root, View toolbar, String name, String handle, String body,
                              long created, String avatar) {
        try {
            View old = root.findViewWithTag(TAG_CARD);
            if (old != null) root.removeView(old);
            Look look = new Look(a);
            int pad = Math.round(dp(a, 16)), gap = Math.round(dp(a, 12));

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, gap, pad, gap);

            // Picture, name and handle, as on a post's page.
            LinearLayout header = new LinearLayout(a);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            int size = Math.round(dp(a, 40));
            ImageView picture = new ImageView(a);
            GradientDrawable placeholder = new GradientDrawable();
            placeholder.setShape(GradientDrawable.OVAL);
            placeholder.setColor(look.line);
            picture.setImageDrawable(placeholder);
            header.addView(picture, new LinearLayout.LayoutParams(size, size));
            LinearLayout names = new LinearLayout(a);
            names.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = text(a, look, name == null ? handle : name, 15, look.text, true);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(nameView);
            TextView handleView = text(a, look, "@" + handle, 15, look.secondary, false);
            handleView.setSingleLine(true);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
            hp.topMargin = Math.round(dp(a, 4));
            names.addView(handleView, hp);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
            np.setMarginStart(gap);
            header.addView(names, np);
            header.setOnClickListener(v -> {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("twitter://user?screen_name=" + handle)).setPackage(a.getPackageName()));
                } catch (Throwable ignored) {
                }
            });
            card.addView(header);

            if (body != null && !body.isEmpty()) {
                TextView bodyView = text(a, look, body, 17, look.text, false);
                bodyView.setLineSpacing(0, 1.2f);
                bodyView.setTextIsSelectable(true);
                bodyView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
                bp.topMargin = gap;
                card.addView(bodyView, bp);
            }

            if (created > 0) {
                Date d = new Date(created);
                String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(d) + " · "
                        + new SimpleDateFormat("d MMM yy", Locale.getDefault()).format(d);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
                tp.topMargin = gap;
                card.addView(text(a, look, time, 15, look.secondary, false), tp);
            }

            View separator = new View(a);
            separator.setBackgroundColor(look.line);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(dp(a, 0.5f))));
            sp.topMargin = gap;
            card.addView(separator, sp);

            card.addView(deletedLine(a, look), lineParams(a));
            addCard(a, root, toolbar, card, look);

            if (avatar != null) loadPicture(picture, avatar);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static void loadPicture(ImageView view, String url) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
                if (b == null) return;
                Drawable round = new Circle(b);
                new Handler(Looper.getMainLooper()).post(() -> view.setImageDrawable(round));
            } catch (Throwable ignored) {
            }
        }, "piko-deleted-avatar").start();
    }

    /** A picture cropped to a circle, as X shows profile pictures. */
    private static final class Circle extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Bitmap bitmap;

        Circle(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect r = getBounds();
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(r.width() / (float) bitmap.getWidth(), r.height() / (float) bitmap.getHeight());
            m.postTranslate(r.left, r.top);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            canvas.drawCircle(r.exactCenterX(), r.exactCenterY(), Math.min(r.width(), r.height()) / 2f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
