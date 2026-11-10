package com.rookmender.kxit;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.google.android.material.color.DynamicColors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Android lifecycle bridge. All gameplay and rendering are handled by libGDX. */
public final class MainActivity extends AndroidApplication {
    private boolean fatalDialogShown;
    private File packageDirectory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        try {
            prepareRuntimeFolders();
            AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
            config.useAccelerometer = false;
            config.useCompass = false;
            config.useWakelock = true;
            config.useImmersiveMode = true;
            config.numSamples = 0;
            config.useGL30 = false;
            initialize(new KXitGame(
                    new File(packageDirectory, "shaders"),
                    new File(packageDirectory, "modules"),
                    getFilesDir(),
                    this::showFatalStartupError
            ), config);
        } catch (Throwable error) {
            showFatalStartupError("KXit could not start:\n\n" + android.util.Log.getStackTraceString(error));
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    private void prepareRuntimeFolders() throws Exception {
        File appFiles = getExternalFilesDir(null);
        if (appFiles == null || appFiles.getParentFile() == null) {
            throw new IllegalStateException("Android app-specific external storage is unavailable.");
        }
        packageDirectory = appFiles.getParentFile();
        File shaders = new File(packageDirectory, "shaders");
        File modules = new File(packageDirectory, "modules");
        File models = new File(packageDirectory, "models");
        ensureDirectory(shaders);
        ensureDirectory(modules);
        ensureDirectory(models);
        copyAssetIfMissing("shaders/terrain.vert", new File(shaders, "terrain.vert"));
        copyAssetIfMissing("shaders/terrain.frag", new File(shaders, "terrain.frag"));
        File modelReadme = new File(models, "README.txt");
        if (!modelReadme.exists()) {
            writeText(modelReadme, "KXit model directory\n\nOBJ loading API is available for future entity/model systems.\nThe default voxel world does not scan or load OBJ files at startup.\n");
        }
        File moduleReadme = new File(modules, "README.txt");
        if (!moduleReadme.exists()) {
            writeText(moduleReadme,
                    "KXit runtime modules\n\n"
                    + "Place trusted Android DEX modules here. Each module needs:\n"
                    + "  example.dex\n  example.kxm\n\n"
                    + "The .kxm file contains the public class name on its first non-empty line.\n"
                    + "Modules implement com.rookmender.kxit.module.KXitModule.\n"
                    + "Code is copied to private app storage and made read-only before loading.\n");
        }
    }

    private static void ensureDirectory(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create runtime directory: " + directory);
        }
    }

    private void copyAssetIfMissing(String assetPath, File destination) throws Exception {
        if (destination.isFile() && destination.length() > 0) return;
        ensureDirectory(destination.getParentFile());
        try (InputStream input = getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!destination.isFile() || destination.length() == 0) {
            throw new IllegalStateException("Bundled default shader could not be copied: " + assetPath);
        }
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private void showFatalStartupError(String message) {
        runOnUiThread(() -> {
            if (fatalDialogShown || isFinishing()) return;
            fatalDialogShown = true;
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("KXit startup error")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("COPY ERROR", (d, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("KXit startup error", message));
                        Toast.makeText(this, "Error copied", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .setNegativeButton("EXIT", (d, which) -> finish())
                    .create();
            dialog.show();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }
}
