/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import app.morphe.extension.crimera.PikoUtils;

/**
 * A post folding away, drawn over the list: the post fades out while the posts below it slide up
 * into its place. X meanwhile changes the list underneath several times (it removes the post, puts
 * its feedback row there, then a loading row, then a "Thanks" row): the overlay is a picture of
 * the list taken at the tap, so none of that shows. It is removed once the list has settled.
 */
final class FoldOverlay extends Drawable {
    static final long FOLD_MS = 220;
    private static final long MAX_WAIT_MS = 700;

    private final ViewGroup list;
    private final Bitmap post, below;
    private final int top, bottom, background;
    /** Where the post's row began, also above the list's top: the rows below end up there. */
    private int rowTop;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float progress;
    private boolean folded, settled, removed;
    private long foldedAt;

    private FoldOverlay(ViewGroup list, Bitmap post, Bitmap below, int top, int bottom, int background) {
        this.list = list;
        this.post = post;
        this.below = below;
        this.top = top;
        this.bottom = bottom;
        this.background = background;
    }

    /** Starts folding {@code row} away; returns the overlay ({@link #settled()} lets it go), or null. */
    static FoldOverlay start(ViewGroup list, View row) {
        try {
            int w = list.getWidth(), h = list.getHeight();
            int top = row.getTop(), bottom = row.getBottom();
            if (w <= 0 || h <= 0 || bottom <= top || bottom <= 0 || top >= h) return null;
            int postTop = Math.max(0, top), postBottom = Math.min(h, bottom);
            // One picture of the list from the post down (drawn once): the post, then what is below it.
            Bitmap shot = Bitmap.createBitmap(w, h - postTop, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(shot);
            c.translate(0, -postTop);
            list.draw(c);
            // Playing videos are drawn by the GPU, not into a software picture: their current frames are copied in.
            for (android.graphics.Rect r : videosBelow(list, postTop + 1)) frameInto(list, c, r);
            Bitmap post = shot;
            Bitmap below = postBottom < h ? shot : null;
            FoldOverlay overlay = new FoldOverlay(list, post, below, postTop, postBottom, backgroundOf(list));
            overlay.setBounds(0, 0, w, h);
            overlay.videos = videosBelow(list, postBottom);
            overlay.rowTop = top;
            overlay.next = firstBelow(list, bottom);
            overlay.aboveTops = overlay.tops();
            list.getOverlay().add(overlay);
            overlay.animate();
            return overlay;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    /**
     * Where videos play below the post (relative to the list). A picture taken in software has a hole
     * where a video plays (its frames are drawn by the GPU, not into the picture): once the posts
     * below have slid up, the real video shows through there instead of an empty box.
     */
    private static java.util.List<android.graphics.Rect> videosBelow(ViewGroup list, int postBottom) {
        java.util.List<android.graphics.Rect> out = new java.util.ArrayList<>();
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            View row = list.getChildAt(i);
            if (row.getBottom() > postBottom && row.getVisibility() == View.VISIBLE) collectVideos(list, row, out);
        }
        return out;
    }

    private static void collectVideos(ViewGroup list, View view, java.util.List<android.graphics.Rect> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof android.view.TextureView || view instanceof android.view.SurfaceView) {
            android.graphics.Rect r = new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight());
            try {
                list.offsetDescendantRectToMyCoords(view, r);
                out.add(r);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) collectVideos(list, g.getChildAt(i), out);
        }
    }

    private java.util.List<android.graphics.Rect> videos;

    /** The current frame of the video (a TextureView) at {@code r} (list coordinates), drawn into the picture. */
    private static void frameInto(ViewGroup list, Canvas c, android.graphics.Rect r) {
        try {
            View v = videoAt(list, r);
            if (!(v instanceof android.view.TextureView)) return;
            Bitmap frame = ((android.view.TextureView) v).getBitmap(r.width(), r.height());
            if (frame == null) return;
            c.drawBitmap(frame, r.left, r.top, null);
            frame.recycle();
        } catch (Throwable ignored) {
        }
    }

    private static View videoAt(ViewGroup list, android.graphics.Rect r) {
        View[] found = new View[1];
        findVideo(list, list, r, found);
        return found[0];
    }

