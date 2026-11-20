/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.voice;

import android.os.Build;

import java.util.Arrays;

import app.morphe.extension.crimera.PikoUtils;

/**
 * Background noise removal with DeepFilterNet3 (soniqo/DeepFilterNet3-ONNX, the same network desktop clearcut runs),
 * in the native library bundled in the APK: lib/arm64-v8a/libpikodf.so (libDF's DSP + ONNX Runtime's
 * libonnxruntime.so next to it).
 */
public final class DfNative {
    private static volatile Boolean loaded;

    private DfNative() {
    }

    private static native int ping();

    /** Resamples to 48 kHz and back natively (VoiceCut.resample's math, bit-identical). */
    private static native float[] nativeDenoise(float[] pcm, int rate, int budgetMs);

    private static native String lastInfo();

    private static synchronized boolean load() {
        if (loaded != null) return loaded;
        boolean ok = false;
        try {
            // On x86 devices (emulators, Chromebooks) this arm64 X runs through binary translation, where ONNX
            // Runtime crashes the process (seen on an Android 14 emulator): noise removal stays off there.
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis.length == 0 || !"arm64-v8a".equals(abis[0])) {
                PikoUtils.logger("voice note: noise removal off, translated arm64 on " + Arrays.toString(abis));
                loaded = false;
                return false;
            }
            System.loadLibrary("onnxruntime");
            System.loadLibrary("pikodf");
            ok = ping() == 42;
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        loaded = ok;
        return ok;
    }

    /**
     * The note without its background noise (same rate and length), or null if the library is missing, fails, or
     * would run past {@code budgetMs} (0 = no limit).
     */
    public static float[] denoise(float[] pcm, int rate, int budgetMs) {
        if (pcm.length == 0 || !load()) return null;
        try {
            float[] y = nativeDenoise(pcm, rate, Math.max(0, budgetMs));
            PikoUtils.logger("voice note: " + (y == null ? "noise removal skipped: " : "noise removed, ") + lastInfo());
            return y != null && y.length == pcm.length ? y : null;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }
}
