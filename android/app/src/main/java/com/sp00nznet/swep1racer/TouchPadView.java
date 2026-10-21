package com.sp00nznet.swep1racer;

// A transparent full-screen view drawn on top of the SDL game surface. It draws an N64 pad (stick, A, B, Z, R,
// Start, 4 C buttons, BOOST), tracks every finger, and sends the pad state to native code through
// SWE1RacerActivity.nativeSetTouchPad(buttons, x, y). Native code ORs it into the N64 controller state (get_input).
// The ≡ button at the top centre opens the side menu (RacerMenu). In editing mode (the menu's "Move and resize
// controls") every control can be dragged and resized; the layout is saved in SharedPreferences "racer".

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

public final class TouchPadView extends View {
    // N64 button bits (same as get_input in src\main.cpp)
    static final int BTN_A = 0x8000, BTN_B = 0x4000, BTN_Z = 0x2000, BTN_START = 0x1000;
    static final int BTN_R = 0x0010;
    static final int BTN_CU = 0x0008, BTN_CD = 0x0004, BTN_CL = 0x0002, BTN_CR = 0x0001;

    // Controls 0..8: the buttons (bit, label). Racer's own control list (ROM): A accel, B brake, R repair,
    // C camera/roll/roll/look back, Z slide. L is not used by Racer, so it has no button (2026-10-21).
    // Control 9: the stick. Control 10: BOOST.
    private static final int[] BITS = { BTN_A, BTN_B, BTN_Z, BTN_R, BTN_START, BTN_CU, BTN_CD, BTN_CL, BTN_CR };
    private static final String[] LABEL = { "A", "B", "Z", "R", "START", "C^", "Cv", "C<", "C>" };
    private static final int STICK = 9, BOOST = 10, CONTROLS = 11;
    // Default layout: centre x (fraction of width), centre y (fraction of height), radius (fraction of height).
    private static final float[] DEF_X = { 0.88f, 0.77f, 0.07f, 0.93f, 0.50f, 0.86f, 0.86f, 0.80f, 0.92f, 0.17f, 0.70f };
    private static final float[] DEF_Y = { 0.72f, 0.84f, 0.30f, 0.14f, 0.90f, 0.26f, 0.50f, 0.38f, 0.38f, 0.70f, 0.60f };
    private static final float[] DEF_R = { 0.090f, 0.075f, 0.075f, 0.065f, 0.050f, 0.048f, 0.048f, 0.048f, 0.048f, 0.20f, 0.065f };
    static final String LAYOUT = "pad_layout";

    // BOOST (2026-10-21), one thumb. Racer charges a boost while accelerate is held (above ~half top speed), fires it
    // on a fresh accelerate press, and cancels it as soon as accelerate is let go. So BOOST:
    //  - held: holds A (accelerate) and pushes the stick forward (the stick still steers);
    //  - fires by itself the moment the game reports the boost ready (SWE1RacerActivity.nativeBoostState() == 2,
    //    checked every BOOST_POLL_MS): A off for BOOST_GAP_MS, then on again, and keeps holding A. Keep holding and it
    //    fires again each time the game has recharged. When the game's state cannot be read, it fires once after
    //    BOOST_CHARGE_MS instead;
    //  - lift and re-tap within BOOST_RETAP_MS: fires right away (for when the automatic fire came before the charge);
    //  - released: A stays held for BOOST_GRACE_MS so the thumb can slide over to A without a throttle gap.
    private static final long BOOST_CHARGE_MS = 1200, BOOST_GAP_MS = 80, BOOST_RETAP_MS = 400, BOOST_GRACE_MS = 250;
    private static final long BOOST_POLL_MS = 30;

