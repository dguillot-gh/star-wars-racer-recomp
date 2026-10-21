package com.sp00nznet.swep1racer;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.KeyEvent;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * The game. Started from LauncherActivity (the start screen picks and checks the ROM), in its own process ":game"
 * (AndroidManifest.xml): SDL cannot start twice in one process, so leaving the game ends that process and the
 * launcher is back for the next Play.
 */
public final class SWE1RacerActivity extends SDLActivity {
    private static final String ROM_RELATIVE_PATH = "swe1racer/roms/selected.z64";

    // Implemented in src/main.cpp.
    static native void nativeSetTouchPad(int buttons, float x, float y);

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // No ROM yet (started some other way than Play): go to the start screen instead of a game that waits.
        if (!new File(getFilesDir(), ROM_RELATIVE_PATH).isFile()) {
            super.onCreate(savedInstanceState);
            startActivity(new Intent(this, LauncherActivity.class));
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        if (mLayout != null) {
            mLayout.addView(new TouchPadView(this), new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // SDL takes the Back key in its own key handling before onBackPressed runs, so catch it here first.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                askQuit();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        askQuit();
    }

    private void askQuit() {
        new AlertDialog.Builder(this)
                .setMessage("Quit Star Wars Episode I: Racer?\nRace progress the game has saved is kept.")
                .setPositiveButton("Quit", (d, w) -> finish())
                .setNegativeButton("Keep playing", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // SDL and the recompiled game cannot be restarted in this process; end it so the next Play starts clean.
        // Not inside onDestroy itself: Android has not recorded the finish yet, sees the process die and starts the
        // game again. A moment later the activity is gone and the launcher (the task's root) comes back.
        new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 400);
    }
}
