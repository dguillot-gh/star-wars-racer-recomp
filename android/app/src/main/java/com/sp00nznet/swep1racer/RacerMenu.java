package com.sp00nznet.swep1racer;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * The game's menu (the pad's ≡ button, 2026-10-21): a scrolling panel over the right part of the screen, the game
 * still visible beside it. Tapping outside it, ✕ or Back closes it.
 *
 * Display: aspect ratio, sharp or smooth picture, resolution, FPS counter. Controls: show the pad, BOOST button,
 * opacity, size. Game: quit. Every choice is saved (SharedPreferences "racer") and applied again at the next start.
 */
final class RacerMenu {
    private static final int ACCENT = 0xFF8AB4FF, PANEL = 0xF0181820, ROW = 0xFF2A2A34, HINT = 0xFF9090A0;

    // Saved settings and their defaults (the defaults match what RT64 used before the menu existed).
    static final String PREFS = "racer";
    static final String ASPECT = "aspect", FILTER = "filter", RESOLUTION = "resolution", FPS = "fps";
    static final String PAD = "pad", BOOST = "boost", OPACITY = "opacity", SIZE = "size";
    static final int DEF_ASPECT = 0, DEF_FILTER = 1, DEF_RESOLUTION = 3, DEF_OPACITY = 0, DEF_SIZE = 1;
    static final int[] OPACITIES = { 100, 75, 50, 30 };
    static final float[] SIZES = { 0.85f, 1.0f, 1.2f };
    static final String[] SIZE_NAMES = { "Small", "Medium", "Large" };

    private final SWE1RacerActivity activity;
    private final TouchPadView pad;
    private final SharedPreferences prefs;
    private final float dp;
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout list;  // the panel's contents (rebuilt by refresh)

