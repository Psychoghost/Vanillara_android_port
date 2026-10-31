package com.vanillara.redalert;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Imports user-supplied Red Alert data into the app's private storage and
 * answers questions about what is installed.
 *
 * The native engine runs with its working directory set to
 * files/redalert-root and expects:
 *
 *   assets/redalert/allies/INSTALL/REDALERT.INI
 *   assets/redalert/allies/.../*.MIX
 *   assets/redalert/soviet/.../*.MIX
 *
 * Nothing is bundled in the APK. The importer is the only way data gets in.
 */
public final class GameDataStore {

    public static final String ROOT_DIR = "redalert-root";
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
     * Prefer app-specific external storage so the folders are visible over USB
     * and to file managers. Fall back to internal storage when external
     * storage is unavailable.
     */
    private static File storageBase(Context context) {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            if (!external.isDirectory() && !external.mkdirs()) {
                return context.getFilesDir();
            }
            if (external.canWrite()) {
                return external;
            }
        }
        return context.getFilesDir();
    }

    public static File root(Context context) {
        return new File(storageBase(context), ROOT_DIR);
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

        writeIfMissing(new File(root, "PUT_GAME_FILES_HERE.txt"),
            "VANILLARA GAME DATA\n"
                + "==================\n\n"
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
