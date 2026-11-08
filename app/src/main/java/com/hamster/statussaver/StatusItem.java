package com.hamster.statussaver;

import android.net.Uri;

public final class StatusItem {
    public final Uri uri;
    public final String name;
    public final String mimeType;
    /** Epoch millis. */
    public final long lastModified;
    /** Bytes, or 0 if unknown. */
    public final long size;

    public StatusItem(Uri uri, String name, String mimeType, long lastModified, long size) {
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        this.lastModified = lastModified;
        this.size = size;
    }

    public boolean isVideo() {
        return mimeType.startsWith("video/");
    }

    /** WhatsApp keeps a status 24h. Returns millis left (estimated from download time), or -1 if unknown. */
    public long millisLeft(long now) {
        if (lastModified <= 0) return -1;
        return Math.max(0, lastModified + 24L * 3600_000L - now);
    }
}
