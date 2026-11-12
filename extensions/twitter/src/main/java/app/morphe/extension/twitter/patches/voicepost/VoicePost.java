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
import android.content.Context;
import android.content.ContextWrapper;
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
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Locale;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;

/**
 * Voice posts: X for Android can play audio posts but only the iOS app can make them. This records
 * the microphone, turns the recording into a short MP4 that looks like the iOS app's voice posts
 * (your profile picture on a plain card, the length and "Voice") plus the audio, and attaches it to
 * a post as a video, so it is uploaded and posted the normal way.
 * It opens from a microphone button in the composer (the clip goes into the post being written,
 * replies included) or from Piko settings (a new composer opens with the clip).
 */
@SuppressWarnings("unused")
public final class VoicePost {
    private static final String COMPOSER = "com.twitter.composer.ComposerActivity";
    private static final String BRIDGE = "pikoAttachMedia"; // added to the composer by the patch
    private static final String BUTTON_TAG = "piko_voice_post";
    private static final int MAX_MS = 140_000; // X's video limit without Premium
    private static final int W = 1280, H = 720; // the iOS app's voice posts are 16:9
    private static final int FPS = 5;
    private static final int SAMPLE_MS = 100;
    private static final long KEEP_MS = 3 * 24 * 60 * 60 * 1000L;
    private static final int BLUE = Color.rgb(29, 155, 240);

    private VoicePost() {
    }

    /** Piko settings entry: record, then open a new composer with the clip attached. */
    public static void show(Context context) {
        show(context, null);
    }

