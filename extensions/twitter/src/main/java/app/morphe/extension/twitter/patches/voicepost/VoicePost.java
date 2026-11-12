/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.voicepost;

import static app.morphe.extension.shared.StringRef.str;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.crimera.PikoUtils;

/**
 * Voice posts: X for Android can play audio posts but only the iOS app can make them. This records
 * the microphone, turns the recording into a short MP4 (a waveform card plus the audio) and opens
 * X's own composer with it attached as a video, so it is uploaded and posted the normal way.
 */
@SuppressWarnings("unused")
public final class VoicePost {
    private static final String COMPOSER = "com.twitter.composer.ComposerActivity";
    private static final int MAX_MS = 140_000; // X's video limit without Premium
    private static final int SIZE = 720;
    private static final int FPS = 5;
    private static final int SAMPLE_MS = 100;

    private VoicePost() {
    }

    public static void show(Context context) {
        try {
            if (!(context instanceof Activity)) return;
            Activity activity = (Activity) context;
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0x7650);
                PikoUtils.toast(str("piko_voice_post_permission"));
                return;
            }
            new Session(activity).open();
        } catch (Throwable t) {
            PikoUtils.logger(t);
            PikoUtils.toast(t.toString());
        }
    }

    private static final class Session {
        private final Activity activity;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final List<Integer> levels = new ArrayList<>();
        private final File dir;
        private final File audio;
        private MediaRecorder recorder;
        private long startedAt;
        private long durationMs;
        private AlertDialog dialog;
        private TextView timer;
        private Button record;
        private Button post;
        private boolean busy;

        Session(Activity activity) {
            this.activity = activity;
            dir = new File(activity.getCacheDir(), "voicepost");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            audio = new File(dir, "voice.m4a");
        }

        void open() {
            float d = activity.getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = (int) (20 * d);
            root.setPadding(p, p, p, p / 2);

            timer = new TextView(activity);
            timer.setTextSize(36);
            timer.setGravity(Gravity.CENTER);
            timer.setText(format(0));
            root.addView(timer);

            TextView hint = new TextView(activity);
            hint.setGravity(Gravity.CENTER);
            hint.setText(str("piko_voice_post_hint"));
            hint.setPadding(0, p / 2, 0, p / 2);
            root.addView(hint);

            record = new Button(activity);
            record.setText(str("piko_voice_post_record"));
            record.setOnClickListener(v -> {
                if (recorder == null) start();
                else stop();
            });
            root.addView(record);

            post = new Button(activity);
            post.setText(str("piko_voice_post_post"));
            post.setEnabled(false);
            post.setOnClickListener(v -> render());
            root.addView(post);

            dialog = new AlertDialog.Builder(activity)
                    .setTitle(str("piko_voice_post_title"))
                    .setView(root)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(di -> {
                if (recorder != null) release();
            });
            dialog.show();
        }

        private static String format(long ms) {
            long s = ms / 1000;
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (recorder == null) return;
                try {
                    levels.add(recorder.getMaxAmplitude());
                } catch (Throwable ignored) {
                }
                long el = SystemClock.elapsedRealtime() - startedAt;
                timer.setText(format(el) + " / " + format(MAX_MS));
                main.postDelayed(this, SAMPLE_MS);
            }
        };

        private void start() {
            try {
                levels.clear();
                //noinspection ResultOfMethodCallIgnored
                audio.delete();
                MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(activity) : new MediaRecorder();
                r.setAudioSource(MediaRecorder.AudioSource.MIC);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioSamplingRate(44_100);
                r.setAudioEncodingBitRate(128_000);
                r.setAudioChannels(1);
                r.setMaxDuration(MAX_MS);
                r.setOutputFile(audio.getAbsolutePath());
                r.setOnInfoListener((mr, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post(this::stop);
                });
                r.prepare();
                r.start();
                recorder = r;
                startedAt = SystemClock.elapsedRealtime();
                record.setText(str("piko_voice_post_stop"));
                post.setEnabled(false);
                main.post(tick);
            } catch (Throwable t) {
                PikoUtils.logger(t);
                PikoUtils.toast(t.toString());
                release();
            }
        }

        private void stop() {
            if (recorder == null) return;
            durationMs = SystemClock.elapsedRealtime() - startedAt;
            boolean ok = true;
            try {
                recorder.stop();
            } catch (Throwable t) {
                ok = false; // stop() right after start() throws: nothing was recorded
            }
            release();
            record.setText(str("piko_voice_post_record_again"));
            timer.setText(format(durationMs));
            post.setEnabled(ok && durationMs >= 1000 && audio.length() > 0);
        }

        private void release() {
            main.removeCallbacks(tick);
            try {
                if (recorder != null) recorder.release();
            } catch (Throwable ignored) {
            }
            recorder = null;
        }

        private void render() {
            if (busy) return;
            busy = true;
            record.setEnabled(false);
            post.setEnabled(false);
            timer.setText(str("piko_voice_post_preparing"));
            final List<Integer> lv = new ArrayList<>(levels);
            new Thread(() -> {
                try {
                    File video = new File(dir, "card.mp4");
                    File out = new File(dir, "voice-post.mp4");
                    long audioUs = audioDurationUs(audio);
                    long us = audioUs > 0 ? audioUs : durationMs * 1000L;
                    encodeCard(card(lv, us / 1000), us, video);
                    mux(video, audio, out);
                    Uri uri = publish(out);
                    main.post(() -> {
                        dialog.dismiss();
                        compose(uri);
                    });
                } catch (Throwable t) {
                    PikoUtils.logger(t);
                    main.post(() -> {
                        PikoUtils.toast(t.toString());
                        busy = false;
                        record.setEnabled(true);
                        post.setEnabled(true);
                        timer.setText(format(durationMs));
                    });
                }
            }, "piko-voice-post").start();
        }

        private void compose(Uri uri) {
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setClassName(activity.getPackageName(), COMPOSER)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                activity.startActivity(intent);
            } catch (Throwable t) {
                PikoUtils.logger(t);
                activity.startActivity(Intent.createChooser(intent.setComponent(null), null));
            }
        }

        /** Saves the clip to Movies/Piko and returns its content URI. */
        private Uri publish(File file) throws Exception {
            String name = "voice-post-" + System.currentTimeMillis() + ".mp4";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            Uri collection;
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Piko");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Piko");
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                v.put(MediaStore.Video.Media.DATA, new File(d, name).getAbsolutePath());
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }
            Uri uri = activity.getContentResolver().insert(collection, v);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            try (InputStream in = new FileInputStream(file);
                 OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("MediaStore open failed");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                activity.getContentResolver().update(uri, done, null, null);
            }
            return uri;
        }
    }

    // ---- rendering -----------------------------------------------------------------------------

    /** The video frame: microphone glyph, the recording's waveform and its length. */
    static Bitmap card(List<Integer> levels, long durationMs) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.rgb(21, 32, 43));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Microphone
        p.setColor(Color.rgb(29, 155, 240));
        float cx = SIZE / 2f;
        c.drawRoundRect(new RectF(cx - 42, 120, cx + 42, 260), 42, 42, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(14);
        c.drawArc(new RectF(cx - 72, 150, cx + 72, 300), 0, 180, false, p);
        c.drawLine(cx, 300, cx, 340, p);
        c.drawLine(cx - 40, 340, cx + 40, 340, p);
        p.setStyle(Paint.Style.FILL);

        // Waveform: 48 bars from the sampled peak levels, scaled to the loudest
        int bars = 48;
        float top = 400, height = 180, left = 60, width = SIZE - 120;
        float slot = width / bars, bar = slot * 0.6f;
        int max = 1;
        for (int l : levels) max = Math.max(max, l);
        for (int i = 0; i < bars; i++) {
            float v = 0.06f;
            if (!levels.isEmpty()) {
                int from = i * levels.size() / bars, to = Math.max(from + 1, (i + 1) * levels.size() / bars);
                int peak = 0;
                for (int j = from; j < to && j < levels.size(); j++) peak = Math.max(peak, levels.get(j));
                v = Math.max(v, (float) Math.sqrt(peak / (double) max));
            }
            float h = v * height, x = left + i * slot + (slot - bar) / 2, mid = top + height / 2;
            c.drawRoundRect(new RectF(x, mid - h / 2, x + bar, mid + h / 2), bar / 2, bar / 2, p);
        }

        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(52);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        long s = Math.round(durationMs / 1000.0);
        c.drawText(String.format(Locale.US, "%d:%02d", s / 60, s % 60), cx, 660, p);
        return bmp;
    }

    private static long audioDurationUs(File audio) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(audio.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                if (f.containsKey(MediaFormat.KEY_DURATION)) return f.getLong(MediaFormat.KEY_DURATION);
            }
        } catch (Throwable ignored) {
        } finally {
            ex.release();
        }
        return 0;
    }

    /** Encodes the still card as H.264 for durationUs at a low frame rate (identical frames are tiny). */
    static void encodeCard(Bitmap bmp, long durationUs, File out) throws Exception {
        byte[] y = new byte[SIZE * SIZE];
        byte[] u = new byte[SIZE * SIZE / 4];
        byte[] v = new byte[SIZE * SIZE / 4];
        int[] px = new int[SIZE * SIZE];
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int c = px[j * SIZE + i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                y[j * SIZE + i] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int k = (j / 2) * (SIZE / 2) + i / 2;
                    u[k] = (byte) clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                    v[k] = (byte) clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                }
            }
        }

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIZE, SIZE);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 400_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            long frameUs = 1_000_000L / FPS;
            int frames = (int) Math.max(1, (durationUs + frameUs - 1) / frameUs);
            int sent = 0, track = -1;
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        long pts = sent * frameUs;
                        if (sent >= frames) {
                            codec.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            android.media.Image img = codec.getInputImage(in);
                            if (img == null) throw new IllegalStateException("encoder has no image input");
                            fill(img, y, u, v);
                            codec.queueInputBuffer(in, 0, SIZE * SIZE * 3 / 2, pts, 0);
                            sent++;
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                } else if (o >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && buf != null && track >= 0) muxer.writeSampleData(track, buf, info);
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            muxer.stop();
        } finally {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            codec.release();
            try {
                muxer.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int clamp(int x) {
        return x < 0 ? 0 : Math.min(255, x);
    }

    private static void fill(android.media.Image img, byte[] y, byte[] u, byte[] v) {
        android.media.Image.Plane[] planes = img.getPlanes();
        byte[][] src = {y, u, v};
        for (int p = 0; p < 3; p++) {
            int w = p == 0 ? SIZE : SIZE / 2;
            ByteBuffer buf = planes[p].getBuffer();
            int row = planes[p].getRowStride(), pix = planes[p].getPixelStride();
            for (int j = 0; j < w; j++) {
                int base = j * row;
                if (pix == 1) {
                    buf.position(base);
                    buf.put(src[p], j * w, w);
                } else {
                    for (int i = 0; i < w; i++) buf.put(base + i * pix, src[p][j * w + i]);
                }
            }
        }
    }

    /** Muxes the card video and the recorded AAC audio into one MP4, interleaved by time. */
    static void mux(File video, File audio, File out) throws Exception {
        MediaExtractor ve = new MediaExtractor(), ae = new MediaExtractor();
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vt = track(ve, "video/"), at = track(ae, "audio/");
            ve.selectTrack(vt);
            ae.selectTrack(at);
            int mv = muxer.addTrack(ve.getTrackFormat(vt));
            int ma = muxer.addTrack(ae.getTrackFormat(at));
            muxer.start();
            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean vDone = false, aDone = false;
            while (!vDone || !aDone) {
                long vts = vDone ? Long.MAX_VALUE : ve.getSampleTime();
                long ats = aDone ? Long.MAX_VALUE : ae.getSampleTime();
                if (vts < 0) {
                    vDone = true;
                    continue;
                }
                if (ats < 0) {
                    aDone = true;
                    continue;
                }
                boolean useVideo = vts <= ats;
                MediaExtractor ex = useVideo ? ve : ae;
                info.offset = 0;
                info.size = ex.readSampleData(buf, 0);
                if (info.size < 0) {
                    if (useVideo) vDone = true;
                    else aDone = true;
                    continue;
                }
                info.presentationTimeUs = ex.getSampleTime();
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(useVideo ? mv : ma, buf, info);
                ex.advance();
            }
            muxer.stop();
        } finally {
            ve.release();
            ae.release();
            try {
                muxer.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int track(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        throw new IllegalStateException("no " + prefix + " track");
    }
}