    RacerMenu(SWE1RacerActivity activity, TouchPadView pad, SharedPreferences prefs) {
        this.activity = activity;
        this.pad = pad;
        this.prefs = prefs;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() { return root != null; }

    void close() {
        if (root == null) return;
        ((ViewGroup) root.getParent()).removeView(root);
        root = null;
    }

    void open() {
        if (root != null) return;
        final ViewGroup host = (ViewGroup) pad.getParent();
        if (host == null) return;
        pad.releaseAll();
        root = new FrameLayout(activity);
        root.setBackgroundColor(0x55000000);
        root.setOnClickListener(v -> close());  // a tap outside the panel

        scroll = new ScrollView(activity);
        scroll.setBackgroundColor(PANEL);
        scroll.setOnClickListener(v -> { });  // taps inside do not close it
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(16), px(10), px(16), px(24));
        scroll.addView(list);
        final int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        final int panelW = Math.min(screenW, Math.max(px(320), (int) (screenW * 0.40f)));
        build(list);
        root.addView(scroll, new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        host.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Rebuilds the panel's contents with the new values, in place (the panel stays, so it does not flash). */
    private void refresh() {
        if (root == null) return;
        final int y = scroll.getScrollY();
        list.removeAllViews();
        build(list);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---- contents ---------------------------------------------------------------------------------------------------
    private void build(LinearLayout list) {
        final LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = text("Menu", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView fps = text(String.format(Locale.US, "%.0f fps", SWE1RacerActivity.nativeGameFps()), 13, HINT);
        fps.setPadding(0, 0, px(8), 0);
        head.addView(fps);
        final TextView x = text("✕", 24, Color.WHITE);
        x.setPadding(px(12), px(4), px(4), px(4));
        x.setOnClickListener(v -> close());
        head.addView(x);
        list.addView(head);

        section(list, "Display");
        list.addView(label("Aspect ratio", "4:3 is the original picture. The wide ones show more of the track at the "
                + "sides; 21:9 is fitted to the width of the screen. Some objects may appear late at the edges."));
        chips(list, new String[] {"4:3", "16:9", "21:9"}, Math.min(2, prefs.getInt(ASPECT, DEF_ASPECT)), i -> {
            prefs.edit().putInt(ASPECT, i).apply();
            activity.applyVideo();
            refresh();
        });
        list.addView(label("Picture", "Sharp keeps the N64's pixels crisp; smooth softens them when scaled up."));
        chips(list, new String[] {"Sharp", "Smooth"}, prefs.getInt(FILTER, DEF_FILTER), i -> {
            prefs.edit().putInt(FILTER, i).apply();
            activity.applyVideo();
            refresh();
        });
        list.addView(label("Resolution", "How sharp the 3D is drawn. Higher costs more battery and heat; Fit uses the "
                + "biggest whole multiple that fits the screen."));
        chips(list, new String[] {"1×", "2×", "3×", "Fit"}, prefs.getInt(RESOLUTION, DEF_RESOLUTION), i -> {
            prefs.edit().putInt(RESOLUTION, i).apply();
            activity.applyVideo();
            refresh();
        });
        toggle(list, "FPS counter", "Frames per second the game finishes, and display lists (rendering work) per "
                + "second. Shown in the top-left corner.", prefs.getBoolean(FPS, false), on -> {
                    prefs.edit().putBoolean(FPS, on).apply();
                    activity.setFpsShown(on);
                });

        section(list, "Controls");
        toggle(list, "Show the pad", "Turn off when you play with a controller. The ≡ button always stays.",
                prefs.getBoolean(PAD, true), on -> {
                    prefs.edit().putBoolean(PAD, on).apply();
                    pad.setPadVisible(on);
                });
        toggle(list, "BOOST button", "Hold to accelerate and charge; it fires by itself. A quick re-tap fires now.",
                prefs.getBoolean(BOOST, true), on -> {
                    prefs.edit().putBoolean(BOOST, on).apply();
                    pad.setBoostEnabled(on);
                });
        row(list, "Move and resize controls", () -> {
            close();
            pad.startEditing();
        });
        final int opacity = Math.max(0, Math.min(OPACITIES.length - 1, prefs.getInt(OPACITY, DEF_OPACITY)));
        row(list, "Opacity: " + OPACITIES[opacity] + "%", () -> {
            final int next = (opacity + 1) % OPACITIES.length;
            prefs.edit().putInt(OPACITY, next).apply();
            pad.setOpacity(OPACITIES[next] / 100f);
            refresh();
        });
        final int size = Math.max(0, Math.min(SIZES.length - 1, prefs.getInt(SIZE, DEF_SIZE)));
        row(list, "Size (all controls): " + SIZE_NAMES[size], () -> {
            final int next = (size + 1) % SIZES.length;
            prefs.edit().putInt(SIZE, next).apply();
            pad.setSizeScale(SIZES[next]);
            refresh();
        });

        section(list, "Game");
        row(list, "Quit the game", () -> {
            close();
            activity.askQuit();
        });

        final TextView about = text("Star Wars Episode I: Racer runs natively: the game's own code was recompiled for "
                + "this phone (N64Recomp), and RT64 draws it with Vulkan.", 12, HINT);
        about.setPadding(0, px(20), 0, 0);
        list.addView(about);
    }

    // ---- widgets ----------------------------------------------------------------------------------------------------
    interface OnToggle { void set(boolean on); }

    interface OnChoice { void pick(int index); }

    private int px(float v) { return (int) (v * dp + 0.5f); }

    private TextView text(String s, float sp, int color) {
        final TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private GradientDrawable rounded(int color) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(10));
        return bg;
    }

    private void section(LinearLayout list, String name) {
        final TextView t = text(name.toUpperCase(Locale.US), 13, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(18), 0, px(4));
        list.addView(t);
    }

    private void row(LinearLayout list, String name, Runnable action) {
        final TextView t = text(name, 17, Color.WHITE);
        t.setPadding(px(12), 0, px(12), 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackground(rounded(ROW));
        t.setOnClickListener(v -> action.run());
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        lp.topMargin = px(6);
        list.addView(t, lp);
    }

    private LinearLayout label(String name, String hint) {
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(10), 0, 0);
        box.addView(text(name, 17, Color.WHITE));
        if (hint != null) box.addView(text(hint, 12, HINT));
        return box;
    }

    private void toggle(LinearLayout list, String name, String hint, boolean on, OnToggle onToggle) {
        final LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(name, hint), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Switch sw = new Switch(activity);
        // A track and knob of our own (the theme's switch is a faint ON / OFF label).
        final android.content.res.ColorStateList trackColors = new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}}, new int[] {ACCENT, 0xFF44444F});
        final GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(px(13));
        track.setSize(px(46), px(26));
        track.setColor(trackColors);
        final GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(px(26), px(26));
        thumb.setColor(Color.WHITE);
        thumb.setStroke(px(3), trackColors);
        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        sw.setShowText(false);
        sw.setText(null);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, checked) -> onToggle.set(checked));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        list.addView(row);
    }

    /** A row of choices, the selected one in the accent colour. */
    private void chips(LinearLayout list, String[] names, int selected, OnChoice onChoice) {
        final LinearLayout row = new LinearLayout(activity);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            final boolean sel = i == selected;
            final TextView b = text(names[i], 16, sel ? Color.BLACK : Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setBackground(rounded(sel ? ACCENT : ROW));
            b.setOnClickListener(v -> onChoice.pick(index));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(44), 1);
            lp.setMargins(i == 0 ? 0 : px(6), px(6), 0, px(4));
            row.addView(b, lp);
        }
        list.addView(row);
    }
}