    private static final float TOGGLE_X = 0.50f, TOGGLE_Y = 0.06f, TOGGLE_R = 0.045f;
    private static final int ACCENT = 0xFF8AB4FF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Layout: per-control position and size factor (on top of the menu's overall Size).
    private final float[] cx = DEF_X.clone(), cy = DEF_Y.clone();
    private final float[] cscale = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
    private SharedPreferences prefs;

    private boolean visible = true;           // pad shown (the menu's "Show the pad")
    private Runnable onMenu;                  // the top-centre menu button
    private float opacity = 1f, sizeScale = 1f;
    private boolean boostEnabled = true;
    private int stickPointerId = -1;
    private float stickX = 0f, stickY = 0f;   // -1..1, y up = +1
    private int buttons = 0;                  // buttons under the fingers
    private boolean boostHeld = false;
    private boolean boostGap = false;         // A forced off for a moment: the release half of a fire
    private long boostReleasedAt = -100000;   // uptime of the last BOOST release
    private long boostGraceUntil = 0;         // A held until this uptime after BOOST is released
    private int boostState = -1;              // the game's boost status while held (-1 unknown, 0/1/2)
    private boolean boostFired = false;       // fired during this hold (the 1.2 s fallback fires only once)
    private long boostLastFireAt = 0;         // uptime of the last fire (one ready state = one fire)
    private final Runnable boostAutoFire = () -> { if (!boostFired && boostState < 0) boostFire(); };
    private final Runnable boostPoll = new Runnable() {
        @Override
        public void run() {
            if (!boostHeld) return;
            int st = -1;
            try {
                st = SWE1RacerActivity.nativeBoostState();
            } catch (UnsatisfiedLinkError ignored) {
                // native library not loaded yet
            }
            if (st != boostState) {
                android.util.Log.i("SWE1Racer", "[boost] game boost state " + boostState + " -> " + st);
                boostState = st;
                invalidate();
            }
            // Every time the game reports a boost ready while BOOST is held (it recharges after a boost ends).
            if (st == 2 && !boostGap && SystemClock.uptimeMillis() - boostLastFireAt > 300) {
                boostFire();
            }
            handler.postDelayed(this, BOOST_POLL_MS);
        }
    };
    private final Runnable boostGapEnd = () -> { boostGap = false; send(); invalidate(); };
    private final Runnable boostGraceEnd = () -> { send(); invalidate(); };
    private int lastSentButtons = -1;
    private float lastSentX = 99f, lastSentY = 99f;
    private int shownButtons = 0;             // what the game gets (fingers + BOOST), for drawing pressed buttons

    // Editing mode
    private boolean editing = false;
    private int selected = -1;                // the control the editing tools act on
    private int dragging = -1, dragPointer = -1;
    private float dragDx, dragDy;             // finger offset from the control's centre (pixels)
    private static final String[] TOOLS = { "Smaller", "Bigger", "Reset", "Done" };

    public TouchPadView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4f);
        stroke.setColor(0x99FFFFFF);
        text.setColor(0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
    }

    // ---- set by the side menu (RacerMenu) / the activity ----
    void setOnMenu(Runnable r) { onMenu = r; }
    void setPadVisible(boolean on) { releaseAll(); visible = on; invalidate(); }
    void setOpacity(float a) { opacity = a; invalidate(); }
    void setSizeScale(float sc) { sizeScale = sc; invalidate(); }
    void setBoostEnabled(boolean on) { releaseAll(); boostEnabled = on; invalidate(); }

    /** Loads the saved layout ("x,y,scale" per control, ';' between controls); missing or bad = default. */
    void loadLayout(SharedPreferences p) {
        prefs = p;
        final String s = p.getString(LAYOUT, null);
        if (s == null) return;
        final String[] parts = s.split(";");
        for (int i = 0; i < CONTROLS && i < parts.length; i++) {
            final String[] v = parts[i].split(",");
            if (v.length != 3) continue;
            try {
                cx[i] = clampf(Float.parseFloat(v[0]), 0.02f, 0.98f);
                cy[i] = clampf(Float.parseFloat(v[1]), 0.04f, 0.96f);
                cscale[i] = clampf(Float.parseFloat(v[2]), 0.5f, 2.0f);
            } catch (NumberFormatException ignored) {
                // keep the default for that control
            }
        }
        invalidate();
    }

    private void saveLayout() {
        if (prefs == null) return;
        final StringBuilder s = new StringBuilder();
        for (int i = 0; i < CONTROLS; i++) {
            if (i > 0) s.append(';');
            s.append(String.format(Locale.US, "%.4f,%.4f,%.3f", cx[i], cy[i], cscale[i]));
        }
        prefs.edit().putString(LAYOUT, s.toString()).apply();
    }

