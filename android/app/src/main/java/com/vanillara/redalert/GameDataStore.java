package com.vanillara.redalert;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Imports user-supplied Red Alert data into storage the app controls and
 * answers questions about what is installed.
 *
 * The default location is Android's internal shared storage,
 * /sdcard/Vanillara/redalert-root, so a file manager can see it. The player can
 * switch to a removable SD card from the setup screen. If file access is not
 * granted, the app falls back to its private internal directory and the in-app
 * import buttons.
 *
 * The native engine runs with its working directory set to redalert-root and
 * expects:
 *
 *   assets/redalert/allies/INSTALL/REDALERT.INI
 *   assets/redalert/allies/.../*.MIX
 *   assets/redalert/soviet/.../*.MIX
 *
 * Nothing is bundled in the APK. The importer is the only way data gets in.
 */
public final class GameDataStore {

    public static final String ROOT_DIR = "redalert-root";
    /** Folder name used inside the selected volume. */
    public static final String PUBLIC_DIR = "Vanillara";
    /** Tells the native engine which redalert-root to use. */
    private static final String POINTER_FILE = "vanillara-root.txt";
    private static final String PREFS = "vanillara-storage";
    private static final String PREF_VOLUME = "volume-root";
    private static final String ASSETS_DIR = "assets";
    private static final String GAME_ASSETS_DIR = "redalert";

