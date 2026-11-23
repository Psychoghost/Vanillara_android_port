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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * redalert-root and searches only these folders (upper-case names):
 *
 *   assets/redalert/allies/        and  assets/redalert/allies/INSTALL/
 *   assets/redalert/soviet/        and  assets/redalert/soviet/INSTALL/
 *
 * The importer scans the selected folder in whatever layout it has, works out
 * what each file is, renames it to upper case and places it where the engine
 * looks: each disc's MAIN.MIX at the side's top level, and REDALERT.MIX, the
 * expansion archives and the other shared .MIX files in INSTALL on both sides.
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

    private static final String REDALERT_MIX = GameDataInspector.MAIN_MIX;
    private static final String DISC_MAIN_MIX = GameDataLayout.MAIN_NAME;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 40000;

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
        /** Plain-text report of what is and is not in place after the import. */
        public final String summary;

        ImportResult(GameDataInspector.Status status, int importedFiles, long importedBytes,
                     boolean configCreated, String summary) {
            this.status = status;
            this.importedFiles = importedFiles;
            this.importedBytes = importedBytes;
            this.configCreated = configCreated;
            this.summary = summary;
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

    private static final class Entry {
        final DocumentFile file;
        final String name;

        Entry(DocumentFile file, String name) {
            this.file = file;
            this.name = name;
        }
    }

    /** What the scan found, before anything is copied. */
    private static final class Plan {
        Entry mainAllies;
        Entry mainSoviet;
        final List<Entry> mainUnknown = new ArrayList<>();
        final Map<String, Entry> shared = new LinkedHashMap<>();
        Entry config;
        final List<Entry> scenarios = new ArrayList<>();
        int expansionMains;
        int duplicates;
        int skipped;
        int seen;

        void add(DocumentFile file, String rawName, String[] folders) {
            GameDataLayout.Decision decision = GameDataLayout.classify(folders, rawName);
            Entry entry = new Entry(file, decision.name);
            switch (decision.kind) {
                case MAIN_ALLIES:
                    if (mainAllies == null) {
                        mainAllies = entry;
                    } else {
                        duplicates++;
                    }
                    break;
                case MAIN_SOVIET:
                    if (mainSoviet == null) {
                        mainSoviet = entry;
                    } else {
                        duplicates++;
                    }
                    break;
                case MAIN_UNKNOWN:
                    mainUnknown.add(entry);
                    break;
                case MAIN_EXPANSION:
                    expansionMains++;
                    break;
                case SHARED_MIX:
                    if (!shared.containsKey(decision.name)) {
                        shared.put(decision.name, entry);
                    } else {
                        duplicates++;
                    }
                    break;
                case CONFIG_INI:
                    if (config == null) {
                        config = entry;
                    } else {
                        duplicates++;
                    }
                    break;
                case SCENARIO_FILE:
                    scenarios.add(entry);
                    break;
                default:
                    skipped++;
                    break;
            }
        }
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
                + "The easiest way to add your own legally obtained files is the Import\n"
                + "buttons in the app: pick the folder that holds your disc contents (or a\n"
                + "folder with allied/ and soviet/ subfolders). The importer sorts and\n"
                + "renames the files for you.\n\n"
                + "If you copy files by hand, the engine only looks here, with UPPER-CASE\n"
                + "names:\n\n"
                + "  " + allies.getAbsolutePath() + "\\MAIN.MIX (Allied disc)\n"
                + "  " + allies.getAbsolutePath() + "\\INSTALL\\REDALERT.MIX\n"
                + "  " + soviet.getAbsolutePath() + "\\MAIN.MIX (Soviet disc)\n"
                + "  " + soviet.getAbsolutePath() + "\\INSTALL\\REDALERT.MIX\n\n"
                + "Put EXPAND.MIX, EXPAND2.MIX and the other shared .MIX files in INSTALL.\n"
                + "Mount or extract ISO/BIN/CUE images first; disc images and archives\n"
                + "are not read by the game. After copying, open Vanillara and tap\n"
                + "\"Rescan\".\n");
        writeIfMissing(new File(allies, "PUT_ALLIED_FILES_HERE.txt"),
            "ALLIED RED ALERT DATA\n"
                + "=====================\n\n"
                + "Needed here: MAIN.MIX from the Allied disc, and INSTALL\\REDALERT.MIX.\n"
                + "The Import button does this for you. Names must be UPPER CASE.\n"
                + "Do not put .ISO, .BIN, or .CUE images here; mount or extract them first.\n");
        writeIfMissing(new File(soviet, "PUT_SOVIET_FILES_HERE.txt"),
            "SOVIET RED ALERT DATA\n"
                + "=====================\n\n"
                + "Needed here: MAIN.MIX from the Soviet disc, and INSTALL\\REDALERT.MIX.\n"
                + "The Import button does this for you. Names must be UPPER CASE.\n"
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

    /** True when the disc's MAIN.MIX is where the engine searches for it. */
    public static boolean hasEngineMain(Context context, Side side) {
        return hasEngineFile(sideDir(context, side), DISC_MAIN_MIX);
    }

    private static boolean hasEngineFile(File sideDir, String name) {
        return new File(sideDir, name).isFile()
            || new File(new File(sideDir, GameDataInspector.INSTALL_DIR), name).isFile();
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

    /**
     * Imports the whole game from the selected folder, whatever its layout.
     * The button that was pressed only matters for a MAIN.MIX whose folder
     * names do not say which disc it came from.
     */
    public static ImportResult importTree(Context context, Uri treeUri, Side side,
                                          ProgressListener listener) throws IOException {
        if (treeUri == null) {
            throw new ImportException("No folder was selected.");
        }
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.isDirectory()) {
            throw new ImportException("The selected item is not a readable folder.");
        }

        Plan plan = new Plan();
        List<String> folders = new ArrayList<>();
        if (tree.getName() != null) {
            folders.add(tree.getName());
        }
        collect(tree, folders, plan, 0);
        assignUnknownMains(plan, side);

        File alliesDir = sideDir(context, Side.ALLIES);
        File sovietDir = sideDir(context, Side.SOVIET);
        boolean haveRedAlert = plan.shared.containsKey(REDALERT_MIX)
            || hasEngineFile(alliesDir, REDALERT_MIX) || hasEngineFile(sovietDir, REDALERT_MIX);
        boolean haveMain = plan.mainAllies != null || plan.mainSoviet != null
            || hasEngineFile(alliesDir, DISC_MAIN_MIX) || hasEngineFile(sovietDir, DISC_MAIN_MIX);
        if (!haveRedAlert || !haveMain) {
            throw new ImportException(missingMessage(plan, haveRedAlert, haveMain));
        }

        File alliesInstall = new File(alliesDir, GameDataInspector.INSTALL_DIR);
        File sovietInstall = new File(sovietDir, GameDataInspector.INSTALL_DIR);
        makeDirectory(alliesInstall);
        makeDirectory(sovietInstall);

        ContentResolver resolver = context.getContentResolver();
        Counter counter = new Counter();

        if (plan.mainAllies != null) {
            copyFile(resolver, plan.mainAllies.file, new File(alliesDir, DISC_MAIN_MIX), counter, listener);
        }
        if (plan.mainSoviet != null) {
            copyFile(resolver, plan.mainSoviet.file, new File(sovietDir, DISC_MAIN_MIX), counter, listener);
        }
        for (Entry entry : plan.shared.values()) {
            File first = new File(alliesInstall, entry.name);
            copyFile(resolver, entry.file, first, counter, listener);
            copyLocal(first, new File(sovietInstall, entry.name));
        }
        if (plan.config != null) {
            File first = new File(alliesInstall, plan.config.name);
            copyFile(resolver, plan.config.file, first, counter, listener);
            copyLocal(first, new File(sovietInstall, plan.config.name));
        }
        File scenarioDir = sideDir(context, side);
        for (Entry entry : plan.scenarios) {
            copyFile(resolver, entry.file, new File(scenarioDir, entry.name), counter, listener);
        }

        boolean configCreated = GameDataInspector.ensureConfig(alliesDir);
        configCreated |= GameDataInspector.ensureConfig(sovietDir);
        writePointer(context);

        GameDataInspector.Status status = GameDataInspector.inspect(sideDir(context, side));
        return new ImportResult(status, counter.files, counter.bytes, configCreated,
            buildSummary(context, plan));
    }

    private static void collect(DocumentFile directory, List<String> folders, Plan plan, int depth)
            throws ImportException {
        if (depth > MAX_DEPTH) {
            return;
        }
        DocumentFile[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (DocumentFile child : children) {
            if (++plan.seen > MAX_ENTRIES) {
                throw new ImportException("That folder holds too many files. "
                    + "Pick the folder that contains the game files.");
            }
            String rawName = child.getName();
            if (rawName == null) {
                continue;
            }
            if (child.isDirectory()) {
                folders.add(rawName);
                collect(child, folders, plan, depth + 1);
                folders.remove(folders.size() - 1);
            } else if (child.isFile()) {
                plan.add(child, rawName, folders.toArray(new String[0]));
            }
        }
    }

    /**
     * A MAIN.MIX with no Allied/Soviet hint goes to the side whose button was
     * pressed. Two unlabeled ones cannot be told apart, so the user is asked
     * to import one disc at a time.
     */
    private static void assignUnknownMains(Plan plan, Side preferred) throws ImportException {
        if (plan.mainUnknown.isEmpty()) {
            return;
        }
        if (plan.mainUnknown.size() > 1) {
            throw new ImportException("Found " + plan.mainUnknown.size()
                + " MAIN.MIX files with no Allied or Soviet folder name, so they cannot be told apart.\n\n"
                + "Import one disc at a time: pick the Allied disc folder with the Allied button, "
                + "then the Soviet disc folder with the Soviet button. Or name the folders allied and soviet.");
        }
        Entry entry = plan.mainUnknown.get(0);
        boolean preferAllies = preferred == Side.ALLIES;
        if (preferAllies && plan.mainAllies == null) {
            plan.mainAllies = entry;
        } else if (!preferAllies && plan.mainSoviet == null) {
            plan.mainSoviet = entry;
        } else if (plan.mainAllies == null) {
            plan.mainAllies = entry;
        } else if (plan.mainSoviet == null) {
            plan.mainSoviet = entry;
        } else {
            plan.duplicates++;
        }
    }

    private static String missingMessage(Plan plan, boolean haveRedAlert, boolean haveMain) {
        StringBuilder text = new StringBuilder("Nothing was imported.\n\n");
        if (!haveRedAlert) {
            text.append("- REDALERT.MIX was not found (retail discs keep it in the INSTALL folder).\n");
        }
        if (!haveMain) {
            text.append("- MAIN.MIX was not found (retail discs keep it in the disc root).\n");
        }
        text.append("\nSelect the folder that holds the disc contents, or a folder that contains "
            + "allied and soviet subfolders with the files. Disc images (ISO/BIN/CUE) and archives "
            + "are not read.\n\nOther .MIX files found: ").append(plan.shared.size());
        return text.toString();
    }

    private static String buildSummary(Context context, Plan plan) {
        File allies = sideDir(context, Side.ALLIES);
        File soviet = sideDir(context, Side.SOVIET);
        GameDataInspector.Status alliesStatus = GameDataInspector.inspect(allies);
        GameDataInspector.Status sovietStatus = GameDataInspector.inspect(soviet);
        boolean redAlert = hasEngineFile(allies, REDALERT_MIX) || hasEngineFile(soviet, REDALERT_MIX);

        StringBuilder text = new StringBuilder();
        text.append("Allied MAIN.MIX: ")
            .append(hasEngineFile(allies, DISC_MAIN_MIX) ? "ready" : "MISSING").append('\n');
        text.append("Soviet MAIN.MIX: ")
            .append(hasEngineFile(soviet, DISC_MAIN_MIX) ? "ready" : "MISSING").append('\n');
        text.append("REDALERT.MIX: ").append(redAlert ? "ready" : "MISSING").append('\n');
        text.append("Counterstrike: ")
            .append(alliesStatus.hasCounterstrike || sovietStatus.hasCounterstrike ? "found" : "not found")
            .append('\n');
        text.append("Aftermath: ")
            .append(alliesStatus.hasAftermath || sovietStatus.hasAftermath ? "found" : "not found")
            .append('\n');
        if (plan.expansionMains > 0) {
            text.append("\nSkipped ").append(plan.expansionMains)
                .append(" expansion-disc MAIN.MIX file(s). The game detects the expansions from "
                    + "EXPAND.MIX and EXPAND2.MIX.");
        }
        if (plan.duplicates > 0) {
            text.append("\nIgnored ").append(plan.duplicates).append(" duplicate file(s).");
        }
        if (plan.skipped > 0) {
            text.append("\nIgnored ").append(plan.skipped).append(" unrelated file(s).");
        }
        return text.toString().trim();
    }

    private static void makeDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create " + directory.getAbsolutePath());
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

    /** Copies a file that is already in app storage, e.g. to the second side's INSTALL. */
    private static void copyLocal(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent.getAbsolutePath());
        }
        File temporary = new File(parent, target.getName() + ".part");
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
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
