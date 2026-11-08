package com.statusvault.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity {

    enum Section { WHATSAPP, BUSINESS, SAVED }

    enum Filter { ALL, PHOTOS, VIDEOS }

    static final String EXTRA_OPEN_SAVED = "open_saved";

    private static final int REQ_PICK_FOLDER = 100; // + source ordinal
    private static final int REQ_LEGACY_STORAGE = 200;
    private static final int REQ_DELETE = 300;
    private static final String KEY_SECTION = "section";
    private static final String KEY_FILTER = "filter";
    private static final String[] SECTION_TITLES = {"WhatsApp", "Business", "Saved"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private StatusRepository repo;
    /** The live main screen, so Settings can rebuild it in the background after a theme switch. */
    private static java.lang.ref.WeakReference<MainActivity> current;

    static void recreateInBackground() {
        MainActivity m = current != null ? current.get() : null;
        if (m != null && !m.isFinishing() && !m.isDestroyed()) m.recreate();
    }
    private Notice notice;
    /** "View" action on save messages: jump to the Saved tab. */
    private final Runnable openSaved = new Runnable() {
        @Override
        public void run() {
            switchSection(Section.SAVED);
        }
    };
    private Prefs prefs;
    private Palette palette;
    private int appliedTheme, appliedColumns, appliedAccent;

    // State
    private Section section = Section.WHATSAPP;
    private Filter filter = Filter.ALL;
    private final Map<Section, List<StatusItem>> items = new EnumMap<>(Section.class);
    private final Map<StatusSource, Boolean> hasAccess = new EnumMap<>(StatusSource.class);
    private final Map<StatusSource, Boolean> installed = new EnumMap<>(StatusSource.class);
    private final Set<String> savedNames = new HashSet<>();
    private Set<String> seen = new HashSet<>();
    /** Selected items by URI; non-empty means selection mode. */
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean loadedOnce, loading, autoSaving;
    /** The "update ready" message is shown once per visit, not on every resume. */
    private boolean updateOffered, updateChecking;
    private int loadGeneration = 0;

    // Views
    private View root, header, selectionBar, empty, filtersRow;
    private TextView title, subtitle, saveAll, selCount, selAll;
    private GridView grid;
    private ImageView emptyIcon;
    private TextView emptyTitle, emptyBody;
    private Button emptyAction;
    private ProgressBar progress;
    private FrameLayout floatingBar;
    private GlowView barGlow;
    private View navIndicator;
    private LinearLayout navTabs, navActions;
    private final View[] statCards = new View[3];
    private final TextView[] statNumbers = new TextView[3];
    private final TextView[] statLabels = new TextView[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private final TextView[] navBadges = new TextView[3];
    private View actSave, actRepost, actShare, actDelete;
    private Section indicatorAt;
    private boolean barInSelectionMode;
    private int bottomInset;

    private final StatusAdapter adapter = new StatusAdapter();
    private final List<StatusItem> visible = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        current = new java.lang.ref.WeakReference<>(this);
        repo = new StatusRepository(this);
        prefs = new Prefs(this);
        palette = new Palette(this);
        // Messages sit just above the floating bar.
        notice = new Notice(this, new Notice.Offset() {
            @Override
            public int bottomPx() {
                return bottomInset + dp(14 + 62 + 12);
            }
        });
        appliedTheme = prefs.theme();
        appliedColumns = prefs.columns();
        appliedAccent = prefs.accent();
        for (Section s : Section.values()) items.put(s, new ArrayList<StatusItem>());

        if (savedInstanceState != null) {
            section = Section.values()[savedInstanceState.getInt(KEY_SECTION, 0)];
            filter = Filter.values()[savedInstanceState.getInt(KEY_FILTER, 0)];
        }
        else {
            // Settings → Open on: a fixed tab, or wherever you left off
            int open = prefs.openOn() == 3 ? prefs.lastTab() : prefs.openOn();
            section = Section.values()[Math.max(0, Math.min(2, open))];
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_SAVED, false)) section = Section.SAVED;

        root = findViewById(R.id.root);
        header = findViewById(R.id.header);
        selectionBar = findViewById(R.id.selection_bar);
        title = (TextView) findViewById(R.id.title);
        subtitle = (TextView) findViewById(R.id.subtitle);
        saveAll = (TextView) findViewById(R.id.save_all);
        selCount = (TextView) findViewById(R.id.sel_count);
        selAll = (TextView) findViewById(R.id.sel_all);
        filtersRow = findViewById(R.id.filters);
        empty = findViewById(R.id.empty);
        emptyIcon = (ImageView) findViewById(R.id.empty_icon);
        emptyTitle = (TextView) findViewById(R.id.empty_title);
        emptyBody = (TextView) findViewById(R.id.empty_body);
        emptyAction = (Button) findViewById(R.id.empty_action);
        progress = (ProgressBar) findViewById(R.id.progress);
        floatingBar = (FrameLayout) findViewById(R.id.floating_bar);
        barGlow = (GlowView) findViewById(R.id.bar_glow);
        navIndicator = findViewById(R.id.nav_indicator);
        navTabs = (LinearLayout) findViewById(R.id.nav_tabs);
        navActions = (LinearLayout) findViewById(R.id.nav_actions);

        onClick(R.id.refresh, new Runnable() { public void run() { refresh(); } });
        onClick(R.id.settings, new Runnable() {
            public void run() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        onClick(R.id.sel_close, new Runnable() { public void run() { clearSelection(); } });
        onClick(R.id.sel_all, new Runnable() { public void run() { selectAll(); } });
        saveAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                List<StatusItem> unsaved = new ArrayList<>();
                for (StatusItem it : items.get(section)) if (!savedNames.contains(it.name)) unsaved.add(it);
                saveMany(unsaved);
            }
        });

        buildStatCards();
        buildFloatingBar();

        grid = (GridView) findViewById(R.id.grid);
        grid.setAdapter(adapter);
        grid.setLayoutAnimation(tileEntrance());
        applyInsets();
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!selected.isEmpty()) toggleSelection(position);
                else openViewer(position);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                toggleSelection(position);
                return true;
            }
        });

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_STORAGE);
        }
        applyColors();
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_SAVED, false)) switchSection(Section.SAVED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.theme() != appliedTheme) { // changed in Settings
            recreate();
            return;
        }
        if (prefs.accent() != appliedAccent) {
            appliedAccent = prefs.accent();
            palette = new Palette(this);
            applyColors();
        }
        if (prefs.columns() != appliedColumns) {
            appliedColumns = prefs.columns();
            animateGrid();
        }
        // New statuses appear whenever the user views them in WhatsApp, so reload on return.
        refresh();
        handleUpdates();
    }

    // ---------------- App updates ----------------

    /** On open: mention a known newer version, or check GitHub (at most every 6 hours). */
    private void handleUpdates() {
        if (!Updater.available(this) || !prefs.autoUpdate()) return;
        Updater.Release known = Updater.knownUpdate(this);
        if (known != null) {
            offerUpdate(known);
            return;
        }
        boolean due = System.currentTimeMillis() - prefs.lastUpdateCheck() > 6 * 3600_000L;
        if (!due || updateChecking) return;
        updateChecking = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                Updater.Release r = null;
                try {
                    r = Updater.check(MainActivity.this);
                } catch (Exception ignored) {
                    // offline or GitHub unavailable — try again later
                }
                final Updater.Release found = r;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        updateChecking = false;
                        if (found != null && !isDestroyed()) offerUpdate(found);
                    }
                });
            }
        });
    }

    private void offerUpdate(final Updater.Release r) {
        if (updateOffered) return;
        updateOffered = true;
        notice.show("Version " + r.version + " is available", Notice.SUCCESS, "Download", new Runnable() {
            @Override
            public void run() {
                Updater.download(MainActivity.this, r, notice);
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!selected.isEmpty()) clearSelection();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SECTION, section.ordinal());
        outState.putInt(KEY_FILTER, filter.ordinal());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---------------- Loading ----------------

    private void refresh() {
        final int generation = ++loadGeneration;
        loading = true;
        render();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Map<Section, List<StatusItem>> next = new EnumMap<>(Section.class);
                final Map<StatusSource, Boolean> access = new EnumMap<>(StatusSource.class);
                final Map<StatusSource, Boolean> inst = new EnumMap<>(StatusSource.class);
                Set<String> present = new HashSet<>();
                for (StatusSource src : StatusSource.values()) {
                    boolean granted = repo.accessUri(src) != null;
                    access.put(src, granted);
                    inst.put(src, repo.isInstalled(src));
                    List<StatusItem> list = granted ? repo.loadStatuses(src) : new ArrayList<StatusItem>();
                    for (StatusItem it : list) present.add(it.name);
                    next.put(src == StatusSource.WHATSAPP ? Section.WHATSAPP : Section.BUSINESS, list);
                }
                next.put(Section.SAVED, repo.loadSaved());
                if (access.containsValue(Boolean.TRUE)) prefs.pruneSeen(present);
                final Set<String> seenNow = prefs.seen();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != loadGeneration || isDestroyed()) return;
                        items.putAll(next);
                        hasAccess.putAll(access);
                        installed.putAll(inst);
                        seen = seenNow;
                        savedNames.clear();
                        for (StatusItem it : next.get(Section.SAVED)) savedNames.add(it.name);
                        loading = false;
                        loadedOnce = true;
                        Set<String> all = new HashSet<>();
                        for (StatusItem it : items.get(section)) all.add(it.uri.toString());
                        selected.retainAll(all);
                        render();
                        maybeAutoSave();
                    }
                });
            }
        });
    }

    /** Settings → Auto-save: save anything new as soon as the app shows it. */
    private void maybeAutoSave() {
        if (!prefs.autoSave() || autoSaving) return;
        Set<String> skip = prefs.skipAutoSave();
        boolean anyNew = false;
        for (Section s : new Section[]{Section.WHATSAPP, Section.BUSINESS}) {
            for (StatusItem it : items.get(s)) {
                if (!savedNames.contains(it.name) && !skip.contains(it.name)) {
                    anyNew = true;
                    break;
                }
            }
        }
        if (!anyNew) return;
        autoSaving = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final int n = repo.autoSaveNew();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        autoSaving = false;
                        if (isDestroyed()) return;
                        if (n > 0) {
                            notice.show("Auto-saved " + n + (n == 1 ? " new status" : " new statuses"), Notice.SUCCESS, "View", openSaved);
                            refresh();
                        }
                    }
                });
            }
        });
    }

    // ---------------- Rendering ----------------

    /** Glass fill with a thin accent-gradient edge. */
    private android.graphics.drawable.Drawable glowingBarBackground() {
        float radius = dp(31);
        android.graphics.drawable.GradientDrawable edge = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{(palette.primary & 0x00FFFFFF) | 0x73000000, (palette.secondary & 0x00FFFFFF) | 0x73000000});
        edge.setCornerRadius(radius);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor((getColor(R.color.surface) & 0x00FFFFFF) | 0xF7000000);
        int e = Math.max(2, dp(1.5f));
        fill.setCornerRadius(radius - e);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{edge, fill});
        ld.setLayerInset(1, e, e, e, e);
        return ld;
    }

    /** Colours that depend on the accent: background glow, buttons, bar. */
    private void applyColors() {
        root.setBackground(palette.backgroundGlow(getColor(R.color.bg), getResources().getDisplayMetrics().widthPixels));
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        floatingBar.setBackground(glowingBarBackground());
        navIndicator.setBackground(palette.gradient(25));
        barGlow.setColors(palette.primary, palette.secondary);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Coloured shadows: the bar and its sliding pill glow in the accent colour
            floatingBar.setOutlineSpotShadowColor(palette.primary);
            floatingBar.setOutlineAmbientShadowColor(palette.primary);
            navIndicator.setOutlineSpotShadowColor(palette.primary);
            navIndicator.setOutlineAmbientShadowColor(palette.secondary);
        }
        navIndicator.setElevation(dp(3));
        navTabs.setElevation(dp(8));   // keep icons/labels above the glowing pill
        navActions.setElevation(dp(8));
        saveAll.setBackground(palette.rounded(palette.primary, 18));
        saveAll.setTextColor(palette.onPrimary);
        saveAll.setCompoundDrawableTintList(ColorStateList.valueOf(palette.onPrimary));
        selAll.setBackground(palette.rounded(palette.container, 18));
        selAll.setTextColor(palette.onContainer);
        styleTabs();
        styleActions();
    }

    private void render() {
        grid.setNumColumns(columns());
        styleTabs();
        moveIndicator(indicatorAt != null);

        List<StatusItem> all = sorted(items.get(section));
        int photos = 0, videos = 0, fresh = 0, unsaved = 0;
        long bytes = 0;
        for (StatusItem it : all) {
            if (it.isVideo()) videos++; else photos++;
            bytes += it.size;
            if (!seen.contains(it.name)) fresh++;
            if (!savedNames.contains(it.name)) unsaved++;
        }
        StatusSource source = sourceOf(section);

        // Large title + summary line
        title.setText(SECTION_TITLES[section.ordinal()]);
        StringBuilder sum = new StringBuilder();
        if (source != null) {
            sum.append(all.size()).append(all.size() == 1 ? " status" : " statuses");
            if (prefs.newBadges() && fresh > 0) sum.append("  ·  ").append(fresh).append(" new");
        } else {
            sum.append(all.size()).append(" saved");
        }
        if (!all.isEmpty()) sum.append("  ·  ").append(Formatter.formatShortFileSize(this, bytes));
        subtitle.setText(sum);
        boolean showSaveAll = source != null && unsaved > 0 && Boolean.TRUE.equals(hasAccess.get(source));
        saveAll.setVisibility(showSaveAll ? View.VISIBLE : View.GONE);
        if (showSaveAll) saveAll.setText("Save all " + unsaved);

        // Stat cards (also the filters)
        int[] counts = {all.size(), photos, videos};
        for (int i = 0; i < 3; i++) styleStatCard(i, counts[i], Filter.values()[i] == filter);

        visible.clear();
        for (StatusItem it : all) {
            if (filter == Filter.ALL || (filter == Filter.VIDEOS) == it.isVideo()) visible.add(it);
        }
        adapter.notifyDataSetChanged();
        renderSelection();

        progress.setVisibility(loading && !loadedOnce ? View.VISIBLE : View.GONE);
        if (!loadedOnce) {
            empty.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            return;
        }

        if (source != null && !Boolean.TRUE.equals(hasAccess.get(source))) {
            filtersRow.setVisibility(View.GONE);
            grid.setVisibility(View.GONE);
            subtitle.setText(Boolean.TRUE.equals(installed.get(source)) ? "Not connected yet" : "Not installed");
            if (!Boolean.TRUE.equals(installed.get(source))) {
                showEmpty(R.drawable.ic_block, source.label + " isn't installed",
                        "Install it and view a few statuses, then come back here.", null, null);
            } else {
                final StatusSource src = source;
                showEmpty(R.drawable.ic_folder, "Connect " + source.label,
                        "Android only lets apps read statuses from a folder you pick. The next screen opens "
                                + "in the right folder — just tap “Use this folder”, then “Allow”.",
                        "Grant access", new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pickFolder(src);
                            }
                        });
            }
            return;
        }

        filtersRow.setVisibility(View.VISIBLE);
        if (visible.isEmpty()) {
            grid.setVisibility(View.GONE);
            if (source == null) {
                showEmpty(R.drawable.ic_nav_saved, filter == Filter.ALL ? "Nothing saved yet" : "Nothing here",
                        "Tap the download button on any status to keep it in your gallery.", null, null);
            } else if (!all.isEmpty()) {
                showEmpty(R.drawable.ic_photos, filter == Filter.VIDEOS ? "No videos" : "No photos",
                        "Try another filter.", null, null);
            } else {
                showEmpty(R.drawable.ic_photos, "No statuses yet",
                        "Open " + source.label + " and view some statuses — they'll appear here. "
                                + "WhatsApp removes statuses after 24 hours.", null, null);
            }
        } else {
            empty.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
        }
    }

    /** The user's grid size, plus extra columns on tablets / landscape so tiles stay a sensible size. */
    private int columns() {
        int pref = Math.max(2, Math.min(5, prefs.columns()));
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        if (widthDp >= 840) return pref + 3;
        if (widthDp >= 600) return pref + 2;
        return pref;
    }

    private List<StatusItem> sorted(List<StatusItem> list) {
        List<StatusItem> copy = new ArrayList<>(list);
        final int sort = prefs.sort();
        Collections.sort(copy, new Comparator<StatusItem>() {
            @Override
            public int compare(StatusItem a, StatusItem b) {
                switch (sort) {
                    case Prefs.SORT_OLDEST: return Long.compare(a.lastModified, b.lastModified);
                    case Prefs.SORT_LARGEST: return Long.compare(b.size, a.size);
                    default: return Long.compare(b.lastModified, a.lastModified);
                }
            }
        });
        return copy;
    }

    private void showEmpty(int icon, String titleText, String body, String action, View.OnClickListener onAction) {
        if (empty.getVisibility() != View.VISIBLE || !titleText.contentEquals(emptyTitle.getText())) {
            empty.setAlpha(0f);
            empty.setTranslationY(dp(14));
            empty.animate().alpha(1f).translationY(0).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        empty.setVisibility(View.VISIBLE);
        emptyIcon.setImageResource(icon);
        emptyIcon.setBackground(palette.gradient(36));
        emptyIcon.setImageTintList(ColorStateList.valueOf(palette.onPrimary));
        emptyTitle.setText(titleText);
        emptyBody.setText(body);
        if (action != null) {
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(action);
            emptyAction.setBackground(palette.gradient(26));
            emptyAction.setTextColor(palette.onPrimary);
            emptyAction.setOnClickListener(onAction);
        } else {
            emptyAction.setVisibility(View.GONE);
        }
    }

    // ---------------- Stat cards (filters) ----------------

    private void buildStatCards() {
        LinearLayout row = (LinearLayout) filtersRow;
        String[] labels = {"All", "Photos", "Videos"};
        for (int i = 0; i < 3; i++) {
            final Filter f = Filter.values()[i];
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(10), dp(12), dp(10));
            TextView number = new TextView(this);
            number.setTextSize(22);
            number.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            number.setIncludeFontPadding(false);
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(12);
            label.setPadding(0, dp(3), 0, 0);
            card.addView(number);
            card.addView(label);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (filter == f) return;
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            statCardsSpring();
                        }
                    }).start();
                    filter = f;
                    animateGrid();
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(card, lp);
            statCards[i] = card;
            statNumbers[i] = number;
            statLabels[i] = label;
        }
    }

    private void statCardsSpring() {
        for (View c : statCards) {
            c.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new OvershootInterpolator(2f)).start();
        }
    }

    private void styleStatCard(int i, int count, boolean isSelected) {
        statNumbers[i].setText(String.valueOf(count));
        if (isSelected) {
            statCards[i].setBackground(palette.gradient(18));
            statNumbers[i].setTextColor(palette.onPrimary);
            statLabels[i].setTextColor((palette.onPrimary & 0x00FFFFFF) | 0xCC000000);
        } else {
            statCards[i].setBackground(palette.glass(getColor(R.color.surface), 18));
            statNumbers[i].setTextColor(getColor(R.color.on_surface));
            statLabels[i].setTextColor(getColor(R.color.on_surface_variant));
        }
    }

    // ---------------- Floating bar ----------------

    private void buildFloatingBar() {
        int[] icons = {R.drawable.ic_nav_chat, R.drawable.ic_nav_store, R.drawable.ic_nav_saved};
        for (int i = 0; i < 3; i++) {
            final Section s = Section.values()[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(SECTION_TITLES[i]);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (section == s) grid.smoothScrollToPosition(0); // tap again: back to top
                    else switchSection(s);
                }
            });
            FrameLayout iconBox = new FrameLayout(this);
            iconBox.setClipChildren(false);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            iconBox.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            TextView badge = new TextView(this);
            badge.setTextSize(10);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(16));
            badge.setPadding(dp(4), 0, dp(4), 0);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setVisibility(View.GONE);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP | Gravity.END);
            iconBox.addView(badge, bp);
            item.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(24)));
            TextView label = new TextView(this);
            label.setText(SECTION_TITLES[i]);
            label.setTextSize(11);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(2);
            item.addView(label, llp); // wrap_content, so the item's centre gravity actually centres it
            navTabs.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            navIcons[i] = icon;
            navLabels[i] = label;
            navBadges[i] = badge;
        }
        floatingBar.setClipChildren(false);

        actSave = addBarAction(R.drawable.ic_download, "Save", new Runnable() {
            public void run() { saveMany(selectedItems()); clearSelection(); }
        });
        actRepost = addBarAction(R.drawable.ic_repost, "Repost", new Runnable() {
            public void run() { shareMany(selectedItems(), true); clearSelection(); }
        });
        actShare = addBarAction(R.drawable.ic_share, "Share", new Runnable() {
            public void run() { shareMany(selectedItems(), false); clearSelection(); }
        });
        actDelete = addBarAction(R.drawable.ic_delete, "Delete", new Runnable() {
            public void run() { confirmDelete(selectedItems(), sourceOf(section)); }
        });

        // Keep the sliding pill under the right tab when the bar is laid out or resized.
        navTabs.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol) moveIndicator(false);
            }
        });
    }

    private View addBarAction(int icon, String label, final Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setContentDescription(label);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        item.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(0, dp(4), 0, 0);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        item.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                action.run();
            }
        });
        navActions.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return item;
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            boolean sel = Section.values()[i] == section;
            int c = sel ? palette.onPrimary : getColor(R.color.on_surface_variant);
            navIcons[i].setImageTintList(ColorStateList.valueOf(c));
            navLabels[i].setTextColor(c);
            int fresh = 0;
            if (i < 2 && prefs.newBadges()) {
                for (StatusItem it : items.get(Section.values()[i])) if (!seen.contains(it.name)) fresh++;
            }
            TextView badge = navBadges[i];
            badge.setVisibility(fresh > 0 ? View.VISIBLE : View.GONE);
            if (fresh > 0) {
                badge.setText(fresh > 99 ? "99+" : String.valueOf(fresh));
                badge.setTextColor(sel ? palette.primary : palette.onPrimary);
                badge.setBackground(palette.rounded(sel ? palette.onPrimary : palette.primary, 9));
            }
        }
    }

    private void styleActions() {
        for (View v : new View[]{actSave, actRepost, actShare, actDelete}) {
            int c = v == actDelete ? palette.error : getColor(R.color.on_surface);
            LinearLayout item = (LinearLayout) v;
            ((ImageView) item.getChildAt(0)).setImageTintList(ColorStateList.valueOf(c));
            ((TextView) item.getChildAt(1)).setTextColor(c);
        }
    }

    /** Slides the gradient pill under the selected tab. */
    private void moveIndicator(boolean animate) {
        int barWidth = navTabs.getWidth();
        if (barWidth == 0) return;
        int tabWidth = barWidth / 3;
        int inset = dp(6);
        int pillWidth = tabWidth - inset * 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) navIndicator.getLayoutParams();
        if (lp.width != pillWidth) {
            lp.width = pillWidth;
            navIndicator.setLayoutParams(lp);
        }
        float x = section.ordinal() * tabWidth + inset;
        if (animate && indicatorAt != section) {
            navIndicator.animate().translationX(x).setDuration(340)
                    .setInterpolator(new OvershootInterpolator(1.1f)).start();
        } else if (indicatorAt != section || !animate) {
            navIndicator.animate().cancel();
            navIndicator.setTranslationX(x);
        }
        indicatorAt = section;
    }

    /** Swaps the floating bar between tabs and selection actions with a quick crossfade. */
    private void morphBar(final boolean selecting) {
        if (selecting == barInSelectionMode) return;
        barInSelectionMode = selecting;
        final View out = selecting ? navTabs : navActions;
        final View in = selecting ? navActions : navTabs;
        navIndicator.animate().alpha(selecting ? 0f : 1f).setDuration(160).start();
        barGlow.animate().scaleX(selecting ? 0.9f : 1f).setDuration(200).start();
        out.animate().alpha(0f).translationY(dp(10)).setDuration(130).withEndAction(new Runnable() {
            @Override
            public void run() {
                out.setVisibility(View.GONE);
                in.setVisibility(View.VISIBLE);
                in.setAlpha(0f);
                in.setTranslationY(dp(10));
                in.animate().alpha(1f).translationY(0).setDuration(200)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }).start();
        floatingBar.animate().scaleX(0.96f).scaleY(0.96f).setDuration(110).withEndAction(new Runnable() {
            @Override
            public void run() {
                floatingBar.animate().scaleX(1f).scaleY(1f).setDuration(240)
                        .setInterpolator(new OvershootInterpolator(2f)).start();
            }
        }).start();
    }

    private void switchSection(Section s) {
        if (section == s) return;
        section = s;
        prefs.setLastTab(s.ordinal());
        selected.clear();
        animateGrid();
        moveIndicator(true);
        title.setAlpha(0f);
        title.setTranslationX(dp(18));
        title.animate().alpha(1f).translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        render();
        grid.setSelection(0);
    }

    // ---------------- Motion & insets ----------------

    /** Tiles fade and rise in, slightly staggered. */
    private LayoutAnimationController tileEntrance() {
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new AlphaAnimation(0f, 1f));
        set.addAnimation(new TranslateAnimation(0, 0, dp(18), 0));
        set.setDuration(260);
        set.setInterpolator(new DecelerateInterpolator(1.5f));
        return new LayoutAnimationController(set, 0.06f);
    }

    private void animateGrid() {
        grid.scheduleLayoutAnimation();
    }

    /** Draws edge-to-edge: the header clears the status bar, the floating bar clears the nav bar. */
    @SuppressWarnings("deprecation")
    private void applyInsets() {
        final View column = findViewById(R.id.column);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                ? Color.TRANSPARENT : 0x66000000); // Android 8.0 can't draw dark nav icons
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                column.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), 0);
                bottomInset = insets.getSystemWindowInsetBottom();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) floatingBar.getLayoutParams();
                lp.bottomMargin = bottomInset + dp(14);
                // Floating pill: at most 380dp wide (16dp from the edges on small phones), centred.
                int screen = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
                lp.width = Math.min(screen - dp(32), dp(380));
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                floatingBar.setLayoutParams(lp);
                // Glow sits behind the bar, a little larger so its blur spills out softly.
                FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) barGlow.getLayoutParams();
                glp.width = lp.width + dp(40);
                glp.height = dp(62 + 40);
                glp.bottomMargin = lp.bottomMargin - dp(20) + dp(4); // nudged down: light from below
                glp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                barGlow.setLayoutParams(glp);
                // Let the grid scroll under the floating bar, but end above it.
                grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), grid.getPaddingRight(),
                        bottomInset + dp(14 + 62 + 16));
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    // ---------------- Selection ----------------

    private void toggleSelection(int position) {
        if (position < 0 || position >= visible.size()) return;
        String key = visible.get(position).uri.toString();
        if (!selected.remove(key)) selected.add(key);
        adapter.notifyDataSetChanged();
        renderSelection();
    }

    private void selectAll() {
        if (selected.size() == visible.size()) {
            selected.clear();
        } else {
            for (StatusItem it : visible) selected.add(it.uri.toString());
        }
        adapter.notifyDataSetChanged();
        renderSelection();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        renderSelection();
    }

    private List<StatusItem> selectedItems() {
        List<StatusItem> out = new ArrayList<>();
        for (StatusItem it : visible) if (selected.contains(it.uri.toString())) out.add(it);
        return out;
    }

    private void renderSelection() {
        boolean selecting = !selected.isEmpty();
        if (selecting && header.getVisibility() == View.VISIBLE) {
            header.setVisibility(View.INVISIBLE); // keep its height so nothing below jumps
            selectionBar.setVisibility(View.VISIBLE);
            selectionBar.setAlpha(0f);
            selectionBar.animate().alpha(1f).setDuration(180).start();
        } else if (!selecting && header.getVisibility() != View.VISIBLE) {
            selectionBar.setVisibility(View.GONE);
            header.setVisibility(View.VISIBLE);
            header.setAlpha(0f);
            header.animate().alpha(1f).setDuration(180).start();
        }
        morphBar(selecting);
        if (!selecting) return;
        selCount.setText(String.valueOf(selected.size()) + " selected");
        selAll.setText(selected.size() == visible.size() ? "Clear" : "Select all");
        boolean saved = section == Section.SAVED;
        actSave.setVisibility(saved ? View.GONE : View.VISIBLE);
        actDelete.setVisibility(saved ? View.VISIBLE : View.GONE); // delete only for saved items
        actRepost.setVisibility(repostPackage() != null ? View.VISIBLE : View.GONE);
    }

    // ---------------- Actions ----------------

    private void pickFolder(StatusSource source) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, source.initialPickerUri());
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER + source.ordinal());
        } catch (ActivityNotFoundException e) {
            notice.show("Your phone doesn't have a folder picker", Notice.ERROR);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) notice.show("Deleted", Notice.SUCCESS);
            return; // onResume reloads
        }
        int idx = requestCode - REQ_PICK_FOLDER;
        if (idx < 0 || idx >= StatusSource.values().length) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        StatusSource source = StatusSource.values()[idx];
        if (!repo.isStatusFolder(uri)) {
            notice.show("That's not the status folder. Try again and tap \u201cUse this folder\u201d straight away.", Notice.ERROR);
            return;
        }
        try {
            repo.grantAccess(source, uri);
        } catch (SecurityException e) {
            notice.show("Couldn't get access to that folder. Please try again.", Notice.ERROR);
        }
        // onResume runs right after this and reloads.
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LEGACY_STORAGE) refresh();
    }

    private void saveMany(final List<StatusItem> list) {
        final List<StatusItem> todo = new ArrayList<>();
        for (StatusItem it : list) if (!savedNames.contains(it.name)) todo.add(it);
        if (todo.isEmpty()) {
            notice.show(list.size() == 1 ? "Already in your gallery" : "These are already in your gallery", Notice.INFO);
            return;
        }
        for (StatusItem it : todo) savedNames.add(it.name); // flip icons right away
        adapter.notifyDataSetChanged();
        io.execute(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                String error = null;
                final List<StatusItem> failed = new ArrayList<>();
                for (StatusItem it : todo) {
                    try {
                        repo.save(it);
                        ok++;
                    } catch (Exception e) {
                        failed.add(it);
                        error = e.getMessage();
                    }
                }
                final int saved = ok;
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        for (StatusItem it : failed) savedNames.remove(it.name);
                        if (failed.isEmpty()) {
                            if (todo.size() == 1) {
                                notice.show(todo.get(0).isVideo() ? "Video saved to gallery" : "Photo saved to gallery", Notice.SUCCESS, "View", openSaved);
                            } else {
                                notice.show("Saved " + saved + " statuses to gallery", Notice.SUCCESS, "View", openSaved);
                            }
                        } else {
                            notice.show(saved > 0 ? "Saved " + saved + " \u00b7 " + failed.size() + " couldn't be saved" : "Couldn't save. It may have just expired.", Notice.ERROR);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    /** Package to repost into: the open WhatsApp tab, or whichever WhatsApp is installed. */
    private String repostPackage() {
        StatusSource s = sourceOf(section);
        if (s != null) return Boolean.TRUE.equals(installed.get(s)) ? s.packageName : null;
        if (Boolean.TRUE.equals(installed.get(StatusSource.WHATSAPP))) return StatusSource.WHATSAPP.packageName;
        if (Boolean.TRUE.equals(installed.get(StatusSource.BUSINESS))) return StatusSource.BUSINESS.packageName;
        return null;
    }

    private void shareMany(final List<StatusItem> list, final boolean repost) {
        if (list.isEmpty()) return;
        final String pkg = repost ? repostPackage() : null;
        io.execute(new Runnable() {
            @Override
            public void run() {
                ArrayList<Uri> uris = null;
                try {
                    uris = repo.shareableUris(list);
                } catch (Exception ignored) {
                }
                final ArrayList<Uri> result = uris;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (result == null || result.isEmpty()) {
                            notice.show("Couldn't get this ready to share", Notice.ERROR);
                            return;
                        }
                        launchShare(MainActivity.this, notice, list, result, pkg);
                    }
                });
            }
        });
    }

    /** Opens the share sheet, or WhatsApp directly when [pkg] is set. */
    static void launchShare(android.app.Activity a, Notice notice, List<StatusItem> list, ArrayList<Uri> uris, String pkg) {
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND)
                    .setType(list.get(0).mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            boolean allImages = true, allVideos = true;
            for (StatusItem it : list) {
                if (it.isVideo()) allImages = false; else allVideos = false;
            }
            send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType(allImages ? "image/*" : allVideos ? "video/*" : "*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        ClipData clip = ClipData.newRawUri(list.get(0).name, uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            if (pkg != null) {
                send.setPackage(pkg);
                a.startActivity(send);
            } else {
                a.startActivity(Intent.createChooser(send, "Share"));
            }
        } catch (ActivityNotFoundException e) {
            notice.show(pkg != null ? "WhatsApp isn't installed" : "No app on your phone can share this", Notice.ERROR);
        }
    }

    /** Asks before deleting. [source] is null for the Saved tab. */
    private void confirmDelete(final List<StatusItem> list, final StatusSource source) {
        if (list.isEmpty()) return;
        int n = list.size();
        String what = source != null ? (n == 1 ? "status" : n + " statuses") : (n == 1 ? "saved item" : n + " saved items");
        String message = source != null
                ? "They'll be removed from this phone. Copies you've saved stay in your gallery."
                : "They'll be removed from your gallery.";
        new AlertDialog.Builder(this)
                .setTitle("Delete " + (n == 1 ? "this " : "") + what + "?")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        clearSelection();
                        if (source != null) deleteStatuses(list, source);
                        else deleteSaved(list);
                    }
                })
                .show()
                .getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(palette.error);
    }

    private void deleteStatuses(final List<StatusItem> list, final StatusSource source) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                int done = 0;
                boolean needsWrite = false;
                String error = null;
                try {
                    done = repo.deleteStatuses(list);
                } catch (SecurityException e) {
                    needsWrite = true;
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final int deleted = done;
                final boolean regrant = needsWrite;
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (regrant) {
                            askToRegrant(source);
                        } else if (err != null && deleted == 0) {
                            notice.show("Couldn't delete. Please try again.", Notice.ERROR);
                        } else {
                            notice.show(deleted == 1 ? "Deleted" : "Deleted " + deleted + " statuses", Notice.SUCCESS);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    /** Folder access granted before v1.3 is read-only; one more tap in the picker adds delete. */
    private void askToRegrant(final StatusSource source) {
        new AlertDialog.Builder(this)
                .setTitle("Allow deleting")
                .setMessage("To delete statuses, Status Vault needs to be allowed to change the "
                        + source.label + " folder. The next screen opens it — tap “Use this folder”, then “Allow”.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        pickFolder(source);
                    }
                })
                .show();
    }

    private void deleteSaved(final List<StatusItem> list) {
        List<String> names = new ArrayList<>();
        for (StatusItem it : list) names.add(it.name);
        prefs.addSkipAutoSave(names); // don't let auto-save bring them back
        io.execute(new Runnable() {
            @Override
            public void run() {
                IntentSender sender = null;
                String error = null;
                try {
                    sender = repo.delete(list);
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : "unknown error";
                }
                final IntentSender confirm = sender;
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (err != null) {
                            notice.show("Couldn't delete. Please try again.", Notice.ERROR);
                        } else if (confirm != null) {
                            try {
                                startIntentSenderForResult(confirm, REQ_DELETE, null, 0, 0, 0);
                            } catch (IntentSender.SendIntentException e) {
                                notice.show("Couldn't delete. Please try again.", Notice.ERROR);
                            }
                            return;
                        } else {
                            notice.show(list.size() == 1 ? "Deleted" : "Deleted " + list.size() + " items", Notice.SUCCESS);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    private void openViewer(int position) {
        if (position < 0 || position >= visible.size()) return;
        StatusSource s = sourceOf(section);
        Intent intent = ViewerActivity.intent(this, visible, position, section == Section.SAVED,
                savedNames, repostPackage(), s != null, s);
        startActivity(intent);
    }

    // ---------------- Helpers ----------------

    private void onClick(int id, final Runnable action) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
    }

    private static StatusSource sourceOf(Section s) {
        switch (s) {
            case WHATSAPP: return StatusSource.WHATSAPP;
            case BUSINESS: return StatusSource.BUSINESS;
            default: return null;
        }
    }



    static String timeLeftLabel(long millis) {
        long minutes = millis / 60_000L;
        if (minutes >= 60) return (minutes / 60) + "h left";
        return Math.max(1, minutes) + "m left";
    }

    static String durationLabel(long ms) {
        long s = Math.max(0, Math.round(ms / 1000.0));
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ---------------- Grid ----------------

    private final class StatusAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public StatusItem getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_status, parent, false);
                v.findViewById(R.id.card).setClipToOutline(true);
            }
            final StatusItem item = getItem(position);
            AspectFrameLayout ring = (AspectFrameLayout) v;
            View card = v.findViewById(R.id.card);
            ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
            final TextView duration = (TextView) v.findViewById(R.id.duration);
            CountdownBadge countdown = (CountdownBadge) v.findViewById(R.id.countdown);
            ImageButton saveBtn = (ImageButton) v.findViewById(R.id.save);
            View bottomRow = v.findViewById(R.id.bottom_row);
            View overlay = v.findViewById(R.id.sel_overlay);
            ImageView check = (ImageView) v.findViewById(R.id.sel_check);
            saveBtn.setFocusable(false); // ImageButton forces focusable=true, which would block grid item clicks

            boolean isStatus = section != Section.SAVED;
            boolean selecting = !selected.isEmpty();
            boolean isSelected = selected.contains(item.uri.toString());
            int cols = Math.max(2, Math.min(5, prefs.columns()));
            boolean compact = cols >= 5;
            boolean small = cols >= 4;
            ViewGroup.LayoutParams sbp = saveBtn.getLayoutParams();
            int sbSize = dp(compact ? 24 : small ? 26 : 30);
            if (sbp.width != sbSize) {
                sbp.width = sbSize;
                sbp.height = sbSize;
                saveBtn.setLayoutParams(sbp);
                saveBtn.setPadding(dp(small ? 5 : 7), dp(small ? 5 : 7), dp(small ? 5 : 7), dp(small ? 5 : 7));
            }

            // Unopened statuses: a small glowing, pulsing dot (no outline around the tile)
            boolean isNew = isStatus && prefs.newBadges() && !seen.contains(item.name);
            ring.setNewDot(isNew && !selecting ? palette.primary : 0);
            card.setBackground(palette.rounded(getColor(R.color.surface_variant), 18));
            ring.setSelected(isSelected);

            // Thumbnail + duration (videos)
            duration.setVisibility(View.GONE);
            ThumbnailLoader.get(MainActivity.this).load(item, thumb, dp(240),
                    item.isVideo() ? new ThumbnailLoader.DurationListener() {
                        @Override
                        public void onDuration(long ms) {
                            duration.setText(durationLabel(ms));
                            duration.setVisibility(View.VISIBLE);
                        }
                    } : null);
            if (item.isVideo() && duration.getVisibility() != View.VISIBLE) {
                duration.setText("");
                duration.setVisibility(View.VISIBLE); // play glyph until the length is known
            }

            // Circular countdown: the ring drains as the 24h run out (red under 3h)
            long left = isStatus && prefs.timeLeft() ? item.millisLeft(System.currentTimeMillis()) : -1;
            countdown.setVisibility(left > 0 && !selecting ? View.VISIBLE : View.INVISIBLE);
            if (left > 0) countdown.set(left, left < 3 * 3600_000L ? 0xFFFF8A80 : palette.primary);
            ViewGroup.LayoutParams clp = countdown.getLayoutParams();
            int cSize = dp(compact ? 24 : small ? 26 : 30);
            if (clp.width != cSize) {
                clp.width = cSize;
                clp.height = cSize;
                countdown.setLayoutParams(clp);
            }

            // Save button: glass circle, or accent with a check once saved
            if (!isStatus || selecting) {
                saveBtn.setVisibility(View.GONE);
            } else {
                boolean saved = savedNames.contains(item.name);
                saveBtn.setVisibility(View.VISIBLE);
                if (saved) {
                    saveBtn.setBackground(Palette.oval(palette.primary));
                    saveBtn.setImageTintList(ColorStateList.valueOf(palette.onPrimary));
                } else {
                    android.graphics.drawable.GradientDrawable glass = Palette.oval(0x66000000);
                    glass.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
                    saveBtn.setBackground(glass);
                    saveBtn.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                }
                saveBtn.setImageResource(saved ? R.drawable.ic_done : R.drawable.ic_download);
                saveBtn.setContentDescription(saved ? "Saved" : "Save");
                saveBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        view.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                // spring back, then the adapter refresh shows the check
                            }
                        }).start();
                        List<StatusItem> one = new ArrayList<>();
                        one.add(item);
                        saveMany(one);
                    }
                });
                saveBtn.setScaleX(1f);
                saveBtn.setScaleY(1f);
            }
            bottomRow.setVisibility(isStatus ? View.VISIBLE : View.GONE);

            // Selection state
            overlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            overlay.setBackgroundColor((palette.primary & 0x00FFFFFF) | 0x59000000);
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setImageResource(isSelected ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
            check.setImageTintList(ColorStateList.valueOf(isSelected ? palette.primary : Color.WHITE));
            check.setBackground(isSelected ? Palette.oval(Color.WHITE) : null);
            float scale = isSelected ? 0.92f : 1f;
            if (v.getScaleX() != scale) v.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
            return v;
        }
    }
}
