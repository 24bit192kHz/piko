/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * The app was replaced by a new version (MY_PACKAGE_REPLACED) and the update was started from the
 * update prompt a moment ago: the app opens again. Android lets an app open a screen from the
 * background only while it shows a window over other apps, so with "Display over other apps"
 * allowed a 1-pixel window is shown for that moment; otherwise a notification opens it with one tap.
 */
public final class UpdateRestart extends BroadcastReceiver {
    private static final String CHANNEL = "piko_updates";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences("piko_updates", Context.MODE_PRIVATE);
        long at = p.getLong("restart_at", 0);
        p.edit().remove("restart_at").commit();
        if (at == 0 || System.currentTimeMillis() - at > 10 * 60 * 1000L) return;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Context app = context.getApplicationContext();
        if (Settings.canDrawOverlays(app) && openWithOverlay(app, launch)) return;
        notifyUpdated(app, launch);
    }

    private static boolean openWithOverlay(Context c, Intent launch) {
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            View dot = new View(c);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(dot, lp);
            Handler h = new Handler(Looper.getMainLooper());
            // Once the window is on screen, open the app, then take the window away.
            h.postDelayed(() -> {
                try {
                    c.startActivity(launch);
                } catch (Throwable t) {
                    notifyUpdated(c, launch);
                }
                h.postDelayed(() -> {
                    try {
                        wm.removeView(dot);
                    } catch (Throwable ignored) {
                    }
                }, 1500);
            }, 300);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void notifyUpdated(Context c, Intent launch) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        AdvancedSearch.t(c, "piko_update_channel", "Piko updates"), NotificationManager.IMPORTANCE_HIGH));
            }
            PendingIntent open = PendingIntent.getActivity(c, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(c.getApplicationInfo().icon)
                    .setContentTitle(AdvancedSearch.t(c, "piko_update_done", "Piko updated"))
                    .setContentText(AdvancedSearch.t(c, "piko_update_open", "Tap to open"))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setTimeoutAfter(10 * 60 * 1000L);
            nm.notify(0x70696b, b.build());
        } catch (Throwable ignored) {
        }
    }
}
