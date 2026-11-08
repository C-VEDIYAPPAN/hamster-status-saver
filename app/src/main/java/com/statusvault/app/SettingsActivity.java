package com.statusvault.app;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Settings with a live preview card that reacts to accent, theme and grid changes. */
public class SettingsActivity extends BaseActivity {

    private static final int REQ_NOTIFICATIONS = 1;

    private Prefs prefs;
    private Palette pal;
    private StatusRepository repo;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private FrameLayout previewHost;
    private LinearLayout swatchRow;
    private TextView columnsLabel;
    private ToggleSwitch backgroundSwitch;
    private TextView storageValue;
    private final java.util.List<ToggleSwitch> switches = new java.util.ArrayList<>();
    private final java.util.List<LinearLayout> segments = new java.util.ArrayList<>();
    private final java.util.List<int[]> segmentSelections = new java.util.ArrayList<>();
    private SeekBar columnsSeek;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        repo = new StatusRepository(this);
        pal = new Palette(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(getColor(R.color.bg));

        // Top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(16), 0);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(getColor(R.color.on_surface)));
        back.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("Settings", 22, R.color.on_surface, true);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Keep the column readable on tablets / landscape: at most 600dp wide, centred.
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        int side = widthDp > 632 ? dp((widthDp - 600) / 2f) : dp(16);
        content.setPadding(side, dp(4), side, dp(32));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStorage();
    }

    // ---------------- Layout ----------------

