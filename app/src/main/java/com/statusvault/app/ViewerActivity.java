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
    private static final String EXTRA_SOURCE = "source";
    private static final int REQ_DELETE = 1;
    private static final int REQ_REGRANT = 2;

    static Intent intent(Context context, List<StatusItem> items, int index, boolean isSavedSection,
                         Collection<String> saved, String repostPackage, boolean canSave, StatusSource source) {
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
                .putExtra(EXTRA_SOURCE, source != null ? source.ordinal() : -1)
                .putStringArrayListExtra(EXTRA_SAVED, new ArrayList<>(saved));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private StatusRepository repo;
    private Notice notice;
    private Prefs prefs;

    private final List<StatusItem> items = new ArrayList<>();
    private final Set<String> savedNames = new HashSet<>();
    private boolean canSave, isSavedSection;
    private String repostPackage;
    /** WhatsApp folder the items come from, or null for saved items. */
    private StatusSource source;
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
    private View prevBtn, nextBtn, videoControls;
    private android.widget.ImageButton playPause;
    private android.widget.SeekBar seek;
    private TextView positionText, durationText;
    private boolean userSeeking;
    /** The VideoView's player, captured when prepared — needed for exact seeking, speed and volume. */
    private MediaPlayer player;
    private boolean seekPending, resumeAfterSeek, muted;
    private static final float[] SPEEDS = {1f, 1.5f, 2f, 0.5f};
    private int speedIndex = 0;
    private long lastLiveSeek;
    private int lastTickBucket = -1;
    private ScrubFrames scrub;
    private View scrubPreview, skipBack, skipForward;
    private ImageView scrubFrame;
    private View media;
    private ImageView poster;
    private int navToken = 0;
    /** The spinner only appears if loading takes a moment, so quick loads don't flicker. */
    private final Runnable showSpinner = new Runnable() {
        @Override
        public void run() {
            progress.setVisibility(View.VISIBLE);
            progress.setAlpha(0f);
            progress.animate().alpha(1f).setDuration(150).start();
        }
    };
    private TextView scrubTime, speedText;
    private android.widget.ImageButton muteBtn;
    /** Moves the seek bar along while a video plays. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (video != null && video.getVisibility() == View.VISIBLE) {
                int pos = video.getCurrentPosition();
                if (!userSeeking && !seekPending) seek.setProgress(pos);
                if (!userSeeking && !seekPending) positionText.setText(MainActivity.durationLabel(pos));
            }
            main.postDelayed(this, 250);
        }
    };

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        repo = new StatusRepository(this);
        // Messages sit just above whatever is showing at the bottom: video controls, actions, or nothing.
        notice = new Notice(this, new Notice.Offset() {
            @Override
            public int bottomPx() {
                int nav = Notice.navOverlap(ViewerActivity.this);
                if (videoControls != null && videoControls.getVisibility() == View.VISIBLE) return nav + dp(12 + 80 + 8 + 52 + 10);
                if (bottomBar != null && bottomBar.getVisibility() == View.VISIBLE) return nav + dp(12 + 80 + 10);
                return nav + dp(24);
            }
        });
        prefs = new Prefs(this);
        muted = prefs.startMuted(); // Settings → Start videos muted
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
        int src = in.getIntExtra(EXTRA_SOURCE, -1);
        source = src >= 0 && src < StatusSource.values().length ? StatusSource.values()[src] : null;
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
        prevBtn = findViewById(R.id.prev);
        nextBtn = findViewById(R.id.next);
        videoControls = findViewById(R.id.video_controls);
        playPause = (android.widget.ImageButton) findViewById(R.id.play_pause);
        seek = (android.widget.SeekBar) findViewById(R.id.seek);
        positionText = (TextView) findViewById(R.id.position);
        durationText = (TextView) findViewById(R.id.duration_total);
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setThumbTintList(ColorStateList.valueOf(accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onSwipePrevious();
            }
        });
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onSwipeNext();
            }
        });
        View.OnClickListener toggle = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        };
        playPause.setOnClickListener(toggle);
        playState.setOnClickListener(toggle);
        scrub = new ScrubFrames(this);
        media = findViewById(R.id.media);
        poster = (ImageView) findViewById(R.id.poster);
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) revealVideo();
                return false;
            }
        });
        scrubPreview = findViewById(R.id.scrub_preview);
        scrubFrame = (ImageView) findViewById(R.id.scrub_frame);
        scrubTime = (TextView) findViewById(R.id.scrub_time);
        skipBack = findViewById(R.id.skip_back);
        skipForward = findViewById(R.id.skip_forward);
        speedText = (TextView) findViewById(R.id.speed);
        muteBtn = (android.widget.ImageButton) findViewById(R.id.mute);
        muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        speedText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                speedIndex = (speedIndex + 1) % SPEEDS.length;
                updateSpeedLabel();
                applySpeed();
            }
        });
        muteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                muted = !muted;
                applyVolume();
            }
        });
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int ms, boolean fromUser) {
                if (!fromUser) return;
                positionText.setText(MainActivity.durationLabel(ms));
                showScrubPreview(ms);
                // Live scrubbing: move the video itself (fast keyframe seek, throttled)
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastLiveSeek > 120) {
                    lastLiveSeek = now;
                    seekTo(ms, MediaPlayer.SEEK_CLOSEST_SYNC);
                }
                // A light tick every 5 seconds of video
                int bucket = ms / 5000;
                if (bucket != lastTickBucket) {
                    if (lastTickBucket >= 0) s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    lastTickBucket = bucket;
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
                userSeeking = true;
                lastTickBucket = s.getProgress() / 5000;
                if (video.isPlaying()) video.pause(); // hold still while scrubbing
                scrubPreview.animate().cancel();
                scrubPreview.setAlpha(0f);
                scrubPreview.setScaleX(0.85f);
                scrubPreview.setScaleY(0.85f);
                scrubPreview.setVisibility(View.VISIBLE);
                scrubPreview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start();
                showScrubPreview(s.getProgress());
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
                userSeeking = false;
                scrubPreview.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(120)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                scrubPreview.setVisibility(View.GONE);
                            }
                        }).start();
                // Exact seek to where the finger lifted, then play from there.
                resumeAfterSeek = true;
                setPlayingUi(true);
                seekTo(s.getProgress(), MediaPlayer.SEEK_CLOSEST);
            }
        });

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
            public void run() { confirmDelete(items.get(index)); }
        });
        styleAction(actDelete, false, false);
        ((TextView) actDelete.findViewById(R.id.action_label)).setTextColor(0xFFFFB4AB);
        // On tablets / landscape, keep the action capsule a comfortable width, centred.
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        if (widthDp > 560) {
            android.widget.FrameLayout.LayoutParams blp = (android.widget.FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            blp.width = dp(460);
            bottomBar.setLayoutParams(blp);
            android.widget.FrameLayout.LayoutParams vlp = (android.widget.FrameLayout.LayoutParams) videoControls.getLayoutParams();
            vlp.width = dp(460);
            videoControls.setLayoutParams(vlp);
        }

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                player = mp;
                mp.setLooping(true);
                mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                    @Override
                    public void onSeekComplete(MediaPlayer m) {
                        if (userSeeking) return; // live scrubbing; the final seek comes on release
                        seekPending = false;
                        if (resumeAfterSeek) {
                            resumeAfterSeek = false;
                            video.start();
                            applySpeed();
                            setPlayingUi(true);
                        }
                    }
                });
                applyVolume();
                hideSpinner();
                // Fallback in case a device never reports "rendering started".
                final int forToken = loadToken;
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (forToken == loadToken) revealVideo();
                    }
                }, 400);
                int length = video.getDuration();
                seek.setMax(Math.max(0, length));
                seek.setProgress(0);
                durationText.setText(MainActivity.durationLabel(length));
                if (prefs.autoplay()) {
                    video.start();
                    applySpeed();
                    setPlayingUi(true);
                } else {
                    // Settings → Autoplay off: show the first frame and wait for a tap on play
                    video.seekTo(1);
                    setPlayingUi(false);
                }
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                player = null;
                hideSpinner();
                notice.show("This video can't be played", Notice.ERROR);
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
        bottomBar.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private void styleAction(View v, boolean primary, boolean done) {
        ImageView iv = (ImageView) v.findViewById(R.id.action_icon);
        boolean danger = v == actDelete;
        int bg = done ? 0x26FFFFFF : primary ? accent : danger ? 0x33FF5449 : 0x26FFFFFF;
        int fg = done ? 0xCCFFFFFF : primary ? onAccent : danger ? 0xFFFFB4AB : 0xFFFFFFFF;
        iv.setBackground(Palette.oval(bg));
        iv.setImageTintList(ColorStateList.valueOf(fg));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (seek != null) main.post(ticker);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_INDEX, index);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
        if (scrubPreview != null) scrubPreview.setVisibility(View.GONE);
        userSeeking = false;
        if (video != null && video.isPlaying()) {
            video.pause();
            setPlayingUi(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(ticker);
        main.removeCallbacks(showSpinner);
        if (scrub != null) scrub.release();
        if (video != null) video.stopPlayback();
        io.shutdownNow();
    }

    // ---------------- Display ----------------

    private void show() {
        show(0);
    }

    /**
     * Moves to items[index]. [dir] +1 / -1 slides the current status out and the new one in from
     * that side; 0 shows it in place. Rapid taps simply retarget the running transition.
     */
    private void show(final int dir) {
        if (dir == 0 || media.getWidth() == 0) {
            media.animate().cancel();
            media.setTranslationX(0);
            media.setAlpha(1f);
            render();
            return;
        }
        final int nav = ++navToken;
        freezeVideo();
        media.animate().cancel();
        final float shift = dp(56);
        media.animate().translationX(-dir * shift).alpha(0f).setDuration(110)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (nav != navToken || isDestroyed()) return;
                        render();
                        media.setTranslationX(dir * shift);
                        media.animate().translationX(0).alpha(1f).setDuration(260)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
                    }
                }).start();
        // Update the counter straight away so the tap feels instant.
        counter.setText((index + 1) + " / " + items.size());
    }

    /** Before sliding a video away, cover it with its poster so the video surface doesn't lag or flash. */
    private void freezeVideo() {
        if (video.getVisibility() != View.VISIBLE) return;
        if (video.isPlaying()) video.pause();
        if (poster.getVisibility() != View.VISIBLE) {
            poster.animate().cancel();
            poster.setAlpha(1f);
            poster.setVisibility(View.VISIBLE);
        }
        video.setVisibility(View.INVISIBLE);
    }

    /** Fades the poster away once the video is actually drawing. */
    private void revealVideo() {
        if (poster.getVisibility() != View.VISIBLE || video.getVisibility() != View.VISIBLE) return;
        poster.animate().cancel();
        poster.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                poster.setVisibility(View.GONE);
            }
        }).start();
    }

    private void hideSpinner() {
        main.removeCallbacks(showSpinner);
        progress.animate().cancel();
        progress.setVisibility(View.GONE);
    }

    private void render() {
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
        hideSpinner();
        // Instant placeholder: the grid thumbnail (already in memory), replaced by full quality when ready.
        Bitmap placeholder = ThumbnailLoader.get(this).peek(item, ThumbnailLoader.gridSizePx(this));
        image.setImageBitmap(placeholder);
        prevBtn.setAlpha(index > 0 ? 1f : 0.3f);
        prevBtn.setEnabled(index > 0);
        nextBtn.setAlpha(index < items.size() - 1 ? 1f : 0.3f);
        nextBtn.setEnabled(index < items.size() - 1);
        player = null;
        seekPending = false;
        resumeAfterSeek = false;
        userSeeking = false;
        scrubPreview.setVisibility(View.GONE);
        scrubFrame.setImageDrawable(null);
        scrub.setVideo(item.isVideo() ? item.uri : null);
        seek.setProgress(0);
        seek.setMax(0);
        positionText.setText("0:00");
        durationText.setText("0:00");
        updateChrome();

        main.postDelayed(showSpinner, 350);
        if (item.isVideo()) {
            image.setVisibility(View.GONE);
            poster.animate().cancel();
            poster.setImageBitmap(placeholder);
            poster.setAlpha(1f);
            poster.setVisibility(View.VISIBLE); // covers the video until its first frame
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(item.uri);
        } else {
            video.setVisibility(View.GONE);
            poster.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
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
                            hideSpinner();
                            if (bmp != null) image.setImageBitmap(bmp); // same framing, just sharper
                            else notice.show("This photo can't be opened", Notice.ERROR);
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
        actDelete.setVisibility(isSavedSection ? View.VISIBLE : View.GONE); // delete only for saved items
    }

    @Override
    public void onSwipeNext() {
        if (index < items.size() - 1) {
            index++;
            show(1);
        }
    }

    @Override
    public void onSwipePrevious() {
        if (index > 0) {
            index--;
            show(-1);
        }
    }

    @Override
    public void onTap() {
        chromeVisible = !chromeVisible;
        updateChrome();
    }

    /** Shows or hides the bars, arrows and video controls together, with a quick fade. */
    private void updateChrome() {
        boolean isVideo = items.get(index).isVideo();
        fade(topBar, chromeVisible);
        fade(bottomBar, chromeVisible);
        fade(prevBtn, chromeVisible && items.size() > 1);
        fade(nextBtn, chromeVisible && items.size() > 1);
        fade(videoControls, chromeVisible && isVideo);
    }

    private void fade(final View v, final boolean show) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setVisibility(View.VISIBLE);
            }
            float target = v == prevBtn ? (index > 0 ? 1f : 0.3f) : v == nextBtn ? (index < items.size() - 1 ? 1f : 0.3f) : 1f;
            v.animate().alpha(target).setDuration(160).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
                @Override
                public void run() {
                    v.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    private void togglePlay() {
        if (video.getVisibility() != View.VISIBLE || player == null) return; // not ready yet
        if (video.isPlaying()) {
            video.pause();
            setPlayingUi(false);
        } else {
            video.start();
            applySpeed();
            setPlayingUi(true);
        }
    }

    // ---------------- Player helpers ----------------

    /** Seeks precisely (SEEK_CLOSEST) or quickly (SEEK_CLOSEST_SYNC); falls back to VideoView's seek. */
    private void seekTo(int ms, int mode) {
        seekPending = true;
        try {
            if (player != null) {
                player.seekTo((long) ms, mode);
                return;
            }
        } catch (IllegalStateException ignored) {
        }
        video.seekTo(ms);
        if (!userSeeking) {
            seekPending = false;
            if (resumeAfterSeek) {
                resumeAfterSeek = false;
                video.start();
            }
        }
    }

    private String speedLabel() {
        float sp = SPEEDS[speedIndex];
        return (sp == Math.floor(sp) ? String.valueOf((int) sp) : String.valueOf(sp)) + "x";
    }

    private void updateSpeedLabel() {
        speedText.setText(speedLabel());
        boolean normal = SPEEDS[speedIndex] == 1f;
        speedText.setTextColor(normal ? 0xFFFFFFFF : onAccent);
        speedText.setBackground(normal ? getDrawable(R.drawable.bg_speed_pill) : Palette.oval(accent));
        if (!normal) {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setColor(accent);
            d.setCornerRadius(dp(10));
            speedText.setBackground(d);
        }
    }

    /** Applies the chosen speed — only while playing, since setting it on a paused player starts playback. */
    private void applySpeed() {
        if (player == null || !video.isPlaying()) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(SPEEDS[speedIndex]));
        } catch (Exception ignored) {
        }
    }

    private void applyVolume() {
        muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        muteBtn.setContentDescription(muted ? "Unmute" : "Mute");
        if (player == null) return;
        try {
            player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        } catch (IllegalStateException ignored) {
        }
    }

    /** Positions the frame preview above the seek-bar thumb and loads the exact frame. */
    private void showScrubPreview(int ms) {
        scrubTime.setText(MainActivity.durationLabel(ms) + " / " + MainActivity.durationLabel(seek.getMax()));
        int max = Math.max(1, seek.getMax());
        float track = seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight();
        float thumbX = videoControls.getLeft() + seek.getLeft() + seek.getPaddingLeft() + track * ms / max;
        int w = scrubPreview.getWidth() > 0 ? scrubPreview.getWidth() : dp(104);
        View parent = (View) scrubPreview.getParent();
        float left = Math.max(dp(8), Math.min(thumbX - w / 2f, parent.getWidth() - w - dp(8)));
        scrubPreview.setTranslationX(left - scrubPreview.getLeft());
        scrub.request(ms, dp(220), new ScrubFrames.Callback() {
            @Override
            public void onFrame(long timeMs, Bitmap frame) {
                if (userSeeking) scrubFrame.setImageBitmap(frame);
            }
        });
    }

    @Override
    public void onDoubleTap(boolean rightSide) {
        StatusItem item = items.get(index);
        if (!item.isVideo() || player == null) return;
        int length = Math.max(0, seek.getMax());
        int target = Math.max(0, Math.min(length, video.getCurrentPosition() + (rightSide ? 5000 : -5000)));
        resumeAfterSeek = video.isPlaying();
        seek.setProgress(target);
        positionText.setText(MainActivity.durationLabel(target));
        seekTo(target, MediaPlayer.SEEK_CLOSEST);
        root().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        final View bubble = rightSide ? skipForward : skipBack;
        bubble.animate().cancel();
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(0f);
        bubble.setScaleX(0.6f);
        bubble.setScaleY(0.6f);
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).withEndAction(new Runnable() {
            @Override
            public void run() {
                bubble.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(250).setDuration(250)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                bubble.setVisibility(View.GONE);
                                bubble.animate().setStartDelay(0);
                            }
                        }).start();
            }
        }).start();
    }

    private View root() {
        return findViewById(R.id.root);
    }

    private void setPlayingUi(boolean playing) {
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPause.setContentDescription(playing ? "Pause" : "Play");
        playState.setImageResource(R.drawable.ic_play);
        playState.setVisibility(playing ? View.GONE : View.VISIBLE);
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
                            notice.show("Couldn't save. It may have just expired.", Notice.ERROR);
                        } else {
                            notice.show(item.isVideo() ? "Video saved to gallery" : "Photo saved to gallery", Notice.SUCCESS);
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
                            notice.show("Couldn't get this ready to share", Notice.ERROR);
                            return;
                        }
                        MainActivity.launchShare(ViewerActivity.this, notice, Collections.singletonList(item), result, pkg);
                    }
                });
            }
        });
    }

    /** Saves the video frame currently on screen as a photo. */
    private void grabFrame(final StatusItem item) {
        if (!item.isVideo()) return;
        final long position = video.getCurrentPosition();
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
                        notice.show(error == null ? "This frame was saved as a photo" : "Couldn't grab this frame", error == null ? Notice.SUCCESS : Notice.ERROR);
                    }
                });
            }
        });
    }

    private void confirmDelete(final StatusItem item) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(source != null ? "Delete this status?" : "Delete this saved item?")
                .setMessage(source != null
                        ? "It'll be removed from this phone. A copy you've saved stays in your gallery."
                        : "It'll be removed from your gallery.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (source != null) deleteStatus(item);
                        else delete(item);
                    }
                })
                .show()
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(0xFFFFB4AB);
    }

    private void deleteStatus(final StatusItem item) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                boolean regrant = false;
                String err = null;
                try {
                    repo.deleteStatuses(Collections.singletonList(item));
                } catch (SecurityException e) {
                    regrant = true;
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : "unknown error";
                }
                final boolean needsRegrant = regrant;
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (needsRegrant) askToRegrant();
                        else if (error != null) notice.show("Couldn't delete. Please try again.", Notice.ERROR);
                        else removeCurrent();
                    }
                });
            }
        });
    }

    /** Access granted before v1.3 is read-only; re-picking the folder adds permission to delete. */
    private void askToRegrant() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Allow deleting")
                .setMessage("To delete statuses, Status Vault needs to be allowed to change the " + source.label
                        + " folder. The next screen opens it \u2014 tap \u201cUse this folder\u201d, then \u201cAllow\u201d.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, source.initialPickerUri());
                        try {
                            startActivityForResult(pick, REQ_REGRANT);
                        } catch (android.content.ActivityNotFoundException e) {
                            notice.show("Your phone doesn't have a folder picker", Notice.ERROR);
                        }
                    }
                })
                .show();
    }

    private void delete(final StatusItem item) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                IntentSender sender = null;
                String err = null;
                prefs.addSkipAutoSave(Collections.singletonList(item.name));
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
                            notice.show("Couldn't delete. Please try again.", Notice.ERROR);
                        } else if (confirm != null) {
                            try {
                                startIntentSenderForResult(confirm, REQ_DELETE, null, 0, 0, 0);
                            } catch (IntentSender.SendIntentException e) {
                                notice.show("Couldn't delete. Please try again.", Notice.ERROR);
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
        if (requestCode == REQ_REGRANT && resultCode == RESULT_OK && data != null && data.getData() != null && source != null) {
            if (!repo.isStatusFolder(data.getData())) {
                notice.show("That's not the status folder", Notice.ERROR);
                return;
            }
            try {
                repo.grantAccess(source, data.getData());
                deleteStatus(items.get(index)); // try again now that we can
            } catch (SecurityException e) {
                notice.show("Couldn't get access to that folder", Notice.ERROR);
            }
        }
    }

    private void removeCurrent() {
        notice.show("Deleted", Notice.SUCCESS);
        items.remove(index);
        if (items.isEmpty()) {
            finish();
            return;
        }
        index = Math.min(index, items.size() - 1);
        show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }


}
