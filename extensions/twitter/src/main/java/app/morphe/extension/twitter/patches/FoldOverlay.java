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
    private static final long MAX_WAIT_MS = 1200;

    private final ViewGroup list;
    private final Bitmap post, below;
    private final int top, bottom, background;
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
            Bitmap post = shot;
            Bitmap below = postBottom < h ? shot : null;
            FoldOverlay overlay = new FoldOverlay(list, post, below, postTop, postBottom, backgroundOf(list));
            overlay.setBounds(0, 0, w, h);
            overlay.aboveTops = overlay.tops();
            list.getOverlay().add(overlay);
            overlay.animate();
            return overlay;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
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
                removeWhenSettled();
            }
        });
        anim.start();
    }

    /** X's rows in the post's place are hidden now: the real list looks like the end of the fold. */
    void settled() {
        settled = true;
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
            remove();
            return;
        }
        stillFrames = layout.equals(lastLayout) ? stillFrames + 1 : 0;
        lastLayout = layout;
        boolean waited = SystemClock.uptimeMillis() - foldedAt >= MAX_WAIT_MS;
        if ((settled && stillFrames >= STILL_FRAMES) || waited) {
            remove();
            return;
        }
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
        float belowTop = bottom - progress * (bottom - top);
        paint.setColor(background);
        paint.setAlpha(Math.round(255 * fade));
        canvas.drawRect(0, top, w, h, paint);
        int postAlpha = Math.round(255 * fade * Math.max(0f, 1f - progress * 2.5f));
        int w0 = post.getWidth(), postH = bottom - top;
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