    private void build() {
        // Live preview
        previewHost = new FrameLayout(this);
        content.addView(previewHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderPreview();

        // ---- Appearance ----
        section("Appearance");
        LinearLayout appearance = card();
        label(appearance, "Theme", null);
        appearance.addView(segmented(new String[]{"System", "Light", "Dark"}, prefs.theme(), new Choice() {
            @Override
            public void chosen(int i) {
                if (i == prefs.theme()) return;
                prefs.setTheme(i);
                recreate(); // re-applies light/dark immediately
            }
        }), segmentParams());

        label(appearance, "Accent colour", null);
        swatchRow = new LinearLayout(this);
        swatchRow.setGravity(Gravity.CENTER_VERTICAL);
        swatchRow.setPadding(0, dp(4), 0, dp(12));
        appearance.addView(swatchRow);
        renderSwatches();

        LinearLayout colsHeader = new LinearLayout(this);
        colsHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView colsTitle = text("Grid size", 16, R.color.on_surface, false);
        colsHeader.addView(colsTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        columnsLabel = text("", 14, R.color.on_surface_variant, false);
        colsHeader.addView(columnsLabel);
        colsHeader.setPadding(0, dp(8), 0, 0);
        appearance.addView(colsHeader);
        columnsSeek = new SeekBar(this);
        columnsSeek.setMax(3); // 2..5 columns
        columnsSeek.setProgress(prefs.columns() - 2);
        columnsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                prefs.setColumns(progress + 2);
                s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                updateColumnsLabel();
                updatePreviewColumns();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        appearance.addView(columnsSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        updateColumnsLabel();

        // ---- Browsing ----
        section("Browsing");
        LinearLayout browsing = card();
        label(browsing, "Sort by", null);
        browsing.addView(segmented(new String[]{"Newest", "Oldest", "Largest"}, prefs.sort(), new Choice() {
            @Override
            public void chosen(int i) {
                prefs.setSort(i);
            }
        }), segmentParams());
        toggle(browsing, "Highlight new statuses", "A small glowing dot on statuses you haven't opened",
                prefs.newBadges(), new Toggle() {
                    @Override
                    public void changed(ToggleSwitch s, boolean on) {
                        prefs.setNewBadges(on);
                        renderPreview();
                    }
                });
        toggle(browsing, "Show time left", "A small timer showing how long until WhatsApp deletes a status (estimated)",
                prefs.timeLeft(), new Toggle() {
                    @Override
                    public void changed(ToggleSwitch s, boolean on) {
                        prefs.setTimeLeft(on);
                        renderPreview();
                    }
                });

        // ---- Auto-save ----
        section("Auto-save");
        LinearLayout auto = card();
        toggle(auto, "Auto-save new statuses", "Every new status is saved as soon as you open the app",
                prefs.autoSave(), new Toggle() {
                    @Override
                    public void changed(ToggleSwitch s, boolean on) {
                        prefs.setAutoSave(on);
                        backgroundSwitch.setEnabled(on);
                        backgroundSwitch.setAlpha(on ? 1f : 0.5f);
                        AutoSaveJob.sync(SettingsActivity.this);
                    }
                });
        backgroundSwitch = toggle(auto, "Check in background",
                "About once an hour, even when the app is closed. Shows a notification.",
                prefs.backgroundSave(), new Toggle() {
                    @Override
                    public void changed(ToggleSwitch s, boolean on) {
                        prefs.setBackgroundSave(on);
                        AutoSaveJob.sync(SettingsActivity.this);
                        if (on && Build.VERSION.SDK_INT >= 33
                                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                        }
                    }
                });
        backgroundSwitch.setEnabled(prefs.autoSave());
        backgroundSwitch.setAlpha(prefs.autoSave() ? 1f : 0.5f);

        // ---- Privacy ----
        section("Privacy");
        LinearLayout privacy = card();
        toggle(privacy, "App lock", "Fingerprint, face or screen lock to open Status Vault",
                prefs.appLock(), new Toggle() {
                    @Override
                    public void changed(ToggleSwitch s, boolean on) {
                        if (on) {
                            KeyguardManager km = getSystemService(KeyguardManager.class);
                            if (km == null || !km.isDeviceSecure()) {
                                s.setChecked(false, true);
                                toast("Set a screen lock on your phone first");
                                return;
                            }
                            StatusVaultApp.get().markUnlocked(); // don't lock the user out right now
                        }
                        prefs.setAppLock(on);
                    }
                });
        TextView privacyNote = text("Everything stays on your phone. Status Vault has no internet permission.",
                13, R.color.on_surface_variant, false);
        privacyNote.setPadding(0, dp(4), 0, dp(4));
        privacy.addView(privacyNote);

        // ---- Storage & folders ----
        section("Storage");
        LinearLayout storage = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(text("Saved in gallery", 16, R.color.on_surface, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        storageValue = text("…", 14, R.color.on_surface_variant, false);
        row.addView(storageValue);
        storage.addView(row);
        action(storage, "Clear temporary files", "Copies made for sharing", new Runnable() {
            public void run() {
                long freed = clearShareCache();
                toast(freed > 0 ? "Freed " + Formatter.formatShortFileSize(SettingsActivity.this, freed) : "Already clean");
            }
        });
        for (final StatusSource src : StatusSource.values()) {
            final boolean granted = repo.accessUri(src) != null;
            action(storage, "Change " + src.label + " folder",
                    granted ? "Access granted — tap to reset and pick again" : "Not set up yet", new Runnable() {
                        public void run() {
                            repo.revokeAccess(src);
                            toast("Reset. Open the " + (src == StatusSource.WHATSAPP ? "WhatsApp" : "Business") + " tab to pick the folder again.");
                        }
                    });
        }

        // ---- About ----
        section("About");
        LinearLayout about = card();
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        TextView ver = text("Status Vault " + version, 16, R.color.on_surface, false);
        ver.setPadding(0, dp(6), 0, dp(2));
        about.addView(ver);
        TextView note = text("Only save statuses you have permission to keep.", 13, R.color.on_surface_variant, false);
        note.setPadding(0, 0, 0, dp(6));
        about.addView(note);
    }

    // ---------------- Live preview ----------------

    private LinearLayout previewCard, previewTiles;
    private TextView previewChipA, previewChipB, previewButton;

    /** Builds the preview once, then only restyles it, so its size never jumps. */
    private void renderPreview() {
        if (previewCard == null) {
            previewCard = new LinearLayout(this);
            previewCard.setOrientation(LinearLayout.VERTICAL);
            previewCard.setPadding(dp(14), dp(14), dp(14), dp(14));

            LinearLayout chips = new LinearLayout(this);
            chips.setGravity(Gravity.CENTER_VERTICAL);
            previewChipA = text("All · 12", 13, 0, false);
            previewChipA.setPadding(dp(12), dp(5), dp(12), dp(5));
            chips.addView(previewChipA);
            previewChipB = text("Videos · 4", 13, R.color.on_surface, false);
            previewChipB.setPadding(dp(12), dp(5), dp(12), dp(5));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = dp(8);
            chips.addView(previewChipB, cp);
            TextView live = text("Live preview", 12, R.color.on_surface_variant, false);
            live.setGravity(Gravity.END);
            chips.addView(live, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            previewCard.addView(chips);

            // Fixed-height tile area: two rows whose tiles resize smoothly with the column count.
            previewTiles = new LinearLayout(this);
            previewTiles.setOrientation(LinearLayout.VERTICAL);
            for (int r = 0; r < 2; r++) {
                LinearLayout row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
                if (r > 0) rp.topMargin = dp(6);
                previewTiles.addView(row, rp);
            }
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176));
            tp.topMargin = dp(12);
            previewCard.addView(previewTiles, tp);

            previewButton = text("Grant access", 14, 0, true);
            previewButton.setGravity(Gravity.CENTER);
            previewButton.setPadding(dp(22), 0, dp(22), 0);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            bp.topMargin = dp(12);
            bp.gravity = Gravity.CENTER_HORIZONTAL;
            previewCard.addView(previewButton, bp);
            previewHost.addView(previewCard);
        }

        GradientDrawable bg = pal.rounded(getColor(R.color.surface), 20);
        bg.setStroke(dp(1), getColor(R.color.surface_variant));
        previewCard.setBackground(bg);
        previewChipA.setTextColor(pal.onPrimary);
        previewChipA.setBackground(pal.gradient(14));
        previewChipB.setBackground(pal.outlined(getColor(R.color.outline), 14));
        previewButton.setTextColor(pal.onPrimary);
        previewButton.setBackground(pal.gradient(18));

        // Restyle tiles in place (accent / badge changes)
        int cols = previewColumns();
        for (int r = 0; r < 2; r++) {
            LinearLayout row = (LinearLayout) previewTiles.getChildAt(r);
            row.removeAllViews();
            for (int c = 0; c < cols; c++) addPreviewTile(row, r * 5 + c, c > 0);
        }
    }

    /** Called while dragging the grid slider: tiles slide/resize into the new layout. */
    private void updatePreviewColumns() {
        int cols = previewColumns();
        android.transition.AutoTransition t = new android.transition.AutoTransition();
        t.setDuration(220);
        t.setInterpolator(new android.view.animation.DecelerateInterpolator());
        android.transition.TransitionManager.beginDelayedTransition(previewTiles, t);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = (LinearLayout) previewTiles.getChildAt(r);
            while (row.getChildCount() > cols) row.removeViewAt(row.getChildCount() - 1);
            while (row.getChildCount() < cols) {
                int c = row.getChildCount();
                addPreviewTile(row, r * 5 + c, c > 0);
            }
        }
    }

    private int previewColumns() {
        return Math.max(2, Math.min(5, prefs.columns()));
    }

    private void addPreviewTile(LinearLayout row, int i, boolean gap) {
        int[] shades = {0x40, 0x2A, 0x50, 0x33, 0x46};
        boolean isNew = prefs.newBadges() && i % 3 == 0;
        FrameLayout outer = new FrameLayout(this);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(pal.rounded((pal.primary & 0x00FFFFFF) | (shades[i % shades.length] << 24), 11));
        outer.addView(tile, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (isNew) {
            // Glowing dot, like on the real tiles
            View halo = new View(this);
            halo.setBackground(Palette.oval((pal.primary & 0x00FFFFFF) | 0x59000000));
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP | Gravity.START);
            hp.setMargins(dp(3), dp(3), 0, 0);
            tile.addView(halo, hp);
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable d = Palette.oval(pal.primary);
            d.setStroke(dp(1), 0xF2FFFFFF);
            dot.setBackground(d);
            FrameLayout.LayoutParams dpp = new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP | Gravity.START);
            dpp.setMargins(dp(6), dp(6), 0, 0);
            tile.addView(dot, dpp);
        }

        if (prefs.timeLeft()) {
            // Circular countdown, bottom-left
            CountdownBadge cd = new CountdownBadge(this);
            long hoursLeft = Math.max(1, 23 - (i % 5) * 4);
            cd.set(hoursLeft * 3600_000L, hoursLeft < 3 ? 0xFFFF8A80 : pal.primary);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM | Gravity.START);
            cp.setMargins(dp(4), 0, 0, dp(4));
            tile.addView(cd, cp);
        }
        ImageView save = new ImageView(this);
        save.setImageResource(R.drawable.ic_download);
        save.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        GradientDrawable glassDot = Palette.oval(0x66000000);
        glassDot.setStroke(dp(1), 0x40FFFFFF);
        save.setBackground(glassDot);
        save.setPadding(dp(3), dp(3), dp(3), dp(3));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.END);
        sp.setMargins(0, 0, dp(4), dp(4));
        tile.addView(save, sp);

        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (gap) tl.leftMargin = dp(6);
        row.addView(outer, tl);
    }

