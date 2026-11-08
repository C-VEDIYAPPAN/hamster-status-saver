package com.statusvault.app;

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

    // ---- statuses the user has opened (for the NEW badge) ----

    Set<String> seen() {
        return new HashSet<>(p.getStringSet("seen", new HashSet<String>()));
    }

    void markSeen(Collection<String> names) {
        Set<String> s = seen();
        if (s.addAll(names)) p.edit().putStringSet("seen", s).apply();
    }

    /** Forget statuses that no longer exist, so the set doesn't grow forever. */
    void pruneSeen(Set<String> present) {
        Set<String> s = seen();
        if (s.retainAll(present)) p.edit().putStringSet("seen", s).apply();
    }
}
