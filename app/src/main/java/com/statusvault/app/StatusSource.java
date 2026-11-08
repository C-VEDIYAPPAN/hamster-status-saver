package com.statusvault.app;

import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

/** The apps we read statuses from, and where each keeps them. */
public enum StatusSource {
    WHATSAPP("WhatsApp", "com.whatsapp",
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "WhatsApp/Media/.Statuses"),
    BUSINESS("WhatsApp Business", "com.whatsapp.w4b",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
            "WhatsApp Business/Media/.Statuses");

    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    public final String label;
    public final String packageName;
    private final String modernPath;
    private final String legacyPath;

    StatusSource(String label, String packageName, String modernPath, String legacyPath) {
        this.label = label;
        this.packageName = packageName;
        this.modernPath = modernPath;
        this.legacyPath = legacyPath;
    }

    String prefKey() {
        return "tree_uri_" + name();
    }

    /** Opens the system folder picker directly inside .Statuses. */
    Uri initialPickerUri() {
        String path = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? modernPath : legacyPath;
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:" + path);
    }
}
