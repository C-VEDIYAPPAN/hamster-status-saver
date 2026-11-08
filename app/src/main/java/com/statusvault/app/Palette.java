package com.statusvault.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Switch;

/** Accent colours chosen in Settings, resolved for the current light/dark mode. */
final class Palette {

    static final String[] NAMES = {"Teal", "Ocean", "Violet", "Rose", "Amber", "Forest"};

    // light primary, light container, light on-container, dark primary, dark container, dark on-container, dark on-primary
    private static final int[][] SETS = {
            {0xFF00696B, 0xFFC8F0EE, 0xFF00302F, 0xFF7DD6D2, 0xFF0E4E4C, 0xFFB6F0EC, 0xFF003735},
            {0xFF1A5FB4, 0xFFD6E3FF, 0xFF001B3E, 0xFFA9C7FF, 0xFF0F4A8A, 0xFFD6E3FF, 0xFF002F64},
            {0xFF6B4EAD, 0xFFEADDFF, 0xFF25005A, 0xFFD0BCFF, 0xFF4F378B, 0xFFEADDFF, 0xFF381E72},
            {0xFFB3265E, 0xFFFFD9E2, 0xFF3E001D, 0xFFFFB1C8, 0xFF8E0F47, 0xFFFFD9E2, 0xFF5E1133},
            {0xFF8A5100, 0xFFFFDCBE, 0xFF2C1600, 0xFFFFB870, 0xFF693C00, 0xFFFFDCBE, 0xFF4A2800},
            {0xFF2E6B30, 0xFFB1F2A9, 0xFF002204, 0xFF96D68F, 0xFF15521B, 0xFFB1F2A9, 0xFF00390A},
    };

    final int primary, onPrimary, container, onContainer;
    /** Second colour for gradients: the next accent in the list (teal → ocean, rose → amber, …). */
    final int secondary;
    /** Destructive actions (delete). */
    final int error;
    final boolean dark;
    private final float density;

    Palette(Context context) {
        this(context, new Prefs(context).accent());
    }

    Palette(Context context, int accent) {
        dark = isDark(context);
        int[] s = SETS[clamp(accent)];
        primary = dark ? s[3] : s[0];
        onPrimary = dark ? s[6] : 0xFFFFFFFF;
        container = dark ? s[4] : s[1];
        onContainer = dark ? s[5] : s[2];
        int[] next = SETS[(clamp(accent) + 1) % SETS.length];
        secondary = dark ? next[3] : next[0];
        error = dark ? 0xFFFFB4AB : 0xFFBA1A1A;
        density = context.getResources().getDisplayMetrics().density;
    }

    /** Colours for the always-dark viewer. */
    static int viewerPrimary(Context c) { return SETS[clamp(new Prefs(c).accent())][3]; }
    static int viewerOnPrimary(Context c) { return SETS[clamp(new Prefs(c).accent())][6]; }

    static int swatch(int index, boolean dark) { return SETS[clamp(index)][dark ? 3 : 0]; }
    static int count() { return SETS.length; }

    static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int clamp(int i) {
        return i < 0 || i >= SETS.length ? 0 : i;
    }

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    GradientDrawable outlined(int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(Math.round(density), strokeColor);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    /** Diagonal accent gradient (story ring, selected stat card). */
    GradientDrawable gradient(float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{primary, secondary});
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    /** Frosted surface for floating elements: translucent fill with a hairline edge. */
    GradientDrawable glass(int surfaceColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor((surfaceColor & 0x00FFFFFF) | (dark ? 0xE6000000 : 0xF0000000));
        d.setStroke(Math.max(1, Math.round(density)), dark ? 0x22FFFFFF : 0x14000000);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    /** Soft accent glow in the top corner of the screen, over the background colour. */
    android.graphics.drawable.Drawable backgroundGlow(int bgColor, int widthPx) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(bgColor);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{(primary & 0x00FFFFFF) | (dark ? 0x38000000 : 0x2A000000), (primary & 0x00FFFFFF)});
        glow.setGradientCenter(0.9f, 0.0f);
        glow.setGradientRadius(widthPx * 0.95f);
        GradientDrawable glow2 = new GradientDrawable();
        glow2.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow2.setColors(new int[]{(secondary & 0x00FFFFFF) | (dark ? 0x1C000000 : 0x14000000), (secondary & 0x00FFFFFF)});
        glow2.setGradientCenter(0.0f, 0.35f);
        glow2.setGradientRadius(widthPx * 0.8f);
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{base, glow, glow2});
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /**
     * Material 3 style switch: a filled accent track with a light thumb when on, and an outlined
     * track with a grey thumb when off. Drawn by us so it stays clearly visible in dark mode.
     */
    void styleSwitch(Switch sw) {
        int trackW = Math.round(52 * density), trackH = Math.round(32 * density);
        int outline = dark ? 0xFF8B9796 : 0xFF6F7979;
        int offTrack = dark ? 0xFF2B3534 : 0xFFE3ECEB;

        GradientDrawable trackOn = new GradientDrawable();
        trackOn.setColor(primary);
        trackOn.setCornerRadius(trackH / 2f);
        trackOn.setSize(trackW, trackH);
        GradientDrawable trackOff = new GradientDrawable();
        trackOff.setColor(offTrack);
        trackOff.setStroke(Math.round(2 * density), outline);
        trackOff.setCornerRadius(trackH / 2f);
        trackOff.setSize(trackW, trackH);
        StateListDrawable track = new StateListDrawable();
        track.setEnterFadeDuration(150);
        track.setExitFadeDuration(150);
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOff);

        // Thumb: big light dot when on, smaller outline-coloured dot when off.
        StateListDrawable thumb = new StateListDrawable();
        thumb.setEnterFadeDuration(150);
        thumb.setExitFadeDuration(150);
        thumb.addState(new int[]{android.R.attr.state_checked}, thumbDot(onPrimary, 24, trackH));
        thumb.addState(new int[]{}, thumbDot(outline, 16, trackH));

        sw.setThumbTintList(null);
        sw.setTrackTintList(null);
        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        sw.setSwitchMinWidth(trackW);
        sw.setShowText(false);
        sw.refreshDrawableState();
    }

    /** A dot of [dotDp] centred in a square cell the height of the track. */
    private android.graphics.drawable.Drawable thumbDot(int color, int dotDp, int cell) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        int d = Math.round(dotDp * density);
        int inset = (cell - d) / 2;
        dot.setSize(d, d);
        android.graphics.drawable.InsetDrawable in = new android.graphics.drawable.InsetDrawable(dot, inset);
        return in;
    }
}