    /** Injection point: the composer's footer bar has inflated its buttons. Adds the microphone after GIF. */
    public static void onComposerFooter(View footer) {
        try {
            if (!Boolean.TRUE.equals(Utils.getBooleanPref(Settings.VOICE_POST_BUTTON))) return;
            Context context = footer.getContext();
            View c = footer.findViewById(id(context, "main_buttons_container", "id"));
            if (!(c instanceof ViewGroup)) return;
            ViewGroup container = (ViewGroup) c;
            if (container.findViewWithTag(BUTTON_TAG) != null) return;
            View gallery = container.findViewById(id(context, "gallery", "id"));
            if (gallery == null) return;

            ImageButton button;
            try {
                // The same widget class as its neighbours, so it gets their tint and ripple.
                button = (ImageButton) gallery.getClass().getConstructor(Context.class, AttributeSet.class)
                        .newInstance(gallery.getContext(), null);
            } catch (Throwable t) {
                button = new ImageButton(gallery.getContext());
                button.setBackground(null);
            }
            button.setTag(BUTTON_TAG);
            button.setImageResource(id(context, "ic_vector_microphone_stroke", "drawable"));
            button.setColorFilter(iconColor(gallery), android.graphics.PorterDuff.Mode.SRC_IN);
            button.setContentDescription(str("piko_voice_post_title"));
            if (gallery instanceof ImageView) button.setScaleType(((ImageView) gallery).getScaleType());
            button.setPadding(gallery.getPaddingLeft(), gallery.getPaddingTop(), gallery.getPaddingRight(), gallery.getPaddingBottom());
            View gif = container.findViewById(id(context, "found_media", "id"));
            View after = gif != null ? gif : gallery;
            ViewGroup.LayoutParams lp = after.getLayoutParams();
            button.setLayoutParams(lp instanceof ViewGroup.MarginLayoutParams
                    ? new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) lp)
                    : new LinearLayout.LayoutParams(lp.width, lp.height));
            button.setOnClickListener(v -> show(v.getContext(), footer));
            container.addView(button, container.indexOfChild(after) + 1);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** The colour the neighbouring icon is drawn in (its drawable is already themed), so the microphone matches. */
    private static int iconColor(View like) {
        try {
            android.graphics.drawable.Drawable d = ((ImageView) like).getDrawable();
            android.graphics.drawable.Drawable.ConstantState state = d.getConstantState();
            if (state != null) d = state.newDrawable(like.getResources(), like.getContext().getTheme()).mutate();
            Bitmap bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 32);
            d.draw(new Canvas(bmp));
            int best = 0;
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    int c = bmp.getPixel(x, y);
                    if (Color.alpha(c) > Color.alpha(best)) best = c;
                }
            }
            bmp.recycle();
            if (Color.alpha(best) > 128 && (best & 0xFFFFFF) != 0) return best | 0xFF000000;
        } catch (Throwable ignored) {
        }
        return BLUE;
    }

    private static int id(Context context, String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static boolean canRecord(Activity activity) {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private static void show(Context context, View footer) {
        try {
            Activity activity = activity(context);
            if (activity == null) return;
            cleanup(activity);
            if (canRecord(activity)) {
                new Session(activity, footer).open();
                return;
            }
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0x7650);
            PikoUtils.toast(str("piko_voice_post_permission"));
            // The answer goes to the activity, which this code is not part of: wait for it here.
            Handler main = new Handler(Looper.getMainLooper());
            long until = SystemClock.elapsedRealtime() + 60_000;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (canRecord(activity)) new Session(activity, footer).open();
                    else if (SystemClock.elapsedRealtime() < until) main.postDelayed(this, 300);
                }
            }, 300);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            PikoUtils.toast(str("piko_voice_post_error_record"));
        }
    }

    /** Finished clips live in the app's cache (not the gallery); X copies what it attaches. */
    private static File clipDir(Context context) {
        File base = null;
        try {
            base = context.getExternalCacheDir();
        } catch (Throwable ignored) {
        }
        if (base == null) base = context.getCacheDir();
        File dir = new File(base, "voicepost");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static void cleanup(Context context) {
        try {
            File[] files = clipDir(context).listFiles();
            if (files == null) return;
            long old = System.currentTimeMillis() - KEEP_MS;
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                if (f.isFile() && f.lastModified() < old) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Attaches the clip to the post being written: the footer bar's listener is the composer, which the
     * patch gave a bridge to its own "attach this shared media" code.
     */
    private static boolean attach(View footer, Uri uri) {
        try {
            for (Field field : footer.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(footer);
                if (value == null) continue;
                Method bridge;
                try {
                    bridge = value.getClass().getMethod(BRIDGE, Uri.class);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                return Boolean.TRUE.equals(bridge.invoke(value, uri));
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return false;
    }

    private static final class Session {
        private final Activity activity;
        private final View footer; // the composer's footer bar when opened from the composer
        private final Handler main = new Handler(Looper.getMainLooper());
        private final File audio;
        private final File card;
        private MediaRecorder recorder;
        private MediaPlayer player;
        private long startedAt;
        private long durationMs;
        private AlertDialog dialog;
        private TextView timer;
        private ProgressBar level;
        private Button record;
        private Button listen;
        private Button post;
        private boolean busy;

        Session(Activity activity, View footer) {
            this.activity = activity;
            this.footer = footer;
            File dir = new File(activity.getCacheDir(), "voicepost");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            audio = new File(dir, "voice.m4a");
            card = new File(dir, "card.mp4");
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

            // Shows that the microphone hears something while recording.
            level = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            level.setMax(100);
            root.addView(level, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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

            listen = new Button(activity);
            listen.setText(str("piko_voice_post_listen"));
            listen.setEnabled(false);
            listen.setOnClickListener(v -> {
                if (player == null) play();
                else stopPlaying();
            });
            root.addView(listen);

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
                release();
                stopPlaying();
                //noinspection ResultOfMethodCallIgnored
                if (!busy) audio.delete();
            });
            dialog.show();
        }

        private static String format(long ms) {
            long s = ms / 1000;
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        }

        /** A recording stops when the screen turns off, so it stays on while one runs. */
        private void keepScreenOn(boolean on) {
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) return;
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (recorder == null) return;
                try {
                    level.setProgress((int) (100 * Math.sqrt(recorder.getMaxAmplitude() / 32767.0)));
                } catch (Throwable ignored) {
                }
                long el = SystemClock.elapsedRealtime() - startedAt;
                timer.setText(format(el) + " / " + format(MAX_MS));
                main.postDelayed(this, SAMPLE_MS);
            }
        };

        private void start() {
            try {
                stopPlaying();
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
                recorder = r; // released by release() if preparing or starting fails
                r.prepare();
                r.start();
                startedAt = SystemClock.elapsedRealtime();
                record.setText(str("piko_voice_post_stop"));
                listen.setEnabled(false);
                post.setEnabled(false);
                keepScreenOn(true);
                main.post(tick);
            } catch (Throwable t) {
                PikoUtils.logger(t);
                PikoUtils.toast(str("piko_voice_post_error_record"));
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
            ok = ok && durationMs >= 1000 && audio.length() > 0;
            record.setText(str("piko_voice_post_record_again"));
            timer.setText(format(durationMs));
            listen.setEnabled(ok);
            post.setEnabled(ok);
        }

        private void release() {
            main.removeCallbacks(tick);
            try {
                if (recorder != null) recorder.release();
            } catch (Throwable ignored) {
            }
            recorder = null;
            if (level != null) level.setProgress(0);
            keepScreenOn(false);
        }

        private final Runnable playTick = new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                try {
                    timer.setText(format(player.getCurrentPosition()) + " / " + format(durationMs));
                } catch (Throwable ignored) {
                }
                main.postDelayed(this, 200);
            }
        };

        private void play() {
            try {
                MediaPlayer p = new MediaPlayer();
                player = p;
                p.setDataSource(audio.getAbsolutePath());
                p.setOnCompletionListener(mp -> stopPlaying());
                p.prepare();
                p.start();
                listen.setText(str("piko_voice_post_stop"));
                main.post(playTick);
            } catch (Throwable t) {
                PikoUtils.logger(t);
                stopPlaying();
            }
        }

        private void stopPlaying() {
            main.removeCallbacks(playTick);
            if (player == null) return;
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
            listen.setText(str("piko_voice_post_listen"));
            if (!busy) timer.setText(format(durationMs));
        }

        private void render() {
            if (busy) return;
            stopPlaying();
            busy = true;
            record.setEnabled(false);
            listen.setEnabled(false);
            post.setEnabled(false);
            timer.setText(str("piko_voice_post_preparing"));
            new Thread(() -> {
                File out = new File(clipDir(activity), "voice-post-" + System.currentTimeMillis() + ".mp4");
                try {
                    long audioUs = audioDurationUs(audio);
                    long us = audioUs > 0 ? audioUs : durationMs * 1000L;
                    encodeCard(card(avatar(activity), us / 1000), us, card);
                    mux(card, audio, out);
                    Uri uri = Uri.fromFile(out);
                    main.post(() -> {
                        dialog.dismiss();
                        if (!deliver(uri)) PikoUtils.toast(str("piko_voice_post_error_attach"));
                    });
                } catch (Throwable t) {
                    PikoUtils.logger(t);
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    main.post(() -> {
                        PikoUtils.toast(str("piko_voice_post_error_prepare"));
                        busy = false;
                        record.setEnabled(true);
                        listen.setEnabled(true);
                        post.setEnabled(true);
                        timer.setText(format(durationMs));
                    });
                    return;
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    card.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                audio.delete();
            }, "piko-voice-post").start();
        }

        /** Into the open composer when there is one, otherwise a new composer through X's share target. */
        private boolean deliver(Uri uri) {
            if (footer != null && attach(footer, uri)) return true;
            try {
                activity.startActivity(new Intent(Intent.ACTION_SEND)
                        .setClassName(activity.getPackageName(), COMPOSER)
                        .setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, uri));
                return true;
            } catch (Throwable t) {
                PikoUtils.logger(t);
                return false;
            }
        }
    }

    // ---- rendering -----------------------------------------------------------------------------

    /**
     * The signed-in account's profile picture: its address is in the app's own database, the picture
     * comes from X's image server. Null when there is none (the card then shows a microphone).
     */
    private static Bitmap avatar(Context context) {
        try {
            String id = com.twitter.util.user.UserIdentifier.getCurrent().getStringId();
            File db = null;
            File[] files = context.getDatabasePath("x").getParentFile().listFiles();
            if (files != null) {
                for (File f : files) if (f.getName().matches(id + "-\\d+\\.db")) db = f;
            }
            if (db == null) return null;
            String url = null;
            try (android.database.sqlite.SQLiteDatabase sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                    db.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                 android.database.Cursor c = sql.rawQuery("SELECT image_url FROM users WHERE user_id = ?", new String[]{id})) {
                if (c.moveToFirst()) url = c.getString(0);
            }
            if (url == null || !url.startsWith("https://")) return null;
            url = url.replaceFirst("_(normal|bigger|mini|x96|200x200|reasonably_small)(\\.[A-Za-z]+)$", "_400x400$2");
            java.net.HttpURLConnection http = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            http.setConnectTimeout(4000);
            http.setReadTimeout(4000);
            try (java.io.InputStream in = http.getInputStream()) {
                return android.graphics.BitmapFactory.decodeStream(in);
            } finally {
                http.disconnect();
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    /** The card's colour: the picture's main hue, toned so white text reads on it. */
    static int background(Bitmap avatar) {
        Bitmap small = Bitmap.createScaledBitmap(avatar, 32, 32, true);
        float[] weight = new float[24], hue = new float[24], sat = new float[24], val = new float[24];
        float[] hsv = new float[3];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int c = small.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.15f || hsv[2] < 0.2f) continue;
                int bin = Math.min(23, (int) (hsv[0] / 15f));
                float w = hsv[1] * hsv[2];
                weight[bin] += w;
                hue[bin] += hsv[0] * w;
                sat[bin] += hsv[1] * w;
                val[bin] += hsv[2] * w;
            }
        }
        int best = 0;
        for (int i = 1; i < 24; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < 4f) return Color.rgb(83, 100, 113); // a grey picture
        float w = weight[best];
        return Color.HSVToColor(new float[]{hue[best] / w, Math.max(0.35f, Math.min(0.55f, sat[best] / w)),
                Math.max(0.65f, Math.min(0.82f, val[best] / w))});
    }

    /** The video frame, laid out like the iOS app's: picture in the middle, length and speaker left, "Voice" right. */
    static Bitmap card(Bitmap avatar, long durationMs) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float cx = W / 2f, cy = H / 2f, r = H / 4f;
        if (avatar != null) {
            c.drawColor(background(avatar));
            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(avatar,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            float scale = 2 * r / Math.min(avatar.getWidth(), avatar.getHeight());
            m.setScale(scale, scale);
            m.postTranslate(cx - avatar.getWidth() * scale / 2, cy - avatar.getHeight() * scale / 2);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
        } else {
            c.drawColor(BLUE);
            p.setColor(Color.argb(60, 255, 255, 255));
            c.drawCircle(cx, cy, r, p);
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(cx - 34, cy - 100, cx + 34, cy + 20), 34, 34, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12);
            c.drawArc(new RectF(cx - 60, cy - 70, cx + 60, cy + 56), 0, 180, false, p);
            c.drawLine(cx, cy + 56, cx, cy + 92, p);
            c.drawLine(cx - 34, cy + 92, cx + 34, cy + 92, p);
            p.setStyle(Paint.Style.FILL);
        }

        // Length and a speaker, bottom left
        p.setColor(Color.argb(150, 255, 255, 255));
        p.setTextSize(38);
        long s = Math.round(durationMs / 1000.0);
        String length = String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        float base = H - 50;
        c.drawText(length, 40, base, p);
        float x = 40 + p.measureText(length) + 26, y = base - 13;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path speaker = new android.graphics.Path();
        speaker.moveTo(x, y - 7);
        speaker.lineTo(x + 9, y - 7);
        speaker.lineTo(x + 20, y - 17);
        speaker.lineTo(x + 20, y + 17);
        speaker.lineTo(x + 9, y + 7);
        speaker.lineTo(x, y + 7);
        speaker.close();
        c.drawPath(speaker, p);
        c.drawArc(new RectF(x + 12, y - 10, x + 32, y + 10), -50, 100, false, p);
        c.drawArc(new RectF(x + 6, y - 18, x + 42, y + 18), -50, 100, false, p);
        p.setStyle(Paint.Style.FILL);

        // "Voice", bottom right
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Voice", W - 40, base, p);
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
        byte[] y = new byte[W * H];
        byte[] u = new byte[W * H / 4];
        byte[] v = new byte[W * H / 4];
        int[] px = new int[W * H];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        bmp.recycle();
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                int c = px[j * W + i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                y[j * W + i] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int k = (j / 2) * (W / 2) + i / 2;
                    u[k] = (byte) clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                    v[k] = (byte) clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                }
            }
        }

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
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
                            codec.queueInputBuffer(in, 0, W * H * 3 / 2, pts, 0);
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
            int w = p == 0 ? W : W / 2, h = p == 0 ? H : H / 2;
            ByteBuffer buf = planes[p].getBuffer();
            int row = planes[p].getRowStride(), pix = planes[p].getPixelStride();
            for (int j = 0; j < h; j++) {
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