    private static void findVideo(ViewGroup list, View view, android.graphics.Rect r, View[] found) {
        if (found[0] != null || view.getVisibility() != View.VISIBLE) return;
        if (view instanceof android.view.TextureView) {
            android.graphics.Rect b = new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight());
            list.offsetDescendantRectToMyCoords(view, b);
            if (b.equals(r)) found[0] = view;
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0, n = g.getChildCount(); i < n; i++) findVideo(list, g.getChildAt(i), r, found);
        }
    }
    /** The row right below the post: once X has taken the post out, the picture follows where it really is. */
    private View next;

    private static View firstBelow(ViewGroup list, int postBottom) {
        View best = null;
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            View c = list.getChildAt(i);
            if (c.getTop() >= postBottom - 1 && c.getVisibility() == View.VISIBLE && (best == null || c.getTop() < best.getTop())) best = c;
        }
        return best;
    }

    /** Where the rows below the post end up: the next row's real place once X moved it, else where the post began. */
    private float target() {
        float guess = Math.min(top, rowTop);
        if (!settled || next == null || next.getParent() != list) return guess;
        float real = next.getTop() + next.getTranslationY();
        return real < bottom - 1 ? real : guess;
    }

    private static int backgroundOf(View list) {
        for (View v = list; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            Drawable bg = v.getBackground();
            if (bg instanceof ColorDrawable && (((ColorDrawable) bg).getColor() >>> 24) == 0xFF) return ((ColorDrawable) bg).getColor();
        }
        try {
            TypedArray a = list.getContext().obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
            int color = a.getColor(0, 0xFF000000);
            a.recycle();
            return color | 0xFF000000;
        } catch (Throwable t) {
            return 0xFF000000;
        }
    }

    private void animate() {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(FOLD_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            invalidateSelf();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                folded = true;
                foldedAt = SystemClock.uptimeMillis();
                invalidateSelf(); // videos below show through from now on
                removeWhenSettled();
            }
        });
        anim.start();
    }

    /** X's rows in the post's place are hidden now: the real list looks like the end of the fold. */
    void settled() {
        settled = true;
        invalidateSelf();
    }

    private static final int STILL_FRAMES = 8;
    private String lastLayout;
    private int stillFrames;

    /**
     * Removed once the list underneath has stopped changing (X's own row animations included)
     * for a few frames, so the picture and the list match when it goes; at once when the list
     * scrolls (a row above the post moved), so the picture never stands still under a finger.
     */
    private void removeWhenSettled() {
        if (removed) return;
        String layout = layoutOf();
        if (scrolled()) {
            if (DislikeButton.DEBUG) DislikeButton.debug("fold: removed (scrolled)");
            remove();
            return;
        }
        stillFrames = layout.equals(lastLayout) ? stillFrames + 1 : 0;
        lastLayout = layout;
        boolean waited = SystemClock.uptimeMillis() - foldedAt >= MAX_WAIT_MS;
        if ((settled && stillFrames >= STILL_FRAMES) || waited) {
            if (DislikeButton.DEBUG) DislikeButton.debug("fold: removed " + (SystemClock.uptimeMillis() - foldedAt)
                    + " ms after the fold, settled " + settled + ", still " + stillFrames + ", videos " + videos);
            remove();
            return;
        }
        invalidateSelf(); // the picture follows the real rows while X still moves them
        list.postOnAnimation(this::removeWhenSettled);
    }

    private int[] aboveTops;

    /** Where the rows above the post are: they only move when the list scrolls. */
    private int[] tops() {
        int n = list.getChildCount();
        int[] t = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            View c = list.getChildAt(i);
            if (c.getBottom() <= top) t[k++] = c.getTop() + Math.round(c.getTranslationY());
        }
        return java.util.Arrays.copyOf(t, k);
    }

    private boolean scrolled() {
        int[] now = tops();
        if (aboveTops == null) {
            aboveTops = now;
            return false;
        }
        return !java.util.Arrays.equals(now, aboveTops);
    }

    private String layoutOf() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            View c = list.getChildAt(i);
            sb.append(c.getTop() + Math.round(c.getTranslationY())).append(',').append(c.getHeight()).append(',')
                    .append(c.getVisibility()).append(';');
        }
        return sb.toString();
    }

    private float fade = 1f;

    private void remove() {
        if (removed) return;
        removed = true;
        ValueAnimator out = ValueAnimator.ofFloat(1f, 0f);
        out.setDuration(120);
        out.addUpdateListener(a -> {
            fade = (Float) a.getAnimatedValue();
            invalidateSelf();
        });
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                list.getOverlay().remove(FoldOverlay.this);
                post.recycle();
            }
        });
        out.start();
    }

    @Override
    public void draw(Canvas canvas) {
        if (post.isRecycled()) return;
        int h = getBounds().height(), w = getBounds().width();
        // The posts below slide up over the fading post. While the overlay fades out at the end,
        // the real list (laid out the same way by then) shows through.
        float target = target();
        float belowTop = bottom - progress * (bottom - target);
        int postAlpha = Math.round(255 * fade * Math.max(0f, 1f - progress * 2.5f));
        int w0 = post.getWidth(), postH = bottom - top;
        // The picture ends where the screen ended at the tap: under it (what slid into view from below)
        // the real list shows, already laid out without the post, instead of an empty area.
        // Until X has taken the post out of the list (its feedback row was caught) the real list still
        // shows it there: the area stays plain for those few frames.
        float end = !settled && !folded ? h : below != null ? belowTop + post.getHeight() - postH : (postAlpha > 0 ? bottom : top);
        canvas.save();
        if (folded && videos != null && android.os.Build.VERSION.SDK_INT >= 26) {
            for (android.graphics.Rect r : videos) {
                int shift = Math.round(bottom - target);
                canvas.clipOutRect(r.left, r.top - shift, r.right, r.bottom - shift);
            }
        }
        paint.setColor(background);
        paint.setAlpha(Math.round(255 * fade));
        canvas.drawRect(0, top, w, Math.min(h, end), paint);
        if (postAlpha > 0) {
            paint.setAlpha(postAlpha);
            src.set(0, 0, w0, postH);
            dst.set(0, top, w0, bottom);
            canvas.drawBitmap(post, src, dst, paint);
        }
        if (below != null) {
            paint.setAlpha(Math.round(255 * fade));
            src.set(0, postH, w0, post.getHeight());
            dst.set(0, belowTop, w0, belowTop + post.getHeight() - postH);
            canvas.drawBitmap(below, src, dst, paint);
        }
        canvas.restore();
    }

    private final android.graphics.Rect src = new android.graphics.Rect();
    private final android.graphics.RectF dst = new android.graphics.RectF();

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
