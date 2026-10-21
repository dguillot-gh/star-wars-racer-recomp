package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class SWE1RacerActivity extends SDLActivity {
    private static final String TAG = "SWE1Racer";
    private static final int REQUEST_ROM = 4201;
    private static final String ROM_RELATIVE_PATH = "swe1racer/roms/selected.z64";

    // Implemented in src/main.cpp.
    static native void nativeSetTouchPad(int buttons, float x, float y);

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mLayout != null) {
            mLayout.addView(new TouchPadView(this), new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
        File rom = new File(getFilesDir(), ROM_RELATIVE_PATH);
        if (!rom.isFile()) {
            new Handler(Looper.getMainLooper()).postDelayed(this::requestRom, 700);
        }
    }

    private void requestRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_ROM);
        } catch (Exception e) {
            Log.e(TAG, "Could not open Android's document picker", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ROM || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            Log.e(TAG, "Document picker returned no ROM URI");
            return;
        }

        File rom = new File(getFilesDir(), ROM_RELATIVE_PATH);
        File parent = rom.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            Log.e(TAG, "Could not create private ROM directory");
            return;
        }

        File temporary = new File(parent, "selected.z64.partial");
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(temporary)) {
            if (input == null) {
                throw new IllegalStateException("The selected document could not be opened");
            }
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            if (rom.exists() && !rom.delete()) {
                throw new IllegalStateException("Could not replace the selected ROM");
            }
            if (!temporary.renameTo(rom)) {
                throw new IllegalStateException("Could not finish copying the selected ROM");
            }
            Log.i(TAG, "Copied user-selected ROM into app-private storage");
        } catch (Exception e) {
            temporary.delete();
            Log.e(TAG, "Failed to import selected ROM", e);
        }
    }
}
