package com.sp00nznet.swep1racer;

// A transparent full-screen view drawn on top of the SDL game surface. It draws an N64 pad (stick, A, B, Z, R,
// Start, 4 C buttons, BOOST), tracks every finger, and sends the pad state to native code through
// SWE1RacerActivity.nativeSetTouchPad(buttons, x, y). Native code ORs it into the N64 controller state (get_input).
// The small "pad" button at the top centre hides/shows the pad (when hidden, only that button stays visible).

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

public final class TouchPadView extends View {
    // N64 button bits (same as get_input in src\main.cpp)
    static final int BTN_A = 0x8000, BTN_B = 0x4000, BTN_Z = 0x2000, BTN_START = 0x1000;
    static final int BTN_R = 0x0010;
    static final int BTN_CU = 0x0008, BTN_CD = 0x0004, BTN_CL = 0x0002, BTN_CR = 0x0001;

    // Button table: bit, centre x (fraction of width), centre y (fraction of height), radius (fraction of height), label.
    // Racer's own control list (ROM): A accel, B brake, R repair, C camera/roll/roll/look back, Z slide. L is not used
    // by Racer, so it has no button (2026-10-21).
    private static final int[] BITS = { BTN_A, BTN_B, BTN_Z, BTN_R, BTN_START, BTN_CU, BTN_CD, BTN_CL, BTN_CR };
    private static final float[] BX = { 0.88f, 0.77f, 0.07f, 0.93f, 0.50f, 0.86f, 0.86f, 0.80f, 0.92f };
    private static final float[] BY = { 0.72f, 0.84f, 0.30f, 0.14f, 0.90f, 0.26f, 0.50f, 0.38f, 0.38f };
    private static final float[] BR = { 0.090f, 0.075f, 0.075f, 0.065f, 0.050f, 0.048f, 0.048f, 0.048f, 0.048f };
    private static final String[] LABEL = { "A", "B", "Z", "R", "START", "C^", "Cv", "C<", "C>" };

    // BOOST (2026-10-21), one thumb. Racer charges a boost while accelerate is held (above ~half top speed), fires it
    // on a fresh accelerate press, and cancels it as soon as accelerate is let go. So BOOST:
    //  - held: holds A (accelerate) and pushes the stick forward (the stick still steers);
    //  - after BOOST_CHARGE_MS of holding: fires by itself (A off for BOOST_GAP_MS, then on again) and keeps holding A;
    //  - lift and re-tap within BOOST_RETAP_MS: fires right away (for when the automatic fire came before the charge);
    //  - released: A stays held for BOOST_GRACE_MS so the thumb can slide over to A without a throttle gap.
    private static final float BOOST_X = 0.70f, BOOST_Y = 0.60f, BOOST_R = 0.065f;
    private static final long BOOST_CHARGE_MS = 1200, BOOST_GAP_MS = 80, BOOST_RETAP_MS = 400, BOOST_GRACE_MS = 250;

    private static final float STICK_X = 0.17f, STICK_Y = 0.70f, STICK_R = 0.20f; // stick base, radius = fraction of height
    private static final float TOGGLE_X = 0.50f, TOGGLE_Y = 0.06f, TOGGLE_R = 0.045f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

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
    private final Runnable boostAutoFire = this::boostFire;
    private final Runnable boostGapEnd = () -> { boostGap = false; send(); invalidate(); };
    private final Runnable boostGraceEnd = () -> { send(); invalidate(); };
    private int lastSentButtons = -1;
    private float lastSentX = 99f, lastSentY = 99f;
    private int shownButtons = 0;             // what the game gets (fingers + BOOST), for drawing pressed buttons

