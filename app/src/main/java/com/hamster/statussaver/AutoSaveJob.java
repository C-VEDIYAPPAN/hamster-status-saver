package com.hamster.statussaver;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.concurrent.TimeUnit;

/** Settings → Auto-save → "Check in background": saves new statuses about once an hour. */
public class AutoSaveJob extends JobService {

    private static final int JOB_ID = 4201;
    private static final String CHANNEL = "autosave";

    /** Schedules or cancels the job to match the current settings. */
    static void sync(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        Prefs p = new Prefs(c);
        if (p.autoSave() && p.backgroundSave()) {
            if (js.getPendingJob(JOB_ID) == null) {
                js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, AutoSaveJob.class))
                        .setPeriodic(TimeUnit.HOURS.toMillis(1))
                        .setRequiresBatteryNotLow(true)
                        .setPersisted(true)
                        .build());
            }
        } else {
            js.cancel(JOB_ID);
        }
    }

    static void createChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Auto-save", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Tells you when new statuses were saved in the background");
        nm.createNotificationChannel(ch);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try {
                    n = new StatusRepository(AutoSaveJob.this).autoSaveNew();
                } catch (Exception ignored) {
                }
                if (n > 0) notifySaved(n);
                jobFinished(params, false);
            }
        }, "auto-save").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // retry later
    }

    private void notifySaved(int n) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_SAVED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setColor(new Palette(this).primary)
                .setContentTitle("Saved " + n + (n == 1 ? " new status" : " new statuses"))
                .setContentText("Tap to see them in Saved")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(JOB_ID, notification);
    }
}