    private static final String[] DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    };

    /** One storage volume the player can choose. */
    public static final class Volume {
        public final File root;
        public final String label;
        public final boolean removable;

        Volume(File root, String label, boolean removable) {
            this.root = root;
            this.label = label;
            this.removable = removable;
        }
    }

    public enum Side {
        ALLIES("allies", "Allied"),
        SOVIET("soviet", "Soviet");

        public final String folder;
        public final String displayName;

        Side(String folder, String displayName) {
            this.folder = folder;
            this.displayName = displayName;
        }
    }

    public static final class ImportResult {
        public final GameDataInspector.Status status;
        public final int importedFiles;
        public final long importedBytes;
        public final boolean configCreated;

        ImportResult(GameDataInspector.Status status, int importedFiles, long importedBytes,
                     boolean configCreated) {
            this.status = status;
            this.importedFiles = importedFiles;
            this.importedBytes = importedBytes;
            this.configCreated = configCreated;
        }
    }

    public static final class ImportException extends IOException {
        ImportException(String message) {
            super(message);
        }
    }

    public interface ProgressListener {
        void onProgress(String currentFile, int files, long bytes);
    }

    private static final class Counter {
        int files;
        long bytes;
    }

    /** A single SAF document, read in one cursor pass per directory. */
    private static final class SafNode {
        final String documentId;
        final String name;
        final boolean directory;
        final Uri uri;

        SafNode(String documentId, String name, boolean directory, Uri uri) {
            this.documentId = documentId;
            this.name = name;
            this.directory = directory;
            this.uri = uri;
        }
    }

    /**
     * Status cache. Scanning a multi-gigabyte tree on every onResume made the
     * setup screen feel slow, so results are kept until an import, clear,
     * storage change, or explicit Rescan invalidates them.
     */
    private static final Map<String, GameDataInspector.Status> STATUS_CACHE = new HashMap<>();

    private GameDataStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Internal shared storage (/sdcard, also called "Internal storage" by
     * Android and by file managers). This is deliberately not
     * getExternalFilesDir(): that path can resolve to a removable SD card and
     * is hidden inside Android/data on Android 11+.
     */
    public static File sharedStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    /**
     * Every volume the player can choose: internal storage first, then any
     * mounted removable SD card.
     */
    public static List<Volume> availableVolumes(Context context) {
        List<Volume> volumes = new ArrayList<>();
        addVolume(volumes, Environment.getExternalStorageDirectory(), "Internal storage", false);

        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager != null) {
            for (StorageVolume volume : manager.getStorageVolumes()) {
                if (!volume.isRemovable()) {
                    continue;
                }
                File directory = volume.getDirectory();
                if (directory == null) {
                    continue;
                }
                addVolume(volumes, directory, volume.getDescription(context), true);
            }
        }
        return volumes;
    }

    private static void addVolume(List<Volume> volumes, File root, String label, boolean removable) {
        String path = root.getAbsolutePath();
        for (Volume volume : volumes) {
            if (volume.root.getAbsolutePath().equals(path)) {
                return;
            }
        }
        volumes.add(new Volume(root, label, removable));
    }

    /** The selected volume, or internal storage when the SD card is gone. */
    public static Volume currentVolume(Context context) {
        List<Volume> volumes = availableVolumes(context);
        String saved = prefs(context).getString(PREF_VOLUME, null);
        if (saved != null) {
            for (Volume volume : volumes) {
                if (volume.root.getAbsolutePath().equals(saved)) {
                    return volume;
                }
            }
        }
        return volumes.get(0);
    }

    public static boolean hasRemovableVolume(Context context) {
        for (Volume volume : availableVolumes(context)) {
            if (volume.removable) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the app can write directly to shared storage. Android 12+
     * requires "All files access".
     */
    public static boolean hasSharedStorageAccess(Context context) {
        return Environment.isExternalStorageManager();
    }

    private static File prepareBase(File base) {
        if (!base.isDirectory() && !base.mkdirs()) {
            return null;
        }
        return base.canWrite() ? base : null;
    }

    /**
     * The visible data folder on the selected volume, or null when the app
     * does not have permission to write to shared storage.
     */
    private static File publicBase(Context context) {
        if (!hasSharedStorageAccess(context)) {
            return null;
        }
        Volume selected = currentVolume(context);
        File base = prepareBase(new File(selected.root, PUBLIC_DIR));
        if (base != null) {
            return base;
        }
        // The selected volume may be read-only or removed; try the others
        // rather than silently dropping to private storage.
        for (Volume volume : availableVolumes(context)) {
            if (volume.root.getAbsolutePath().equals(selected.root.getAbsolutePath())) {
                continue;
            }
            base = prepareBase(new File(volume.root, PUBLIC_DIR));
            if (base != null) {
                return base;
            }
        }
        return null;
    }

    /**
     * Visible shared storage when permitted; otherwise the app-private
     * internal directory.
     */
    private static File storageBase(Context context) {
        File shared = publicBase(context);
        if (shared != null) {
            return shared;
        }
        File internal = context.getFilesDir();
        if (internal != null && (internal.isDirectory() || internal.mkdirs())) {
            return internal;
        }
        return context.getCacheDir();
    }

    /** True when the drop folders are in visible shared storage. */
    public static boolean isUsingSharedStorage(Context context) {
        return publicBase(context) != null;
    }

    /** True when the active drop folders are on a removable SD card. */
    public static boolean isUsingRemovableStorage(Context context) {
        return publicBase(context) != null && currentVolume(context).removable;
    }

    public static File root(Context context) {
        return new File(storageBase(context), ROOT_DIR);
    }

    /**
     * Records the active data root so the native engine does not have to guess
     * between storage volumes.
     */
    private static void writePointer(Context context) throws IOException {
        File pointer = new File(context.getFilesDir(), POINTER_FILE);
        try (OutputStream output = new FileOutputStream(pointer)) {
            output.write(root(context).getAbsolutePath().getBytes("UTF-8"));
        }
    }

    /**
     * Creates the drop folders and short README files on first launch so the
     * player knows exactly where to put the disc contents.
     */
    public static void ensureFolders(Context context) throws IOException {
        File root = root(context);
        File allies = sideDir(context, Side.ALLIES);
        File soviet = sideDir(context, Side.SOVIET);
        File alliesInstall = new File(allies, GameDataInspector.INSTALL_DIR);
        File sovietInstall = new File(soviet, GameDataInspector.INSTALL_DIR);

        File[] directories = {root, allies, alliesInstall, soviet, sovietInstall};
        for (File directory : directories) {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Unable to create " + directory.getAbsolutePath());
            }
        }

        writePointer(context);

        writeIfMissing(new File(root, "PUT_GAME_FILES_HERE.txt"),
            "VANILLARA GAME DATA\n"
                + "==================\n\n"
                + "This folder is on " + currentVolume(context).label + ":\n"
                + "  " + root.getAbsolutePath() + "\n\n"
                + "This app does not include Red Alert game files. Copy your own\n"
                + "legally obtained Allied and Soviet disc/install contents into:\n\n"
                + "  " + allies.getAbsolutePath() + "\n"
                + "  " + soviet.getAbsolutePath() + "\n\n"
                + "Each folder should contain INSTALL\\REDALERT.INI and the .MIX files\n"
                + "(REDALERT.MIX, CONQUER.MIX, GENERAL.MIX, and so on).\n\n"
                + "Mount or extract ISO/BIN/CUE images first; disc images and archives\n"
                + "are not read by the game. After copying, open Vanillara and tap\n"
                + "\"Rescan\".\n");
        writeIfMissing(new File(allies, "PUT_ALLIED_FILES_HERE.txt"),
            "ALLIED RED ALERT DATA\n"
                + "=====================\n\n"
                + "Copy the contents of your Allied disc or full installation here.\n"
                + "Expected: INSTALL\\REDALERT.INI and the .MIX files from the disc.\n"
                + "Do not put .ISO, .BIN, or .CUE images here; mount or extract them first.\n");
        writeIfMissing(new File(soviet, "PUT_SOVIET_FILES_HERE.txt"),
            "SOVIET RED ALERT DATA\n"
                + "=====================\n\n"
                + "Copy the contents of your Soviet disc or full installation here.\n"
                + "Expected: INSTALL\\REDALERT.INI and the .MIX files from the disc.\n"
                + "Do not put .ISO, .BIN, or .CUE images here; mount or extract them first.\n");
    }

    private static void writeIfMissing(File file, String text) throws IOException {
        if (file.isFile()) {
            return;
        }
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes("UTF-8"));
        }
    }

    public static File gameAssetsDir(Context context) {
        return new File(new File(root(context), ASSETS_DIR), GAME_ASSETS_DIR);
    }

    public static File sideDir(Context context, Side side) {
        return new File(gameAssetsDir(context), side.folder);
    }

    public static synchronized GameDataInspector.Status status(Context context, Side side) {
        File dir = sideDir(context, side);
        String key = dir.getAbsolutePath();
        GameDataInspector.Status cached = STATUS_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        GameDataInspector.Status status = GameDataInspector.inspect(dir);
        STATUS_CACHE.put(key, status);
        return status;
    }

    public static synchronized void rememberStatus(Context context, Side side,
                                                   GameDataInspector.Status status) {
        STATUS_CACHE.put(sideDir(context, side).getAbsolutePath(), status);
    }

    public static synchronized void invalidateStatus() {
        STATUS_CACHE.clear();
    }

    public static boolean isPlayable(Context context) {
        return status(context, Side.ALLIES).isPlayable()
            || status(context, Side.SOVIET).isPlayable();
    }

    public static boolean hasGameData(Context context) {
        return status(context, Side.ALLIES).hasMix || status(context, Side.SOVIET).hasMix;
    }

    /**
     * Creates the INI files the engine expects and reports whether at least one
     * side has the main game archive. Called right before the SDL activity
     * starts.
     */
    public static boolean prepareForLaunch(Context context) {
        try {
            ensureFolders(context);
        } catch (IOException ignored) {
            // A read-only or full volume is reported by the setup screen.
        }
        boolean playable = false;
        for (Side side : Side.values()) {
            File dir = sideDir(context, side);
            GameDataInspector.Status status = status(context, side);
            if (status.hasMix || side == Side.ALLIES) {
                try {
                    if (GameDataInspector.ensureConfig(dir)) {
                        invalidateStatus();
                    }
                } catch (IOException ignored) {
                    // The status screen reports the missing data; the engine will
                    // also fail cleanly if its INI cannot be created.
                }
            }
            if (status.isPlayable()) {
                playable = true;
            }
        }
        return playable;
    }

    /** Selects a volume without moving existing files. */
    public static void setVolume(Context context, File volumeRoot) {
        prefs(context).edit().putString(PREF_VOLUME, volumeRoot.getAbsolutePath()).apply();
        invalidateStatus();
    }

    /**
     * Moves the whole redalert-root tree to another volume. The destination is
     * copied first and the old tree is deleted only after the copy succeeds, so
     * a failure leaves the current installation intact.
     */
    public static void relocate(Context context, File newVolumeRoot,
                                ProgressListener listener) throws IOException {
        File oldRoot = root(context);
        File newBase = new File(newVolumeRoot, PUBLIC_DIR);
        File newRoot = new File(newBase, ROOT_DIR);
        if (oldRoot.getAbsolutePath().equals(newRoot.getAbsolutePath())) {
            setVolume(context, newVolumeRoot);
            return;
        }
        if (!newBase.isDirectory() && !newBase.mkdirs()) {
            throw new IOException("Unable to create " + newBase.getAbsolutePath());
        }
        if (!newBase.canWrite()) {
            throw new IOException("The selected location is not writable.");
        }

        String previous = prefs(context).getString(PREF_VOLUME, null);
        boolean hadData = oldRoot.isDirectory();
        try {
            if (hadData) {
                Counter counter = new Counter();
                copyLocalTree(oldRoot, newRoot, counter, listener);
            }
            setVolume(context, newVolumeRoot);
            ensureFolders(context);
            if (hadData) {
                GameDataInspector.deleteTree(oldRoot);
            }
            invalidateStatus();
        } catch (IOException error) {
            if (previous == null) {
                prefs(context).edit().remove(PREF_VOLUME).apply();
            } else {
                prefs(context).edit().putString(PREF_VOLUME, previous).apply();
            }
            GameDataInspector.deleteTree(newRoot);
            invalidateStatus();
            throw error;
        }
    }

    public static void clear(Context context) {
        for (Volume volume : availableVolumes(context)) {
            GameDataInspector.deleteTree(
                new File(new File(volume.root, PUBLIC_DIR), ROOT_DIR));
        }
        GameDataInspector.deleteTree(new File(context.getFilesDir(), ROOT_DIR));
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles != null) {
            GameDataInspector.deleteTree(new File(externalFiles, ROOT_DIR));
        }
        invalidateStatus();
    }

    public static ImportResult importTree(Context context, Uri treeUri, Side side,
                                          ProgressListener listener) throws IOException {
        if (treeUri == null) {
            throw new ImportException("No folder was selected.");
        }

        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException error) {
            throw new ImportException("The selected item is not a readable folder.");
        }
        if (rootId == null) {
            throw new ImportException("The selected item is not a readable folder.");
        }

        ContentResolver resolver = context.getContentResolver();
        String rootName = queryDisplayName(resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId));
        SafNode source = resolveSource(resolver, treeUri, rootId, rootName, side);

        File sideDir = sideDir(context, side);
        File target = sideDir;
        if (source.name != null
            && source.name.equalsIgnoreCase(GameDataInspector.INSTALL_DIR)) {
            target = new File(sideDir, GameDataInspector.INSTALL_DIR);
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Unable to create " + target.getAbsolutePath());
        }

        Counter counter = new Counter();
        copyDirectory(resolver, treeUri, source, target, counter, listener);

        boolean configCreated = GameDataInspector.ensureConfig(sideDir);
        GameDataInspector.Status status = GameDataInspector.inspect(sideDir);
        rememberStatus(context, side, status);
        if (!status.hasMainMix) {
            throw new ImportException(
                "No " + GameDataInspector.MAIN_MIX + " was found in the selected folder.\n\n"
                    + "Choose the root of the " + side.displayName
                    + " disc (the folder that contains INSTALL and the .MIX files), "
                    + "not a single subfolder. Disc images are not imported.");
        }
        return new ImportResult(status, counter.files, counter.bytes, configCreated);
    }

    private static String queryDisplayName(ContentResolver resolver, Uri documentUri) {
        try (Cursor cursor = resolver.query(documentUri,
                new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // The name is only used to detect an INSTALL folder.
        }
        return null;
    }

    private static SafNode resolveSource(ContentResolver resolver, Uri treeUri, String rootId,
                                         String rootName, Side side) throws IOException {
        List<SafNode> children = listChildren(resolver, treeUri, rootId);
        SafNode matching = find(children, side.folder);
        if (matching != null && matching.directory) {
            return matching;
        }

        boolean hasAllies = find(children, "allies") != null;
        boolean hasSoviet = find(children, "soviet") != null;
        if (hasAllies || hasSoviet) {
            Side other = side == Side.ALLIES ? Side.SOVIET : Side.ALLIES;
            throw new ImportException(
                "This folder contains the " + other.displayName + " data. "
                    + "Use the \"" + other.displayName + "\" import button, "
                    + "or select the folder that contains both allies/ and soviet/.");
        }
        return new SafNode(rootId, rootName, true,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId));
    }

    private static SafNode find(List<SafNode> nodes, String name) {
        for (SafNode node : nodes) {
            if (node.name != null && node.name.equalsIgnoreCase(name)) {
                return node;
            }
        }
        return null;
    }

    /**
     * Lists one directory with a single cursor query. DocumentFile.listFiles()
     * performs several IPC calls per entry, which is the main reason imports
     * were slow on large disc trees.
     */
    private static List<SafNode> listChildren(ContentResolver resolver, Uri treeUri,
                                              String parentId) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        List<SafNode> nodes = new ArrayList<>();
        try (Cursor cursor = resolver.query(childrenUri, DOCUMENT_PROJECTION,
                null, null, null)) {
            if (cursor == null) {
                throw new IOException("The selected folder could not be listed.");
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String id = idIndex >= 0 ? cursor.getString(idIndex) : null;
                String name = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                String mime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : null;
                if (id == null || name == null) {
                    continue;
                }
                nodes.add(new SafNode(id, name,
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id)));
            }
        } catch (RuntimeException error) {
            throw new IOException("The selected folder could not be listed.", error);
        }
        return nodes;
    }

    private static void copyDirectory(ContentResolver resolver, Uri treeUri, SafNode source,
                                      File target, Counter counter, ProgressListener listener)
        throws IOException {
        for (SafNode child : listChildren(resolver, treeUri, source.documentId)) {
            String name = GameDataInspector.normalizeSegment(child.name);
            if (child.directory) {
                File directory = new File(target, name);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Unable to create " + directory.getAbsolutePath());
                }
                copyDirectory(resolver, treeUri, child, directory, counter, listener);
            } else if (!GameDataInspector.isBlockedFile(child.name)) {
                copyFile(resolver, child, new File(target, name), counter, listener);
            }
        }
    }

    private static void copyFile(ContentResolver resolver, SafNode source, File target,
                                 Counter counter, ProgressListener listener) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent.getAbsolutePath());
        }

        File temporary = new File(parent, target.getName() + ".part");
        long nextNotification = 4L * 1024L * 1024L;
        try (InputStream input = resolver.openInputStream(source.uri)) {
            if (input == null) {
                throw new IOException("Unable to open " + target.getName());
            }
            try (OutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    counter.bytes += read;
                    if (listener != null && counter.bytes >= nextNotification) {
                        listener.onProgress(target.getName(), counter.files, counter.bytes);
                        nextNotification = counter.bytes + 4L * 1024L * 1024L;
                    }
                }
            }
        }

        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Unable to replace " + target.getAbsolutePath());
        }
        if (!temporary.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Unable to finish writing " + target.getAbsolutePath());
        }

        counter.files++;
        if (listener != null) {
            listener.onProgress(target.getName(), counter.files, counter.bytes);
        }
    }

    private static void copyLocalTree(File source, File target, Counter counter,
                                      ProgressListener listener) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Unable to create " + target.getAbsolutePath());
        }
        File[] children = source.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                copyLocalTree(child, new File(target, child.getName()), counter, listener);
            } else if (child.isFile()) {
                copyLocalFile(child, new File(target, child.getName()), counter, listener);
            }
        }
    }

    private static void copyLocalFile(File source, File target, Counter counter,
                                      ProgressListener listener) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent.getAbsolutePath());
        }
        File temporary = new File(parent, target.getName() + ".part");
        long nextNotification = 4L * 1024L * 1024L;
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                counter.bytes += read;
                if (listener != null && counter.bytes >= nextNotification) {
                    listener.onProgress(target.getName(), counter.files, counter.bytes);
                    nextNotification = counter.bytes + 4L * 1024L * 1024L;
                }
            }
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Unable to replace " + target.getAbsolutePath());
        }
        if (!temporary.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Unable to finish writing " + target.getAbsolutePath());
        }
        counter.files++;
        if (listener != null) {
            listener.onProgress(target.getName(), counter.files, counter.bytes);
        }
    }

    /** Free space on the volume that contains the given directory. */
    public static long freeBytes(File directory) {
        try {
            return new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException error) {
            return -1;
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }
}
