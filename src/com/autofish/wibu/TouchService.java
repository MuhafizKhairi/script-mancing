package com.autofish.wibu;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Accessibility service: injects taps / press-and-hold, and shows the small floating control panel.
 * Holding uses continued strokes (willContinue=true) chained in short segments, so the finger stays down
 * for as long as the bot wants and can be lifted within one segment.
 */
public class TouchService extends AccessibilityService {

    public static volatile TouchService instance;

    private static final long SEG_MS = 40;

    private Handler main;
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams lp;
    private TextView statusTv, detTv;
    private Button toggle, recBtn;

    // hold engine state (main thread only)
    private boolean wantHold, strokeActive, inFlight, alt;
    private GestureDescription.StrokeDescription cur;
    private float hx, hy;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!DevLock.allowed(this)) { // dev build on someone else's phone: do nothing
            android.util.Log.i("AutoMancing", "Kode HP dev: " + DevLock.deviceCode(this));
            disableSelf();
            return;
        }
        instance = this;
        main = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createPanel();
        main.post(updater);
        Prefs.log("Layanan aksesibilitas aktif");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (main != null) main.removeCallbacksAndMessages(null);
        if (panel != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ gestures
    private float[] toScreen(float fx, float fy) {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new float[]{fx * dm.widthPixels, fy * dm.heightPixels};
    }

    public void tap(final float fx, final float fy) {
        main.post(() -> {
            if (strokeActive || inFlight || wantHold) return;
            float[] p = toScreen(fx, fy);
            Path path = new Path();
            path.moveTo(p[0], p[1]);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build();
            dispatchGesture(g, null, null);
        });
    }

    public void setHold(final boolean down, final float fx, final float fy) {
        main.post(() -> {
            wantHold = down;
            if (!strokeActive) {
                float[] p = toScreen(fx, fy);
                hx = p[0];
                hy = p[1];
            }
            pump();
        });
    }

    private final GestureResultCallback callback = new GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            inFlight = false;
            pump();
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            inFlight = false;
            strokeActive = false;
            if (wantHold) main.postDelayed(TouchService.this::pump, 15);
        }
    };

    private void pump() {
        if (inFlight) return;
        if (!strokeActive) {
            if (!wantHold) return;
            Path p = new Path();
            p.moveTo(hx, hy);
            p.lineTo(hx, hy + 1);
            alt = true;
            cur = new GestureDescription.StrokeDescription(p, 0, SEG_MS, true);
            strokeActive = true;
        } else {
            // continue from where the previous segment ended (wiggle 1px so the path is never empty)
            Path p = new Path();
            float y0 = alt ? hy + 1 : hy, y1 = alt ? hy : hy + 1;
            alt = !alt;
            p.moveTo(hx, y0);
            p.lineTo(hx, y1);
            boolean keep = wantHold;
            cur = cur.continueStroke(p, 0, keep ? SEG_MS : 8, keep);
            if (!keep) strokeActive = false;
        }
        inFlight = true;
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(cur).build(), callback, main);
        if (!ok) {
            inFlight = false;
            strokeActive = false;
        }
    }

    // ------------------------------------------------------------------ floating panel
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(6), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC202020);
        bg.setCornerRadius(dp(10));
        panel.setBackground(bg);

        toggle = new Button(this);
        toggle.setText("▶");
        toggle.setTextSize(16);
        toggle.setTextColor(Color.WHITE);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0xFF2E7D32);
        tb.setCornerRadius(dp(8));
        toggle.setBackground(tb);
        toggle.setMinWidth(0);
        toggle.setMinHeight(0);
        toggle.setMinimumWidth(0);
        toggle.setMinimumHeight(0);
        toggle.setPadding(dp(10), dp(4), dp(10), dp(4));
        toggle.setOnClickListener(v -> onToggle());
        panel.addView(toggle, new LinearLayout.LayoutParams(dp(40), dp(36)));

        if (Riwayat.ENABLED) {
            // private build: "rekam semua" for studying new game mechanics (turns itself off after REC_MINUTES)
            recBtn = new Button(this);
            recBtn.setText("REC");
            recBtn.setTextSize(10);
            recBtn.setTextColor(Color.WHITE);
            GradientDrawable rb = new GradientDrawable();
            rb.setColor(0xFF455A64);
            rb.setCornerRadius(dp(8));
            recBtn.setBackground(rb);
            recBtn.setMinWidth(0);
            recBtn.setMinHeight(0);
            recBtn.setMinimumWidth(0);
            recBtn.setMinimumHeight(0);
            recBtn.setPadding(dp(4), dp(2), dp(4), dp(2));
            recBtn.setOnClickListener(v -> {
                if (!Riwayat.ready()) {
                    Prefs.log("Mulai rekam layar dulu");
                    return;
                }
                Riwayat.setRecAll(!Riwayat.recAll());
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(40), dp(36));
            rl.leftMargin = dp(4);
            panel.addView(recBtn, rl);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);
        statusTv = new TextView(this);
        statusTv.setTextColor(Color.WHITE);
        statusTv.setTextSize(11);
        statusTv.setMaxWidth(dp(170));
        detTv = new TextView(this);
        detTv.setTextColor(0xFFB0BEC5);
        detTv.setTextSize(9);
        detTv.setMaxWidth(dp(170));
        texts.addView(statusTv);
        texts.addView(detTv);
        panel.addView(texts);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        lp.x = 0;
        lp.y = (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.25f);

        // drag the panel by its text area
        texts.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = lp.x;
                        oy = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ox + (int) (e.getRawX() - sx);
                        lp.y = oy + (int) (e.getRawY() - sy);
                        wm.updateViewLayout(panel, lp);
                        return true;
                }
                return false;
            }
        });
        wm.addView(panel, lp);
    }

    private void onToggle() {
        CaptureService cs = CaptureService.instance;
        if (cs == null) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        cs.setActive(!cs.isActive());
    }

    /** Where our panel is on screen (fractions; -1 = unknown): the detector ignores what it covers. */
    static volatile float[] panelRect;
    private final int[] loc = new int[2];
    private final DisplayMetrics rdm = new DisplayMetrics();

    private void publishPanelRect() {
        if (panel == null || wm == null || panel.getWidth() == 0) return;
        panel.getLocationOnScreen(loc);
        wm.getDefaultDisplay().getRealMetrics(rdm);
        float W = rdm.widthPixels, H = rdm.heightPixels;
        if (W <= 0 || H <= 0) return;
        panelRect = new float[]{loc[0] / W, loc[1] / H, (loc[0] + panel.getWidth()) / W, (loc[1] + panel.getHeight()) / H};
    }

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            CaptureService cs = CaptureService.instance;
            String st, dt;
            boolean active = false;
            if (cs == null) {
                st = "Buka app → Mulai rekam layar";
                dt = "";
            } else {
                active = cs.isActive();
                st = active ? cs.statusLine() : "Siap. Buka game lalu tekan ▶";
                dt = cs.debugLine();
            }
            statusTv.setText(st);
            detTv.setText(dt);
            publishPanelRect();
            toggle.setText(active ? "■" : "▶");
            ((GradientDrawable) toggle.getBackground()).setColor(active ? 0xFFC62828 : 0xFF2E7D32);
            if (recBtn != null) {
                boolean rec = Riwayat.recAll();
                recBtn.setText(rec ? "●" + Riwayat.recMinutesLeft() + "m" : "REC");
                ((GradientDrawable) recBtn.getBackground()).setColor(rec ? 0xFFD32F2F : 0xFF455A64);
            }
            main.postDelayed(this, 300);
        }
    };
}
