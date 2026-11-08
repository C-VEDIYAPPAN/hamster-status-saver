package com.statusvault.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads statuses through the Storage Access Framework (the only way on Android 11+)
 * and saves copies into the shared Pictures / Movies folders through MediaStore.
 * All methods except the access helpers do I/O — call them off the main thread.
 */
public final class StatusRepository {

    public static final String SAVE_FOLDER = "StatusVault";

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;

    public StatusRepository(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.prefs = this.context.getSharedPreferences("status_vault", Context.MODE_PRIVATE);
    }

    // ---------------- Access ----------------

    @SuppressWarnings("deprecation")
    public boolean isInstalled(StatusSource source) {
        try {
            context.getPackageManager().getPackageInfo(source.packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The folder the user granted for this source, if the grant is still valid. */
    public Uri accessUri(StatusSource source) {
        String stored = prefs.getString(source.prefKey(), null);
        if (stored == null) return null;
        Uri uri = Uri.parse(stored);
        for (UriPermission p : resolver.getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isReadPermission()) return uri;
        }
        return null;
    }

    /** True if the picked folder really is a .Statuses folder. */
    public boolean isStatusFolder(Uri treeUri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(treeUri);
            while (id.endsWith("/")) id = id.substring(0, id.length() - 1);
            return id.endsWith(".Statuses");
        } catch (Exception e) {
            return false;
        }
    }

    public void grantAccess(StatusSource source, Uri treeUri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        prefs.edit().putString(source.prefKey(), treeUri.toString()).apply();
    }

    // ---------------- Reading ----------------

    public List<StatusItem> loadStatuses(StatusSource source) {
        List<StatusItem> items = new ArrayList<>();
        Uri tree = accessUri(source);
        if (tree == null) return items;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        // One cursor for the whole folder — far faster than DocumentFile.listFiles().
        try (Cursor c = resolver.query(children, projection, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    if (docId == null || name == null || name.startsWith(".")) continue; // skips .nomedia
                    String mime = resolveMime(c.getString(2), name);
                    if (mime == null) continue;
                    items.add(new StatusItem(
                            DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                            name, mime, c.getLong(3)));
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // Folder moved or permission revoked — treat as empty.
        }
        sortNewestFirst(items);
        return items;
    }

    /** Everything previously saved by this app. */
    @SuppressWarnings("deprecation")
    public List<StatusItem> loadSaved() {
        List<StatusItem> items = new ArrayList<>();
        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        String selection = (modern ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA) + " LIKE ?";
        String[] args = {"%/" + SAVE_FOLDER + "/%"};
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
        };
        Uri[] collections = {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        };
        for (Uri collection : collections) {
            try (Cursor c = resolver.query(collection, projection, selection, args, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (name == null || mime == null) continue;
                    items.add(new StatusItem(
                            ContentUris.withAppendedId(collection, c.getLong(0)),
                            name, mime, c.getLong(3) * 1000L));
                }
            } catch (SecurityException e) {
                // Android 9 and below, before the storage permission is granted.
            }
        }
        sortNewestFirst(items);
        return items;
    }

    // ---------------- Saving & sharing ----------------

    @SuppressWarnings("deprecation")
    public void save(StatusItem item) throws IOException {
        String baseDir = item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = item.isVideo()
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, item.name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir + "/" + SAVE_FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri target = resolver.insert(collection, values);
            if (target == null) throw new IOException("Couldn't create the file in your gallery");
            try {
                try (InputStream in = open(item.uri); OutputStream out = resolver.openOutputStream(target)) {
                    if (out == null) throw new IOException("Couldn't write to your gallery");
                    copy(in, out);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(target, values, null, null);
            } catch (IOException | RuntimeException e) {
                resolver.delete(target, null, null);
                throw e;
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(baseDir), SAVE_FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Couldn't create the " + SAVE_FOLDER + " folder");
            File file = new File(dir, item.name);
            try (InputStream in = open(item.uri); OutputStream out = new FileOutputStream(file)) {
                copy(in, out);
            }
            scanAndWait(file, item.mimeType);
        }
    }

    /**
     * A URI other apps can read. Gallery copies are shared as-is; statuses are copied into
     * the cache first because the folder grant belongs to this app only.
     */
    public Uri shareableUri(StatusItem item) throws IOException {
        if (MediaStore.AUTHORITY.equals(item.uri.getAuthority())) return item.uri;
        File dir = ShareProvider.shareDir(context);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete(); // keep only the latest share
        File file = new File(dir, item.name);
        try (InputStream in = open(item.uri); OutputStream out = new FileOutputStream(file)) {
            copy(in, out);
        }
        return ShareProvider.uriFor(file);
    }

    // ---------------- Helpers ----------------

    private InputStream open(Uri uri) throws IOException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IOException("Couldn't read the status");
        return in;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private void scanAndWait(File file, String mime) {
        final CountDownLatch done = new CountDownLatch(1);
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{mime},
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        done.countDown();
                    }
                });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String resolveMime(String reported, String name) {
        if (reported != null && (reported.startsWith("image/") || reported.startsWith("video/"))) return reported;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (guessed == null) return null;
        return guessed.startsWith("image/") || guessed.startsWith("video/") ? guessed : null;
    }

    private static void sortNewestFirst(List<StatusItem> items) {
        Collections.sort(items, new Comparator<StatusItem>() {
            @Override
            public int compare(StatusItem a, StatusItem b) {
                return Long.compare(b.lastModified, a.lastModified);
            }
        });
    }
}
