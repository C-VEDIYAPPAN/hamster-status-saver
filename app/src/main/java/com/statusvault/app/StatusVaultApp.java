package com.statusvault.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/** Shows the lock screen when the app comes to the foreground (if App lock is on). */
public class StatusVaultApp extends Application implements Application.ActivityLifecycleCallbacks {

    /** Coming back within this long (e.g. from the share sheet) doesn't re-lock. */
    private static final long GRACE_MS = 60_000L;

    private static StatusVaultApp instance;
    private int started = 0;
    private boolean inBackground = true;
    private long backgroundSince = 0;
    private boolean unlocked = false;
    private boolean suppressNextCheck = false;

    static StatusVaultApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        registerActivityLifecycleCallbacks(this);
        AutoSaveJob.createChannel(this);
        AutoSaveJob.sync(this);
    }

    void markUnlocked() {
        unlocked = true;
    }

    /** Called before the lock screen opens another app (the system PIN screen on older Android). */
    void suppressNextLockCheck() {
        suppressNextCheck = true;
    }

    @Override
    public void onActivityStarted(Activity activity) {
        boolean cameToForeground = started++ == 0 && inBackground;
        if (!cameToForeground) return;
        inBackground = false;
        if (suppressNextCheck) {
            suppressNextCheck = false;
            return;
        }
        if (activity instanceof LockActivity || !new Prefs(this).appLock()) return;
        boolean expired = SystemClock.elapsedRealtime() - backgroundSince > GRACE_MS;
        if (!unlocked || expired) {
            unlocked = false;
            activity.startActivity(new Intent(activity, LockActivity.class));
            activity.overridePendingTransition(0, 0);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (--started == 0 && !activity.isChangingConfigurations()) {
            inBackground = true;
            backgroundSince = SystemClock.elapsedRealtime();
        }
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityResumed(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
