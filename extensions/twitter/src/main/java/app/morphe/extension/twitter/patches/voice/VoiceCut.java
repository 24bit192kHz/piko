/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes the pauses from a voice note the way clearcut does: Silero VAD v6 at 16 kHz, then jumpcutter's
 * SpeechRegionPlanner (hysteresis 0.5 / 0.35, 75 ms kept around speech, gaps under 180 ms kept), and the kept
 * parts joined with short crossfades at the note's own sample rate. Pure Java, no Android classes.
 */
public final class VoiceCut {
    public static final float THRESHOLD = 0.5f, OFFSET_RATIO = 0.7f;
    public static final int MARGIN_MS = 75, MIN_SILENCE_MS = 180, FPS = 100, CROSSFADE_MS = 8;

    private VoiceCut() {
    }

    /** A cut note: mono samples at the input rate, and the kept source ranges in seconds. */
    public static final class Result {
        public final float[] pcm;
        public final int sampleRate;
        public final double before, after;
        public final List<double[]> kept;
        /** Each kept part as {source start, source end, start in the cut audio}, seconds. */
        public final List<double[]> segments = new ArrayList<>();

        Result(float[] pcm, int sampleRate, double before, List<double[]> kept) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.before = before;
            this.after = pcm.length / (double) sampleRate;
            this.kept = kept;
        }
    }

    public static Result cut(SileroVad vad, float[] pcm, int sampleRate) {
        float[] pcm16k = sampleRate == 16000 ? pcm : resample(pcm, sampleRate, 16000);
        float[] probs = vad.probabilities(pcm16k);
        double duration = pcm.length / (double) sampleRate;
        return cut(pcm, sampleRate, keep(plan(probs, duration), duration));
    }

    /**
     * The kept parts of {@code audio} (e.g. the same note without its background noise, as desktop clearcut lays the
     * denoised audio on the cut it planned from the original).
     */
    public static Result cut(float[] audio, int sampleRate, List<double[]> keep) {
        List<double[]> segments = new ArrayList<>();
        Result r = new Result(splice(audio, sampleRate, keep, segments), sampleRate, audio.length / (double) sampleRate, keep);
        r.segments.addAll(segments);
        return r;
    }

    // region planner (jumpcutter SpeechRegionPlanner, as clearcut-web's planSilences)

    /** Pauses to remove, [start, end] in seconds. */
    public static List<double[]> plan(float[] probs, double duration) {
        int frames = Math.max(1, (int) Math.round(duration * FPS));
        float onset = THRESHOLD, offset = onset * OFFSET_RATIO;
        boolean[] mask = new boolean[frames];
        boolean speaking = false;
        for (int i = 0; i < frames; i++) {
            int a = Math.min(probs.length, (int) Math.floor(i / (double) FPS * 16000 / 512));
            int b = Math.min(probs.length, Math.max(a + 1, (int) Math.floor((i + 1) / (double) FPS * 16000 / 512)));
            float p = 0f;
            for (int k = a; k < b; k++) if (probs[k] > p) p = probs[k];
            speaking = speaking ? p >= offset : p >= onset;
            mask[i] = speaking;
        }
        int margin = Math.max(0, Math.round(MARGIN_MS * FPS / 1000f));
        boolean[] padded = mask.clone();
        if (margin > 0) {
            for (int i = 0; i < frames; i++) {
                if (!mask[i]) continue;
                for (int j = Math.max(0, i - margin), e = Math.min(frames, i + margin + 1); j < e; j++) padded[j] = true;
            }
        }
        int minSil = Math.max(1, Math.round(MIN_SILENCE_MS * FPS / 1000f));
        for (int i = 0; i < frames; ) {            // bridge only short internal gaps
            if (padded[i]) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < frames && !padded[j]) j++;
            if (i > 0 && j < frames && j - i < minSil) for (int k = i; k < j; k++) padded[k] = true;
            i = j;
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < frames; ) {
            if (padded[i]) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < frames && !padded[j]) j++;
            double s = i / (double) FPS, e = Math.min(duration, j / (double) FPS);
            if (e - s > 0.01) out.add(new double[]{s, e});
            i = j;
        }
        return out;
    }

    /** What stays, [start, end] in seconds, from the pauses removed. */
    public static List<double[]> keep(List<double[]> cuts, double duration) {
        List<double[]> keep = new ArrayList<>();
        double t = 0;
        for (double[] c : cuts) {
            if (c[0] - t > 0.02) keep.add(new double[]{t, c[0]});
            t = Math.max(t, c[1]);
        }
        if (duration - t > 0.02) keep.add(new double[]{t, duration});
        return keep;
    }

    // endregion

    /** The kept parts back to back, each join a short linear crossfade (no clicks). */
    public static float[] splice(float[] pcm, int sr, List<double[]> keep) {
        return splice(pcm, sr, keep, null);
    }

    /** As {@link #splice(float[], int, List)}, also listing where each part starts in the result. */
    public static float[] splice(float[] pcm, int sr, List<double[]> keep, List<double[]> segments) {
        int fade = Math.max(1, sr * CROSSFADE_MS / 1000);
        List<int[]> parts = new ArrayList<>();
        int total = 0;
        for (double[] k : keep) {
            int a = Math.max(0, (int) Math.round(k[0] * sr)), b = Math.min(pcm.length, (int) Math.round(k[1] * sr));
            if (b - a < 2 * fade) continue;
            parts.add(new int[]{a, b});
            total += b - a;
        }
        if (parts.isEmpty()) return new float[0];
        total -= (parts.size() - 1) * fade;
        float[] out = new float[total];
        int o = 0;
        for (int p = 0; p < parts.size(); p++) {
            int a = parts.get(p)[0], b = parts.get(p)[1];
            if (p == 0) {
                System.arraycopy(pcm, a, out, 0, b - a);
                o = b - a;
                if (segments != null) segments.add(new double[]{a / (double) sr, b / (double) sr, 0});
                continue;
            }
            if (segments != null) segments.add(new double[]{a / (double) sr, b / (double) sr, (o - fade) / (double) sr});
            // Overlap this part's first `fade` samples with the previous part's last `fade` samples.
            int start = o - fade;
            for (int i = 0; i < fade; i++) {
                float w = (i + 0.5f) / fade;
                out[start + i] = out[start + i] * (1f - w) + pcm[a + i] * w;
            }
            System.arraycopy(pcm, a + fade, out, o, b - a - fade);
            o += b - a - fade;
        }
        return out;
    }

    // region resampling (torchaudio.functional.resample, sinc_interp_hann, lowpass_filter_width 6, rolloff 0.99)

    public static float[] resample(float[] x, int orig, int target) {
        if (orig == target) return x;
        int g = gcd(orig, target);
        int o = orig / g, n = target / g;
        int lpw = 6;
        double base = Math.min(o, n) * 0.99;
        int width = (int) Math.ceil(lpw * o / base);
        int klen = 2 * width + o;
        float[] kernels = new float[n * klen];
        double scale = base / o;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < klen; i++) {
                double idx = (i - width) / (double) o;
                double t = (-j / (double) n + idx) * base;
                t = Math.max(-lpw, Math.min(lpw, t));
                double window = Math.cos(t * Math.PI / lpw / 2);
                window *= window;
                t *= Math.PI;
                double k = t == 0 ? 1.0 : Math.sin(t) / t;
                kernels[j * klen + i] = (float) (k * window * scale);
            }
        }
        int len = x.length;
        // waveform padded by (width, width + orig), conv1d stride orig, then interleave the n phases.
        int padLen = len + 2 * width + o;
        float[] pad = new float[padLen];
        System.arraycopy(x, 0, pad, width, len);
        int frames = (padLen - klen) / o + 1;
        int outLen = (int) Math.ceil(n * (long) len / (double) o);
        // Only taps with |t| < 6 zero crossings are non-zero (the window is 0 beyond): ~17 of 459 at 22050 Hz.
        int[] from = new int[n], to = new int[n];
        for (int j = 0; j < n; j++) {
            int kb = j * klen, a = 0, b = klen;
            while (a < b && kernels[kb + a] == 0f) a++;
            while (b > a && kernels[kb + b - 1] == 0f) b--;
            from[j] = a;
            to[j] = b;
        }
        float[] out = new float[outLen];
        for (int f = 0; f < frames; f++) {
            int base0 = f * o;
            for (int j = 0; j < n; j++) {
                int idx = f * n + j;
                if (idx >= outLen) break;
                float s = 0f;
                int kb = j * klen;
                for (int i = from[j], e = to[j]; i < e; i++) s += kernels[kb + i] * pad[base0 + i];
                out[idx] = s;
            }
        }
        return out;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    // endregion
}