    public TouchPadView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4f);
        stroke.setColor(0x99FFFFFF);
        text.setColor(0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
    }

    // ---- set by the side menu (RacerMenu) ----
    void setOnMenu(Runnable r) { onMenu = r; }
    void setPadVisible(boolean on) { releaseAll(); visible = on; invalidate(); }
    void setOpacity(float a) { opacity = a; invalidate(); }
    void setSizeScale(float sc) { sizeScale = sc; invalidate(); }
    void setBoostEnabled(boolean on) { releaseAll(); boostEnabled = on; invalidate(); }

    /** Lets go of everything (menu opened, pad hidden): the game sees no button and a centred stick. */
    void releaseAll() {
        stickPointerId = -1;
        buttons = 0; stickX = 0f; stickY = 0f;
        boostHeld = false; boostGap = false; boostGraceUntil = 0;
        handler.removeCallbacksAndMessages(null);
        send();
        invalidate();
    }

    private float w() { return getWidth(); }
    private float h() { return getHeight(); }

    private boolean inside(float px, float py, float cx, float cy, float r) {
        float dx = px - cx, dy = py - cy;
        return dx * dx + dy * dy <= r * r;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actionIndex = e.getActionIndex();
        float hh = h();

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
            if (stickPointerId == -1 && inside(x, y, STICK_X * w(), STICK_Y * hh, STICK_R * sizeScale * hh * 1.6f)) {
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
                float r = STICK_R * sizeScale * hh;
                float dx = (x - STICK_X * w()) / r;
                float dy = (STICK_Y * hh - y) / r;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) { dx /= len; dy /= len; }
                sx = dx; sy = dy;
                continue;
            }
            for (int b = 0; b < BITS.length; b++) {
                if (inside(x, y, BX[b] * w(), BY[b] * hh, BR[b] * sizeScale * hh * 1.25f)) {
                    newButtons |= BITS[b];
                }
            }
            if (boostEnabled && inside(x, y, BOOST_X * w(), BOOST_Y * hh, BOOST_R * sizeScale * hh * 1.25f)) {
                newBoost = true;
            }
        }
        buttons = newButtons;
        stickX = sx; stickY = sy;
        long now = SystemClock.uptimeMillis();
        if (newBoost && !boostHeld) {
            handler.removeCallbacks(boostAutoFire);
            boostHeld = true;
            if (now - boostReleasedAt < BOOST_RETAP_MS) {
                boostFire();                      // quick re-tap: fire now (no automatic fire later: it would cancel it)
            } else {
                handler.postDelayed(boostAutoFire, BOOST_CHARGE_MS);
            }
        } else if (!newBoost && boostHeld) {
            handler.removeCallbacks(boostAutoFire);
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

    // Fire: let go of A for BOOST_GAP_MS while BOOST is held; A comes back on by itself (the fresh press fires).
    private void boostFire() {
        if (!boostHeld) {
            return;
        }
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
        text.setTextSize(hh * 0.035f);
        // toggle
        fill.setColor(0x55000000);
        c.drawCircle(TOGGLE_X * w(), TOGGLE_Y * hh, TOGGLE_R * hh, fill);
        c.drawCircle(TOGGLE_X * w(), TOGGLE_Y * hh, TOGGLE_R * hh, stroke);
        text.setTextSize(hh * 0.05f);
        c.drawText("≡", TOGGLE_X * w(), TOGGLE_Y * hh + hh * 0.017f, text);
        text.setTextSize(hh * 0.035f);
        if (!visible) {
            return;
        }
        final int layer = c.saveLayerAlpha(0, 0, w(), hh, Math.round(255 * opacity));
        // stick
        float r = STICK_R * sizeScale * hh;
        fill.setColor(0x33FFFFFF);
        c.drawCircle(STICK_X * w(), STICK_Y * hh, r, fill);
        c.drawCircle(STICK_X * w(), STICK_Y * hh, r, stroke);
        fill.setColor(0x88FFFFFF);
        float drawY = boostHeld ? 1f : stickY;
        c.drawCircle(STICK_X * w() + stickX * r, STICK_Y * hh - drawY * r, r * 0.42f, fill);
        // buttons
        for (int b = 0; b < BITS.length; b++) {
            boolean down = (shownButtons & BITS[b]) != 0;
            fill.setColor(down ? 0xAAFFFFFF : 0x44FFFFFF);
            c.drawCircle(BX[b] * w(), BY[b] * hh, BR[b] * sizeScale * hh, fill);
            c.drawCircle(BX[b] * w(), BY[b] * hh, BR[b] * sizeScale * hh, stroke);
            c.drawText(LABEL[b], BX[b] * w(), BY[b] * hh + hh * 0.012f, text);
        }
        // BOOST: orange tint so it stands out from the N64 buttons
        if (boostEnabled) {
            fill.setColor(boostHeld ? 0xCCFF8A2A : 0x66FF8A2A);
            c.drawCircle(BOOST_X * w(), BOOST_Y * hh, BOOST_R * sizeScale * hh, fill);
            c.drawCircle(BOOST_X * w(), BOOST_Y * hh, BOOST_R * sizeScale * hh, stroke);
            text.setTextSize(hh * 0.026f * sizeScale);
            c.drawText("BOOST", BOOST_X * w(), BOOST_Y * hh + hh * 0.009f, text);
        }
        c.restoreToCount(layer);
    }
}
