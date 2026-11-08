package com.statusvault.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen viewer: swipe left/right between statuses, tap to hide the bars or pause a video. */
public class ViewerActivity extends Activity implements SwipeFrameLayout.Listener {

    private static final String EXTRA_URIS = "uris";
    private static final String EXTRA_NAMES = "names";
    private static final String EXTRA_MIMES = "mimes";
    private static final String EXTRA_TIMES = "times";
    private static final String EXTRA_INDEX = "index";
    private static final String EXTRA_CAN_SAVE = "can_save";
    private static final String EXTRA_SAVED = "saved";

    static Intent intent(Context context, List<StatusItem> items, int index, boolean canSave, Collection<String> saved) {
        ArrayList<Uri> uris = new ArrayList<>(items.size());
        String[] names = new String[items.size()];
        String[] mimes = new String[items.size()];
        long[] times = new long[items.size()];
        for (int i = 0; i < items.size(); i++) {
            StatusItem it = items.get(i);
            uris.add(it.uri);
            names[i] = it.name;
            mimes[i] = it.mimeType;
            times[i] = it.lastModified;
        }
        return new Intent(context, ViewerActivity.class)
                .putParcelableArrayListExtra(EXTRA_URIS, uris)
                .putExtra(EXTRA_NAMES, names)
                .putExtra(EXTRA_MIMES, mimes)
                .putExtra(EXTRA_TIMES, times)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_CAN_SAVE, canSave)
                .putStringArrayListExtra(EXTRA_SAVED, new ArrayList<>(saved));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private StatusRepository repo;

    private final List<StatusItem> items = new ArrayList<>();
    private final Set<String> savedNames = new HashSet<>();
    private boolean canSave;
    private int index;
    private int loadToken = 0;
    private boolean chromeVisible = true;

    private ImageView image, playState;
    private VideoView video;
    private ProgressBar progress;
    private TextView title, counter;
    private Button saveBtn;
    private View topBar, bottomBar;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        repo = new StatusRepository(this);

        Intent in = getIntent();
        ArrayList<Uri> uris = Build.VERSION.SDK_INT >= 33
                ? in.getParcelableArrayListExtra(EXTRA_URIS, Uri.class)
                : in.<Uri>getParcelableArrayListExtra(EXTRA_URIS);
        String[] names = in.getStringArrayExtra(EXTRA_NAMES);
        String[] mimes = in.getStringArrayExtra(EXTRA_MIMES);
        long[] times = in.getLongArrayExtra(EXTRA_TIMES);
        if (uris == null || names == null || mimes == null || times == null || uris.isEmpty()) {
            finish();
            return;
        }
        for (int i = 0; i < uris.size(); i++) items.add(new StatusItem(uris.get(i), names[i], mimes[i], times[i]));
        ArrayList<String> saved = in.getStringArrayListExtra(EXTRA_SAVED);
        if (saved != null) savedNames.addAll(saved);
        canSave = in.getBooleanExtra(EXTRA_CAN_SAVE, false);
        index = savedInstanceState != null
                ? savedInstanceState.getInt(EXTRA_INDEX)
                : in.getIntExtra(EXTRA_INDEX, 0);
        index = Math.max(0, Math.min(index, items.size() - 1));

        ((SwipeFrameLayout) findViewById(R.id.root)).setListener(this);
        image = (ImageView) findViewById(R.id.image);
        video = (VideoView) findViewById(R.id.video);
        playState = (ImageView) findViewById(R.id.play_state);
        progress = (ProgressBar) findViewById(R.id.progress);
        title = (TextView) findViewById(R.id.title);
        counter = (TextView) findViewById(R.id.counter);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        saveBtn = (Button) findViewById(R.id.save);

        findViewById(R.id.close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(items.get(index));
            }
        });
        findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share(items.get(index));
            }
        });
        saveBtn.setVisibility(canSave ? View.VISIBLE : View.GONE);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(true);
                progress.setVisibility(View.GONE);
                video.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                progress.setVisibility(View.GONE);
                toast("Can't play this video");
                return true;
            }
        });

        show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_INDEX, index);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) {
            video.pause();
            playState.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (video != null) video.stopPlayback();
        io.shutdownNow();
    }

    // ---------------- Display ----------------

    private void show() {
        final StatusItem item = items.get(index);
        final int token = ++loadToken;
        counter.setText((index + 1) + " / " + items.size());
        title.setText(android.text.format.DateUtils.getRelativeTimeSpanString(
                item.lastModified, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
        updateSaveButton();

        playState.setVisibility(View.GONE);
        video.stopPlayback();
        image.setImageDrawable(null);

        if (item.isVideo()) {
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            video.setVideoURI(item.uri);
        } else {
            video.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            final int maxPx = Math.max(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            io.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap bmp = ThumbnailLoader.get(ViewerActivity.this).decode(item, Math.min(maxPx, 2560));
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != loadToken || isDestroyed()) return;
                            progress.setVisibility(View.GONE);
                            if (bmp != null) image.setImageBitmap(bmp);
                            else toast("Can't open this photo");
                        }
                    });
                }
            });
        }
    }

    private void updateSaveButton() {
        if (!canSave) return;
        boolean saved = savedNames.contains(items.get(index).name);
        saveBtn.setEnabled(!saved);
        saveBtn.setText(saved ? "Saved" : "Save");
        saveBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(saved ? R.drawable.ic_done : R.drawable.ic_download, 0, 0, 0);
        saveBtn.setBackgroundResource(saved ? R.drawable.bg_viewer_disabled : R.drawable.bg_viewer_primary);
        saveBtn.setTextColor(saved ? 0xCCFFFFFF : 0xFF003735);
        saveBtn.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(saved ? 0xCCFFFFFF : 0xFF003735));
    }

    @Override
    public void onSwipeNext() {
        if (index < items.size() - 1) {
            index++;
            show();
        }
    }

    @Override
    public void onSwipePrevious() {
        if (index > 0) {
            index--;
            show();
        }
    }

    @Override
    public void onTap() {
        StatusItem item = items.get(index);
        if (item.isVideo() && video.getVisibility() == View.VISIBLE && progress.getVisibility() != View.VISIBLE) {
            if (video.isPlaying()) {
                video.pause();
                playState.setImageResource(R.drawable.ic_play);
                playState.setVisibility(View.VISIBLE);
            } else {
                video.start();
                playState.setVisibility(View.GONE);
            }
            return;
        }
        chromeVisible = !chromeVisible;
        topBar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
    }

    // ---------------- Actions ----------------

    private void save(final StatusItem item) {
        if (savedNames.contains(item.name)) return;
        savedNames.add(item.name);
        updateSaveButton();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    repo.save(item);
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : "unknown error";
                }
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (error != null) {
                            savedNames.remove(item.name);
                            updateSaveButton();
                            toast("Save failed: " + error);
                        } else {
                            toast(item.isVideo() ? "Saved to Movies/StatusVault" : "Saved to Pictures/StatusVault");
                        }
                    }
                });
            }
        });
    }

    private void share(final StatusItem item) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                Uri shareUri = null;
                try {
                    shareUri = repo.shareableUri(item);
                } catch (Exception ignored) {
                }
                final Uri uri = shareUri;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (uri == null) {
                            toast("Couldn't share this status");
                            return;
                        }
                        Intent send = new Intent(Intent.ACTION_SEND)
                                .setType(item.mimeType)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(android.content.ClipData.newRawUri(item.name, uri));
                        try {
                            startActivity(Intent.createChooser(send, "Share status"));
                        } catch (ActivityNotFoundException e) {
                            toast("No app can share this");
                        }
                    }
                });
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
