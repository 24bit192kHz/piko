/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Lookup order, batching and budgets follow Control Panel for Twitter
 * (https://github.com/insin/control-panel-for-twitter), MIT License,
 * Copyright (c) 2019 Jonny Buchanan. The shared cache is X-Posed's
 * (https://x-posed-cache.xaitax.workers.dev).
 */

package app.morphe.extension.twitter.patches.country;

import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import app.morphe.extension.crimera.PikoUtils;

/**
 * Finds countries for handles the local database doesn't know: X-Posed's shared cache first
 * (one request per 100 handles, at most 30 a minute), then X itself for what the cache doesn't
 * know (a small budget, only while the app is being used). X's own answers are shared back to
 * the cache once a day per account. Nothing here runs on the timeline parse thread.
 */
public final class CountryLookup {
    private static final String CLOUD_LOOKUP_URL = "https://x-posed-cache.xaitax.workers.dev/lookup?users=";
    private static final String CLOUD_CONTRIBUTE_URL = "https://x-posed-cache.xaitax.workers.dev/contribute";
    private static final int CLOUD_BATCH = 100;
    private static final long CLOUD_DELAY_MS = 1500;
    private static final long CLOUD_FAST_DELAY_MS = 200;
    private static final int CLOUD_MAX_PER_MINUTE = 30;
    private static final long CLOUD_BACKOFF_MS = 5 * 60_000L;

    private static final long CONTRIBUTE_DELAY_MS = 10_000;
    private static final int CONTRIBUTE_BATCH = 200;
    private static final long CONTRIBUTE_AGAIN_MS = 86_400_000L;
    private static final long CONTRIBUTE_BACKOFF_MS = 5 * 60_000L;

    // X allows 50 AboutAccountQuery lookups per 15 minutes per account. This client can't read
    // the rate-limit headers, so it spends a fixed 30 per window, 1.5 s apart, and stops until the
    // window ends on any failure.
    private static final long X_WINDOW_MS = 15 * 60_000L;
    private static final int X_PER_WINDOW = 30;
    private static final long X_GAP_MS = 1500;
    private static final int X_QUEUE_CAP = 300;
    // X lookups only run while the app is in use: within this long after posts were last seen.
    private static final long X_ACTIVE_MS = 2 * 60_000L;

    private static final Object lock = new Object();
    private static Handler handler;

    private static final LinkedHashSet<String> cloudQueue = new LinkedHashSet<>();
    private static long cloudWindowStart;
    private static int cloudWindowCount;
    private static long cloudPausedUntil;
    private static boolean cloudScheduled;

    private static final LinkedHashMap<String, JSONObject> contributeQueue = new LinkedHashMap<>();
    private static long contributePausedUntil;
    private static boolean contributeScheduled;

    private static final LinkedHashSet<String> xQueue = new LinkedHashSet<>();
    private static long xWindowStart;
    private static int xWindowCount;
    private static long xPausedUntil;
    private static long lastXLookup;
    private static boolean xScheduled;
    private static volatile long lastSeenAt;
    private static volatile boolean xBroken;

    private static WeakReference<Object> graphqlClient = new WeakReference<>(null);

    static final boolean DEBUG = true;

    static void debug(String msg) {
        if (DEBUG) android.util.Log.d("PikoCountries", msg);
    }

    private CountryLookup() {
    }

    private static Handler handler() {
        synchronized (lock) {
            if (handler == null) {
                HandlerThread thread = new HandlerThread("PikoCountries");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
            return handler;
        }
    }

    // region injection points and placeholders (the patch fills in the class names)

    private static String graphqlInterface() {
        return "";
    }

    private static String aboutQueryClass() {
        return "";
    }

    private static String aboutRepositoryClass() {
        return "";
    }

    /** Injection point: the com.x GraphQL client was created (the newest one belongs to the active account). */
    public static void onGraphqlClient(Object client) {
        graphqlClient = new WeakReference<>(client);
    }

    // endregion

    /** A handle appeared on screen and its country is unknown: ask the cache soon, then X. */
    public static void request(String handle) {
        String h = CountryStore.normalize(handle);
        if (h == null) return;
        long now = System.currentTimeMillis();
        lastSeenAt = now;
        if (CountrySettings.cloudLookup() && CountryStore.needsCloud(h, now)) {
            synchronized (lock) {
                cloudQueue.add(h);
            }
            scheduleCloud(CLOUD_FAST_DELAY_MS);
        } else if (CountrySettings.xLookup() && CountryStore.needsX(h, now)) {
            queueX(h);
        }
    }

    // region shared cache

    private static void scheduleCloud(long delay) {
        synchronized (lock) {
            if (cloudScheduled || cloudQueue.isEmpty()) return;
            cloudScheduled = true;
            long due = Math.max(System.currentTimeMillis() + delay, cloudPausedUntil);
            handler().postDelayed(CountryLookup::flushCloud, Math.max(0, due - System.currentTimeMillis()));
        }
    }

    private static void flushCloud() {
        List<String> handles = new ArrayList<>();
        synchronized (lock) {
            cloudScheduled = false;
            long now = System.currentTimeMillis();
            if (now - cloudWindowStart >= 60_000) {
                cloudWindowStart = now;
                cloudWindowCount = 0;
            }
            if (cloudWindowCount >= CLOUD_MAX_PER_MINUTE) {
                cloudPausedUntil = Math.max(cloudPausedUntil, cloudWindowStart + 60_000);
                scheduleCloud(0);
                return;
            }
            cloudWindowCount++;
            Iterator<String> it = cloudQueue.iterator();
            while (it.hasNext() && handles.size() < CLOUD_BATCH) {
                handles.add(it.next());
                it.remove();
            }
        }
        if (handles.isEmpty()) return;
        if (!CountrySettings.cloudLookup()) {
            for (String h : handles) queueX(h);
            return;
        }
        try {
            StringBuilder url = new StringBuilder(CLOUD_LOOKUP_URL);
            for (int i = 0; i < handles.size(); i++) {
                if (i > 0) url.append(',');
                url.append(URLEncoder.encode(handles.get(i), "UTF-8"));
            }
            JSONObject json = new JSONObject(http("GET", url.toString(), null));
            JSONObject results = json.optJSONObject("results");
            boolean changed = false;
            for (String h : handles) {
                JSONObject e = results == null ? null : results.optJSONObject(h);
                if (e == null && results != null) e = findIgnoreCase(results, h);
                String country = e == null ? null : e.optString("l", null);
                boolean accurate = e == null || !e.has("a") || e.optBoolean("a", true);
                changed |= CountryStore.putFromCloud(h, country, accurate);
                if (country == null) queueX(h);
            }
            debug("cloud: " + handles.size() + " asked, " + (results == null ? 0 : results.length()) + " known");
            if (changed) CountryFilter.countriesChanged();
        } catch (Exception ex) {
            PikoUtils.logger(ex);
            synchronized (lock) {
                cloudPausedUntil = System.currentTimeMillis() + CLOUD_BACKOFF_MS;
                cloudQueue.addAll(handles);
            }
        }
        scheduleCloud(CLOUD_DELAY_MS);
    }

    private static JSONObject findIgnoreCase(JSONObject obj, String key) {
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k.equalsIgnoreCase(key)) return obj.optJSONObject(k);
        }
        return null;
    }

    /** Shares X's own answer with the cache: once a day per account, unless X now says something else. */
    public static void contribute(String handle, String country, boolean accurate) {
        if (!CountrySettings.contribute()) return;
        String h = CountryStore.normalize(handle);
        if (h == null || country == null || country.isEmpty()) return;
        CountryStore.Record rec = CountryStore.get(h);
        if (rec != null && System.currentTimeMillis() - rec.contributedAt < CONTRIBUTE_AGAIN_MS
                && country.equals(rec.contributedCountry)) return;
        try {
            JSONObject entry = new JSONObject();
            entry.put("l", country.length() > 60 ? country.substring(0, 60) : country);
            entry.put("a", accurate);
            synchronized (lock) {
                contributeQueue.put(h, entry);
                if (contributeScheduled) return;
                contributeScheduled = true;
                long delay = contributeQueue.size() >= CONTRIBUTE_BATCH ? 0 : CONTRIBUTE_DELAY_MS;
                long due = Math.max(System.currentTimeMillis() + delay, contributePausedUntil);
                handler().postDelayed(CountryLookup::flushContributions, Math.max(0, due - System.currentTimeMillis()));
            }
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    private static void flushContributions() {
        Map<String, JSONObject> batch = new LinkedHashMap<>();
        synchronized (lock) {
            contributeScheduled = false;
            Iterator<Map.Entry<String, JSONObject>> it = contributeQueue.entrySet().iterator();
            while (it.hasNext() && batch.size() < CONTRIBUTE_BATCH) {
                Map.Entry<String, JSONObject> e = it.next();
                batch.put(e.getKey(), e.getValue());
                it.remove();
            }
        }
        if (batch.isEmpty() || !CountrySettings.contribute()) return;
        try {
            JSONObject entries = new JSONObject();
            for (Map.Entry<String, JSONObject> e : batch.entrySet()) entries.put(e.getKey(), e.getValue());
            http("POST", CLOUD_CONTRIBUTE_URL, new JSONObject().put("entries", entries).toString());
            debug("contributed " + batch.size());
            for (Map.Entry<String, JSONObject> e : batch.entrySet()) {
                CountryStore.markContributed(e.getKey(), e.getValue().optString("l"));
            }
        } catch (Exception ex) {
            PikoUtils.logger(ex);
            synchronized (lock) {
                contributePausedUntil = System.currentTimeMillis() + CONTRIBUTE_BACKOFF_MS;
                for (Map.Entry<String, JSONObject> e : batch.entrySet()) contributeQueue.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        synchronized (lock) {
            if (!contributeQueue.isEmpty() && !contributeScheduled) {
                contributeScheduled = true;
                long due = Math.max(System.currentTimeMillis() + CONTRIBUTE_DELAY_MS, contributePausedUntil);
                handler().postDelayed(CountryLookup::flushContributions, Math.max(0, due - System.currentTimeMillis()));
            }
        }
    }

    /** Plain HTTPS without the app's client, cookies or headers: the cache never sees an X identity. */
    private static String http(String method, String url, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // endregion

    // region X lookups

    private static void queueX(String h) {
        if (!CountrySettings.xLookup() || xBroken) return;
        synchronized (lock) {
            xQueue.remove(h);
            xQueue.add(h); // newest last: looked up first
            while (xQueue.size() > X_QUEUE_CAP) {
                Iterator<String> it = xQueue.iterator();
                it.next();
                it.remove();
            }
            scheduleX();
        }
    }

    private static void scheduleX() {
        synchronized (lock) {
            if (xScheduled || xQueue.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - xWindowStart >= X_WINDOW_MS) {
                xWindowStart = now;
                xWindowCount = 0;
            }
            long due = Math.max(lastXLookup + X_GAP_MS, xPausedUntil);
            if (xWindowCount >= X_PER_WINDOW) due = Math.max(due, xWindowStart + X_WINDOW_MS);
            xScheduled = true;
            handler().postDelayed(CountryLookup::runX, Math.max(0, due - now));
        }
    }

    private static void runX() {
        String handle;
        synchronized (lock) {
            xScheduled = false;
            long now = System.currentTimeMillis();
            if (!CountrySettings.xLookup() || xBroken) {
                xQueue.clear();
                return;
            }
            // Only while the app is being used; resumes when posts are seen again.
            if (now - lastSeenAt > X_ACTIVE_MS || xQueue.isEmpty()) return;
            if (now - xWindowStart >= X_WINDOW_MS) {
                xWindowStart = now;
                xWindowCount = 0;
            }
            if (xWindowCount >= X_PER_WINDOW || now < xPausedUntil) {
                scheduleX();
                return;
            }
            Iterator<String> it = xQueue.iterator();
            handle = null;
            while (it.hasNext()) handle = it.next();
            xQueue.remove(handle);
            xWindowCount++;
            lastXLookup = now;
        }
        if (!CountryStore.needsX(handle, System.currentTimeMillis())) {
            scheduleX();
            return;
        }
        CountryStore.markXAsked(handle);
        try {
            Object[] about = lookupX(handle);
            if (about != null) {
                String country = (String) about[0];
                boolean accurate = (Boolean) about[1];
                debug("x: " + handle + " -> " + country);
                if (CountryStore.putFromX(handle, country, accurate)) CountryFilter.countriesChanged();
                if (country != null) contribute(handle, country, accurate);
            }
        } catch (Exception ex) {
            PikoUtils.logger(ex);
            synchronized (lock) {
                xPausedUntil = xWindowStart + X_WINDOW_MS;
            }
        }
        scheduleX();
    }

    /** X's AboutAccountQuery through the app's own client: {country, accurate}, or null. */
    private static Object[] lookupX(String handle) throws Exception {
        Object client = graphqlClient.get();
        if (client == null) throw new IllegalStateException("No X client yet");
        try {
            ClassLoader cl = client.getClass().getClassLoader();
            Class<?> graphql = Class.forName(graphqlInterface(), true, cl);
            Class<?> queryClass = Class.forName(aboutQueryClass(), true, cl);
            Object query = queryClass.getConstructor(String.class).newInstance(handle);

            Method execute = null;
            for (Method m : graphql.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) && p.length == 9 && p[0] == graphql && p[8] == int.class
                        && p[7].getName().equals("kotlin.coroutines.Continuation")) {
                    execute = m;
                    break;
                }
            }
            if (execute == null) throw new IllegalStateException("GraphQL execute not found");
            execute.setAccessible(true);
            Object result = await(execute, client, query, cl);

            Object data = findInstance(result, queryClass.getName() + "$", 3);
            if (data == null) return new Object[]{null, true}; // an error answer: nothing known
            // data → user result (String + inner) → inner (String + profile fragment)
            Object user = firstNonStringField(data);
            if (user == null) return new Object[]{null, true};
            Object inner = firstNonStringField(user);
            Object fragment = inner == null ? null : firstNonStringField(inner);
            String typename = firstStringField(user);
            if (fragment == null) return new Object[]{null, true};

            Class<?> repoClass = Class.forName(aboutRepositoryClass(), true, cl);
            Constructor<?> ctor = null;
            for (Constructor<?> c : repoClass.getDeclaredConstructors()) {
                if (c.getParameterTypes().length == 1 && c.getParameterTypes()[0].isAssignableFrom(client.getClass())) ctor = c;
            }
            if (ctor == null) throw new IllegalStateException("About repository constructor not found");
            ctor.setAccessible(true);
            Object repo = ctor.newInstance(client);
            Method mapper = null;
            for (Method m : repoClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0].isInstance(fragment) && p[1] == String.class) mapper = m;
            }
            if (mapper == null) throw new IllegalStateException("About mapper not found");
            mapper.setAccessible(true);
            Object mapped = mapper.invoke(repo, fragment, typename);
            Object about = null;
            for (Field f : mapped.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(mapped);
                if (v != null && hasMethod(v, "getBasedInCountry")) about = v;
            }
            if (about == null) return new Object[]{null, true};
            String country = (String) about.getClass().getMethod("getBasedInCountry").invoke(about);
            Object accurate = null;
            for (String name : new String[]{"isLocationAccurate", "getIsLocationAccurate"}) {
                try {
                    accurate = about.getClass().getMethod(name).invoke(about);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Kotlin names the getter of "isLocationAccurate" either way.
                }
            }
            return new Object[]{country, !Boolean.FALSE.equals(accurate)};
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalStateException ex) {
            // The app changed under us: no X lookups this session.
            if (!(ex instanceof IllegalStateException) || !"No X client yet".equals(ex.getMessage())) xBroken = true;
            throw ex;
        }
    }

    /** Calls a suspend function from Java and waits (on the lookup thread) for its result. */
    private static Object await(Method suspendFn, Object client, Object query, ClassLoader cl) throws Exception {
        Class<?> continuationClass = Class.forName("kotlin.coroutines.Continuation", true, cl);
        Class<?> emptyContextClass = Class.forName("kotlin.coroutines.EmptyCoroutineContext", true, cl);
        Object emptyContext = null;
        for (Field f : emptyContextClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == emptyContextClass) {
                f.setAccessible(true);
                emptyContext = f.get(null);
            }
        }
        Object suspended = Class.forName("kotlin.coroutines.intrinsics.CoroutineSingletons", true, cl)
                .getField("COROUTINE_SUSPENDED").get(null);
        final Object context = emptyContext;
        final Object[] box = new Object[1];
        final CountDownLatch done = new CountDownLatch(1);
        Object continuation = Proxy.newProxyInstance(cl, new Class<?>[]{continuationClass}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getContext":
                    return context;
                case "resumeWith":
                    box[0] = args[0];
                    done.countDown();
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "PikoContinuation";
                default:
                    return null;
            }
        });
        Object direct = suspendFn.invoke(null, client, query, null, null, null, null, null, continuation, 62);
        if (direct != suspended) return unwrapResult(direct);
        if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("X lookup timed out");
        return unwrapResult(box[0]);
    }

    /** kotlin.Result failures arrive as Result$Failure holding the exception. */
    private static Object unwrapResult(Object value) throws Exception {
        if (value != null && value.getClass().getName().equals("kotlin.Result$Failure")) {
            Field f = value.getClass().getDeclaredField("exception");
            f.setAccessible(true);
            Object t = f.get(value);
            throw t instanceof Exception ? (Exception) t : new IllegalStateException(String.valueOf(t));
        }
        return value;
    }

    private static Object findInstance(Object obj, String classPrefix, int depth) throws Exception {
        if (obj == null || depth < 0) return null;
        if (obj.getClass().getName().startsWith(classPrefix)) return obj;
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object found = findInstance(f.get(obj), classPrefix, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    private static Object firstNonStringField(Object obj) throws Exception {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() == String.class || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v != null) return v;
        }
        return null;
    }

    private static String firstStringField(Object obj) throws Exception {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            f.setAccessible(true);
            return (String) f.get(obj);
        }
        return null;
    }

    private static boolean hasMethod(Object obj, String name) {
        for (Method m : obj.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == 0) return true;
        return false;
    }

    // endregion
}
