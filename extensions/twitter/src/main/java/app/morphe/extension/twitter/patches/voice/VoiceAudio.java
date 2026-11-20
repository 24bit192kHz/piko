/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.voice;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Voice note audio in and out: AAC decode to mono float, AAC encode like X Chat's own recorder, WAV for playback. */
final class VoiceAudio {
    private static final long TIMEOUT_US = 10_000;

    private VoiceAudio() {
    }

    static final class Pcm {
        final float[] data;
        final int rate;

        Pcm(float[] data, int rate) {
            this.data = data;
            this.rate = rate;
        }
    }

    /** The first audio track as mono floats at its decoded rate. */
    static Pcm decode(String path) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("no audio track");
            ex.selectTrack(track);
            dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            dec.configure(format, null, null, 0);
            dec.start();
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            float[] out = new float[1 << 16];
            short[] tmp = new short[8192];
            int n = 0;
            boolean inDone = false, outDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // Feed every free input buffer, then drain every ready output; block (briefly) only when neither moved.
            while (!outDone) {
                boolean moved = false;
                while (!inDone) {
                    int in = dec.dequeueInputBuffer(0);
                    if (in < 0) break;
                    ByteBuffer buf = dec.getInputBuffer(in);
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) {
                        dec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        dec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                    moved = true;
                }
                while (!outDone) {
                    int o = dec.dequeueOutputBuffer(info, moved ? 0 : TIMEOUT_US);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    moved = true;
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = dec.getOutputFormat();
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            ByteBuffer buf = dec.getOutputBuffer(o);
                            buf.position(info.offset).limit(info.offset + info.size);
                            ShortBuffer sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer();
                            int count = sb.remaining();
                            if (tmp.length < count) tmp = new short[count];
                            sb.get(tmp, 0, count);
                            int frames = count / channels;
                            if (n + frames > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, n + frames));
                            if (channels == 1) {
                                for (int i = 0; i < frames; i++) out[n++] = tmp[i] / 32768f;
                            } else {
                                for (int i = 0; i < frames; i++) {
                                    float v = 0;
                                    for (int c = 0; c < channels; c++) v += tmp[i * channels + c];
                                    out[n++] = v / (channels * 32768f);
                                }
                            }
                        }
                        dec.releaseOutputBuffer(o, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    }
                }
            }
            return new Pcm(java.util.Arrays.copyOf(out, n), rate);
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Throwable ignored) {
                }
                dec.release();
            }
            ex.release();
        }
    }

    /** AAC-LC mono in an MP4, with X Chat's own recorder settings (32 kbps). */
    static void encodeAac(float[] pcm, int rate, File out) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            short[] pcm16 = new short[pcm.length];
            for (int i = 0; i < pcm.length; i++) pcm16[i] = (short) Math.round(Math.max(-1f, Math.min(1f, pcm[i])) * 32767f);
            int track = -1, pos = 0;
            boolean inDone = false, outDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // Feed every free input buffer, then drain every ready output; block (briefly) only when neither moved.
            while (!outDone) {
                boolean moved = false;
                while (!inDone) {
                    int in = enc.dequeueInputBuffer(0);
                    if (in < 0) break;
                    ByteBuffer buf = enc.getInputBuffer(in);
                    buf.clear();
                    int frames = Math.min(pcm16.length - pos, buf.remaining() / 2);
                    long pts = pos * 1_000_000L / rate;
                    if (frames <= 0) {
                        enc.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm16, pos, frames);
                        enc.queueInputBuffer(in, 0, frames * 2, pts, 0);
                        pos += frames;
                    }
                    moved = true;
                }
                while (!outDone) {
                    int o = enc.dequeueOutputBuffer(info, moved ? 0 : TIMEOUT_US);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    moved = true;
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                    } else if (o >= 0) {
                        ByteBuffer buf = enc.getOutputBuffer(o);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxStarted) {
                            buf.position(info.offset).limit(info.offset + info.size);
                            mux.writeSampleData(track, buf, info);
                        }
                        enc.releaseOutputBuffer(o, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    }
                }
            }
        } finally {
            try {
                enc.stop();
            } catch (Throwable ignored) {
            }
            enc.release();
            if (mux != null) {
                try {
                    if (muxStarted) mux.stop();
                } catch (Throwable ignored) {
                }
                mux.release();
            }
        }
    }

    /** 16-bit mono WAV (what the player plays for a pause-free copy of a note you received). */
    static void writeWav(float[] pcm, int rate, File out) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int bytes = pcm.length * 2;
        h.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + bytes).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2)
                .putShort((short) 16).put(new byte[]{'d', 'a', 't', 'a'}).putInt(bytes);
        ByteBuffer body = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : pcm) body.putShort((short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f));
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(h.array());
            os.write(body.array());
        }
    }
}