    private void renderSwatches() {
        swatchRow.removeAllViews();
        int current = prefs.accent();
        for (int i = 0; i < Palette.count(); i++) {
            final int idx = i;
            FrameLayout holder = new FrameLayout(this);
            View dot = new View(this);
            GradientDrawable d = Palette.oval(Palette.swatch(i, pal.dark));
            dot.setBackground(d);
            int size = dp(34);
            holder.addView(dot, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            if (i == current) {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setStroke(dp(2), Palette.swatch(i, pal.dark));
                holder.setBackground(ring);
                ImageView check = new ImageView(this);
                check.setImageResource(R.drawable.ic_done);
                check.setImageTintList(ColorStateList.valueOf(pal.dark ? 0xFF1A1A1A : 0xFFFFFFFF));
                holder.addView(check, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            }
            holder.setContentDescription(Palette.NAMES[i] + " accent");
            holder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == prefs.accent()) return;
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    prefs.setAccent(idx);
                    pal = new Palette(SettingsActivity.this);
                    renderSwatches();
                    renderPreview();
                    restyleControls();
                    toast(Palette.NAMES[idx]);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(6);
            swatchRow.addView(holder, lp);
            if (i == current) {
                holder.setScaleX(0.8f);
                holder.setScaleY(0.8f);
                holder.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            }
        }
    }

    private void restyleControls() {
        for (ToggleSwitch s : switches) styleToggle(s);
        for (int i = 0; i < segments.size(); i++) styleSegment(segments.get(i), segmentSelections.get(i)[0]);
        columnsSeek.setProgressTintList(ColorStateList.valueOf(pal.primary));
        columnsSeek.setThumbTintList(ColorStateList.valueOf(pal.primary));
    }

    private void updateColumnsLabel() {
        int c = prefs.columns();
        columnsLabel.setText(c + " columns");
        columnsSeek.setProgressTintList(ColorStateList.valueOf(pal.primary));
        columnsSeek.setThumbTintList(ColorStateList.valueOf(pal.primary));
    }

    // ---------------- Storage ----------------

    private void loadStorage() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<StatusItem> saved = repo.loadSaved();
                long bytes = 0;
                for (StatusItem it : saved) bytes += it.size;
                final String text = saved.size() + (saved.size() == 1 ? " item" : " items") + " · "
                        + Formatter.formatShortFileSize(SettingsActivity.this, bytes);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDestroyed()) storageValue.setText(text);
                    }
                });
            }
        }).start();
    }

    private long clearShareCache() {
        long freed = 0;
        File[] files = ShareProvider.shareDir(this).listFiles();
        if (files != null) {
            for (File f : files) {
                long len = f.length();
                if (f.delete()) freed += len;
            }
        }
        return freed;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            toast("Auto-save still runs, but you won't get notifications");
        }
    }

    // ---------------- Small builders ----------------

    private interface Choice { void chosen(int index); }

    private interface Toggle { void changed(ToggleSwitch s, boolean on); }

    private void section(String title) {
        TextView t = text(title.toUpperCase(), 12, 0, true);
        t.setTextColor(pal.primary);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        content.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        c.setBackground(pal.rounded(getColor(R.color.surface), 18));
        content.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private void label(LinearLayout parent, String title, String subtitle) {
        TextView t = text(title, 16, R.color.on_surface, false);
        t.setPadding(0, dp(8), 0, dp(8));
        parent.addView(t);
        if (subtitle != null) parent.addView(text(subtitle, 13, R.color.on_surface_variant, false));
    }

    private LinearLayout.LayoutParams segmentParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lp.bottomMargin = dp(8);
        return lp;
    }

    private LinearLayout segmented(String[] options, int selectedIndex, final Choice choice) {
        final LinearLayout group = new LinearLayout(this);
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setBackground(pal.rounded(getColor(R.color.surface_variant), 21));
        final int[] selected = {selectedIndex};
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView t = text(options[i], 14, R.color.on_surface_variant, true);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selected[0] == idx) return;
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    selected[0] = idx;
                    styleSegment(group, idx);
                    choice.chosen(idx);
                }
            });
            group.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        styleSegment(group, selectedIndex);
        segments.add(group);
        segmentSelections.add(selected);
        return group;
    }

    private void styleSegment(LinearLayout group, int selectedIndex) {
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView t = (TextView) group.getChildAt(i);
            boolean sel = i == selectedIndex;
            t.setBackground(sel ? pal.rounded(pal.primary, 17) : null);
            t.setTextColor(sel ? pal.onPrimary : getColor(R.color.on_surface_variant));
        }
    }

    private ToggleSwitch toggle(LinearLayout parent, String title, String subtitle, boolean checked, final Toggle listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, R.color.on_surface, false));
        TextView sub = text(subtitle, 13, R.color.on_surface_variant, false);
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.rightMargin = dp(12);
        row.addView(texts, tp);
        final ToggleSwitch sw = new ToggleSwitch(this);
        sw.setChecked(checked, false);
        styleToggle(sw);
        sw.setContentDescription(title);
        sw.setOnChangeListener(new ToggleSwitch.OnChangeListener() {
            @Override
            public void onChanged(ToggleSwitch t, boolean on) {
                listener.changed(t, on);
            }
        });
        // Fixed size, pinned to the right edge of every row so all toggles line up.
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.rightMargin = -dp(4); // cancel the toggle's touch padding so its edge meets the card padding
        row.addView(sw, sp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sw.isEnabled()) sw.performClick();
            }
        });
        parent.addView(row);
        switches.add(sw);
        return sw;
    }

    private void styleToggle(ToggleSwitch sw) {
        sw.setColors(pal.primary, pal.onPrimary,
                pal.dark ? 0xFF2B3534 : 0xFFE3ECEB,
                pal.dark ? 0xFF8B9796 : 0xFF6F7979);
    }

    private void action(LinearLayout parent, String title, String subtitle, final Runnable run) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        TextView t = text(title, 16, 0, false);
        t.setTextColor(pal.primary);
        row.addView(t);
        row.addView(text(subtitle, 13, R.color.on_surface_variant, false));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
        parent.addView(row);
    }

    private TextView text(String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (colorRes != 0) t.setTextColor(getColor(colorRes));
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int resolveAttr(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
