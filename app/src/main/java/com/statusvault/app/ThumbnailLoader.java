package com.statusvault.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads downsampled thumbnails (images and video frames) off the main thread, with a memory cache. */
public final class ThumbnailLoader {

    private static ThumbnailLoader instance;

    public static synchronized ThumbnailLoader get(Context context) {
        if (instance == null) instance = new ThumbnailLoader(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ContentResolver resolver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache;
    /** Video durations by URI, in ms. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> durations = new java.util.concurrent.ConcurrentHashMap<>();

    /** Duration of a video already decoded by this loader, or -1. */
    public long durationOf(StatusItem item) {
        Long d = durations.get(item.uri.toString());
        return d != null ? d : -1;
    }

    public interface DurationListener {
        void onDuration(long ms);
    }

    private DurationListener pendingListenerFor(ImageView v) {
        Object o = v.getTag(R.id.save);
        return o instanceof DurationListener ? (DurationListener) o : null;
    }

    /** Like {@link #load}, and reports a video's duration once known. */
    public void load(StatusItem item, ImageView view, int sizePx, DurationListener onDuration) {
        view.setTag(R.id.save, onDuration);
        long known = durationOf(item);
        if (known >= 0 && onDuration != null) onDuration.onDuration(known);
        load(item, view, sizePx);
    }

    private ThumbnailLoader(Context context) {
        this.context = context;
        this.resolver = context.getContentResolver();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    /** Shows a thumbnail of [item] in [view], about [sizePx] on its longest side. */
    public void load(final StatusItem item, final ImageView view, final int sizePx) {
        final String key = item.uri.toString() + "@" + sizePx;
        view.setTag(R.id.thumb, key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decode(item, sizePx);
                if (bmp != null) cache.put(key, bmp);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        DurationListener dl = pendingListenerFor(view);
                        long d = durationOf(item);
                        if (dl != null && d >= 0 && key.equals(view.getTag(R.id.thumb))) dl.onDuration(d);
                        if (key.equals(view.getTag(R.id.thumb)) && bmp != null) {
                            view.setAlpha(0f);
                            view.setImageBitmap(bmp);
                            view.animate().alpha(1f).setDuration(180).start(); // soft fade-in, no pop
                        }
                    }
                });
            }
        });
    }

    /** Decodes [item] on the calling thread, downsampled to about [maxPx]. Null on failure. */
    public Bitmap decode(StatusItem item, int maxPx) {
        try {
            return item.isVideo() ? decodeVideoFrame(item.uri, maxPx) : decodeImage(item.uri, maxPx);
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap decodeImage(Uri uri, final int maxPx) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder handles EXIF rotation, HEIF and animated WebP's first frame.
            ImageDecoder.Source src = ImageDecoder.createSource(resolver, uri);
            return ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                @Override
                public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source source) {
                    Size s = info.getSize();
                    int longest = Math.max(s.getWidth(), s.getHeight());
                    if (longest > maxPx) {
                        float scale = maxPx / (float) longest;
                        decoder.setTargetSize(Math.max(1, Math.round(s.getWidth() * scale)),
                                Math.max(1, Math.round(s.getHeight() * scale)));
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                }
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx);
        try (InputStream in = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private Bitmap decodeVideoFrame(Uri uri, int maxPx) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            try {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durations.put(uri.toString(), Long.parseLong(d));
            } catch (Exception ignored) {
            }
            Bitmap frame;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                frame = r.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx);
            } else {
                frame = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) frame = r.getFrameAtTime();
            return frame;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static int sampleSize(int w, int h, int maxPx) {
        int sample = 1;
        while (w / (sample * 2) >= maxPx || h / (sample * 2) >= maxPx) sample *= 2;
        return sample;
    }
}
