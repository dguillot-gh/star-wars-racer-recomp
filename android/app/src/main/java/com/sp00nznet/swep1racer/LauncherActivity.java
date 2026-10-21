package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The start screen (2026-10-21): bring in your own Racer ROM, then play. The ROM is
 * copied into the app's own storage (files/swe1racer/roms/selected.z64, the path src/main.cpp loads); the original is
 * only read. .v64 and .n64 dumps are converted to .z64 byte order while copying, then checked by SHA-1.
 */
public final class LauncherActivity extends Activity {
    private static final int PICK_ROM = 1;
    // The dump the game was recompiled for (SHA-1 of the .z64): Star Wars Episode I: Racer (USA).
    private static final String KNOWN_SHA1 = "3542d5597c8a56ea8f5c63bceae97a24c4c08d58";

    private TextView status;
    private Button play;

    private File rom() { return new File(getFilesDir(), "swe1racer/roms/selected.z64"); }

    private File save() { return new File(getFilesDir(), "swe1racer/saves/swe1racer.bin"); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        final int pad = dp(landscape ? 12 : 24);
        box.setPadding(pad, pad, pad, pad);

        final TextView title = new TextView(this);
        title.setText("Star Wars Episode I: Racer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 26 : 34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        final TextView sub = new TextView(this);
        sub.setText("Nintendo 64 · native recomp");
        sub.setTextColor(0xFF8AB4FF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 11 : 13);
        sub.setGravity(Gravity.CENTER);
        box.addView(sub);

        status = new TextView(this);
        status.setTextColor(0xFFB0B0B8);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 11 : 14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(landscape ? 5 : 12), 0, dp(landscape ? 8 : 20));
        box.addView(status);

        play = button(box, "Play", () -> startActivity(new Intent(this, SWE1RacerActivity.class)));
        button(box, "Choose your game ROM (.z64 / .v64 / .n64)", this::pickRom);

        final TextView hint = new TextView(this);
        hint.setText("Use your own dump of the game (USA). It is copied into the app; the file you pick is not "
                + "changed. Your race progress is kept between sessions. In the game, Back asks before quitting.");
        hint.setTextColor(0xFF707078);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 10 : 12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(landscape ? 8 : 16), 0, 0);
        box.addView(hint, new LinearLayout.LayoutParams(dp(landscape ? 680 : 420), ViewGroup.LayoutParams.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box);
        // The start-screen art (res/drawable/launcher_background.xml, an original drawing) fills the screen behind it.
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101014);
        final ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.launcher_background);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        final boolean haveRom = rom().isFile();
        final StringBuilder s = new StringBuilder();
        s.append(haveRom ? "Game ROM: ready" : "Choose your own Star Wars Episode I: Racer (USA) ROM to start.");
        s.append('\n');
        s.append(save().isFile() ? "Save: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(save().lastModified())) : "Save: none yet (the game makes one when you race)");
        status.setText(s);
        play.setEnabled(haveRom);
        play.setAlpha(haveRom ? 1f : 0.5f);
    }

    private Button button(LinearLayout box, String text, Runnable action) {
        final boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        final Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        if (landscape) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setMinHeight(dp(36));
            b.setMinimumHeight(dp(36));
            b.setPadding(dp(8), 0, dp(8), 0);
        }
        b.setOnClickListener(v -> action.run());
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(landscape ? 300 : 320),
                landscape ? dp(36) : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(landscape ? 3 : 8);
        box.addView(b, lp);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void pickRom() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_ROM);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ROM || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        status.setText("Copying the ROM...");
        // Large copy off the UI thread.
        new Thread(() -> {
            String message;
            try {
                message = importRom(uri);
            } catch (IOException e) {
                message = "That did not work: " + e.getMessage();
            }
            final String m = message;
            runOnUiThread(() -> {
                refresh();
                new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show();
            });
        }).start();
    }

    private String importRom(Uri uri) throws IOException {
        final File dest = rom(), tmp = new File(dest.getPath() + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        dest.getParentFile().mkdirs();
        final String sha1 = copyAsZ64(uri, tmp);
        if (sha1 == null) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return "That file is not a Nintendo 64 ROM (.z64, .v64 or .n64).";
        }
        if (!KNOWN_SHA1.equals(sha1)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return "That ROM is not the supported Star Wars Episode I: Racer (USA) dump.\nSHA-1: " + sha1;
        }
        if (dest.exists() && !dest.delete()) throw new IOException("cannot replace the old ROM");
        if (!tmp.renameTo(dest)) throw new IOException("cannot store the ROM");
        return "Game ROM ready. Press Play.";
    }

    /**
     * Copies a picked N64 ROM to `to` in .z64 (big-endian) byte order and returns the SHA-1 of the result (lowercase
     * hex), or null when the file does not start like an N64 ROM. .v64 swaps every 2 bytes, .n64 reverses every 4.
     */
    private String copyAsZ64(Uri uri, File to) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("cannot open the file");
            final MessageDigest sha = MessageDigest.getInstance("SHA-1");
            final byte[] buf = new byte[1 << 16];
            int order = -1; // 0 = z64, 1 = v64, 2 = n64
            int filled;
            while ((filled = readBlock(in, buf)) > 0) {
                if (order < 0) {
                    if (filled < 4) return null;
                    final int magic = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16) | ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
                    if (magic == 0x80371240) order = 0;
                    else if (magic == 0x37804012) order = 1;
                    else if (magic == 0x40123780) order = 2;
                    else return null;
                }
                if (order == 1) {
                    for (int i = 0; i + 1 < filled; i += 2) { byte t = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = t; }
                } else if (order == 2) {
                    for (int i = 0; i + 3 < filled; i += 4) {
                        byte t0 = buf[i], t1 = buf[i + 1];
                        buf[i] = buf[i + 3]; buf[i + 1] = buf[i + 2]; buf[i + 2] = t1; buf[i + 3] = t0;
                    }
                }
                out.write(buf, 0, filled);
                sha.update(buf, 0, filled);
            }
            if (order < 0) return null;
            final StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Fills `buf` as far as the stream allows (so byte swapping always sees whole 4-byte groups). */
    private static int readBlock(InputStream in, byte[] buf) throws IOException {
        int filled = 0;
        while (filled < buf.length) {
            final int n = in.read(buf, filled, buf.length - filled);
            if (n < 0) break;
            filled += n;
        }
        return filled;
    }
}
