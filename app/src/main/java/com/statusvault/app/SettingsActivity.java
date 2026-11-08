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
import android.widget.Switch;
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
    private Switch backgroundSwitch;
    private TextView storageValue;
    private final java.util.List<Switch> switches = new java.util.ArrayList<>();
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
        content.setPadding(dp(16), dp(4), dp(16), dp(32));
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
                renderPreview();
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
        toggle(browsing, "Highlight new statuses", "A NEW badge on statuses you haven't opened",
                prefs.newBadges(), new Toggle() {
                    @Override
                    public void changed(Switch s, boolean on) {
                        prefs.setNewBadges(on);
                        renderPreview();
                    }
                });
        toggle(browsing, "Show time left", "Countdown until WhatsApp deletes a status (estimated)",
                prefs.timeLeft(), new Toggle() {
                    @Override
                    public void changed(Switch s, boolean on) {
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
                    public void changed(Switch s, boolean on) {
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
                    public void changed(Switch s, boolean on) {
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
                    public void changed(Switch s, boolean on) {
                        if (on) {
                            KeyguardManager km = getSystemService(KeyguardManager.class);
                            if (km == null || !km.isDeviceSecure()) {
                                s.setChecked(false);
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

    private void renderPreview() {
        previewHost.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = pal.rounded(getColor(R.color.surface), 20);
        bg.setStroke(dp(1), getColor(R.color.surface_variant));
        card.setBackground(bg);

        LinearLayout chips = new LinearLayout(this);
        TextView chipA = text("All · 12", 13, 0, false);
        chipA.setTextColor(pal.onContainer);
        chipA.setBackground(pal.rounded(pal.container, 14));
        chipA.setPadding(dp(12), dp(5), dp(12), dp(5));
        chips.addView(chipA);
        TextView chipB = text("Videos · 4", 13, R.color.on_surface, false);
        chipB.setBackground(pal.outlined(getColor(R.color.outline), 14));
        chipB.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(8);
        chips.addView(chipB, cp);
        TextView live = text("Live preview", 12, R.color.on_surface_variant, false);
        live.setGravity(Gravity.END);
        chips.addView(live, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(chips);

        int cols = Math.max(2, Math.min(5, prefs.columns()));
        int rows = cols <= 3 ? 1 : 2;
        int[] shades = {0x33, 0x22, 0x44, 0x2A, 0x3A};
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(r == 0 ? 12 : 6);
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                FrameLayout tile = new AspectFrameLayout(this, null);
                tile.setBackground(pal.rounded((pal.primary & 0x00FFFFFF) | (shades[i % shades.length] << 24), 10));
                if (prefs.newBadges() && i % 3 == 0) {
                    TextView pill = text("NEW", 9, 0, true);
                    pill.setTextColor(pal.onPrimary);
                    pill.setBackground(pal.rounded(pal.primary, 8));
                    pill.setPadding(dp(5), dp(1), dp(5), dp(1));
                    FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
                    pp.setMargins(dp(4), dp(4), 0, 0);
                    tile.addView(pill, pp);
                }
                if (prefs.timeLeft() && i % 2 == 1) {
                    TextView t = text((23 - i * 3) + "h", 9, 0, false);
                    t.setTextColor(0xFFFFFFFF);
                    t.setBackground(pal.rounded(0x99000000, 8));
                    t.setPadding(dp(5), dp(1), dp(5), dp(1));
                    FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
                    tp.setMargins(dp(4), 0, 0, dp(4));
                    tile.addView(t, tp);
                }
                ImageView save = new ImageView(this);
                save.setImageResource(R.drawable.ic_download);
                save.setImageTintList(ColorStateList.valueOf(pal.onContainer));
                save.setBackground(Palette.oval(pal.container));
                save.setPadding(dp(3), dp(3), dp(3), dp(3));
                int s = cols >= 5 ? dp(16) : dp(20);
                FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(s, s, Gravity.BOTTOM | Gravity.END);
                sp.setMargins(0, 0, dp(4), dp(4));
                tile.addView(save, sp);

                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (c > 0) tl.leftMargin = dp(6);
                line.addView(tile, tl);
            }
            card.addView(line, lp);
        }

        TextView button = text("Grant access", 14, 0, true);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(pal.onPrimary);
        button.setBackground(pal.rounded(pal.primary, 18));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        bp.topMargin = dp(12);
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        button.setPadding(dp(22), 0, dp(22), 0);
        card.addView(button, bp);

        card.setAlpha(0.6f);
        previewHost.addView(card);
        card.animate().alpha(1f).setDuration(180).start();
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
        for (Switch s : switches) pal.tint(s, getColor(R.color.surface_variant));
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

    private interface Toggle { void changed(Switch s, boolean on); }

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

    private Switch toggle(LinearLayout parent, String title, String subtitle, boolean checked, final Toggle listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, R.color.on_surface, false));
        TextView sub = text(subtitle, 13, R.color.on_surface_variant, false);
        sub.setPadding(0, dp(2), dp(12), 0);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Switch sw = new Switch(this);
        sw.setChecked(checked);
        pal.tint(sw, getColor(R.color.surface_variant));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                listener.changed(sw, on);
            }
        });
        row.addView(sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sw.isEnabled()) sw.toggle();
            }
        });
        parent.addView(row);
        switches.add(sw);
        return sw;
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
