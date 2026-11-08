package com.hamster.statussaver;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Update alerts from GitHub Releases.
 *
 * The app reads the latest release of C-VEDIYAPPAN/hamster-status-saver (its only network call) and, if it
 * is newer, tells the user and opens the APK download in the browser. Android's own installer does
 * the rest — and Android refuses any update that isn't signed with the same key.
 *
 * The app deliberately does NOT install packages itself: apps that do get flagged by Play Protect
 * ("Harmful app blocked") when installed outside Google Play.
 *
 * Switched off automatically when the app came from Google Play (Play handles its updates).
 */
final class Updater {

    static final String REPO = "C-VEDIYAPPAN/hamster-status-saver";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String CHANNEL = "updates";
    static final int NOTIFY_ID = 4203;
    private static final int JOB_ID = 4202;

    private Updater() {
    }

    static final class Release {
        final String version;
        final String apkUrl;

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
        }

        /** Direct APK link, or the release page if the release has no APK attached. */
        String downloadUrl() {
            return apkUrl != null ? apkUrl : RELEASES_PAGE;
        }
    }

    // ---------------- Version info ----------------

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** False when installed from Google Play — updates must then come from Play. */
    @SuppressWarnings("deprecation")
    static boolean available(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            String installer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? pm.getInstallSourceInfo(c.getPackageName()).getInstallingPackageName()
                    : pm.getInstallerPackageName(c.getPackageName());
            return !"com.android.vending".equals(installer);
        } catch (Exception e) {
            return true;
        }
    }

    /** Compares dotted versions numerically: "2.10" > "2.9". */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    // ---------------- Check (blocking — call off the main thread) ----------------

    static Release fetchLatest() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(LATEST_API).openConnection();
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "Hamster-Android");
        con.setConnectTimeout(15_000);
        con.setReadTimeout(20_000);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("GitHub answered " + code);
            JSONObject o = new JSONObject(readAll(con.getInputStream()));
            String tag = o.getString("tag_name");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            String apk = null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.getString("browser_download_url");
                        break;
                    }
                }
            }
            return new Release(version, apk);
        } finally {
            con.disconnect();
        }
    }

    /** Checks GitHub. Returns the newer release, or null when already up to date. Remembers the result. */
    static Release check(Context c) throws Exception {
        Prefs prefs = new Prefs(c);
        prefs.setLastUpdateCheck(System.currentTimeMillis());
        Release r = fetchLatest();
        if (compare(r.version, currentVersion(c)) > 0) {
            prefs.setLatestVersion(r.version);
            prefs.setLatestApkUrl(r.downloadUrl());
            return r;
        }
        prefs.setLatestVersion(null);
        prefs.setLatestApkUrl(null);
        return null;
    }

    /** The newer release found by the last check, if it's still newer than what's installed. */
    static Release knownUpdate(Context c) {
        Prefs prefs = new Prefs(c);
        String v = prefs.latestVersion();
        if (v == null || compare(v, currentVersion(c)) <= 0) return null;
        return new Release(v, prefs.latestApkUrl());
    }

    /** Opens the APK download in the browser; Android's installer takes it from there. */
    static void download(Activity a, Release r, Notice notice) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.downloadUrl())));
            notice.show("When the download finishes, tap it to install", Notice.INFO);
        } catch (ActivityNotFoundException e) {
            notice.show("No browser found to download the update", Notice.ERROR);
        }
    }

    /** Removes files left by v2.8, which downloaded updates itself. */
    static void cleanLegacy(Context c) {
        File dir = new File(c.getFilesDir(), "updates");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------------- Background schedule & notifications ----------------

    /** Schedules (or cancels) the twice-a-day background check to match Settings. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (new Prefs(c).autoUpdate() && available(c)) {
            if (js.getPendingJob(JOB_ID) == null) {
                js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                        .setPeriodic(TimeUnit.HOURS.toMillis(12))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
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
        NotificationChannel ch = new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("New versions of Hamster");
        nm.createNotificationChannel(ch);
    }

    /** "Update available" notification; tapping starts the download in the browser. */
    static void notifyAvailable(Context c, Release r) {
        PendingIntent pi = PendingIntent.getActivity(c, 7,
                new Intent(Intent.ACTION_VIEW, Uri.parse(r.downloadUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifyText(c, "Hamster " + r.version + " is available", "Tap to download the update", pi);
    }

    static void notifyText(Context c, String title, String text, PendingIntent tap) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setColor(new Palette(c).primary)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true);
        if (tap != null) b.setContentIntent(tap);
        nm.notify(NOTIFY_ID, b.build());
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
