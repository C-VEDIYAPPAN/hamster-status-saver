package com.statusvault.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Self-update from GitHub Releases.
 *
 * 1. Reads the latest release of C-VEDIYAPPAN/StatusVault (the only network call the app makes).
 * 2. If it's newer, downloads the APK into the app's private storage.
 * 3. Accepts it only if it is this same app, a higher version, and signed with the same key.
 * 4. Installs it through PackageInstaller: the first time Android asks the user to confirm;
 *    on Android 12+ later updates can install silently, because the app is then its own installer.
 *
 * Turned off automatically when the app came from Google Play (Play forbids self-updating).
 */
final class Updater {

    static final String REPO = "C-VEDIYAPPAN/StatusVault";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String ACTION_INSTALL_STATUS = "com.statusvault.app.INSTALL_STATUS";
    private static final String CHANNEL = "updates";
    static final int NOTIFY_ID = 4203;
    private static final int JOB_ID = 4202;
    private static final long MAX_APK_BYTES = 50L * 1024 * 1024;

    private Updater() {
    }

    static final class Release {
        final String version;
        final String apkUrl;

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
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

    @SuppressWarnings("deprecation")
    private static long currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
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

    // ---------------- Check & download (blocking — call off the main thread) ----------------

    static Release fetchLatest() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(LATEST_API).openConnection();
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "StatusVault-Android");
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

    /**
     * Checks GitHub and, if there's a newer version, downloads and verifies it.
     * Returns the ready-to-install APK, or null when already up to date.
     */
    static File checkAndDownload(Context c) throws Exception {
        Prefs prefs = new Prefs(c);
        prefs.setLastUpdateCheck(System.currentTimeMillis());
        Release r = fetchLatest();
        prefs.setLatestVersion(r.version);
        if (r.apkUrl == null || compare(r.version, currentVersion(c)) <= 0) {
            clean(c, null);
            return null;
        }
        File ready = readyFile(c, r.version);
        if (ready.exists() && verify(c, ready)) return ready;
        clean(c, null);
        File tmp = new File(updatesDir(c), "download.tmp");
        download(r.apkUrl, tmp);
        if (!tmp.renameTo(ready)) throw new IOException("Couldn't store the update");
        if (!verify(c, ready)) {
            //noinspection ResultOfMethodCallIgnored
            ready.delete();
            throw new IOException("The downloaded update didn't pass the safety check");
        }
        return ready;
    }

    /** An already downloaded, verified update that's newer than the installed app, or null. */
    static File readyUpdate(Context c) {
        File[] files = updatesDir(c).listFiles();
        if (files == null) return null;
        File best = null;
        String bestVersion = currentVersion(c);
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith("StatusVault-v") || !n.endsWith(".apk")) continue;
            String v = n.substring("StatusVault-v".length(), n.length() - 4);
            if (compare(v, bestVersion) > 0 && verify(c, f)) {
                best = f;
                bestVersion = v;
            }
        }
        return best;
    }

    static String versionOf(File apk) {
        String n = apk.getName();
        return n.substring("StatusVault-v".length(), n.length() - 4);
    }

    private static File updatesDir(Context c) {
        File d = new File(c.getFilesDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File readyFile(Context c, String version) {
        return new File(updatesDir(c), "StatusVault-v" + version + ".apk");
    }

    /** Deletes downloaded updates except [keep]. */
    static void clean(Context c, File keep) {
        File[] files = updatesDir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (keep == null || !f.equals(keep)) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    private static void download(String url, File into) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "StatusVault-Android");
        con.setConnectTimeout(15_000);
        con.setReadTimeout(60_000);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("Download failed (" + code + ")");
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(into)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_APK_BYTES) throw new IOException("Update is unexpectedly large");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            con.disconnect();
        }
    }

    // ---------------- Safety check ----------------

    /** Same package, higher version code, and signed with exactly the same key as this app. */
    @SuppressWarnings("deprecation")
    static boolean verify(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo theirs = archiveInfo(pm, apk);
            if (theirs == null || !c.getPackageName().equals(theirs.packageName)) return false;
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? theirs.getLongVersionCode() : theirs.versionCode;
            if (code <= currentVersionCode(c)) return false;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo ours = pm.getPackageInfo(c.getPackageName(), flags);
            Set<Signature> a = signers(ours), b = signers(theirs);
            return !a.isEmpty() && a.equals(b);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo archiveInfo(PackageManager pm, File apk) {
        PackageInfo info = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info != null && signers(info).isEmpty()) info = null;
        }
        if (info == null) info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        return info;
    }

    @SuppressWarnings("deprecation")
    private static Set<Signature> signers(PackageInfo info) {
        Signature[] s = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            s = info.signingInfo.getApkContentsSigners();
        }
        if (s == null || s.length == 0) s = info.signatures;
        return s == null ? new HashSet<Signature>() : new HashSet<>(Arrays.asList(s));
    }

    // ---------------- Install ----------------

    /** True if Android lets this app install packages (the "install unknown apps" switch). */
    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Starts installing [apk] from a screen the user is looking at. */
    static void install(final Activity a, final File apk, Notice notice) {
        if (!canInstall(a)) {
            new Prefs(a).setPendingInstall(true);
            notice.show("Allow Status Vault to install updates, then come back", Notice.INFO);
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException ignored) {
            }
            return;
        }
        new Prefs(a).setPendingInstall(false);
        notice.show("Installing update " + versionOf(apk) + "…", Notice.INFO);
        final Context app = a.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    commit(app, apk);
                } catch (Exception e) {
                    notifyText(app, "Update couldn't be installed", "Please try again from Settings → Updates.", null);
                }
            }
        }, "install-update").start();
    }

    /**
     * Hands the APK to Android's installer. On Android 12+ we ask for no user action; Android
     * honours that once this app is its own installer, otherwise it asks the user to confirm.
     */
    static void commit(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(c, InstallReceiver.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, id, result, flags);
            session.commit(pi.getIntentSender());
        }
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
        ch.setDescription("New versions of Status Vault");
        nm.createNotificationChannel(ch);
    }

    /** "Update ready" notification; tapping opens the app and installs. */
    static void notifyReady(Context c, String version) {
        Intent open = new Intent(c, MainActivity.class)
                .putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 7, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifyText(c, "Status Vault " + version + " is ready", "Tap to install the update", pi);
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
