/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.voicenotes;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Speed (1x, 1.5x, 2x) and seeking for X Chat voice notes: a small bar over the chat while a voice
 * note plays. X's own player only has a play/pause button.
 */
@SuppressWarnings("unused")
public final class VoiceNoteControls {
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final String PREFS = "piko_voice_notes";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static boolean callbacksRegistered;

    private static WeakReference<MediaPlayer> player = new WeakReference<>(null);
    private static View bar;
    private static SeekBar seek;
    private static TextView time;
    private static TextView speedButton;
    private static boolean dragging;
    private static Runnable ticker;

    private VoiceNoteControls() {
    }

    private static void track() {
        if (callbacksRegistered) return;
        Context context = app.morphe.extension.shared.Utils.getContext();
        if (!(context instanceof Application)) context = context == null ? null : context.getApplicationContext();
        if (!(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) { resumed = new WeakReference<>(a); }
            public void onActivityPaused(Activity a) { if (resumed.get() == a) hide(); }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
        callbacksRegistered = true;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static float speed(Context c) {
        return prefs(c).getFloat("speed", 1f);
    }

    private static MediaPlayer mediaPlayerOf(Object voicePlayer) {
        if (voicePlayer == null) return null;
        for (Field f : voicePlayer.getClass().getDeclaredFields()) {
            if (f.getType() == MediaPlayer.class) {
                try {
                    f.setAccessible(true);
                    return (MediaPlayer) f.get(voicePlayer);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static void applySpeed(MediaPlayer mp, float speed) {
        try {
            boolean wasPlaying = mp.isPlaying();
            PlaybackParams params = mp.getPlaybackParams().setSpeed(speed);
            mp.setPlaybackParams(params);
            // Setting params on a paused player starts it; keep it paused.
            if (!wasPlaying) mp.pause();
        } catch (Exception ignored) {
        }
    }

    /** The speed voice notes play at (1x to 2x). */
    public static float currentSpeed() {
        Context c = app.morphe.extension.shared.Utils.getContext();
        return c == null ? 1f : speed(c);
    }

    /** The next speed (1x, 1.25x, 1.5x, 1.75x, 2x, then 1x again), saved and applied to the note playing now. */
    public static float cycleSpeed() {
        Context c = app.morphe.extension.shared.Utils.getContext();
        if (c == null) return 1f;
        float current = speed(c), next = SPEEDS[0];
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - current) < 0.01f) next = SPEEDS[(i + 1) % SPEEDS.length];
        prefs(c).edit().putFloat("speed", next).apply();
        MediaPlayer mp = player.get();
        if (mp != null) {
            try {
                applySpeed(mp, next);
            } catch (Throwable ignored) {
            }
        }
        return next;
    }

    /** Injection point: X Chat's voice note player started or resumed playback. */
    public static void onPlaying(Object voicePlayer) {
        try {
            track();
            MediaPlayer mp = mediaPlayerOf(voicePlayer);
            if (mp == null) return;
            player = new WeakReference<>(mp);
            Context c = app.morphe.extension.shared.Utils.getContext();
            if (c != null) applySpeed(mp, speed(c));
            // The speed button lives in the voice note itself now (VoiceNotes); no bar over the chat.
        } catch (Throwable ignored) {
        }
    }

    /** Injection point: the voice note player stopped (released) or a voice note finished. */
    public static void onStopped() {
        main.post(VoiceNoteControls::hide);
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private static String format(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String label(float speed) {
        return (speed == (int) speed ? String.valueOf((int) speed) : String.valueOf(speed)) + "×";
    }

    /** The resumed activity, also when it resumed before tracking started. */
    @SuppressWarnings("unchecked")
    private static Activity currentActivity() {
        Activity a = resumed.get();
        if (a != null) return a;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            for (Object record : ((java.util.Map<Object, Object>) f.get(thread)).values()) {
                Field paused = record.getClass().getDeclaredField("paused");
                paused.setAccessible(true);
                if (!paused.getBoolean(record)) {
                    Field activity = record.getClass().getDeclaredField("activity");
                    activity.setAccessible(true);
                    a = (Activity) activity.get(record);
                    resumed = new WeakReference<>(a);
                    return a;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void show() {
        Activity a = currentActivity();
        if (a == null) return;
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return;
        if (bar == null || bar.getParent() != content) {
            hide();
            int accent = Color.rgb(29, 155, 240);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(a, 12), dp(a, 6), dp(a, 12), dp(a, 6));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(235, 21, 32, 43));
            bg.setCornerRadius(dp(a, 20));
            row.setBackground(bg);

            speedButton = new TextView(a);
            speedButton.setTextColor(Color.WHITE);
            speedButton.setTextSize(14);
            speedButton.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(accent);
            pill.setCornerRadius(dp(a, 12));
            speedButton.setBackground(pill);
            speedButton.setContentDescription("Playback speed");
            speedButton.setText(label(speed(a)));
            speedButton.setOnClickListener(v -> {
                float current = speed(v.getContext());
                float next = SPEEDS[0];
                for (int i = 0; i < SPEEDS.length; i++) if (SPEEDS[i] == current) next = SPEEDS[(i + 1) % SPEEDS.length];
                prefs(v.getContext()).edit().putFloat("speed", next).apply();
                speedButton.setText(label(next));
                MediaPlayer mp = player.get();
                if (mp != null) applySpeed(mp, next);
            });
            row.addView(speedButton);

            seek = new SeekBar(a);
            seek.setContentDescription("Voice note position");
            seek.setMax(1000);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    MediaPlayer mp = player.get();
                    if (fromUser && mp != null) {
                        try {
                            time.setText(format((int) ((long) mp.getDuration() * p / 1000)) + " / " + format(mp.getDuration()));
                        } catch (Exception ignored) {
                        }
                    }
                }
                public void onStartTrackingTouch(SeekBar s) { dragging = true; }
                public void onStopTrackingTouch(SeekBar s) {
                    dragging = false;
                    MediaPlayer mp = player.get();
                    if (mp == null) return;
                    try {
                        mp.seekTo((int) ((long) mp.getDuration() * s.getProgress() / 1000));
                    } catch (Exception ignored) {
                    }
                }
            });
            row.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            time = new TextView(a);
            time.setTextColor(Color.rgb(139, 152, 165));
            time.setTextSize(13);
            row.addView(time);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            lp.setMargins(dp(a, 12), dp(a, 100), dp(a, 12), 0);
            content.addView(row, lp);
            bar = row;
        }
        tick();
    }

    private static void tick() {
        if (ticker != null) main.removeCallbacks(ticker);
        Runnable update = new Runnable() {
            public void run() {
                MediaPlayer mp = player.get();
                if (bar == null || mp == null) return;
                try {
                    int duration = mp.getDuration();
                    int position = mp.getCurrentPosition();
                    if (!dragging && duration > 0) seek.setProgress((int) ((long) position * 1000 / duration));
                    if (!dragging) time.setText(format(position) + " / " + format(duration));
                } catch (Exception e) {
                    hide(); // released
                    return;
                }
                main.postDelayed(this, 200);
            }
        };
        ticker = update;
        main.post(update);
    }

    private static void hide() {
        if (ticker != null) main.removeCallbacks(ticker);
        ticker = null;
        if (bar != null && bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
        bar = null;
    }
}
