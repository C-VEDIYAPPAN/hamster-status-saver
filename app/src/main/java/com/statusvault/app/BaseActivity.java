package com.statusvault.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/** Applies the Light / Dark / System choice from Settings to every screen. */
public abstract class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        int mode = new Prefs(base).theme();
        if (mode != Prefs.THEME_SYSTEM) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            int night = mode == Prefs.THEME_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
