package com.vanillara.redalert;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.libsdl.app.SDLActivity;

/**
 * Hosts the native Red Alert engine. The game data has already been imported
 * into app-private storage by SetupActivity.
 */
public class RedAlertActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL2", "main"};
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!GameDataStore.prepareForLaunch(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        configureImmersiveMode();
        super.onCreate(savedInstanceState);
        registerBackCallback();
        configureImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        configureImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureImmersiveMode();
        }
    }

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerOnBackInvokedCallback();
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void registerOnBackInvokedCallback() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    sendEscapePulse();
                }
            }
        );
    }

    private void configureImmersiveMode() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }

        View decorView = window.getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    @Override
    public void onBackPressed() {
        sendEscapePulse();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            KeyEvent escapeEvent = new KeyEvent(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                KeyEvent.KEYCODE_ESCAPE,
                event.getRepeatCount(),
                event.getMetaState(),
                event.getDeviceId(),
                event.getScanCode(),
                event.getFlags(),
                event.getSource()
            );
            super.dispatchKeyEvent(escapeEvent);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void sendEscapePulse() {
        long now = SystemClock.uptimeMillis();
        dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
        dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));
    }
}
