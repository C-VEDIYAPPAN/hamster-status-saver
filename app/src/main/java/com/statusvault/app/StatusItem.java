package com.statusvault.app;

import android.net.Uri;

public final class StatusItem {
    public final Uri uri;
    public final String name;
    public final String mimeType;
    /** Epoch millis. */
    public final long lastModified;

    public StatusItem(Uri uri, String name, String mimeType, long lastModified) {
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        this.lastModified = lastModified;
    }

    public boolean isVideo() {
        return mimeType.startsWith("video/");
    }
}
