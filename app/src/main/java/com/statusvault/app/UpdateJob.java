package com.statusvault.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;

import java.io.File;

/**
 * Twice-a-day background check. Downloads and verifies a new version; then installs it silently
 * when Android allows (Android 12+, after the first self-update), otherwise notifies the user.
 */
public class UpdateJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File apk = Updater.checkAndDownload(UpdateJob.this);
                    if (apk != null) {
                        StatusVaultApp app = StatusVaultApp.get();
                        boolean appInUse = app != null && app.isForeground();
                        if (!appInUse && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                && Updater.canInstall(UpdateJob.this)) {
                            // Silent if Android allows; if not, InstallReceiver turns it into a notification.
                            Updater.commit(UpdateJob.this, apk);
                        } else {
                            Updater.notifyReady(UpdateJob.this, Updater.versionOf(apk));
                        }
                    }
                } catch (Exception ignored) {
                    // No network, GitHub unavailable, etc. — try again next time.
                }
                jobFinished(params, false);
            }
        }, "update-check").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
