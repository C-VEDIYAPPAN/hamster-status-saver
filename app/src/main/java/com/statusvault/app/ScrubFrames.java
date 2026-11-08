package com.statusvault.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decodes small preview frames for the seek-bar bubble. One background thread, one open
 * retriever per video, and only the latest request is decoded so dragging stays smooth.
 */
final class ScrubFrames {

    interface Callback {
        void onFrame(long timeMs, Bitmap frame);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong latest = new AtomicLong();
    /** Frames in 250 ms buckets, so scrubbing back and forth is instant. */
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(12 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private MediaMetadataRetriever retriever;
    private Uri openUri;

    ScrubFrames(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Switch to a new video (or null). Clears cached frames. */
    void setVideo(final Uri uri) {
        cache.evictAll();
        latest.incrementAndGet();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                closeRetriever();
                if (uri == null) return;
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(context, uri);
                    retriever = r;
                    openUri = uri;
                } catch (RuntimeException ignored) {
                    retriever = null;
                }
            }
        });
    }

    void request(final long timeMs, final int maxPx, final Callback cb) {
        final long bucket = timeMs / 250;
        Bitmap hit = cache.get(bucket);
        if (hit != null) {
            cb.onFrame(timeMs, hit);
            return;
        }
        final long ticket = latest.incrementAndGet();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (ticket != latest.get() || retriever == null) return; // a newer request is waiting
                Bitmap frame = null;
                try {
                    long us = bucket * 250_000L;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        frame = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, maxPx, maxPx);
                    } else {
                        Bitmap full = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (full != null) {
                            float s = maxPx / (float) Math.max(full.getWidth(), full.getHeight());
                            frame = s < 1f ? Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * s),
                                    Math.round(full.getHeight() * s), true) : full;
                        }
                    }
                } catch (RuntimeException ignored) {
                }
                if (frame == null) return;
                cache.put(bucket, frame);
                final Bitmap result = frame;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ticket == latest.get()) cb.onFrame(timeMs, result);
                    }
                });
            }
        });
    }

    void release() {
        latest.incrementAndGet();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                closeRetriever();
            }
        });
        worker.shutdown();
    }

    private void closeRetriever() {
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        retriever = null;
        openUri = null;
    }
}
