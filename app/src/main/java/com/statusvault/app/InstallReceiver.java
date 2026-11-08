package com.statusvault.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Result of an update install: asks the user to confirm when Android requires it, or reports errors. */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            StatusVaultApp app = StatusVaultApp.get();
            if (app != null && app.isForeground()) {
                try {
                    c.startActivity(confirm); // Android's "Do you want to update this app?" screen
                    return;
                } catch (RuntimeException ignored) {
                }
            }
            // App not on screen: Android won't let us pop up a screen, so leave a notification.
            PendingIntent pi = PendingIntent.getActivity(c, 8, confirm,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Updater.notifyText(c, "Status Vault update is ready", "Tap to install it", pi);
        } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Updater.notifyText(c, "Update couldn't be installed",
                    "Try again from Settings → Updates, or download it from GitHub.", null);
        }
    }
}
