package com.autofish.fakegame;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * Test harness only: imitates the fishing loop using real frames from the gameplay video, plus a
 * physics-driven minigame bar (hold = zone accelerates up). Used to test the bot end-to-end on an emulator.
 */
public class Game extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(new GameView(this));
    }

    static class GameView extends View {
        static final int MANCING = 0, LEPAS = 1, ANGKAT = 2, STRIKE = 3, TARIK = 4, RESULT = 5;
        final Bitmap[] img = new Bitmap[6];
        int state = MANCING;
        long stateAt = SystemClock.uptimeMillis(), waitMs;
        final Random rnd = new Random(7);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int caught, escaped, missedStrike, minigames;
        long inZoneFrames, gameFrames;

        // minigame (1280x576 space)
        final float tTop = 180, tBot = 467, zoneH = 108;
        float zc, v, fish, fishTarget, fishSpeed, progress;
        boolean holding;
        long lastPhys;
        boolean legendary;

        GameView(Context c) {
            super(c);
            String[] names = {"mancing", "lepas", "angkat", "strike", "tarik", "result"};
            for (int i = 0; i < 6; i++) {
                int id = c.getResources().getIdentifier(names[i], "drawable", c.getPackageName());
                img[i] = BitmapFactory.decodeResource(c.getResources(), id);
            }
        }

        void go(int s) {
            state = s;
            stateAt = SystemClock.uptimeMillis();
            if (s == ANGKAT) waitMs = 1500 + rnd.nextInt(3000);
            if (s == TARIK) {
                minigames++;
                legendary = minigames % 2 == 0; // every 2nd fish has the inverted-controls event
                zc = 400;
                v = 0;
                fish = 300;
                fishTarget = 300;
                fishSpeed = 80;
                progress = 0.3f;
                holding = false;
                lastPhys = SystemClock.uptimeMillis();
            }
            Log.i("FAKEGAME", "state=" + s + " caught=" + caught + " escaped=" + escaped + " missedStrike=" + missedStrike
                    + " inZone=" + (gameFrames > 0 ? (100 * inZoneFrames / gameFrames) : 0) + "%");
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float s = getWidth() / 1280f;
            float x = e.getX() / s, y = e.getY() / s;
            boolean onBtn = Math.hypot(x - 1190, y - 488) < 90;
            int a = e.getActionMasked();
            if (state == TARIK) {
                if (a == MotionEvent.ACTION_DOWN && onBtn) holding = true;
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) holding = false;
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN && state == RESULT) { go(MANCING); return true; }
            if (a == MotionEvent.ACTION_DOWN && onBtn) {
                if (state == MANCING) go(LEPAS);
                else if (state == LEPAS && SystemClock.uptimeMillis() - stateAt > 200) go(ANGKAT);
                else if (state == STRIKE) go(TARIK);
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            long inState = now - stateAt;
            if (state == ANGKAT && inState > waitMs) go(STRIKE);
            else if (state == STRIKE && inState > 1500) {
                missedStrike++;
                go(MANCING);
            }

            Rect dst = new Rect(0, 0, getWidth(), getHeight());
            c.drawBitmap(img[state], null, dst, null);
            float s = getWidth() / 1280f;
            c.save();
            c.scale(s, s);
            if (state == TARIK) {
                physics(now);
                drawBar(c);
            }
            p.setColor(Color.WHITE);
            p.setTextSize(18);
            c.drawText("TEST  caught " + caught + "  escaped " + escaped + "  missed " + missedStrike
                    + (legendary && state == TARIK ? "  [LEGENDARY: Balik! 4-7s]" : ""), 330, 140, p);
            c.restore();
            postInvalidateOnAnimation();
        }

        void physics(long now) {
            float dt = Math.min(0.05f, (now - lastPhys) / 1000f);
            lastPhys = now;
            float trackH = tBot - tTop, A = 2.2f * trackH;
            long t = now - stateAt;
            boolean inverted = legendary && t > 4000 && t < 7000;
            boolean up = holding != inverted;
            v += (up ? -A : A) * dt;
            v = Math.max(-440, Math.min(440, v));
            zc += v * dt;
            if (zc - zoneH / 2 < tTop) { zc = tTop + zoneH / 2; v = Math.max(0, v); }
            if (zc + zoneH / 2 > tBot) { zc = tBot - zoneH / 2; v = Math.min(0, v); }
            if (rnd.nextFloat() < dt * 0.9f) {
                fishTarget = tTop + 20 + rnd.nextFloat() * (trackH - 40);
                fishSpeed = 60 + rnd.nextFloat() * 220;
            }
            float df = fishTarget - fish;
            fish += Math.signum(df) * Math.min(Math.abs(df), fishSpeed * dt);
            boolean in = Math.abs(fish - zc) < zoneH / 2;
            gameFrames++;
            if (in) inZoneFrames++;
            progress += (in ? 0.22f : -0.12f) * dt;
            if (progress >= 1) {
                caught++;
                go(RESULT);
            } else if (progress <= 0) {
                escaped++;
                go(RESULT);
            }
        }

        void drawBar(Canvas c) {
            // cover the bar baked into the frame, then draw ours
            p.setColor(Color.rgb(253, 246, 230));
            c.drawRoundRect(new RectF(760, tTop - 10, 820, tBot + 10), 14, 14, p);
            p.setColor(Color.rgb(40, 138, 152));
            c.drawRect(771, tTop, 809, tBot, p);
            float z0 = zc - zoneH / 2;
            p.setColor(Color.rgb(110, 182, 134));
            c.drawRect(771, z0, 809, z0 + zoneH, p);
            p.setColor(Color.rgb(199, 253, 211));
            c.drawRect(771, z0, 809, z0 + 3, p);
            p.setColor(Color.rgb(8, 30, 22));
            c.drawOval(new RectF(764, fish - 14, 816, fish + 14), p);
            // progress
            p.setColor(Color.rgb(253, 246, 230));
            c.drawRoundRect(new RectF(826, tTop - 10, 856, tBot + 10), 10, 10, p);
            p.setColor(Color.rgb(63, 42, 38));
            c.drawRect(832, tTop, 850, tBot, p);
            p.setColor(progress > 0.8f ? Color.rgb(110, 190, 90) : Color.rgb(244, 209, 90));
            c.drawRect(832, tBot - (tBot - tTop) * progress, 850, tBot, p);
        }
    }
}
