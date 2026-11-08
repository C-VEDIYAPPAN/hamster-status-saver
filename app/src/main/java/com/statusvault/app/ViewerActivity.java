package com.statusvault.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen viewer: swipe left/right between statuses, tap a video to pause, tap a photo to hide the bars.
 * Actions: Save, Repost to WhatsApp, Share, grab a photo from a video, Delete (Saved tab).
 */
public class ViewerActivity extends Activity implements SwipeFrameLayout.Listener {

    private static final String EXTRA_URIS = "uris";
    private static final String EXTRA_NAMES = "names";
    private static final String EXTRA_MIMES = "mimes";
    private static final String EXTRA_TIMES = "times";
    private static final String EXTRA_SIZES = "sizes";
    private static final String EXTRA_INDEX = "index";
    private static final String EXTRA_IS_SAVED = "is_saved";
    private static final String EXTRA_CAN_SAVE = "can_save";
    private static final String EXTRA_SAVED = "saved";
    private static final String EXTRA_REPOST_PKG = "repost_pkg";
    private static final int REQ_DELETE = 1;

    static Intent intent(Context context, List<StatusItem> items, int index, boolean isSavedSection,
                         Collection<String> saved, String repostPackage, boolean canSave) {
        int n = items.size();
        ArrayList<Uri> uris = new ArrayList<>(n);
        String[] names = new String[n];
        String[] mimes = new String[n];
        long[] times = new long[n];
        long[] sizes = new long[n];
        for (int i = 0; i < n; i++) {
            StatusItem it = items.get(i);
            uris.add(it.uri);
            names[i] = it.name;
            mimes[i] = it.mimeType;
            times[i] = it.lastModified;
            sizes[i] = it.size;
        }
        return new Intent(context, ViewerActivity.class)
                .putParcelableArrayListExtra(EXTRA_URIS, uris)
                .putExtra(EXTRA_NAMES, names)
                .putExtra(EXTRA_MIMES, mimes)
                .putExtra(EXTRA_TIMES, times)
                .putExtra(EXTRA_SIZES, sizes)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_IS_SAVED, isSavedSection)
                .putExtra(EXTRA_CAN_SAVE, canSave)
                .putExtra(EXTRA_REPOST_PKG, repostPackage)
                .putStringArrayListExtra(EXTRA_SAVED, new ArrayList<>(saved));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private StatusRepository repo;
    private Prefs prefs;

    private final List<StatusItem> items = new ArrayList<>();
    private final Set<String> savedNames = new HashSet<>();
    private boolean canSave, isSavedSection;
    private String repostPackage;
    private int index;
    private int loadToken = 0;
    private boolean chromeVisible = true;
    private int accent, onAccent;

    private ImageView image, playState;
    private VideoView video;
    private ProgressBar progress;
    private TextView title, counter;
    private View topBar;
    private LinearLayout bottomBar;
    private View actSave, actRepost, actShare, actFrame, actDelete;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        repo = new StatusRepository(this);
        prefs = new Prefs(this);
        accent = Palette.viewerPrimary(this);
        onAccent = Palette.viewerOnPrimary(this);

        Intent in = getIntent();
        ArrayList<Uri> uris = Build.VERSION.SDK_INT >= 33
                ? in.getParcelableArrayListExtra(EXTRA_URIS, Uri.class)
                : in.<Uri>getParcelableArrayListExtra(EXTRA_URIS);
        String[] names = in.getStringArrayExtra(EXTRA_NAMES);
        String[] mimes = in.getStringArrayExtra(EXTRA_MIMES);
        long[] times = in.getLongArrayExtra(EXTRA_TIMES);
        long[] sizes = in.getLongArrayExtra(EXTRA_SIZES);
        if (uris == null || names == null || mimes == null || times == null || sizes == null || uris.isEmpty()) {
            finish();
            return;
        }
        for (int i = 0; i < uris.size(); i++) {
            items.add(new StatusItem(uris.get(i), names[i], mimes[i], times[i], sizes[i]));
        }
        ArrayList<String> saved = in.getStringArrayListExtra(EXTRA_SAVED);
        if (saved != null) savedNames.addAll(saved);
        canSave = in.getBooleanExtra(EXTRA_CAN_SAVE, false);
        isSavedSection = in.getBooleanExtra(EXTRA_IS_SAVED, false);
        repostPackage = in.getStringExtra(EXTRA_REPOST_PKG);
        index = savedInstanceState != null
                ? savedInstanceState.getInt(EXTRA_INDEX)
                : in.getIntExtra(EXTRA_INDEX, 0);
        index = Math.max(0, Math.min(index, items.size() - 1));

        ((SwipeFrameLayout) findViewById(R.id.root)).setListener(this);
        image = (ImageView) findViewById(R.id.image);
        video = (VideoView) findViewById(R.id.video);
        playState = (ImageView) findViewById(R.id.play_state);
        progress = (ProgressBar) findViewById(R.id.progress);
        progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        title = (TextView) findViewById(R.id.title);
        counter = (TextView) findViewById(R.id.counter);
        topBar = findViewById(R.id.top_bar);
        bottomBar = (LinearLayout) findViewById(R.id.bottom_bar);

