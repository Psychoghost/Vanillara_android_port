package com.vanillara.redalert;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Imports user-supplied Red Alert data into storage the app controls and
 * answers questions about what is installed.
 *
 * On Android 12+ the data lives in internal shared storage, at
 * /sdcard/Vanillara/redalert-root, so a file manager can see it. It never uses
 * a removable SD card. If the user does not grant file access, the app falls
 * back to its private internal directory and the in-app import buttons.
 *
 * The native engine runs with its working directory set to
 * redalert-root and expects:
 *
 *   assets/redalert/allies/INSTALL/REDALERT.INI
 *   assets/redalert/allies/.../*.MIX
 *   assets/redalert/soviet/.../*.MIX
 *
 * Nothing is bundled in the APK. The importer is the only way data gets in.
 */
public final class GameDataStore {

    public static final String ROOT_DIR = "redalert-root";
    /** Public folder name inside internal shared storage (/sdcard). */
    public static final String PUBLIC_DIR = "Vanillara";
    /** Tells the native engine which redalert-root to use. */
    private static final String POINTER_FILE = "vanillara-root.txt";
    private static final String ASSETS_DIR = "assets";
    private static final String GAME_ASSETS_DIR = "redalert";

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

    private GameDataStore() {
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
     * True when the app can write directly to internal shared storage. Android
     * 11+ requires "All files access"; Android 6-10 uses the storage runtime
     * permission.
     */
    public static boolean hasSharedStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * The public data folder, or null when the app does not have permission to
     * write to internal shared storage.
     */
    private static File sharedBase(Context context) {
        if (!hasSharedStorageAccess(context)) {
            return null;
        }
        File base = new File(sharedStorageRoot(), PUBLIC_DIR);
        if (!base.isDirectory() && !base.mkdirs()) {
            return null;
        }
        return base.canWrite() ? base : null;
    }

    /**
     * Internal shared storage when permitted; otherwise the app-private
     * internal directory. The fallback is never external/SD-card storage.
     */
    private static File storageBase(Context context) {
        File shared = sharedBase(context);
        if (shared != null) {
            return shared;
        }
        File internal = context.getFilesDir();
        if (internal != null && (internal.isDirectory() || internal.mkdirs())) {
            return internal;
        }
        return context.getCacheDir();
    }

    /** True when the drop folders are in visible internal storage. */
    public static boolean isUsingSharedStorage(Context context) {
        return sharedBase(context) != null;
    }

    public static File root(Context context) {
        return new File(storageBase(context), ROOT_DIR);
    }

    /**
     * Records the active data root so the native engine does not have to guess
     * between shared and private storage.
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
                + "This folder is on internal storage (/sdcard/Vanillara), not a\n"
                + "removable SD card. This app does not include Red Alert game files.\n"
                + "Copy your own legally obtained Allied and Soviet disc/install\n"
                + "contents into:\n\n"
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

    public static GameDataInspector.Status status(Context context, Side side) {
        return GameDataInspector.inspect(sideDir(context, side));
    }

    public static boolean isPlayable(Context context) {
        return status(context, Side.ALLIES).isPlayable()
            || status(context, Side.SOVIET).isPlayable();
    }

    /**
     * Creates the INI files the engine expects and reports whether at least one
     * side has the main game archive. Called right before the SDL activity
     * starts, and again from the setup screen for status.
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
            GameDataInspector.Status status = GameDataInspector.inspect(dir);
            if (status.hasMix || side == Side.ALLIES) {
                try {
                    GameDataInspector.ensureConfig(dir);
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

    public static void clear(Context context) {
        GameDataInspector.deleteTree(root(context));

        // Remove data left by the old app-specific external location and by the
        // private fallback, so "Clear" really starts from an empty state.
        File internalRoot = new File(context.getFilesDir(), ROOT_DIR);
        if (!internalRoot.equals(root(context))) {
            GameDataInspector.deleteTree(internalRoot);
        }
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles != null) {
            File externalRoot = new File(externalFiles, ROOT_DIR);
            if (!externalRoot.equals(root(context))) {
                GameDataInspector.deleteTree(externalRoot);
            }
        }
    }

    public static ImportResult importTree(Context context, Uri treeUri, Side side,
                                          ProgressListener listener) throws IOException {
        if (treeUri == null) {
            throw new ImportException("No folder was selected.");
        }
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.isDirectory()) {
            throw new ImportException("The selected item is not a readable folder.");
        }

        DocumentFile source = resolveSource(tree, side);
        File sideDir = sideDir(context, side);
        File target = sideDir;
        String sourceName = source.getName();
        if (sourceName != null && sourceName.equalsIgnoreCase(GameDataInspector.INSTALL_DIR)) {
            target = new File(sideDir, GameDataInspector.INSTALL_DIR);
        }

        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Unable to create " + target.getAbsolutePath());
        }

        Counter counter = new Counter();
        copyDirectory(context.getContentResolver(), source, target, counter, listener);

        boolean configCreated = GameDataInspector.ensureConfig(sideDir);
        GameDataInspector.Status status = GameDataInspector.inspect(sideDir);
        if (!status.hasMainMix) {
            throw new ImportException(
                "No " + GameDataInspector.MAIN_MIX + " was found in the selected folder.\n\n"
                    + "Choose the root of the " + side.displayName
                    + " disc (the folder that contains INSTALL and the .MIX files), "
                    + "not a single subfolder. Disc images are not imported.");
        }
        return new ImportResult(status, counter.files, counter.bytes, configCreated);
    }

    private static DocumentFile resolveSource(DocumentFile tree, Side side) throws ImportException {
        DocumentFile matching = findChild(tree, side.folder);
        if (matching != null && matching.isDirectory()) {
            return matching;
        }

        DocumentFile allies = findChild(tree, "allies");
        DocumentFile soviet = findChild(tree, "soviet");
        if (allies != null || soviet != null) {
            Side other = side == Side.ALLIES ? Side.SOVIET : Side.ALLIES;
            throw new ImportException(
                "This folder contains the " + other.displayName + " data. "
                    + "Use the \"" + other.displayName + "\" import button, "
                    + "or select the folder that contains both allies/ and soviet/.");
        }
        return tree;
    }

    private static DocumentFile findChild(DocumentFile parent, String name) {
        DocumentFile[] children = parent.listFiles();
        if (children == null) {
            return null;
        }
        for (DocumentFile child : children) {
            String childName = child.getName();
            if (childName != null && childName.equalsIgnoreCase(name)) {
                return child;
            }
        }
        return null;
    }

    private static void copyDirectory(ContentResolver resolver, DocumentFile source, File target,
                                      Counter counter, ProgressListener listener) throws IOException {
        DocumentFile[] children = source.listFiles();
        if (children == null) {
            return;
        }
        for (DocumentFile child : children) {
            String rawName = child.getName();
            if (rawName == null) {
                continue;
            }
            String name = GameDataInspector.normalizeSegment(rawName);
            if (child.isDirectory()) {
                File directory = new File(target, name);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Unable to create " + directory.getAbsolutePath());
                }
                copyDirectory(resolver, child, directory, counter, listener);
            } else if (child.isFile() && !GameDataInspector.isBlockedFile(rawName)) {
                copyFile(resolver, child, new File(target, name), counter, listener);
            }
        }
    }

    private static void copyFile(ContentResolver resolver, DocumentFile source, File target,
                                 Counter counter, ProgressListener listener) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent.getAbsolutePath());
        }

        File temporary = new File(parent, target.getName() + ".part");
        long nextNotification = 4L * 1024L * 1024L;
        try (InputStream input = resolver.openInputStream(source.getUri())) {
            if (input == null) {
                throw new IOException("Unable to open " + target.getName());
            }
            try (OutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[128 * 1024];
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
