package com.skyble.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Ongoing "downloading" notification, so a long download stays visible in the
 * shade and on the lock screen instead of hiding in the app. One download at a
 * time is enough for this browser, so a single fixed id is used.
 */
final class Notify {

    private static final String CHANNEL = "downloads";
    private static final int ID = 7401;

    private Notify() { }

    /** Android 13+ ছাড়া অনুমতি লাগে না */
    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    static void start(Context c, Downloader.Task task) {
        post(c, task, -1, true);
    }

    /** শতাংশ বদলালেই ঠেলাঠেলি করে আপডেট হয় */
    static void tick(Context c, Downloader.Task task) {
        int p = task.percent();
        String phase = task.phase == null ? "" : task.phase;
        if (p == task.notified && phase.equals(task.notifiedPhase)) return;
        task.notified = p;
        task.notifiedPhase = phase;
        post(c, task, p, true);
    }

    static void done(Context c, Downloader.Task task) {
        finish(c, task.name, Lang.t("ডাউনলোড সম্পন্ন"));
    }

    static void fail(Context c, String name, String why) {
        finish(c, name, Lang.t("ডাউনলোড হয়নি") + (why == null || why.length() == 0 ? "" : " · " + why));
    }

    static void cancel(Context c) {
        try {
            NotificationManager nm = manager(c);
            if (nm != null) nm.cancel(ID);
        } catch (Exception ignored) { }
    }

    private static void finish(Context c, String name, String text) {
        if (!allowed(c)) return;
        try {
            NotificationManager nm = manager(c);
            if (nm == null) return;
            Notification.Builder b = builder(c);
            if (b == null) return;
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(open(c));
            nm.notify(ID, b.build());
        } catch (Exception ignored) { }
    }

    private static void post(Context c, Downloader.Task task, int percent, boolean ongoing) {
        if (!allowed(c)) return;
        try {
            NotificationManager nm = manager(c);
            if (nm == null) return;
            Notification.Builder b = builder(c);
            if (b == null) return;
            String text = task.phase == null || task.phase.length() == 0
                    ? task.name : task.phase;
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(task.name)
                    .setContentText(percent < 0 ? text : percent + "% · " + text)
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setProgress(100, Math.max(0, percent), percent < 0)
                    .setContentIntent(open(c));
            nm.notify(ID, b.build());
        } catch (Exception ignored) { }
    }

    private static NotificationManager manager(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        "Downloads", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            return nm;
        }
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(c, CHANNEL);
        }
        Notification.Builder b = new Notification.Builder(c);
        b.setPriority(Notification.PRIORITY_LOW);
        return b;
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        return PendingIntent.getActivity(c, 0, i, flags);
    }
}