    /** Editing mode on: drag controls, resize the selected one; the game gets no input meanwhile. */
    void startEditing() {
        releaseAll();
        editing = true;
        selected = -1;
        invalidate();
    }

    boolean isEditing() { return editing; }

    void stopEditing() {
        editing = false;
        dragging = -1;
        selected = -1;
        saveLayout();
        invalidate();
    }

    /** Lets go of everything (menu opened, pad hidden): the game sees no button and a centred stick. */
    void releaseAll() {
        stickPointerId = -1;
        buttons = 0; stickX = 0f; stickY = 0f;
        boostHeld = false; boostGap = false; boostGraceUntil = 0;
        handler.removeCallbacksAndMessages(null);
        send();
        invalidate();
    }

    private static float clampf(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private float w() { return getWidth(); }
    private float h() { return getHeight(); }
    private float px(int i) { return cx[i] * w(); }
    private float py(int i) { return cy[i] * h(); }
    private float pr(int i) { return DEF_R[i] * cscale[i] * sizeScale * h(); }

    private boolean inside(float x, float y, float centreX, float centreY, float r) {
        float dx = x - centreX, dy = y - centreY;
        return dx * dx + dy * dy <= r * r;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actionIndex = e.getActionIndex();
        float hh = h();

        if (editing) {
            return onEditTouch(e, action, actionIndex);
        }

        // Menu button: on a fresh finger down inside it
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(actionIndex), y = e.getY(actionIndex);
            if (inside(x, y, TOGGLE_X * w(), TOGGLE_Y * hh, TOGGLE_R * hh * 1.4f)) {
                releaseAll();
                if (onMenu != null) {
                    onMenu.run();
                }
                return true;
            }
        }
        if (!visible) {
            return false; // let touches fall through to SDL when hidden
        }

        // Stick capture / release
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(actionIndex), y = e.getY(actionIndex);
            if (stickPointerId == -1 && inside(x, y, px(STICK), py(STICK), pr(STICK) * 1.6f)) {
                stickPointerId = e.getPointerId(actionIndex);
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            stickPointerId = -1;
        }
        if (action == MotionEvent.ACTION_POINTER_UP && e.getPointerId(actionIndex) == stickPointerId) {
            stickPointerId = -1;
        }

        // Recompute state from every finger that is still down
        int newButtons = 0;
        boolean newBoost = false;
        float sx = 0f, sy = 0f;
        for (int i = 0; i < e.getPointerCount(); i++) {
            boolean liftingThis = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    || (action == MotionEvent.ACTION_POINTER_UP && i == actionIndex);
            if (liftingThis) {
                continue;
            }
            float x = e.getX(i), y = e.getY(i);
            int id = e.getPointerId(i);
            if (id == stickPointerId) {
                float r = pr(STICK);
                float dx = (x - px(STICK)) / r;
                float dy = (py(STICK) - y) / r;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) { dx /= len; dy /= len; }
                sx = dx; sy = dy;
                continue;
            }
            for (int b = 0; b < BITS.length; b++) {
                if (inside(x, y, px(b), py(b), pr(b) * 1.25f)) {
                    newButtons |= BITS[b];
                }
            }
            if (boostEnabled && inside(x, y, px(BOOST), py(BOOST), pr(BOOST) * 1.25f)) {
                newBoost = true;
            }
        }
        buttons = newButtons;
        stickX = sx; stickY = sy;
        long now = SystemClock.uptimeMillis();
        if (newBoost && !boostHeld) {
            handler.removeCallbacks(boostAutoFire);
            handler.removeCallbacks(boostPoll);
            boostHeld = true;
            boostFired = false;
            boostState = -1;
            if (now - boostReleasedAt < BOOST_RETAP_MS) {
                boostFire();                      // quick re-tap: fire now (no automatic fire later: it would cancel it)
            } else {
                handler.postDelayed(boostAutoFire, BOOST_CHARGE_MS);
                handler.post(boostPoll);
            }
        } else if (!newBoost && boostHeld) {
            handler.removeCallbacks(boostAutoFire);
            handler.removeCallbacks(boostPoll);
            boostState = -1;
            handler.removeCallbacks(boostGapEnd);
            boostHeld = false;
            boostGap = false;
            boostReleasedAt = now;
            boostGraceUntil = now + BOOST_GRACE_MS;
            handler.postDelayed(boostGraceEnd, BOOST_GRACE_MS + 5);
        }
        send();
        invalidate();
        return true;
    }

    // ---- editing mode ----
    private RectF toolRect(int t) {
        final float bw = w() * 0.11f, bh = h() * 0.085f, gap = w() * 0.012f;
        final float total = TOOLS.length * bw + (TOOLS.length - 1) * gap;
        final float left = (w() - total) / 2f + t * (bw + gap);
        final float top = h() * 0.16f;
        return new RectF(left, top, left + bw, top + bh);
    }

    private boolean onEditTouch(MotionEvent e, int action, int actionIndex) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            final float x = e.getX(actionIndex), y = e.getY(actionIndex);
            for (int t = 0; t < TOOLS.length; t++) {
                if (toolRect(t).contains(x, y)) {
                    onTool(t);
                    return true;
                }
            }
            if (dragging < 0) {
                // the control under the finger (the smallest one wins where controls overlap)
                int best = -1;
                float bestR = Float.MAX_VALUE;
                for (int i = 0; i < CONTROLS; i++) {
                    final float r = Math.max(pr(i), h() * 0.05f);
                    if (inside(x, y, px(i), py(i), r) && r < bestR) {
                        best = i;
                        bestR = r;
                    }
                }
                if (best >= 0) {
                    dragging = best;
                    selected = best;
                    dragPointer = e.getPointerId(actionIndex);
                    dragDx = x - px(best);
                    dragDy = y - py(best);
                    invalidate();
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE && dragging >= 0) {
            final int idx = e.findPointerIndex(dragPointer);
            if (idx >= 0) {
                cx[dragging] = clampf((e.getX(idx) - dragDx) / w(), 0.02f, 0.98f);
                cy[dragging] = clampf((e.getY(idx) - dragDy) / h(), 0.04f, 0.96f);
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP && e.getPointerId(actionIndex) == dragPointer)) {
            dragging = -1;
            dragPointer = -1;
        }
        return true;
    }

    private void onTool(int t) {
        switch (t) {
            case 0: // Smaller
                if (selected >= 0) cscale[selected] = clampf(cscale[selected] / 1.12f, 0.5f, 2.0f);
                break;
            case 1: // Bigger
                if (selected >= 0) cscale[selected] = clampf(cscale[selected] * 1.12f, 0.5f, 2.0f);
                break;
            case 2: // Reset: everything back to the default layout
                for (int i = 0; i < CONTROLS; i++) {
                    cx[i] = DEF_X[i];
                    cy[i] = DEF_Y[i];
                    cscale[i] = 1f;
                }
                selected = -1;
                break;
            default: // Done
                stopEditing();
                return;
        }
        invalidate();
    }

    // Fire: let go of A for BOOST_GAP_MS while BOOST is held; A comes back on by itself (the fresh press fires).
    private void boostFire() {
        if (!boostHeld) {
            return;
        }
        boostFired = true;
        boostLastFireAt = SystemClock.uptimeMillis();
        android.util.Log.i("SWE1Racer", "[boost] fire (game state " + boostState + ")");
        boostGap = true;
        handler.removeCallbacks(boostGapEnd);
        handler.postDelayed(boostGapEnd, BOOST_GAP_MS);
        send();
        invalidate();
    }

    private void send() {
        int outButtons = buttons;
        float outY = stickY;
        if (boostHeld) {
            outY = 1f;
            if (boostGap) {
                outButtons &= ~BTN_A;
            } else {
                outButtons |= BTN_A;
            }
        } else if (SystemClock.uptimeMillis() < boostGraceUntil) {
            outButtons |= BTN_A;
        }
        shownButtons = outButtons;
        if (outButtons != lastSentButtons || Math.abs(stickX - lastSentX) > 0.01f || Math.abs(outY - lastSentY) > 0.01f) {
            lastSentButtons = outButtons; lastSentX = stickX; lastSentY = outY;
            try {
                SWE1RacerActivity.nativeSetTouchPad(outButtons, stickX, outY);
            } catch (UnsatisfiedLinkError ignored) {
                // native library not loaded yet
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float hh = h();
        // menu button (hidden while editing: Done ends editing)
        if (!editing) {
            fill.setColor(0x55000000);
            c.drawCircle(TOGGLE_X * w(), TOGGLE_Y * hh, TOGGLE_R * hh, fill);
            c.drawCircle(TOGGLE_X * w(), TOGGLE_Y * hh, TOGGLE_R * hh, stroke);
            text.setTextSize(hh * 0.05f);
            c.drawText("≡", TOGGLE_X * w(), TOGGLE_Y * hh + hh * 0.017f, text);
        }
        text.setTextSize(hh * 0.035f);
        if (!visible && !editing) {
            return;
        }
        if (editing) {
            c.drawColor(0x66000000);
        }
        final int layer = c.saveLayerAlpha(0, 0, w(), hh, editing ? 255 : Math.round(255 * opacity));
        // stick
        float r = pr(STICK);
        fill.setColor(0x33FFFFFF);
        c.drawCircle(px(STICK), py(STICK), r, fill);
        c.drawCircle(px(STICK), py(STICK), r, stroke);
        fill.setColor(0x88FFFFFF);
        float drawY = boostHeld ? 1f : stickY;
        c.drawCircle(px(STICK) + stickX * r, py(STICK) - drawY * r, r * 0.42f, fill);
        // buttons
        for (int b = 0; b < BITS.length; b++) {
            boolean down = (shownButtons & BITS[b]) != 0;
            fill.setColor(down ? 0xAAFFFFFF : 0x44FFFFFF);
            c.drawCircle(px(b), py(b), pr(b), fill);
            c.drawCircle(px(b), py(b), pr(b), stroke);
            text.setTextSize(hh * 0.035f * Math.min(1.3f, cscale[b] * sizeScale));
            c.drawText(LABEL[b], px(b), py(b) + hh * 0.012f, text);
        }
        // BOOST: orange tint so it stands out from the N64 buttons (shown in editing even when switched off)
        if (boostEnabled || editing) {
            // brighter while the game charges the boost (1), brightest when it is ready (2)
            fill.setColor(!boostHeld ? 0x66FF8A2A : boostState == 2 ? 0xFFFFB86B : boostState == 1 ? 0xDDFF9A40 : 0xCCFF8A2A);
            c.drawCircle(px(BOOST), py(BOOST), pr(BOOST), fill);
            c.drawCircle(px(BOOST), py(BOOST), pr(BOOST), stroke);
            text.setTextSize(hh * 0.026f * cscale[BOOST] * sizeScale);
            c.drawText("BOOST", px(BOOST), py(BOOST) + hh * 0.009f, text);
        }
        c.restoreToCount(layer);
        if (editing) {
            drawEditTools(c, hh);
        }
    }

    private void drawEditTools(Canvas c, float hh) {
        // the selected control
        if (selected >= 0) {
            final Paint sel = new Paint(Paint.ANTI_ALIAS_FLAG);
            sel.setStyle(Paint.Style.STROKE);
            sel.setStrokeWidth(6f);
            sel.setColor(ACCENT);
            c.drawCircle(px(selected), py(selected), pr(selected) + 8f, sel);
        }
        // hint and the tool buttons
        text.setTextSize(hh * 0.032f);
        text.setColor(0xFFFFFFFF);
        c.drawText(selected >= 0 ? "Drag to move · Smaller / Bigger resize the outlined control"
                : "Drag a control to move it · tap one to resize it", w() / 2f, hh * 0.11f, text);
        for (int t = 0; t < TOOLS.length; t++) {
            final RectF rr = toolRect(t);
            final boolean active = (t >= 2) || selected >= 0;
            fill.setColor(t == 3 ? ACCENT : active ? 0xF02A2A34 : 0xF0202028);
            c.drawRoundRect(rr, 18f, 18f, fill);
            text.setColor(t == 3 ? 0xFF000000 : active ? 0xFFFFFFFF : 0xFF606068);
            text.setTextSize(hh * 0.034f);
            c.drawText(TOOLS[t], rr.centerX(), rr.centerY() + hh * 0.012f, text);
        }
        text.setColor(0xCCFFFFFF);
    }
}
