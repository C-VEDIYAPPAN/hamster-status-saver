package com.statusvault.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs after the app has been updated: tidies up, re-schedules jobs and says what's new. */
public class UpdatedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Updater.clean(c, null);
        Updater.schedule(c);
        AutoSaveJob.sync(c);
        Updater.notifyText(c, "Status Vault updated to " + Updater.currentVersion(c), "You're on the latest version.", null);
    }
}
