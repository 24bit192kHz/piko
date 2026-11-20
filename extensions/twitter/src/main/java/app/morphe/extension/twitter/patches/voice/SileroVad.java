/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Silero VAD v6 (https://github.com/snakers4/silero-vad, MIT License, Copyright (c) 2020-present Silero Team),
 * 16 kHz model of silero-vad 6.2.3, run in plain Java: no ONNX runtime, about 0.42M multiply-adds per 32 ms.
 */

package app.morphe.extension.twitter.patches.voice;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Speech probability per 512 samples of 16 kHz audio, like silero-vad's streaming model (64-sample context). */
public final class SileroVad {
    private static final int WINDOW = 512, CONTEXT = 64, INPUT = WINDOW + CONTEXT, PADDED = INPUT + 64;
    private static final int FILTER = 256, HOP = 128, BINS = FILTER / 2 + 1, FRAMES = 4, HIDDEN = 128;

    private final float[] w0, b0, w1, b1, w2, b2, w3, b3;
    private final float[] wih, whh, bih, bhh;    // LSTMCell [512][128]
    private final float[] wdec;
    private final float bdec;

    private static volatile SileroVad shared;

    private SileroVad(float[][] t) {
        w0 = t[1]; b0 = t[2]; w1 = t[3]; b1 = t[4]; w2 = t[5]; b2 = t[6]; w3 = t[7]; b3 = t[8];
        wih = t[9]; whh = t[10]; bih = t[11]; bhh = t[12];
        wdec = t[13]; bdec = t[14][0];
    }

    /** The model from its weight blob (written by export_weights.py). */
    public static SileroVad load(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] magic = new byte[8];
        d.readFully(magic);
        if (magic[0] != 'S' || magic[4] != '6') throw new IOException("not a Silero v6 weight file");
        float[][] t = new float[15][];
        byte[] four = new byte[4];
        for (int i = 0; i < t.length; i++) {
            d.readFully(four);
            int n = ByteBuffer.wrap(four).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] raw = new byte[n * 4];
            d.readFully(raw);
            t[i] = new float[n];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(t[i]);
        }
        return new SileroVad(t);
    }

    public static SileroVad shared(java.util.concurrent.Callable<InputStream> source) throws Exception {
        SileroVad v = shared;
        if (v == null) {
            synchronized (SileroVad.class) {
                v = shared;
                if (v == null) {
                    try (InputStream in = source.call()) {
                        shared = v = load(in);
                    }
                }
            }
        }
        return v;
    }

    /** One probability per 512 samples (the last window zero-padded), state carried across windows. */
    public float[] probabilities(float[] pcm16k) {
        int n = (pcm16k.length + WINDOW - 1) / WINDOW;
        float[] out = new float[n];
        float[] x = new float[PADDED];
        float[] mag = new float[BINS * FRAMES];
        float[] e0 = new float[128 * 4], e1 = new float[64 * 2], e2 = new float[64], e3 = new float[128];
        float[] h = new float[HIDDEN], c = new float[HIDDEN], gates = new float[4 * HIDDEN];
        double[] re = new double[FILTER], im = new double[FILTER];   // per run: the model is shared by threads
        for (int w = 0; w < n; w++) {
            // [64 samples of context | 512 new samples], then reflect-padded by 64 on the right.
            System.arraycopy(x, WINDOW, x, 0, CONTEXT);
            int off = w * WINDOW, len = Math.min(WINDOW, pcm16k.length - off);
            System.arraycopy(pcm16k, off, x, CONTEXT, len);
            for (int i = CONTEXT + len; i < INPUT; i++) x[i] = 0f;
            for (int j = 0; j < 64; j++) x[INPUT + j] = x[INPUT - 2 - j];
            stft(x, mag, re, im);
            conv(mag, BINS, 4, w0, b0, 128, 1, e0, 4);
            conv(e0, 128, 4, w1, b1, 64, 2, e1, 2);
            conv(e1, 64, 2, w2, b2, 64, 2, e2, 1);
            conv(e2, 64, 1, w3, b3, 128, 1, e3, 1);
            lstm(e3, h, c, gates);
            float s = bdec;
            for (int i = 0; i < HIDDEN; i++) s += wdec[i] * Math.max(0f, h[i]);
            out[w] = (float) (1.0 / (1.0 + Math.exp(-s)));
            // x[512..576) (this input's last 64 samples) becomes the next window's context at the loop's start.
        }
        return out;
    }

    // The model's STFT basis is a periodic-Hann-windowed DFT (checked: max error 8e-8), so |FFT(window * x)|
    // gives the same magnitudes with ~2k butterflies per frame instead of 66k multiply-adds.
    private static final double[] HANN = new double[FILTER], COS = new double[FILTER / 2], SIN = new double[FILTER / 2];
    private static final int[] REV = new int[FILTER];

    static {
        for (int t = 0; t < FILTER; t++) HANN[t] = 0.5 - 0.5 * Math.cos(2 * Math.PI * t / FILTER);
        for (int k = 0; k < FILTER / 2; k++) {
            COS[k] = Math.cos(2 * Math.PI * k / FILTER);
            SIN[k] = -Math.sin(2 * Math.PI * k / FILTER);
        }
        int bits = Integer.numberOfTrailingZeros(FILTER);
        for (int i = 0; i < FILTER; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
    }

    /** |STFT|: 4 frames of 256 samples, hop 128, bins 0..128 (re/im: scratch of the calling run). */
    private static void stft(float[] x, float[] mag, double[] re, double[] im) {
        for (int f = 0; f < FRAMES; f++) {
            int base = f * HOP;
            for (int t = 0; t < FILTER; t++) {
                int r = REV[t];
                re[r] = HANN[t] * x[base + t];
                im[r] = 0;
            }
            for (int size = 2; size <= FILTER; size <<= 1) {
                int half = size >> 1, step = FILTER / size;
                for (int i = 0; i < FILTER; i += size) {
                    for (int j = 0; j < half; j++) {
                        double wr = COS[j * step], wi = SIN[j * step];
                        int a = i + j, b = a + half;
                        double tr = wr * re[b] - wi * im[b], ti = wr * im[b] + wi * re[b];
                        re[b] = re[a] - tr;
                        im[b] = im[a] - ti;
                        re[a] += tr;
                        im[a] += ti;
                    }
                }
            }
            for (int k = 0; k < BINS; k++) mag[k * FRAMES + f] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
        }
    }

    /** Conv1d, kernel 3, padding 1, then ReLU. in/out are [channels][time]. */
    private static void conv(float[] in, int cin, int tin, float[] w, float[] b, int cout, int stride, float[] out, int tout) {
        for (int co = 0; co < cout; co++) {
            for (int t = 0; t < tout; t++) {
                float s = b[co];
                int start = t * stride - 1;
                for (int ci = 0; ci < cin; ci++) {
                    int wb = (co * cin + ci) * 3, ib = ci * tin;
                    for (int k = 0; k < 3; k++) {
                        int p = start + k;
                        if (p >= 0 && p < tin) s += w[wb + k] * in[ib + p];
                    }
                }
                out[co * tout + t] = Math.max(0f, s);
            }
        }
    }

    /** PyTorch LSTMCell: gates i, f, g, o. */
    private void lstm(float[] x, float[] h, float[] c, float[] g) {
        for (int r = 0; r < 4 * HIDDEN; r++) {
            float s = bih[r] + bhh[r];
            int o = r * HIDDEN;
            for (int i = 0; i < HIDDEN; i++) s += wih[o + i] * x[i] + whh[o + i] * h[i];
            g[r] = s;
        }
        for (int i = 0; i < HIDDEN; i++) {
            float ig = sigmoid(g[i]), fg = sigmoid(g[HIDDEN + i]);
            float gg = (float) Math.tanh(g[2 * HIDDEN + i]), og = sigmoid(g[3 * HIDDEN + i]);
            c[i] = fg * c[i] + ig * gg;
            h[i] = og * (float) Math.tanh(c[i]);
        }
    }

    private static float sigmoid(float v) {
        return (float) (1.0 / (1.0 + Math.exp(-v)));
    }
}
