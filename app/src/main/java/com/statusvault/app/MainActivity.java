package com.statusvault.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private StatusRepository repo;
    private Prefs prefs;
    private Palette palette;
    private int appliedTheme;

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
    private boolean loadedOnce = false;
    private boolean loading = false;
    private boolean autoSaving = false;
    private int loadGeneration = 0;

    // Views
    private TextView[] tabs;
    private TextView chipAll, chipPhotos, chipVideos, stats, selCount;
    private View filters, empty, header, selectionBar;
    private View selSave, selRepost, selDelete;
    private GridView grid;
    private ImageView emptyIcon;
    private TextView emptyTitle, emptyBody;
    private Button emptyAction;
    private ProgressBar progress;
    private final StatusAdapter adapter = new StatusAdapter();
    private final List<StatusItem> visible = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repo = new StatusRepository(this);
        prefs = new Prefs(this);
        appliedTheme = prefs.theme();
        for (Section s : Section.values()) items.put(s, new ArrayList<StatusItem>());

        if (savedInstanceState != null) {
            section = Section.values()[savedInstanceState.getInt(KEY_SECTION, 0)];
            filter = Filter.values()[savedInstanceState.getInt(KEY_FILTER, 0)];
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_SAVED, false)) section = Section.SAVED;

        tabs = new TextView[]{
                (TextView) findViewById(R.id.tab_whatsapp),
                (TextView) findViewById(R.id.tab_business),
                (TextView) findViewById(R.id.tab_saved),
        };
        for (int i = 0; i < tabs.length; i++) {
            final Section s = Section.values()[i];
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchSection(s);
                }
            });
        }

        chipAll = (TextView) findViewById(R.id.chip_all);
        chipPhotos = (TextView) findViewById(R.id.chip_photos);
        chipVideos = (TextView) findViewById(R.id.chip_videos);
        bindChip(chipAll, Filter.ALL);
        bindChip(chipPhotos, Filter.PHOTOS);
        bindChip(chipVideos, Filter.VIDEOS);
        stats = (TextView) findViewById(R.id.stats);

        header = findViewById(R.id.header);
        selectionBar = findViewById(R.id.selection_bar);
        selCount = (TextView) findViewById(R.id.sel_count);
        selSave = findViewById(R.id.sel_save);
        selRepost = findViewById(R.id.sel_repost);
        selDelete = findViewById(R.id.sel_delete);
        onClick(R.id.sel_close, new Runnable() { public void run() { clearSelection(); } });
        onClick(R.id.sel_all, new Runnable() { public void run() { selectAll(); } });
        onClick(R.id.sel_save, new Runnable() { public void run() { saveMany(selectedItems()); clearSelection(); } });
        onClick(R.id.sel_repost, new Runnable() { public void run() { shareMany(selectedItems(), true); clearSelection(); } });
        onClick(R.id.sel_share, new Runnable() { public void run() { shareMany(selectedItems(), false); clearSelection(); } });
        onClick(R.id.sel_delete, new Runnable() { public void run() { deleteMany(selectedItems()); clearSelection(); } });
        onClick(R.id.refresh, new Runnable() { public void run() { refresh(); } });
        onClick(R.id.settings, new Runnable() {
            public void run() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        filters = findViewById(R.id.filters);
        empty = findViewById(R.id.empty);
        emptyIcon = (ImageView) findViewById(R.id.empty_icon);
        emptyTitle = (TextView) findViewById(R.id.empty_title);
        emptyBody = (TextView) findViewById(R.id.empty_body);
        emptyAction = (Button) findViewById(R.id.empty_action);
        progress = (ProgressBar) findViewById(R.id.progress);

        grid = (GridView) findViewById(R.id.grid);
        grid.setAdapter(adapter);
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
        // New statuses appear whenever the user views them in WhatsApp, so reload on return.
        refresh();
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
                        // Drop selections that no longer exist.
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
        boolean anyNew = false;
        for (Section s : new Section[]{Section.WHATSAPP, Section.BUSINESS}) {
            for (StatusItem it : items.get(s)) {
                if (!savedNames.contains(it.name)) {
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
                            toast("Auto-saved " + n + (n == 1 ? " status" : " statuses"));
                            refresh();
                        }
                    }
                });
            }
        });
    }

    // ---------------- Rendering ----------------

    private void render() {
        palette = new Palette(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        grid.setNumColumns(Math.max(2, Math.min(5, prefs.columns())));

        for (int i = 0; i < tabs.length; i++) {
            boolean sel = Section.values()[i] == section;
            tabs[i].setBackgroundResource(sel ? R.drawable.bg_tab_selected : 0);
            tabs[i].setTextColor(getColor(sel ? R.color.on_surface : R.color.on_surface_variant));
            tabs[i].setElevation(sel ? dp(1) : 0);
        }

        List<StatusItem> all = sorted(items.get(section));
        int photos = 0, videos = 0;
        long bytes = 0;
        for (StatusItem it : all) {
            if (it.isVideo()) videos++; else photos++;
            bytes += it.size;
        }
        styleChip(chipAll, "All · " + all.size(), filter == Filter.ALL);
        styleChip(chipPhotos, "Photos · " + photos, filter == Filter.PHOTOS);
        styleChip(chipVideos, "Videos · " + videos, filter == Filter.VIDEOS);
        stats.setText(all.isEmpty() ? "" : Formatter.formatShortFileSize(this, bytes));

        visible.clear();
        for (StatusItem it : all) {
            if (filter == Filter.ALL || (filter == Filter.VIDEOS) == it.isVideo()) visible.add(it);
        }
        adapter.notifyDataSetChanged();
        renderSelectionBar();

        progress.setVisibility(loading && !loadedOnce ? View.VISIBLE : View.GONE);
        if (!loadedOnce) {
            empty.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            filters.setVisibility(View.VISIBLE);
            return;
        }

        StatusSource source = sourceOf(section);
        if (source != null && !Boolean.TRUE.equals(hasAccess.get(source))) {
            filters.setVisibility(View.GONE);
            grid.setVisibility(View.GONE);
            if (!Boolean.TRUE.equals(installed.get(source))) {
                showEmpty(R.drawable.ic_block, source.label + " isn't installed",
                        "Install it and view a few statuses, then come back here.", null, null);
            } else {
                final StatusSource src = source;
                showEmpty(R.drawable.ic_folder, "Allow access to " + source.label + " statuses",
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

        filters.setVisibility(View.VISIBLE);
        if (visible.isEmpty()) {
            grid.setVisibility(View.GONE);
            if (source == null) {
                showEmpty(R.drawable.ic_download, filter == Filter.ALL ? "Nothing saved yet" : "Nothing here",
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

    private void showEmpty(int icon, String title, String body, String action, View.OnClickListener onAction) {
        empty.setVisibility(View.VISIBLE);
        emptyIcon.setImageResource(icon);
        emptyIcon.setImageTintList(ColorStateList.valueOf(palette.primary));
        emptyTitle.setText(title);
        emptyBody.setText(body);
        if (action != null) {
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(action);
            emptyAction.setBackground(palette.rounded(palette.primary, 24));
            emptyAction.setTextColor(palette.onPrimary);
            emptyAction.setOnClickListener(onAction);
        } else {
            emptyAction.setVisibility(View.GONE);
        }
    }

    private void bindChip(TextView chip, final Filter f) {
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = f;
                render();
            }
        });
    }

    private void styleChip(TextView chip, String text, boolean isSelected) {
        chip.setText(text);
        chip.setBackground(isSelected ? palette.rounded(palette.container, 16)
                : palette.outlined(getColor(R.color.outline), 16));
        chip.setTextColor(isSelected ? palette.onContainer : getColor(R.color.on_surface));
    }

    private void switchSection(Section s) {
        if (section == s) return;
        section = s;
        selected.clear();
        render();
        grid.setSelection(0);
    }

    // ---------------- Selection ----------------

    private void toggleSelection(int position) {
        if (position < 0 || position >= visible.size()) return;
        String key = visible.get(position).uri.toString();
        if (!selected.remove(key)) selected.add(key);
        adapter.notifyDataSetChanged();
        renderSelectionBar();
    }

    private void selectAll() {
        if (selected.size() == visible.size()) {
            selected.clear();
        } else {
            for (StatusItem it : visible) selected.add(it.uri.toString());
        }
        adapter.notifyDataSetChanged();
        renderSelectionBar();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        renderSelectionBar();
    }

    private List<StatusItem> selectedItems() {
        List<StatusItem> out = new ArrayList<>();
        for (StatusItem it : visible) if (selected.contains(it.uri.toString())) out.add(it);
        return out;
    }

    private void renderSelectionBar() {
        boolean selecting = !selected.isEmpty();
        header.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (!selecting) return;
        selCount.setText(selected.size() + " selected");
        boolean saved = section == Section.SAVED;
        selSave.setVisibility(saved ? View.GONE : View.VISIBLE);
        selDelete.setVisibility(saved ? View.VISIBLE : View.GONE);
        selRepost.setVisibility(repostPackage() != null ? View.VISIBLE : View.GONE);
    }

    // ---------------- Actions ----------------

    private void pickFolder(StatusSource source) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, source.initialPickerUri());
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER + source.ordinal());
        } catch (ActivityNotFoundException e) {
            toast("This phone has no folder picker");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) toast("Deleted");
            return; // onResume reloads
        }
        int idx = requestCode - REQ_PICK_FOLDER;
        if (idx < 0 || idx >= StatusSource.values().length) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        StatusSource source = StatusSource.values()[idx];
        if (!repo.isStatusFolder(uri)) {
            toast("That isn't the .Statuses folder. Try again and tap “Use this folder” without moving.");
            return;
        }
        try {
            repo.grantAccess(source, uri);
        } catch (SecurityException e) {
            toast("Couldn't keep access to that folder");
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
            toast(list.size() == 1 ? "Already saved" : "All already saved");
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
                                toast(todo.get(0).isVideo() ? "Saved to Movies/StatusVault" : "Saved to Pictures/StatusVault");
                            } else {
                                toast("Saved " + saved + " statuses");
                            }
                        } else {
                            toast("Saved " + saved + ", failed " + failed.size() + (err != null ? ": " + err : ""));
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
                            toast("Couldn't share");
                            return;
                        }
                        launchShare(MainActivity.this, list, result, pkg);
                    }
                });
            }
        });
    }

    /** Opens the share sheet, or WhatsApp directly when [pkg] is set. */
    static void launchShare(android.app.Activity a, List<StatusItem> list, ArrayList<Uri> uris, String pkg) {
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
            Toast.makeText(a, pkg != null ? "WhatsApp isn't installed" : "No app can share this", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteMany(final List<StatusItem> list) {
        if (list.isEmpty()) return;
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
                            toast("Delete failed: " + err);
                        } else if (confirm != null) {
                            try {
                                startIntentSenderForResult(confirm, REQ_DELETE, null, 0, 0, 0);
                            } catch (IntentSender.SendIntentException e) {
                                toast("Couldn't delete");
                            }
                            return;
                        } else {
                            toast(list.size() == 1 ? "Deleted" : "Deleted " + list.size() + " items");
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
                savedNames, repostPackage(), s != null);
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    static String timeLeftLabel(long millis) {
        long minutes = millis / 60_000L;
        if (minutes >= 60) return (minutes / 60) + "h left";
        return Math.max(1, minutes) + "m left";
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
            View v = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_status, parent, false);
            final StatusItem item = getItem(position);
            ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
            ImageView play = (ImageView) v.findViewById(R.id.play);
            ImageButton saveBtn = (ImageButton) v.findViewById(R.id.save);
            TextView badgeNew = (TextView) v.findViewById(R.id.badge_new);
            TextView badgeTime = (TextView) v.findViewById(R.id.badge_time);
            View overlay = v.findViewById(R.id.sel_overlay);
            ImageView check = (ImageView) v.findViewById(R.id.sel_check);
            saveBtn.setFocusable(false); // ImageButton forces focusable=true, which would block grid item clicks

            int cols = Math.max(2, Math.min(5, prefs.columns()));
            ThumbnailLoader.get(MainActivity.this).load(item, thumb, cols <= 2 ? dp(260) : dp(200));
            play.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);

            boolean isStatus = section != Section.SAVED;
            boolean selecting = !selected.isEmpty();
            boolean isSelected = selected.contains(item.uri.toString());

            // NEW badge
            boolean isNew = isStatus && prefs.newBadges() && !seen.contains(item.name);
            badgeNew.setVisibility(isNew ? View.VISIBLE : View.GONE);
            if (isNew) {
                badgeNew.setBackground(palette.rounded(palette.primary, 10));
                badgeNew.setTextColor(palette.onPrimary);
            }

            // Time left before WhatsApp deletes it
            long left = isStatus && prefs.timeLeft() ? item.millisLeft(System.currentTimeMillis()) : -1;
            badgeTime.setVisibility(left > 0 && !selecting ? View.VISIBLE : View.GONE);
            if (left > 0) badgeTime.setText(timeLeftLabel(left));

            // Save button
            if (!isStatus || selecting) {
                saveBtn.setVisibility(View.GONE);
            } else {
                boolean saved = savedNames.contains(item.name);
                saveBtn.setVisibility(View.VISIBLE);
                saveBtn.setBackground(Palette.oval(palette.container));
                saveBtn.setImageTintList(ColorStateList.valueOf(palette.onContainer));
                saveBtn.setImageResource(saved ? R.drawable.ic_done : R.drawable.ic_download);
                saveBtn.setContentDescription(saved ? "Saved" : "Save");
                saveBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        List<StatusItem> one = new ArrayList<>();
                        one.add(item);
                        saveMany(one);
                    }
                });
            }

            // Selection state
            overlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            overlay.setBackgroundColor((palette.primary & 0x00FFFFFF) | 0x55000000);
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setImageResource(isSelected ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
            check.setImageTintList(ColorStateList.valueOf(isSelected ? palette.primary : 0xFFFFFFFF));
            check.setBackground(isSelected ? Palette.oval(0xFFFFFFFF) : null);
            v.setScaleX(isSelected ? 0.94f : 1f);
            v.setScaleY(isSelected ? 0.94f : 1f);
            return v;
        }
    }
}