        findViewById(R.id.close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        actSave = addAction(R.drawable.ic_download, "Save", true, new Runnable() {
            public void run() { save(items.get(index)); }
        });
        actRepost = addAction(R.drawable.ic_repost, "Repost", false, new Runnable() {
            public void run() { share(items.get(index), repostPackage); }
        });
        actShare = addAction(R.drawable.ic_share, "Share", false, new Runnable() {
            public void run() { share(items.get(index), null); }
        });
        actFrame = addAction(R.drawable.ic_camera, "Photo", false, new Runnable() {
            public void run() { grabFrame(items.get(index)); }
        });
        actDelete = addAction(R.drawable.ic_delete, "Delete", false, new Runnable() {
            public void run() { delete(items.get(index)); }
        });

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

    private View addAction(int icon, String label, boolean primary, final Runnable action) {
        View v = LayoutInflater.from(this).inflate(R.layout.viewer_action, bottomBar, false);
        ImageView iv = (ImageView) v.findViewById(R.id.action_icon);
        iv.setImageResource(icon);
        styleAction(v, primary, false);
        ((TextView) v.findViewById(R.id.action_label)).setText(label);
        v.setContentDescription(label);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                action.run();
            }
        });
        bottomBar.addView(v);
        return v;
    }

    private void styleAction(View v, boolean primary, boolean done) {
        ImageView iv = (ImageView) v.findViewById(R.id.action_icon);
        int bg = done ? 0x33FFFFFF : primary ? accent : 0x33FFFFFF;
        int fg = done ? 0xCCFFFFFF : primary ? onAccent : 0xFFFFFFFF;
        iv.setBackground(Palette.oval(bg));
        iv.setImageTintList(ColorStateList.valueOf(fg));
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
            playState.setImageResource(R.drawable.ic_play);
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
        CharSequence when = DateUtils.getRelativeTimeSpanString(
                item.lastModified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        long left = isSavedSection ? -1 : item.millisLeft(System.currentTimeMillis());
        title.setText(left > 0 ? when + "  ·  " + MainActivity.timeLeftLabel(left) : when);
        updateActions();
        if (!isSavedSection) prefs.markSeen(Collections.singletonList(item.name));

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

    private void updateActions() {
        StatusItem item = items.get(index);
        boolean saved = savedNames.contains(item.name);
        actSave.setVisibility(canSave ? View.VISIBLE : View.GONE);
        styleAction(actSave, true, saved);
        ((TextView) actSave.findViewById(R.id.action_label)).setText(saved ? "Saved" : "Save");
        ((ImageView) actSave.findViewById(R.id.action_icon)).setImageResource(saved ? R.drawable.ic_done : R.drawable.ic_download);
        actSave.setEnabled(!saved);
        actRepost.setVisibility(repostPackage != null ? View.VISIBLE : View.GONE);
        actFrame.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
        actDelete.setVisibility(isSavedSection ? View.VISIBLE : View.GONE);
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
        updateActions();
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
                            updateActions();
                            toast("Save failed: " + error);
                        } else {
                            toast(item.isVideo() ? "Saved to Movies/StatusVault" : "Saved to Pictures/StatusVault");
                        }
                    }
                });
            }
        });
    }

    private void share(final StatusItem item, final String pkg) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                ArrayList<Uri> uris = null;
                try {
                    uris = repo.shareableUris(Collections.singletonList(item));
                } catch (Exception ignored) {
                }
                final ArrayList<Uri> result = uris;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (result == null || result.isEmpty()) {
                            toast("Couldn't share this status");
                            return;
                        }
                        MainActivity.launchShare(ViewerActivity.this, Collections.singletonList(item), result, pkg);
                    }
                });
            }
        });
    }

    /** Saves the video frame currently on screen as a photo. */
    private void grabFrame(final StatusItem item) {
        if (!item.isVideo()) return;
        final long position = video.getCurrentPosition();
        toast("Saving this frame…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    repo.saveVideoFrame(item, position);
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : "unknown error";
                }
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        toast(error == null ? "Frame saved to Pictures/StatusVault" : "Couldn't save frame: " + error);
                    }
                });
            }
        });
    }

    private void delete(final StatusItem item) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                IntentSender sender = null;
                String err = null;
                try {
                    sender = repo.delete(Collections.singletonList(item));
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : "unknown error";
                }
                final IntentSender confirm = sender;
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (error != null) {
                            toast("Delete failed: " + error);
                        } else if (confirm != null) {
                            try {
                                startIntentSenderForResult(confirm, REQ_DELETE, null, 0, 0, 0);
                            } catch (IntentSender.SendIntentException e) {
                                toast("Couldn't delete");
                            }
                        } else {
                            removeCurrent();
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE && resultCode == RESULT_OK) removeCurrent();
    }

    private void removeCurrent() {
        toast("Deleted");
        items.remove(index);
        if (items.isEmpty()) {
            finish();
            return;
        }
        index = Math.min(index, items.size() - 1);
        show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
