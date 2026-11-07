package com.vanillara.redalert;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First screen: imports the player's own Red Alert files and launches the SDL
 * activity only after the data is present.
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_ALLIES = 1001;
    private static final int REQUEST_SOVIET = 1002;
    private static final int REQUEST_STORAGE = 1003;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView alliedStatus;
    private TextView sovietStatus;
    private TextView warning;
    private TextView busyLabel;
    private TextView progressText;
    private TextView dataPath;
    private TextView storageNote;
    private LinearLayout progressContainer;
    private Button grantAccess;
    private Button importAllied;
    private Button importSoviet;
    private Button copyPath;
    private Button rescan;
    private Button play;
    private Button clear;
    private Button help;
    private Button about;

    private boolean importing;
    private boolean canPlay;
    private long lastProgressAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        alliedStatus = findViewById(R.id.allied_status);
        sovietStatus = findViewById(R.id.soviet_status);
        warning = findViewById(R.id.warning);
        busyLabel = findViewById(R.id.busy_label);
        progressText = findViewById(R.id.progress_text);
        progressContainer = findViewById(R.id.progress_container);
        dataPath = findViewById(R.id.data_path);
        storageNote = findViewById(R.id.storage_note);
        grantAccess = findViewById(R.id.grant_access);
        importAllied = findViewById(R.id.import_allied);
        importSoviet = findViewById(R.id.import_soviet);
        copyPath = findViewById(R.id.copy_path);
        rescan = findViewById(R.id.rescan);
        play = findViewById(R.id.play);
        clear = findViewById(R.id.clear);
        help = findViewById(R.id.help);
        about = findViewById(R.id.about);

        try {
            GameDataStore.ensureFolders(this);
        } catch (Exception error) {
            // The status card below reports missing/unreadable data.
        }

        importAllied.setOnClickListener(v -> pickFolder(GameDataStore.Side.ALLIES));
        importSoviet.setOnClickListener(v -> pickFolder(GameDataStore.Side.SOVIET));
        copyPath.setOnClickListener(v -> copyDataPath());
        rescan.setOnClickListener(v -> {
            refreshStatus();
            Toast.makeText(this, "Folders rescanned", Toast.LENGTH_SHORT).show();
        });
        play.setOnClickListener(v -> launchGame());
        clear.setOnClickListener(v -> confirmClear());
        help.setOnClickListener(v -> showTextDialog(getString(R.string.import_help_title),
            getString(R.string.import_help_body)));
        about.setOnClickListener(v -> showTextDialog(getString(R.string.about),
            getString(R.string.about_body)));
        grantAccess.setOnClickListener(v -> requestStorageAccess());

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!importing) {
            try {
                GameDataStore.ensureFolders(this);
            } catch (Exception ignored) {
                // Reported by the status card.
            }
            refreshStatus();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 11+ needs "All files access" before the app can create the
     * visible /sdcard/Vanillara folder. Without it the app stays usable, but
     * data lives in private internal storage and must be imported in-app.
     */
    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                refreshStatus();
                return;
            }
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException first) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException second) {
                    showError("File access unavailable",
                        "Open Settings > Apps > Vanillara > Permissions and allow access to "
                            + "files and media, then return here.");
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE);
        } else {
            refreshStatus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        try {
            GameDataStore.ensureFolders(this);
        } catch (Exception ignored) {
            // Reported by the status card.
        }
        refreshStatus();
        if (!granted) {
            Toast.makeText(this,
                "Without file access, use the Import buttons to add game data.",
                Toast.LENGTH_LONG).show();
        }
    }

    private void pickFolder(GameDataStore.Side side) {
        if (importing) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent,
                side == GameDataStore.Side.ALLIES ? REQUEST_ALLIES : REQUEST_SOVIET);
        } catch (ActivityNotFoundException exception) {
            showError("No file picker",
                "This device does not provide a folder picker. Install a file manager that supports "
                    + "Android's Storage Access Framework and try again.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ALLIES && requestCode != REQUEST_SOVIET) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers grant only a transient permission. The
            // copy happens immediately, so that is still enough.
        }

        GameDataStore.Side side = requestCode == REQUEST_ALLIES
            ? GameDataStore.Side.ALLIES
            : GameDataStore.Side.SOVIET;
        startImport(side, uri);
    }

    private void startImport(GameDataStore.Side side, Uri uri) {
        importing = true;
        lastProgressAt = 0;
        setBusy(true, "Importing " + side.displayName + " data…");
        progressText.setText("Scanning the selected folder…");

        executor.execute(() -> {
            try {
                GameDataStore.ImportResult result = GameDataStore.importTree(
                    this, uri, side, (name, files, bytes) -> publishProgress(name, files, bytes));
                mainHandler.post(() -> finishImport(side, result, null));
            } catch (Throwable error) {
                mainHandler.post(() -> finishImport(side, null, error));
            }
        });
    }

    private void publishProgress(String name, int files, long bytes) {
        long now = SystemClock.uptimeMillis();
        if (now - lastProgressAt < 120) {
            return;
        }
        lastProgressAt = now;
        mainHandler.post(() -> {
            if (!importing) {
                return;
            }
            String shortName = name.length() > 44 ? "…" + name.substring(name.length() - 43) : name;
            progressText.setText(String.format(Locale.US, "%d files • %s\n%s",
                files, GameDataStore.formatBytes(bytes), shortName));
        });
    }

    private void finishImport(GameDataStore.Side side, GameDataStore.ImportResult result,
                              Throwable error) {
        importing = false;
        setBusy(false, null);
        refreshStatus();

        if (error != null) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) {
                message = error.toString();
            }
            showError("Import failed", message);
            return;
        }

        String message = String.format(Locale.US, "Imported %d files (%s).",
            result.importedFiles, GameDataStore.formatBytes(result.importedBytes));
        if (result.configCreated) {
            message += "\n\nA REDALERT.INI was created because the selected folder did not contain one.";
        }
        message += "\n\n" + side.displayName + " data is ready.";
        new AlertDialog.Builder(this)
            .setTitle("Import complete")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void setBusy(boolean busy, String label) {
        importing = busy;
        progressContainer.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (label != null) {
            busyLabel.setText(label);
        }
        grantAccess.setEnabled(!busy);
        importAllied.setEnabled(!busy);
        importSoviet.setEnabled(!busy);
        copyPath.setEnabled(!busy);
        rescan.setEnabled(!busy);
        clear.setEnabled(!busy);
        help.setEnabled(!busy);
        about.setEnabled(!busy);
        play.setEnabled(!busy && canPlay);
    }

    private void refreshStatus() {
        boolean shared = GameDataStore.isUsingSharedStorage(this);
        grantAccess.setVisibility(shared ? View.GONE : View.VISIBLE);
        storageNote.setVisibility(shared ? View.GONE : View.VISIBLE);
        if (!shared) {
            storageNote.setText(getString(R.string.storage_private_note,
                new java.io.File(GameDataStore.sharedStorageRoot(),
                    GameDataStore.PUBLIC_DIR).getAbsolutePath()));
        }

        dataPath.setText(GameDataStore.gameAssetsDir(this).getAbsolutePath());

        GameDataInspector.Status allied = GameDataStore.status(this, GameDataStore.Side.ALLIES);
        GameDataInspector.Status soviet = GameDataStore.status(this, GameDataStore.Side.SOVIET);

        alliedStatus.setText(describe(allied));
        sovietStatus.setText(describe(soviet));
        alliedStatus.setTextColor(getColor(allied.isPlayable()
            ? R.color.text_ok : allied.hasMix ? R.color.text_warning : R.color.text_secondary));
        sovietStatus.setTextColor(getColor(soviet.isPlayable()
            ? R.color.text_ok : soviet.hasMix ? R.color.text_warning : R.color.text_secondary));

        canPlay = allied.isPlayable() || soviet.isPlayable();
        play.setEnabled(canPlay && !importing);
        play.setText(canPlay ? R.string.play : R.string.play_needs_data);

        if (!allied.hasMix && !soviet.hasMix) {
            warning.setText("No game data found yet. Copy your Allied and Soviet files into the "
                + "folders above, then tap Rescan.");
        } else if (allied.isPlayable() && soviet.isPlayable()) {
            warning.setText("Allied and Soviet data are ready.");
        } else if (allied.isPlayable()) {
            warning.setText("Allied data is ready. Soviet data is missing or incomplete, so the "
                + "Soviet campaign and some movies may not work.");
        } else if (soviet.isPlayable()) {
            warning.setText("Soviet data is ready. Allied data is missing or incomplete, so the "
                + "Allied campaign and some movies may not work.");
        } else {
            warning.setText("Game files were found, but REDALERT.MIX is missing. Copy the complete "
                + "disc contents into the folders above.");
        }
    }

    private String describe(GameDataInspector.Status status) {
        if (!status.hasMix) {
            return getString(R.string.not_imported);
        }
        StringBuilder text = new StringBuilder();
        text.append(status.fileCount).append(" files • ")
            .append(GameDataStore.formatBytes(status.byteCount));
        if (status.hasMainMix) {
            text.append(" • ").append(getString(R.string.ready));
        } else {
            text.append(" • missing ").append(GameDataInspector.MAIN_MIX);
        }
        if (status.hasCounterstrike && status.hasAftermath) {
            text.append(" • CS & AM");
        } else if (status.hasCounterstrike) {
            text.append(" • Counterstrike");
        } else if (status.hasAftermath) {
            text.append(" • Aftermath");
        }
        if (!status.hasConfig) {
            text.append(" • settings will be generated");
        }
        return text.toString();
    }

    private void copyDataPath() {
        String path = GameDataStore.gameAssetsDir(this).getAbsolutePath();
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Vanillara game data path", path));
            Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchGame() {
        if (importing) {
            return;
        }
        if (!GameDataStore.prepareForLaunch(this)) {
            refreshStatus();
            showError("Game data missing",
                "Import the original Red Alert data before playing. " + GameDataInspector.MAIN_MIX
                    + " was not found in either the Allied or Soviet folder.");
            return;
        }
        startActivity(new Intent(this, RedAlertActivity.class));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("Clear imported data?")
            .setMessage("This removes the imported game files, saves, and options from this app. "
                + "Your original files are not affected.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Clear", (dialog, which) -> {
                GameDataStore.clear(this);
                try {
                    GameDataStore.ensureFolders(this);
                } catch (Exception error) {
                    showError("Unable to recreate folders", error.getMessage());
                }
                refreshStatus();
                Toast.makeText(this, "Imported data removed", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void showTextDialog(String title, String body) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
