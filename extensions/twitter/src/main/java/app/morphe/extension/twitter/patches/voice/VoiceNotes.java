/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;

/**
 * X Chat voice notes without the pauses (clearcut's method, see {@link VoiceCut}).
 * <ul>
 * <li>Sent: X's recorder finishes the file, the pauses are cut from it before the preview and the upload,
 * and the length, size and waveform describe the cut file.</li>
 * <li>Heard: the time label of a voice note is a button ("✂ 0:42"). Tapping it plays the note without its
 * pauses (a copy in the app's cache); when idle it then reads "0:42 → 0:31". Tapping it again plays the original.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class VoiceNotes {
    private static final String TAG_PREFS = "piko_voice_notes";
    private static final long MAX_CACHE = 64L << 20;

    // Set at patch time.
    private static String attachmentUriMethodName() {
        return "";
    }

    private static String labelFormatClassName() {
        return "";
    }

    private static String labelFormatMethodName() {
        return "";
    }

    private static String clickableClassName() {
        return "";
    }

    private static String clickableMethodName() {
        return "";
    }

    private static String playMethodName() {
        return "";
    }

    private static String playerUriFieldName() {
        return "";
    }

    private static String textClassName() {
        return "";
    }

    private static String textMethodName() {
        return "";
    }

    private static String iconComposableClassName() {
        return "";
    }

    private static String iconComposableMethodName() {
        return "";
    }

    private static String iconTypeClassName() {
        return "";
    }

    private static String sizeClassName() {
        return "";
    }

    private static String sizeMethodName() {
        return "";
    }

    private static String paddingClassName() {
        return "";
    }

    private static String paddingMethodName() {
        return "";
    }

    private static String backgroundClassName() {
        return "";
    }

    private static String backgroundMethodName() {
        return "";
    }

    private static String shapeClassName() {
        return "";
    }

    private static String shapeMethodName() {
        return "";
    }

    private static String collectClassName() {
        return "";
    }

    private static String collectMethodName() {
        return "";
    }

    private static String flowClassName() {
        return "";
    }

    private static String flowMethodName() {
        return "";
    }

    private VoiceNotes() {
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "piko-voice-cut");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean bool(app.morphe.extension.crimera.settings.BooleanSetting s) {
        try {
            return Boolean.TRUE.equals(Utils.getBooleanPref(s));
        } catch (Throwable t) {
            return s.defaultValue;
        }
    }

    private static Context context() {
        return app.morphe.extension.shared.Utils.getContext();
    }

    private static SileroVad vad() throws Exception {
        return SileroVad.shared(() -> context().getAssets().open("piko/silero_vad_v6.bin"));
    }

    private static String path(String uri) {
        if (uri == null) return null;
        if (uri.startsWith("file://")) return uri.substring(7);
        return uri.startsWith("/") ? uri : null;
    }

    // region sent notes

    /** What was kept of a note just cut, so its waveform can follow (X builds it right after). */
    private static final class Kept {
        final List<double[]> ranges;
        final double before, after;

        Kept(VoiceCut.Result r) {
            ranges = r.kept;
            before = r.before;
            after = r.after;
        }
    }

    private static final Map<String, Kept> JUST_CUT = new ConcurrentHashMap<>();
    // Longer notes are sent as recorded (the cut runs while X waits for the recording to finish).
    private static final long MAX_SENT_MS = 5 * 60_000, MAX_DECODE_MS = 4_000, MAX_TOTAL_MS = 8_000;
    // Time kept for encoding: past it the noise removal is dropped first, then the cut.
    private static final long ENCODE_RESERVE_MS = 1_500;

    /** X's recorder finished the file: cut the pauses out of it in place and describe the cut file. */
    public static Object onRecorded(Object recorded) {
        if (recorded == null || !bool(Settings.VOICE_NOTE_CUT_SENT)) return recorded;
        try {
            Class<?> cls = recorded.getClass();
            Field uriF = null, durF = null, sizeF = null;
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == String.class && uriF == null) uriF = f;
                else if (f.getType() == long.class && durF == null) durF = f;
                else if (sizeF == null && f.getType() != String.class && f.getType() != long.class) sizeF = f;
            }
            if (uriF == null || durF == null || sizeF == null) return recorded;
            String uri = (String) uriF.get(recorded), path = path(uri);
            if (path == null) return recorded;
            long oldRaw = durF.getLong(recorded);
            long recordedMs = (oldRaw & 1L) == 0 ? (oldRaw >> 1) / 1_000_000L : oldRaw >> 1;
            if (recordedMs > MAX_SENT_MS) return recorded;
            long start = SystemClock.uptimeMillis();
            VoiceAudio.Pcm pcm = VoiceAudio.decode(path);
            long decoded = SystemClock.uptimeMillis();
            if (decoded - start > MAX_DECODE_MS) return recorded;
            VoiceCut.Result r = VoiceCut.cut(vad(), pcm.data, pcm.rate);
            long planned = SystemClock.uptimeMillis();
            if (planned - start > MAX_TOTAL_MS) return recorded;
            // Fail safe: a cut that would leave almost nothing is music or noise, not speech: left as recorded.
            if (r.after < 0.6 || r.after < r.before * 0.15) return recorded;
            boolean cut = r.before - r.after >= 0.3;
            // Background noise: removed from the whole note (the cut was planned on the original, as clearcut does).
            float[] clean = null;
            if (bool(Settings.VOICE_NOTE_DENOISE)) {
                long budget = MAX_TOTAL_MS - ENCODE_RESERVE_MS - (planned - start);
                if (budget > 200) clean = DfNative.denoise(pcm.data, pcm.rate, (int) budget);
            }
            long denoised = SystemClock.uptimeMillis();
            if (!cut && clean == null) return recorded;
            if (clean != null) r = cut ? VoiceCut.cut(clean, pcm.rate, r.kept) : VoiceCut.cut(clean, pcm.rate, whole(pcm));
            File src = new File(path), tmp = new File(path + ".piko.m4a");
            float[] out = r.pcm;
            int rate = pcm.rate;
            if (rate > 22050) {
                out = VoiceCut.resample(out, rate, 22050);
                rate = 22050;
            }
            VoiceAudio.encodeAac(out, rate, tmp);
            if (tmp.length() <= 0 || !tmp.renameTo(src)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return recorded;
            }
            long afterMs = Math.round(r.after * 1000), beforeMs = Math.round(r.before * 1000);
            // kotlin.time.Duration: nanoseconds << 1, or milliseconds << 1 | 1 for very long ones.
            long raw = !cut ? oldRaw : (oldRaw & 1L) == 0 ? (afterMs * 1_000_000L) << 1 : (afterMs << 1) | 1L;
            Object size = sizeF.getType().getConstructor(long.class).newInstance(src.length());
            Constructor<?> ctor = cls.getConstructor(String.class, long.class, sizeF.getType());
            Object result = ctor.newInstance(uri, raw, size);
            if (cut) {
                if (JUST_CUT.size() > 8) JUST_CUT.clear();
                JUST_CUT.put(uri, new Kept(r));
                rememberSent(src, beforeMs, afterMs);
            }
            long end = SystemClock.uptimeMillis();
            PikoUtils.logger("voice note: " + (cut ? "pauses cut " + beforeMs + " -> " + afterMs + " ms" : "no pauses to cut")
                    + (clean != null ? ", noise removed" : "") + "; decode " + (decoded - start) + " ms, detect "
                    + (planned - decoded) + " ms, denoise " + (denoised - planned) + " ms, encode " + (end - denoised)
                    + " ms, total " + (end - start) + " ms");
            return result;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return recorded;
        }
    }

    /** The whole note as one kept part (noise removed, nothing cut). */
    private static List<double[]> whole(VoiceAudio.Pcm pcm) {
        List<double[]> all = new ArrayList<>();
        all.add(new double[]{0, pcm.data.length / (double) pcm.rate});
        return all;
    }

    /** The recorded note's waveform: X's own levels, re-timed to the cut audio. */
    public static List<?> amplitudes(String uri, Object duration, List<?> levels) {
        Kept r = uri == null ? null : JUST_CUT.remove(uri);
        if (r == null || levels == null || levels.isEmpty()) return levels;
        try {
            int n = levels.size(), m = Math.max(1, (int) Math.round(n * r.after / r.before));
            List<Object> out = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                double t = (i + 0.5) / m * r.after, src = r.before;
                for (double[] k : r.ranges) {   // cut time -> source time
                    double len = k[1] - k[0];
                    if (t <= len) {
                        src = k[0] + t;
                        break;
                    }
                    t -= len;
                }
                out.add(levels.get(Math.min(n - 1, (int) (src / r.before * n))));
            }
            PikoUtils.logger("voice note: waveform re-timed " + n + " -> " + m + " levels");
            return out;
        } catch (Throwable t) {
            return levels;
        }
    }

    private static SharedPreferences prefs() {
        return context().getSharedPreferences(TAG_PREFS, Context.MODE_PRIVATE);
    }

    /** A file's identity: its size and the SHA-1 of its first 32 KB (the sent copy can live elsewhere). */
    private static String signature(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[32768];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            d.update(b, 0, n);
            StringBuilder sb = new StringBuilder().append(f.length()).append(':');
            for (byte x : d.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void rememberSent(File f, long beforeMs, long afterMs) {
        String sig = signature(f);
        if (sig == null) return;
        SharedPreferences p = prefs();
        if (p.getAll().size() > 500) {
            SharedPreferences.Editor ed = p.edit();
            for (String k : p.getAll().keySet()) if (k.startsWith("sent:")) ed.remove(k);
            ed.apply();
        }
        p.edit().putString("sent:" + sig, beforeMs + "|" + afterMs).apply();
    }

    // endregion

    // region heard notes

    private enum State {PROCESSING, READY, NO_PAUSES, FAILED, SENT}

    private static final class Entry {
        volatile State state;
        volatile File cut;
        volatile long beforeMs, afterMs;
        volatile boolean on;
        volatile List<double[]> segments;   // {source start, source end, copy start}, seconds

        Entry(State s) {
            state = s;
        }
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> CHECKED_SENT = new ConcurrentHashMap<>();

    // The note the player last opened (setDataSource), on the main thread.
    private static MediaPlayer lastPlayer;
    private static String lastUri;
    private static boolean lastCut;

    /** X's player opening a voice note: the pause-free copy instead when that note is switched to it. */
    public static void setDataSource(MediaPlayer player, String uri) throws IOException {
        Entry e = uri == null ? null : ENTRIES.get(uri);
        String source = uri;
        if (e != null && e.on && e.state == State.READY && e.cut != null && e.cut.isFile()) source = e.cut.getAbsolutePath();
        player.setDataSource(source);
        lastPlayer = player;
        lastUri = uri;
        lastCut = source != null && !source.equals(uri);
    }

    // The bubble being composed (main thread): its attachment, whether it is yours, its player.
    private static Object curAttachment, curController;
    private static String curUri;
    private static boolean curOutgoing, curClickable;
    private static Method formatMethod;
    private static final Map<Class<?>, Method> URI_METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Object> MODIFIERS = new ConcurrentHashMap<>();

    public static void labelContext(Object attachment, boolean outgoing, Object controller) {
        curAttachment = attachment;
        curOutgoing = outgoing;
        curController = controller;
        curUri = null;
        if (attachment == null) return;
        try {
            Method m = URI_METHODS.get(attachment.getClass());
            if (m == null) {
                m = findMethod(attachment.getClass(), attachmentUriMethodName());
                URI_METHODS.put(attachment.getClass(), m);
            }
            curUri = (String) m.invoke(attachment);
        } catch (Throwable ignored) {
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** X's own duration text ("0:42"). */
    private static String format(long ms) {
        try {
            if (formatMethod == null) {
                formatMethod = Class.forName(labelFormatClassName(), false, VoiceNotes.class.getClassLoader())
                        .getDeclaredMethod(labelFormatMethodName(), long.class);
                formatMethod.setAccessible(true);
            }
            return (String) formatMethod.invoke(null, (ms * 1_000_000L) << 1);
        } catch (Throwable t) {
            long s = Math.round(ms / 1000.0);
            return (s / 60) + ":" + String.format("%02d", s % 60);
        }
    }

    private static final String LTR = "\u200E", RTL = "\u200F", CUT = "\u2702\uFE0E ";

    /** "0:42 → 0:31", or in a right-to-left language (Arabic) "٠:٤٢ ← ٠:٣١", read from the right. */
    private static String beforeAfter(long beforeMs, long afterMs) {
        boolean rtl = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault())
                == android.view.View.LAYOUT_DIRECTION_RTL;
        return rtl ? RTL + format(beforeMs) + RTL + " \u2190 " + RTL + format(afterMs)
                : LTR + format(beforeMs) + " \u2192 " + format(afterMs);
    }

    /** The time label of the bubble being composed: X's own, or "before → after" for a note without pauses. */
    public static String label(String original) {
        curClickable = false;
        if (original == null) return original;
        String uri = curUri;
        if (uri == null || path(uri) == null) return original;
        Entry e = ENTRIES.get(uri);
        if (e == null && lookUpSent(uri)) e = ENTRIES.get(uri);
        if (e == null) return original;
        if (e.state == State.READY && e.on) {
            Long left = remaining(uri);
            return left != null ? format(left) : beforeAfter(e.beforeMs, e.afterMs);
        }
        if (e.state == State.SENT) return remaining(uri) != null ? original : beforeAfter(e.beforeMs, e.afterMs);
        return original;
    }

    /** Whether X's player has this note open (playing or paused): its state holds the note's uri, Idle holds none. */
    private static boolean activeInPlayer(String uri) {
        Object controller = curController;
        if (controller == null) return true;
        try {
            Object flow = controller.getClass().getMethod("getState").invoke(controller);
            Object state = flow.getClass().getMethod("getValue").invoke(flow);
            for (Class<?> c = state.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    if (uri.equals(f.get(state))) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Time left of the pause-free copy while it plays or is paused, or null when idle. */
    private static Long remaining(String uri) {
        MediaPlayer p = lastPlayer;
        if (p == null || !uri.equals(lastUri) || !activeInPlayer(uri)) return null;
        try {
            int d = p.getDuration(), pos = p.getCurrentPosition();
            if (d <= 0 || (!p.isPlaying() && (pos <= 0 || pos >= d - 60))) return null;
            return (long) Math.max(0, d - pos);
        } catch (Throwable t) {
            return null;    // released
        }
    }

    /**
     * Notes you sent with their pauses cut show "before → after": looked up once per note by the file's
     * signature (32 KB read, done in the composition so the first frame is already right).
     */
    private static boolean lookUpSent(String uri) {
        if (CHECKED_SENT.putIfAbsent(uri, Boolean.TRUE) != null) return false;
        try {
            String sig = signature(new File(path(uri)));
            String v = sig == null ? null : prefs().getString("sent:" + sig, null);
            if (v == null) return false;
            String[] parts = v.split("\\|");
            Entry e = new Entry(State.SENT);
            e.beforeMs = Long.parseLong(parts[0]);
            e.afterMs = Long.parseLong(parts[1]);
            return ENTRIES.putIfAbsent(uri, e) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method clickable;
    private static Object modifierCompanion;
    // The player of the chat a note was last shown in (a chat screen opened again has a new one).
    private static final Map<String, Object> CONTROLLERS = new ConcurrentHashMap<>();

    /** The label's tap: X's own clickable modifier on its text, or null to leave the label as it is. */
    public static Object labelModifier() {
        return null;   // the label is X's text again; the scissors button (controls) is next to it
    }

    /** X's Modifier.clickable and Modifier.Companion, looked up once. */
    private static void loadClickable() throws Exception {
        if (clickable != null) return;
        Class<?> c = Class.forName(clickableClassName(), false, VoiceNotes.class.getClassLoader());
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(clickableMethodName()) && m.getParameterTypes().length == 7) {
                m.setAccessible(true);
                clickable = m;
            }
        }
        Class<?> modifier = clickable.getParameterTypes()[0];
        for (Field f : modifier.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && modifier.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                modifierCompanion = f.get(null);
            }
        }
    }

    /** Text's $default mask: the modifier is given when the label is a button. */
    public static int labelMask(int mask) {
        return mask;
    }

    /** Label tapped: make (once) and play the pause-free copy, or go back to the original. */
    private static void toggle(String uri, Object controller) {
        Entry e = ENTRIES.get(uri);
        if (e != null && e.state == State.PROCESSING) return;
        if (e != null && e.state == State.READY) {
            e.on = !e.on;
            changed();
            play(uri, controller);
            return;
        }
        Entry p = new Entry(State.PROCESSING);
        ENTRIES.put(uri, p);
        changed();
        WORKER.execute(() -> {
            try {
                File dir = new File(context().getCacheDir(), "piko_voicecut");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                boolean denoise = bool(Settings.VOICE_NOTE_DENOISE);
                File out = new File(dir, sha1(uri + (denoise ? "#dfn3" : "")) + ".wav");
                VoiceAudio.Pcm pcm = VoiceAudio.decode(path(uri));
                VoiceCut.Result r = VoiceCut.cut(vad(), pcm.data, pcm.rate);
                p.beforeMs = Math.round(r.before * 1000);
                p.afterMs = Math.round(r.after * 1000);
                if (r.before - r.after < 0.3 || r.after < 0.6) {
                    p.state = State.NO_PAUSES;
                } else {
                    // without its background noise too (cut planned on the original, as clearcut does)
                    float[] clean = denoise && r.after >= r.before * 0.15 ? DfNative.denoise(pcm.data, pcm.rate, 0) : null;
                    if (clean != null) r = VoiceCut.cut(clean, pcm.rate, r.kept);
                    VoiceAudio.writeWav(r.pcm, pcm.rate, out);
                    trimCache(dir);
                    p.cut = out;
                    p.segments = new ArrayList<>(r.segments);
                    p.on = true;
                    p.state = State.READY;
                }
            } catch (Throwable t) {
                PikoUtils.logger(t);
                p.state = State.FAILED;
            }
            MAIN.post(() -> {
                changed();
                play(uri, controller);
            });
        });
    }

    /** Plays the note from the start with the current choice (X's player opens it again). */
    private static void play(String uri, Object controller) {
        try {
            Field f = null;
            for (Class<?> c = controller.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(playerUriFieldName());
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f != null) {
                f.setAccessible(true);
                f.set(controller, null);   // so the player is prepared again, from the copy or the original
            }
            findMethod(controller.getClass(), playMethodName(), String.class).invoke(controller, uri);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static void trimCache(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_CACHE) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_CACHE / 2) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            for (Entry e : ENTRIES.values()) if (f.equals(e.cut)) e.state = State.FAILED;
        }
    }

    private static String sha1(String s) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"))) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // endregion

    // region controls in the voice note (speed and pauses), drawn with X's own Text and Icon

    private static Method textFn, iconFn, sizeFn, paddingFn, backgroundFn, collectFn;
    private static Constructor<?> iconCtor;
    private static Object pillShape, uiFlow;
    private static volatile int uiVersion;
    private static int scissorsRes;
    private static boolean controlsBroken;

    private static Method method(String cls, String name, int params) throws Exception {
        Class<?> c = Class.forName(cls, false, VoiceNotes.class.getClassLoader());
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == params && Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(cls + "." + name + "/" + params);
    }

    private static void loadControls() throws Exception {
        if (textFn != null) return;
        loadClickable();
        textFn = method(textClassName(), textMethodName(), 22);
        iconFn = method(iconComposableClassName(), iconComposableMethodName(), 8);
        sizeFn = method(sizeClassName(), sizeMethodName(), 2);
        paddingFn = method(paddingClassName(), paddingMethodName(), 3);
        backgroundFn = method(backgroundClassName(), backgroundMethodName(), 3);
        collectFn = method(collectClassName(), collectMethodName(), 3);
        pillShape = method(shapeClassName(), shapeMethodName(), 1).invoke(null, 50);
        uiFlow = method(flowClassName(), flowMethodName(), 1).invoke(null, 0);
        iconCtor = Class.forName(iconTypeClassName(), false, VoiceNotes.class.getClassLoader()).getConstructor(int.class);
        Context c = context();
        scissorsRes = c.getResources().getIdentifier("ic_vector_scissors_stroke", "drawable", c.getPackageName());
    }

    /** Something the notes show changed (speed, a note without pauses): they draw again. */
    private static void changed() {
        try {
            if (uiFlow != null) uiFlow.getClass().getMethod("setValue", Object.class).invoke(uiFlow, ++uiVersion);
        } catch (Throwable ignored) {
        }
    }

    private static Object onClick(Runnable r) {
        Class<?> function0 = clickable.getParameterTypes()[5];
        InvocationHandler h = (proxy, method, args) -> {
            if ("invoke".equals(method.getName())) {
                r.run();
                return null;
            }
            if ("equals".equals(method.getName())) return proxy == args[0];
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            return "piko voice note control";
        };
        return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0}, h);
    }

    /** A Compose Color (sRGB, packed in the top 32 bits) with another alpha. */
    private static long alpha(long color, float a) {
        if ((color & 0x3FL) != 0) return color;   // not sRGB: leave it
        int argb = (int) (color >>> 32);
        int na = Math.round(((argb >>> 24) & 0xFF) * a);
        return ((long) ((argb & 0x00FFFFFF) | (na << 24))) << 32;
    }

    private static String speedText(float speed) {
        String n = speed == Math.rint(speed) ? String.valueOf((int) speed) : String.valueOf(speed).replaceAll("0+$", "");
        return n + "\u00D7";
    }

    // Compose's own layout primitives as X 12.19.1's compiled Column/Row use them (the patch checks they exist).
    private static final String COMPOSER = "androidx.compose.runtime.Composer";
    private static Method nHash, nLocals, nStart, nInserting, nCreate, nUse, nEnd, nSet, nInit, nMaterialize, colPolicy, rowPolicy;
    private static Object nCtor, nSetPolicy, nSetLocals, nSetHash, nSetInit, nSetModifier, arrTop, arrStart, alignEnd, alignCenterV;

    private static Object staticField(String cls, String name) throws Exception {
        Field f = Class.forName(cls, false, VoiceNotes.class.getClassLoader()).getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void loadLayout() throws Exception {
        if (nEnd != null) return;
        ClassLoader cl = VoiceNotes.class.getClassLoader();
        Class<?> composer = Class.forName(COMPOSER, false, cl);
        nHash = composer.getMethod("c");
        nLocals = composer.getMethod("d");
        nStart = composer.getMethod("k");
        nInserting = composer.getMethod("A");
        nCreate = composer.getMethod("W", Class.forName("kotlin.jvm.functions.Function0", false, cl));
        nUse = composer.getMethod("f");
        nSet = method("androidx.compose.runtime.e6", "c", 3);
        nInit = method("androidx.compose.runtime.e6", "b", 2);
        nMaterialize = method("androidx.compose.ui.g", "c", 2);
        colPolicy = method("androidx.compose.foundation.layout.d0", "a", 4);
        rowPolicy = method("androidx.compose.foundation.layout.o4", "a", 4);
        String node = "androidx.compose.ui.node.g$a";
        nCtor = staticField(node, "b");
        nSetPolicy = staticField(node, "g");
        nSetLocals = staticField(node, "f");
        nSetHash = staticField(node, "j");
        nSetInit = staticField(node, "k");
        nSetModifier = staticField(node, "d");
        arrTop = staticField("androidx.compose.foundation.layout.h", "c");
        arrStart = staticField("androidx.compose.foundation.layout.h", "a");
        alignEnd = staticField("androidx.compose.ui.b$a", "p");
        alignCenterV = staticField("androidx.compose.ui.b$a", "l");
        nEnd = composer.getMethod("h");
    }

    /** Starts a layout node like Compose's inline Column/Row: policy, composition locals, key hash, modifier. */
    private static void beginNode(Object composer, Object policy, Object modifier) throws Exception {
        int hash = Long.hashCode((Long) nHash.invoke(composer));
        Object locals = nLocals.invoke(composer);
        Object mod = nMaterialize.invoke(null, composer, modifier);
        nStart.invoke(composer);
        if ((Boolean) nInserting.invoke(composer)) nCreate.invoke(composer, nCtor);
        else nUse.invoke(composer);
        nSet.invoke(null, composer, policy, nSetPolicy);
        nSet.invoke(null, composer, locals, nSetLocals);
        nSet.invoke(null, composer, hash, nSetHash);
        nInit.invoke(null, composer, nSetInit);
        nSet.invoke(null, composer, mod, nSetModifier);
    }

    private static boolean timeColumnReady() {
        if (controlsBroken) return false;
        try {
            loadControls();
            loadLayout();
            return true;
        } catch (Throwable t) {
            controlsBroken = true;
            PikoUtils.logger(t);
            return false;
        }
    }

    /**
     * Injection point, in place of X's Text for the time of a voice note (same arguments): the time with the
     * speed and pauses buttons in a small row under it, so the waveform keeps its width.
     */
    public static void timeColumn(String text, Object modifier, long color, Object a3, long fontSize, Object fontWeight,
                                  Object fontStyle, Object fontFamily, long letterSpacing, Object decoration, Object align,
                                  long lineHeight, int overflow, boolean softWrap, int maxLines, int minLines,
                                  Object onLayout, Object style, Object composer, int changed0, int changed1, int defaults) throws Exception {
        if (composer == null || !timeColumnReady()) {
            if (textFn == null) textFn = method(textClassName(), textMethodName(), 22);
            textFn.invoke(null, text, modifier, color, a3, fontSize, fontWeight, fontStyle, fontFamily, letterSpacing,
                    decoration, align, lineHeight, overflow, softWrap, maxLines, minLines, onLayout, style, composer,
                    changed0, changed1, defaults);
            return;
        }
        // Read the change signal so this note draws again after a speed or pauses change.
        Object state = collectFn.invoke(null, uiFlow, composer, 0);
        state.getClass().getMethod("getValue").invoke(state);

        beginNode(composer, colPolicy.invoke(null, arrTop, alignEnd, composer, 0), modifierCompanion);
        // X's time, as X draws it.
        textFn.invoke(null, text, modifier, color, a3, fontSize, fontWeight, fontStyle, fontFamily, letterSpacing,
                decoration, align, lineHeight, overflow, softWrap, maxLines, minLines, onLayout, style, composer,
                changed0, changed1, defaults);
        beginNode(composer, rowPolicy.invoke(null, arrStart, alignCenterV, composer, 48), modifierCompanion);

        // Speed: "1.5×", 12 sp, in a pill of the note's text colour.
        Object speedTap = clickable.invoke(null, modifierCompanion, true, null, null, null, onClick(() -> {
            app.morphe.extension.twitter.patches.voicenotes.VoiceNoteControls.cycleSpeed();
            changed();
        }), 15);
        Object pill = paddingFn.invoke(null, backgroundFn.invoke(null, speedTap, alpha(color, 0.2f), pillShape), 5f, 0f);
        float speed = app.morphe.extension.twitter.patches.voicenotes.VoiceNoteControls.currentSpeed();
        long small = 0x100000000L | (Float.floatToRawIntBits(12f) & 0xFFFFFFFFL);   // TextUnit 12.sp
        textFn.invoke(null, speedText(speed), pill, color, a3, small, fontWeight, fontStyle, fontFamily,
                letterSpacing, decoration, align, lineHeight, overflow, softWrap, maxLines, minLines, onLayout, style,
                composer, 0, 0, defaults & ~2 & ~16);

        // Pauses: X's scissors icon; full colour while the note plays without pauses.
        if (bool(Settings.VOICE_NOTE_CUT_BUTTON) && scissorsRes != 0) {
            final String uri = curUri;
            final Object controller = curController;
            Entry e = uri == null ? null : ENTRIES.get(uri);
            boolean on = e != null && e.state == State.READY && e.on;
            boolean busy = e != null && e.state == State.PROCESSING;
            Object cutTap = clickable.invoke(null, modifierCompanion, uri != null && controller != null, null, null, null,
                    onClick(() -> {
                        if (uri != null && controller != null) toggle(uri, controller);
                    }), 15);
            Object icon = sizeFn.invoke(null, paddingFn.invoke(null, cutTap, 4f, 0f), 14f);
            iconFn.invoke(null, iconCtor.newInstance(scissorsRes), "Play without pauses", icon,
                    on ? color : alpha(color, busy ? 0.35f : 0.6f), false, composer, 0, 0x10);
        }
        nEnd.invoke(composer);   // row
        nEnd.invoke(composer);   // column
    }

    // endregion

    // region positions in a note playing without pauses (X's line and taps are the original's)

    private static double[][] currentSegments(double[] lengths) {
        String uri = lastUri;
        if (!lastCut || uri == null) return null;
        Entry e = ENTRIES.get(uri);
        if (e == null || e.segments == null || e.segments.isEmpty() || e.beforeMs <= 0 || e.afterMs <= 0) return null;
        lengths[0] = e.beforeMs / 1000.0;
        lengths[1] = e.afterMs / 1000.0;
        return e.segments.toArray(new double[0][]);
    }

    /** Injection point: X's progress (0..1 of the copy) as a position on the original's line. */
    public static float progressOut(float f) {
        double[] len = new double[2];
        double[][] segs = currentSegments(len);
        if (segs == null) return f;
        double t = f * len[1], src = len[0];
        for (int i = 0; i < segs.length; i++) {
            double next = i + 1 < segs.length ? segs[i + 1][2] : len[1] + 1;
            if (t < next) {
                src = Math.min(segs[i][1], segs[i][0] + Math.max(0, t - segs[i][2]));
                break;
            }
        }
        return (float) Math.max(0, Math.min(1, src / len[0]));
    }

    /** Injection point: a tap on the original's line (0..1) as a position in the copy (a pause: what follows it). */
    public static float seekIn(float f) {
        double[] len = new double[2];
        double[][] segs = currentSegments(len);
        if (segs == null) return f;
        double src = f * len[0], t = len[1];
        for (double[] s : segs) {
            if (src < s[0]) {
                t = s[2];
                break;
            }
            if (src <= s[1]) {
                t = s[2] + (src - s[0]);
                break;
            }
        }
        return (float) Math.max(0, Math.min(1, t / len[1]));
    }

    // endregion
}
