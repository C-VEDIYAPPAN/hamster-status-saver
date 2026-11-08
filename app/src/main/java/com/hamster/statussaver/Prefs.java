package com.hamster.statussaver;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** User settings. */
final class Prefs {
    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    static final int SORT_NEWEST = 0, SORT_OLDEST = 1, SORT_LARGEST = 2;

    private final SharedPreferences p;

    Prefs(Context context) {
        Context app = context.getApplicationContext();
        p = (app != null ? app : context).getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    int theme() { return p.getInt("theme", THEME_SYSTEM); }
    void setTheme(int v) { p.edit().putInt("theme", v).apply(); }

    int accent() { return p.getInt("accent", 0); }
    void setAccent(int v) { p.edit().putInt("accent", v).apply(); }

    int columns() { return p.getInt("columns", 3); }
    void setColumns(int v) { p.edit().putInt("columns", v).apply(); }

    int sort() { return p.getInt("sort", SORT_NEWEST); }
    void setSort(int v) { p.edit().putInt("sort", v).apply(); }

    boolean newBadges() { return p.getBoolean("new_badges", true); }
    void setNewBadges(boolean v) { p.edit().putBoolean("new_badges", v).apply(); }

    boolean timeLeft() { return p.getBoolean("time_left", true); }
    void setTimeLeft(boolean v) { p.edit().putBoolean("time_left", v).apply(); }

    boolean autoSave() { return p.getBoolean("auto_save", false); }
    void setAutoSave(boolean v) { p.edit().putBoolean("auto_save", v).apply(); }

    boolean backgroundSave() { return p.getBoolean("background_save", false); }
    void setBackgroundSave(boolean v) { p.edit().putBoolean("background_save", v).apply(); }

    boolean appLock() { return p.getBoolean("app_lock", false); }
    void setAppLock(boolean v) { p.edit().putBoolean("app_lock", v).apply(); }

    // ---- updates ----
    boolean autoUpdate() { return p.getBoolean("auto_update", true); }
    void setAutoUpdate(boolean v) { p.edit().putBoolean("auto_update", v).apply(); }
    long lastUpdateCheck() { return p.getLong("last_update_check", 0); }
    void setLastUpdateCheck(long v) { p.edit().putLong("last_update_check", v).apply(); }
    String latestVersion() { return p.getString("latest_version", null); }
    void setLatestVersion(String v) { p.edit().putString("latest_version", v).apply(); }
    String latestApkUrl() { return p.getString("latest_apk_url", null); }
    void setLatestApkUrl(String v) { p.edit().putString("latest_apk_url", v).apply(); }
    /** Last version the background check already notified about. */
    String notifiedVersion() { return p.getString("notified_version", null); }
    void setNotifiedVersion(String v) { p.edit().putString("notified_version", v).apply(); }

    /** Tab on launch: 0 WhatsApp, 1 Business, 2 Saved, 3 wherever you left off. */
    int openOn() { return p.getInt("open_on", 3); }
    void setOpenOn(int v) { p.edit().putInt("open_on", v).apply(); }
    int lastTab() { return p.getInt("last_tab", 0); }
    void setLastTab(int v) { p.edit().putInt("last_tab", v).apply(); }

    boolean autoplay() { return p.getBoolean("autoplay", true); }
    void setAutoplay(boolean v) { p.edit().putBoolean("autoplay", v).apply(); }

    boolean startMuted() { return p.getBoolean("start_muted", false); }
    void setStartMuted(boolean v) { p.edit().putBoolean("start_muted", v).apply(); }

    /** Back to defaults. Keeps which statuses you've opened and deleted, and your last tab. */
    void resetSettings() {
        String[] keys = {"theme", "accent", "columns", "sort", "new_badges", "time_left", "auto_save",
                "background_save", "app_lock", "lock_delay", "open_on", "autoplay", "start_muted", "auto_update"};
        SharedPreferences.Editor e = p.edit();
        for (String k : keys) e.remove(k);
        e.apply();
    }

    /** App lock delay: 0 = immediately, 1 = after 1 minute, 2 = after 5 minutes. */
    int lockDelay() { return p.getInt("lock_delay", 1); }
    void setLockDelay(int v) { p.edit().putInt("lock_delay", v).apply(); }

    long lockGraceMs() {
        switch (lockDelay()) {
            case 0: return 0;
            case 2: return 5 * 60_000L;
            default: return 60_000L;
        }
    }

    // ---- statuses the user has opened (for the NEW badge) ----

    Set<String> seen() {
        return new HashSet<>(p.getStringSet("seen", new HashSet<String>()));
    }

    void markSeen(Collection<String> names) {
        Set<String> s = seen();
        if (s.addAll(names)) p.edit().putStringSet("seen", s).apply();
    }

    // ---- saved copies the user deleted, so auto-save doesn't bring them back ----

    Set<String> skipAutoSave() {
        return new HashSet<>(p.getStringSet("skip_auto_save", new HashSet<String>()));
    }

    void addSkipAutoSave(Collection<String> names) {
        Set<String> s = skipAutoSave();
        if (s.addAll(names)) p.edit().putStringSet("skip_auto_save", s).apply();
    }

    /** Forget statuses that no longer exist, so the set doesn't grow forever. */
    void pruneSeen(Set<String> present) {
        Set<String> s = seen();
        if (s.retainAll(present)) p.edit().putStringSet("seen", s).apply();
        Set<String> skip = skipAutoSave();
        if (skip.retainAll(present)) p.edit().putStringSet("skip_auto_save", skip).apply();
    }
}
