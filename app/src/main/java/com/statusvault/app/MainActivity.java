package com.statusvault.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    enum Section { WHATSAPP, BUSINESS, SAVED }

    enum Filter { ALL, PHOTOS, VIDEOS }

    private static final int REQ_PICK_FOLDER = 100; // + section ordinal
    private static final int REQ_LEGACY_STORAGE = 200;
    private static final String KEY_SECTION = "section";
    private static final String KEY_FILTER = "filter";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private StatusRepository repo;

    // State
    private Section section = Section.WHATSAPP;
    private Filter filter = Filter.ALL;
    private final Map<Section, List<StatusItem>> items = new EnumMap<>(Section.class);
    private final Map<StatusSource, Boolean> hasAccess = new EnumMap<>(StatusSource.class);
    private final Map<StatusSource, Boolean> installed = new EnumMap<>(StatusSource.class);
    private final Set<String> savedNames = new HashSet<>();
    private boolean loadedOnce = false;
    private boolean loading = false;
    private int loadGeneration = 0;

    // Views
    private TextView[] tabs;
    private TextView chipAll, chipPhotos, chipVideos;
    private View filters, empty;
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
        for (Section s : Section.values()) items.put(s, new ArrayList<StatusItem>());

        if (savedInstanceState != null) {
            section = Section.values()[savedInstanceState.getInt(KEY_SECTION, 0)];
            filter = Filter.values()[savedInstanceState.getInt(KEY_FILTER, 0)];
        }

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
                    if (section != s) {
                        section = s;
                        render();
                        grid.setSelection(0);
                    }
                }
            });
        }

        chipAll = (TextView) findViewById(R.id.chip_all);
        chipPhotos = (TextView) findViewById(R.id.chip_photos);
        chipVideos = (TextView) findViewById(R.id.chip_videos);
        bindChip(chipAll, Filter.ALL);
        bindChip(chipPhotos, Filter.PHOTOS);
        bindChip(chipVideos, Filter.VIDEOS);

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
                openViewer(position);
            }
        });

        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_STORAGE);
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // New statuses appear whenever the user views them in WhatsApp, so reload on return.
        refresh();
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
                for (StatusSource src : StatusSource.values()) {
                    boolean granted = repo.accessUri(src) != null;
                    access.put(src, granted);
                    inst.put(src, repo.isInstalled(src));
                    next.put(src == StatusSource.WHATSAPP ? Section.WHATSAPP : Section.BUSINESS,
                            granted ? repo.loadStatuses(src) : new ArrayList<StatusItem>());
                }
                next.put(Section.SAVED, repo.loadSaved());
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != loadGeneration || isDestroyed()) return;
                        items.putAll(next);
                        hasAccess.putAll(access);
                        installed.putAll(inst);
                        savedNames.clear();
                        for (StatusItem it : next.get(Section.SAVED)) savedNames.add(it.name);
                        loading = false;
                        loadedOnce = true;
                        render();
                    }
                });
            }
        });
    }

    // ---------------- Rendering ----------------

    private void render() {
        for (int i = 0; i < tabs.length; i++) {
            boolean sel = Section.values()[i] == section;
            tabs[i].setBackgroundResource(sel ? R.drawable.bg_tab_selected : 0);
            tabs[i].setTextColor(getColor(sel ? R.color.on_surface : R.color.on_surface_variant));
            tabs[i].setElevation(sel ? dp(1) : 0);
        }

        List<StatusItem> all = items.get(section);
        int photos = 0, videos = 0;
        for (StatusItem it : all) {
            if (it.isVideo()) videos++; else photos++;
        }
        styleChip(chipAll, "All · " + all.size(), filter == Filter.ALL);
        styleChip(chipPhotos, "Photos · " + photos, filter == Filter.PHOTOS);
        styleChip(chipVideos, "Videos · " + videos, filter == Filter.VIDEOS);

        visible.clear();
        for (StatusItem it : all) {
            if (filter == Filter.ALL || (filter == Filter.VIDEOS) == it.isVideo()) visible.add(it);
        }
        adapter.notifyDataSetChanged();

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

    private void showEmpty(int icon, String title, String body, String action, View.OnClickListener onAction) {
        empty.setVisibility(View.VISIBLE);
        emptyIcon.setImageResource(icon);
        emptyTitle.setText(title);
        emptyBody.setText(body);
        if (action != null) {
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(action);
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

    private void styleChip(TextView chip, String text, boolean selected) {
        chip.setText(text);
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(getColor(selected ? R.color.on_primary_container : R.color.on_surface));
    }

    // ---------------- Actions ----------------

    private void pickFolder(StatusSource source) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, source.initialPickerUri());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER + source.ordinal());
        } catch (ActivityNotFoundException e) {
            toast("This phone has no folder picker");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
            return;
        }
        // onResume runs right after this and reloads.
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LEGACY_STORAGE) refresh();
    }

    private void save(final StatusItem item) {
        if (savedNames.contains(item.name)) {
            toast("Already saved");
            return;
        }
        savedNames.add(item.name); // optimistic, so the icon flips immediately
        adapter.notifyDataSetChanged();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                boolean ok;
                try {
                    repo.save(item);
                    ok = true;
                    msg = item.isVideo() ? "Saved to Movies/StatusVault" : "Saved to Pictures/StatusVault";
                } catch (Exception e) {
                    ok = false;
                    msg = "Save failed: " + (e.getMessage() != null ? e.getMessage() : "unknown error");
                }
                final boolean success = ok;
                final String message = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        toast(message);
                        if (!success) {
                            savedNames.remove(item.name);
                            adapter.notifyDataSetChanged();
                        } else {
                            refresh();
                        }
                    }
                });
            }
        });
    }

    private void openViewer(int position) {
        if (position < 0 || position >= visible.size()) return;
        Intent intent = ViewerActivity.intent(this, visible, position, section != Section.SAVED, savedNames);
        startActivity(intent);
    }

    // ---------------- Helpers ----------------

    private static StatusSource sourceOf(Section s) {
        switch (s) {
            case WHATSAPP: return StatusSource.WHATSAPP;
            case BUSINESS: return StatusSource.BUSINESS;
            default: return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
            saveBtn.setFocusable(false); // ImageButton forces focusable=true, which would block grid item clicks

            ThumbnailLoader.get(MainActivity.this).load(item, thumb, dp(220));
            play.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);

            if (section == Section.SAVED) {
                saveBtn.setVisibility(View.GONE);
            } else {
                boolean saved = savedNames.contains(item.name);
                saveBtn.setVisibility(View.VISIBLE);
                saveBtn.setImageResource(saved ? R.drawable.ic_done : R.drawable.ic_download);
                saveBtn.setContentDescription(saved ? "Saved" : "Save");
                saveBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        save(item);
                    }
                });
            }
            return v;
        }
    }
}
