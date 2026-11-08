package com.hamster.statussaver;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Twice-a-day background check: notifies once per new version. */
public class UpdateJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Updater.Release r = Updater.check(UpdateJob.this);
                    Prefs prefs = new Prefs(UpdateJob.this);
                    if (r != null && !r.version.equals(prefs.notifiedVersion())) {
                        prefs.setNotifiedVersion(r.version); // don't repeat the same alert
                        Updater.notifyAvailable(UpdateJob.this, r);
                    }
                } catch (Exception ignored) {
                    // offline or GitHub unavailable — try again next time
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
