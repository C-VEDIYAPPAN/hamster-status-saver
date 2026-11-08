package com.statusvault.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

/**
 * App lock coordinator: shows the lock screen when the app returns to the foreground,
 * and hides the app's content from the recent-apps screen while App lock is on.
 */
public class StatusVaultApp extends Application implements Application.ActivityLifecycleCallbacks {

    private static StatusVaultApp instance;

    private int started = 0;
    private boolean inBackground = true;
    private long backgroundSince = 0;
    private boolean unlocked = false;
    private long unlockedAt = 0;
    private boolean suppressNextCheck = false;
    /** True while a LockActivity exists, so a second one is never stacked on top. */
    private boolean lockShowing = false;

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
        Updater.createChannel(this);
        Updater.schedule(this);
    }

    /** True while any of the app's screens is visible. */
    boolean isForeground() {
        return started > 0;
    }

    void markUnlocked() {
        unlocked = true;
        unlockedAt = SystemClock.elapsedRealtime();
    }

    /** Called before the lock screen opens another app (the system PIN screen on older Android). */
    void suppressNextLockCheck() {
        suppressNextCheck = true;
    }

    void setLockShowing(boolean showing) {
        lockShowing = showing;
    }

    private boolean needsLock() {
        Prefs prefs = new Prefs(this);
        if (!prefs.appLock()) return false;
        if (!unlocked) return true;
        // Only a trip to the background that started *after* the last unlock counts. This is what
        // stops the lock from coming straight back when Android's own PIN screen briefly covers us.
        if (backgroundSince <= unlockedAt) return false;
        return SystemClock.elapsedRealtime() - backgroundSince >= prefs.lockGraceMs();
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
        if (activity instanceof LockActivity || lockShowing) return;
        if (needsLock()) {
            unlocked = false;
            lockShowing = true;
            Intent i = new Intent(activity, LockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(i);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (started > 0) started--;
        if (started == 0 && !activity.isChangingConfigurations()) {
            inBackground = true;
            backgroundSince = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        applyRecentsPrivacy(activity);
    }

    /** With App lock on, recent apps shows a blank card instead of your statuses. */
    static void applyRecentsPrivacy(Activity activity) {
        boolean hide = new Prefs(activity).appLock();
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setRecentsScreenshotEnabled(!hide);
        } else if (hide) {
            // Older Android has no "hide preview" switch; FLAG_SECURE also blanks the preview.
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
