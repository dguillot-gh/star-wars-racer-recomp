package com.sp00nznet.swep1racer;

// SNIPPET for steps\S7-touch.md. Copy this file UNCHANGED to:
//   J:\n64-recomp\racer\android\app\src\main\java\com\sp00nznet\swep1racer\TouchPadView.java
//
// A transparent full-screen view drawn on top of the SDL game surface. It draws an N64 pad (stick, A, B, Z, L, R,
// Start, 4 C buttons), tracks every finger, and sends the pad state to native code through
// SWE1RacerActivity.nativeSetTouchPad(buttons, x, y). Native code ORs it into the N64 controller state (get_input).
// The small "pad" button at the top centre hides/shows the pad (when hidden, only that button stays visible).

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

public final class TouchPadView extends View {
    // N64 button bits (same as get_input in src\main.cpp)
    static final int BTN_A = 0x8000, BTN_B = 0x4000, BTN_Z = 0x2000, BTN_START = 0x1000;
    static final int BTN_L = 0x0020, BTN_R = 0x0010;
    static final int BTN_CU = 0x0008, BTN_CD = 0x0004, BTN_CL = 0x0002, BTN_CR = 0x0001;

    // Button table: bit, centre x (fraction of width), centre y (fraction of height), radius (fraction of height), label
    private static final int[] BITS = { BTN_A, BTN_B, BTN_Z, BTN_L, BTN_R, BTN_START, BTN_CU, BTN_CD, BTN_CL, BTN_CR };
    private static final float[] BX = { 0.88f, 0.77f, 0.07f, 0.07f, 0.93f, 0.50f, 0.86f, 0.86f, 0.80f, 0.92f };
    private static final float[] BY = { 0.72f, 0.84f, 0.42f, 0.14f, 0.14f, 0.90f, 0.26f, 0.50f, 0.38f, 0.38f };
    private static final float[] BR = { 0.090f, 0.075f, 0.070f, 0.065f, 0.065f, 0.050f, 0.048f, 0.048f, 0.048f, 0.048f };
    private static final String[] LABEL = { "A", "B", "Z", "L", "R", "START", "C^", "Cv", "C<", "C>" };

    private static final float STICK_X = 0.17f, STICK_Y = 0.70f, STICK_R = 0.20f; // stick base, radius = fraction of height
    private static final float TOGGLE_X = 0.50f, TOGGLE_Y = 0.06f, TOGGLE_R = 0.045f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean visible = true;
    private int stickPointerId = -1;
    private float stickX = 0f, stickY = 0f;   // -1..1, y up = +1
    private int buttons = 0;
    private int lastSentButtons = -1;
    private float lastSentX = 99f, lastSentY = 99f;

    public TouchPadView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4f);
        stroke.setColor(0x99FFFFFF);
        text.setColor(0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
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

        // Toggle button: on a fresh finger down inside it
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(actionIndex), y = e.getY(actionIndex);
            if (inside(x, y, TOGGLE_X * w(), TOGGLE_Y * hh, TOGGLE_R * hh * 1.4f)) {
                visible = !visible;
                stickPointerId = -1;
                buttons = 0; stickX = 0f; stickY = 0f;
                send();
                invalidate();
                return true;
            }
        }
        if (!visible) {
            return false; // let touches fall through to SDL when hidden
        }

        // Stick capture / release
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(actionIndex), y = e.getY(actionIndex);
            if (stickPointerId == -1 && inside(x, y, STICK_X * w(), STICK_Y * hh, STICK_R * hh * 1.6f)) {
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
                float r = STICK_R * hh;
                float dx = (x - STICK_X * w()) / r;
                float dy = (STICK_Y * hh - y) / r;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) { dx /= len; dy /= len; }
                sx = dx; sy = dy;
                continue;
            }
            for (int b = 0; b < BITS.length; b++) {
                if (inside(x, y, BX[b] * w(), BY[b] * hh, BR[b] * hh * 1.25f)) {
                    newButtons |= BITS[b];
                }
            }
        }
        buttons = newButtons;
        stickX = sx; stickY = sy;
        send();
        invalidate();
        return true;
    }

    private void send() {
        if (buttons != lastSentButtons || Math.abs(stickX - lastSentX) > 0.01f || Math.abs(stickY - lastSentY) > 0.01f) {
            lastSentButtons = buttons; lastSentX = stickX; lastSentY = stickY;
            try {
                SWE1RacerActivity.nativeSetTouchPad(buttons, stickX, stickY);
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
        c.drawText(visible ? "hide" : "pad", TOGGLE_X * w(), TOGGLE_Y * hh + hh * 0.012f, text);
        if (!visible) {
            return;
        }
        // stick
        float r = STICK_R * hh;
        fill.setColor(0x33FFFFFF);
        c.drawCircle(STICK_X * w(), STICK_Y * hh, r, fill);
        c.drawCircle(STICK_X * w(), STICK_Y * hh, r, stroke);
        fill.setColor(0x88FFFFFF);
        c.drawCircle(STICK_X * w() + stickX * r, STICK_Y * hh - stickY * r, r * 0.42f, fill);
        // buttons
        for (int b = 0; b < BITS.length; b++) {
            boolean down = (buttons & BITS[b]) != 0;
            fill.setColor(down ? 0xAAFFFFFF : 0x44FFFFFF);
            c.drawCircle(BX[b] * w(), BY[b] * hh, BR[b] * hh, fill);
            c.drawCircle(BX[b] * w(), BY[b] * hh, BR[b] * hh, stroke);
            c.drawText(LABEL[b], BX[b] * w(), BY[b] * hh + hh * 0.012f, text);
        }
    }
}
