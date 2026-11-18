/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.country;

/** Off in releases: counts calls and time of the per-post hooks for the audit, logged every 50 calls. */
public final class Perf {
    public static final boolean ON = false;
    private static final String[] NAMES = {"header", "applyRow", "menuState", "actionBar"};
    private static final long[] calls = new long[NAMES.length], nanos = new long[NAMES.length];

    private Perf() {
    }

    public static long start() {
        return ON ? System.nanoTime() : 0;
    }

    public static void end(int hook, long start) {
        if (!ON) return;
        long dt = System.nanoTime() - start;
        synchronized (calls) {
            calls[hook]++;
            nanos[hook] += dt;
            if (calls[hook] % 50 == 0) {
                android.util.Log.d("PikoPerf", NAMES[hook] + ": " + calls[hook] + " calls, avg " + (nanos[hook] / calls[hook] / 1000.0) + " us");
            }
        }
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        synchronized (calls) {
            for (int i = 0; i < NAMES.length; i++) {
                if (calls[i] > 0) sb.append(NAMES[i]).append(' ').append(calls[i]).append(" calls avg ").append(nanos[i] / calls[i] / 1000.0).append(" us; ");
            }
        }
        return sb.toString();
    }
}
