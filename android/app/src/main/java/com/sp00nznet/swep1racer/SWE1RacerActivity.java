package com.sp00nznet.swep1racer;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.Locale;

/**
 * The game. Started from LauncherActivity (the start screen picks and checks the ROM), in its own process ":game"
 * (AndroidManifest.xml): SDL cannot start twice in one process, so leaving the game ends that process and the
 * launcher is back for the next Play. The pad's ≡ button opens RacerMenu; its settings are applied again here at
 * every start.
 */
public final class SWE1RacerActivity extends SDLActivity {
    private static final String ROM_RELATIVE_PATH = "swe1racer/roms/selected.z64";

    // Implemented in src/main.cpp and src/rt64_render_context.cpp.
    static native void nativeSetTouchPad(int buttons, float x, float y);
    static native void nativeSetVideo(int aspect, int filter, int resolution);
    static native float nativeGameFps();
    static native float nativeDisplayListRate();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TouchPadView pad;
    private RacerMenu menu;
    private TextView fpsView;
    private final Runnable fpsTick = new Runnable() {
        @Override
        public void run() {
            if (fpsView != null) {
                fpsView.setText(String.format(Locale.US, "%.0f fps  ·  %.0f lists/s",
                        nativeGameFps(), nativeDisplayListRate()));
                handler.postDelayed(this, 500);
            }
        }
    };

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
        prefs = getSharedPreferences(RacerMenu.PREFS, MODE_PRIVATE);
        if (mLayout != null) {
            pad = new TouchPadView(this);
            mLayout.addView(pad, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            menu = new RacerMenu(this, pad, prefs);
            pad.setOnMenu(menu::open);
            pad.setPadVisible(prefs.getBoolean(RacerMenu.PAD, true));
            pad.setBoostEnabled(prefs.getBoolean(RacerMenu.BOOST, true));
            pad.setOpacity(RacerMenu.OPACITIES[clamp(prefs.getInt(RacerMenu.OPACITY, RacerMenu.DEF_OPACITY),
                    RacerMenu.OPACITIES.length)] / 100f);
            pad.setSizeScale(RacerMenu.SIZES[clamp(prefs.getInt(RacerMenu.SIZE, RacerMenu.DEF_SIZE),
                    RacerMenu.SIZES.length)]);
            setFpsShown(prefs.getBoolean(RacerMenu.FPS, false));
        }
        applyVideo();
    }

    private static int clamp(int v, int count) { return Math.max(0, Math.min(count - 1, v)); }

    /** The menu's picture settings to RT64 (applied on its thread at the next frame). */
    void applyVideo() {
        try {
            nativeSetVideo(prefs.getInt(RacerMenu.ASPECT, RacerMenu.DEF_ASPECT),
                    prefs.getInt(RacerMenu.FILTER, RacerMenu.DEF_FILTER),
                    prefs.getInt(RacerMenu.RESOLUTION, RacerMenu.DEF_RESOLUTION));
        } catch (UnsatisfiedLinkError ignored) {
            // native library not loaded (no ROM path)
        }
    }

    /** The FPS counter: a small label in the top-left corner, updated twice a second. */
    void setFpsShown(boolean on) {
        if (on && fpsView == null && mLayout != null) {
            fpsView = new TextView(this);
            fpsView.setTextColor(Color.WHITE);
            fpsView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            fpsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            fpsView.setBackgroundColor(0xA0000000);
            final int p = Math.round(6 * getResources().getDisplayMetrics().density);
            fpsView.setPadding(p, p / 2, p, p / 2);
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            lp.setMargins(p * 2, p * 2, 0, 0);
            mLayout.addView(fpsView, lp);
            handler.post(fpsTick);
        } else if (!on && fpsView != null) {
            handler.removeCallbacks(fpsTick);
            ((ViewGroup) fpsView.getParent()).removeView(fpsView);
            fpsView = null;
        }
    }

    // Full screen: status and navigation bars hidden, back with a swipe from the edge.
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // SDL takes the Back key in its own key handling before onBackPressed runs, so catch it here first.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                onBackPressed();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (menu != null && menu.isOpen()) {
            menu.close();       // Back closes the menu first
        } else {
            askQuit();
        }
    }

    void askQuit() {
        new AlertDialog.Builder(this)
                .setMessage("Quit Star Wars Episode I: Racer?\nRace progress the game has saved is kept.")
                .setPositiveButton("Quit", (d, w) -> finish())
                .setNegativeButton("Keep playing", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(fpsTick);
        super.onDestroy();
        // SDL and the recompiled game cannot be restarted in this process; end it so the next Play starts clean.
        // Not inside onDestroy itself: Android has not recorded the finish yet, sees the process die and starts the
        // game again. A moment later the activity is gone and the launcher (the task's root) comes back.
        new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 400);
    }
}
