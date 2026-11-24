/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.content.Context;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.util.WeakHashMap;

import app.morphe.extension.crimera.PikoUtils;

/**
 * X limits a tall video's height in the timeline to a share of the width the video has. A box bound
 * before it was ever laid out has no width yet, so X waits for its first layout to set the limit:
 * that layout (and the frame drawn from it) has the video at its full height, up to 1.8 times
 * taller, and the next one shrinks it.
 *
 * <p>Here X's limit is set at once, from the width the video has on this screen (the box's parent:
 * the box itself gets narrower once a tall video is limited, so its own width is not the one to
 * use). X's waiting call is taken over: when the box is first laid out, the limit is set again only
 * if that width turned out different, and a frame laid out without the limit is not drawn.
 */
@SuppressWarnings("unused")
public final class VerticalVideoSize {
    /** Per screen: the width its videos have. */
    private static final WeakHashMap<Context, Integer> widths = new WeakHashMap<>();
    private static View stand;

    private VerticalVideoSize() {
    }

    private static int room(View box) {
        ViewParent p = box.getParent();
        if (!(p instanceof View)) return 0;
        View parent = (View) p;
        return parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
    }

    /** X's own limit for a video {@code width} wide: its listener computes it from the view it is given. */
    private static void limit(View box, View.OnLayoutChangeListener cap, int width) {
        if (stand == null) stand = new View(box.getContext().getApplicationContext());
        stand.layout(0, 0, width, 1);
        cap.onLayoutChange(stand, 0, 0, width, 1, 0, 0, 0, 0);
    }

    /** Injection point: X added {@code cap} to {@code box}, to set the limit at the box's first layout. */
    public static void capEarly(View box, View.OnLayoutChangeListener cap) {
        try {
            if (box == null || cap == null) return;
            box.removeOnLayoutChangeListener(cap);
            Context screen = box.getContext();
            int known = room(box);
            if (known <= 0) {
                Integer w = widths.get(screen);
                known = w == null ? 0 : w;
            }
            if (known > 0) limit(box, cap, known);
            final int applied = known;
            box.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    int width = room(v);
                    if (width <= 0) return;
                    v.removeOnLayoutChangeListener(this);
                    widths.put(screen, width);
                    if (width == applied) return;
                    limit(v, cap, width);
                    // This layout had the wrong limit: the frame is skipped, the next one is laid out with it.
                    ViewTreeObserver.OnPreDrawListener[] skip = new ViewTreeObserver.OnPreDrawListener[1];
                    skip[0] = () -> {
                        ViewTreeObserver o = v.getViewTreeObserver();
                        if (o.isAlive()) o.removeOnPreDrawListener(skip[0]);
                        return !v.isLayoutRequested();
                    };
                    v.getViewTreeObserver().addOnPreDrawListener(skip[0]);
                }
            });
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }
}
