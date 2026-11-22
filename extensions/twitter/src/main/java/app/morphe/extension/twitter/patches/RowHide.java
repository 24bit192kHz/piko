/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.HashSet;
import java.util.Set;

/**
 * Hides timeline rows (direct children of X's lists) for one or more reasons: a blocked country, a
 * muted word, "Not interested". A row stays hidden while any reason holds and gets its height back
 * when the last one goes, so features never undo each other's hiding.
 *
 * <p>A hidden row keeps its place in the list with a height of 0 (the list still owns it); with
 * {@code animate} it first shrinks and fades out, so the rows below slide up instead of jumping.
 */
public final class RowHide {
    private static final int TAG_HEIGHT = 0x70696b20, TAG_REASONS = 0x70696b21, TAG_ANIMATOR = 0x70696b22;
    private static final long COLLAPSE_MS = 220;

    private RowHide() {
    }

    @SuppressWarnings("unchecked")
    private static Set<String> reasons(View row, boolean create) {
        Object tag = row.getTag(TAG_REASONS);
        if (tag instanceof Set) return (Set<String>) tag;
        if (!create) return null;
        Set<String> set = new HashSet<>(2);
        row.setTag(TAG_REASONS, set);
        return set;
    }

    public static boolean isHidden(View row) {
        Set<String> set = reasons(row, false);
        return set != null && !set.isEmpty();
    }

    public static boolean isHiddenBy(View row, String reason) {
        Set<String> set = reasons(row, false);
        return set != null && set.contains(reason);
    }

    /** Hides the row for {@code reason}; returns whether the row was shown until now. */
    public static boolean hide(View row, String reason, boolean animate) {
        Set<String> set = reasons(row, true);
        boolean wasShown = set.isEmpty();
        set.add(reason);
        if (!wasShown) return false;
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return false;
        row.setTag(TAG_HEIGHT, lp.height);
        int from = row.getHeight();
        if (animate && from > 0 && row.isAttachedToWindow() && row.isShown()) {
            ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
            anim.setDuration(COLLAPSE_MS);
            anim.setInterpolator(new DecelerateInterpolator(1.5f));
            lp.height = from;
            anim.addUpdateListener(a -> {
                float f = (Float) a.getAnimatedValue();
                ViewGroup.LayoutParams p = row.getLayoutParams();
                if (p == null) return;
                p.height = Math.max(0, Math.round(from * f));
                row.setAlpha(Math.min(1f, f * 1.6f));
                row.setLayoutParams(p);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    row.setTag(TAG_ANIMATOR, null);
                    if (!cancelled) collapse(row);
                }
            });
            row.setTag(TAG_ANIMATOR, anim);
            anim.start();
        } else {
            collapse(row);
        }
        return true;
    }

    private static void collapse(View row) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null || !isHidden(row)) return;
        lp.height = 0;
        row.setLayoutParams(lp);
        row.setVisibility(View.GONE);
        row.setAlpha(1f);
    }

    /** Drops {@code reason}; returns whether the row is shown again now. */
    public static boolean show(View row, String reason) {
        Set<String> set = reasons(row, false);
        if (set == null || !set.remove(reason) || !set.isEmpty()) return false;
        Object anim = row.getTag(TAG_ANIMATOR);
        if (anim instanceof ValueAnimator) ((ValueAnimator) anim).cancel();
        row.setTag(TAG_ANIMATOR, null);
        Object height = row.getTag(TAG_HEIGHT);
        row.setTag(TAG_HEIGHT, null);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null && height instanceof Integer) {
            lp.height = (Integer) height;
            row.setLayoutParams(lp);
        }
        row.setAlpha(1f);
        row.setVisibility(View.VISIBLE);
        return true;
    }
}
