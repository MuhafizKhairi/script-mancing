package com.autofish.wibu;

/**
 * Pure-Java game logic: decides what to tap / hold for every analysed frame.
 * Coordinates handed to {@link Actions} are fractions of the screen (0..1).
 *
 * Fishing cycle seen in the game:
 *   Mancing (blue) -> Lepas! (orange, power meter) -> "..." (blue) -> Angkat (grey, waiting)
 *   -> Strike! (salmon, a fish bites) -> Tarik (green, the hold minigame) -> result card -> Mancing ...
 *
 * Minigame: hold "Tarik" = the green zone accelerates up, release = it falls. Keep the fish inside the zone.
 * The zone behaves like a double integrator, so a time-optimal bang-bang controller is used:
 *   hold  if  x + v|v|/(2a) > 0     (x = zone - fish, y axis pointing down, v = relative velocity)
 * Legendary fish add events (Balik!, Gravitasi!, Pindah!, Goyang! ...). The bar position is re-detected every
 * frame, the acceleration is re-estimated online, and if holding starts making the zone go DOWN the control
 * direction is flipped automatically.
 */
public final class Bot {

    public interface Actions {
        void tap(float fx, float fy);

        void hold(boolean down, float fx, float fy);

        void log(String msg);
    }

    public static final class Config {
        /** Seconds to wait after "Lepas!" appears before tapping it (lets the power meter fill). */
        public float castDelay = 0.45f;
        /** Expected total latency screen->touch, seconds (used to predict ahead). */
        public float latency = 0.10f;
        /** Initial guess of the zone acceleration, in track-heights per second^2. */
        public float accel = 2.2f;
        /** Stop after this many casts (0 = unlimited). */
        public int maxCasts = 0;
        /** If "Angkat" (waiting) lasts longer than this many seconds, tap it to reel in and recast. 0 = never. */
        public float maxWait = 75f;
        /** Stop if "Mancing" is tapped this many times in a row without the cast starting (bait gone?). */
        public int maxFailedStarts = 6;
        /** Stop if nothing recognisable is seen for this many seconds. */
        public float idleStop = 120f;
        /** Where to tap/hold: right offset (fraction of height) and y (fraction of height). */
        public float touchRightOffH = 0.15f, touchFy = 0.85f;
        /** Shift the zone toward an announced dash (yellow warning line). */
        public boolean anticipate = true;
        /** Extra look-ahead on the fish's own movement (s): the zone otherwise trails a moving fish a little. */
        public float fishLead = 0f;
    }

    /** During a dash warning the fish is kept this far (fraction of zone height) inside the zone edge. */
    static final float LEAD_EDGE = 0.05f;
    public final Config cfg;
    public final Detector det = new Detector();
    private final Actions act;

    // ---- public status ----
    public volatile String status = "Siap";
    /** Last controller numbers (debugging). */
    public String dbg = "";
    public int casts, minigames, strikes;
    public boolean stopped;
    public String stopReason = "";

    // ---- cycle state ----
    private long lastTapMs = -100000;
    private int lastTapBtn = -1;
    private long lepasSinceMs = -1, angkatSinceMs = -1, lastKnownMs = -1;
    private int failedStarts, dismissTaps;
    private long lastGameEndMs = -1;
    private boolean sawCastProgress;
    private long lastLepasSeenMs = -100000;
    /** Mancing taps since the last "Angkat" (waiting for a bite): 3+ means casting is stuck. */
    public int mancingTaps;

    // ---- minigame state ----
    private boolean inGame;
    private long gameStartMs, lastBarMs = -1;
    private boolean holding;
    private float frameW = 1, frameH = 1;
    private long prevMs = -1;
    private float zc = Float.NaN, fy, vz, vf;
    private boolean fishKnown;
    private long lastFishMs;
    private int warnCandidate, warnFrames, activeWarn;
    private long warnSeenMs;
    // "Pindah!" marker: height in the track as a fraction (survives the switch to the other bar)
    private int markerFrames;
    private float markerFrac = Float.NaN;
    private long markerSeenMs, markerFirstMs;
    private float lastBarCx = Float.NaN;
    /** Half height of the fish icon (px, smoothed): the whole body must stay inside the zone for the star. */
    private float fishHalf = -1;
    /** Last readable track ends, for steering blind while an effect hides the bar. */
    private float lastTrackTop = -1, lastTrackBot = -1;
    /** The minigame's own bar (first reading with the Tarik button visible). */
    private int gameBarX0, gameBarW = -1, gameTrackH;
    private long noBtnSinceMs = -1;
    /** After a "Pindah!" jump: the fish lands at this height (fraction of the track) - used while it is invisible. */
    private float landFrac = Float.NaN;
    private long landUntilMs;
    /** Legendary "Rintangan!": steer the zone through the gap of the next fish wall (see Detector.findWall). */
    private boolean rintangan;
    private long gapSeenMs, gapHoldUntilMs;
    private float gapC, gapHalf, gapWallX1;
    /** Frames where the fish stayed inside the zone vs. left it (for the "perfect catch" readout). */
    public int perfectMisses;
    private boolean lastInZone = true;
    private float zoneH = 0;
    private final float[] hHist = new float[31];
    private int hN;
    private float aUp, aDown; // px/s^2, magnitudes while holding / releasing
    private boolean inverted;
    private int invEvidence;
    private long pinSinceMs = -1;
    /** Last inversion flip made by the "pinned against a wall" rule, and the direction before it. */
    private long lastPinFlipMs = -1, pinBlockUntilMs = -1;
    private boolean pinPrevInv;
    // window for physics evaluation
    private long winStartMs = -1;
    private float winStartV;
    private int winHoldFrames, winRelFrames;
    private boolean winTouchedEnd;
    // command history for latency compensation
    private final long[] cmdT = new long[64];
    private final boolean[] cmdV = new boolean[64];
    private int cmdN;

    public Bot(Config cfg, Actions act) {
        this.cfg = cfg;
        this.act = act;
    }

    // touch point = centre of the detected button (remembered while it is visible)
    private float touchX = -1, touchY = -1;

    private float tx() {
        return touchX >= 0 ? touchX : 1f - cfg.touchRightOffH * frameH / frameW;
    }

    private float ty() {
        return touchY >= 0 ? touchY : cfg.touchFy;
    }

    public void stop(String why) {
        if (holding) setHold(false, 0);
        stopped = true;
        stopReason = why;
        status = "Berhenti: " + why;
        act.log(status);
    }

    /** Feed one frame. px = ARGB, timestamp in ms. */
    public void onFrame(int[] px, int w, int h, long now) {
        if (stopped) return;
        frameW = w;
        frameH = h;
        det.analyze(px, w, h);
        int btn = det.button;
        if (btn != Detector.BTN_NONE && !holding) {
            touchX = det.btnCx / w;
            touchY = det.btnCy / h;
        }

        // Without the Tarik button (glare over it) only trust a bar where the minigame's bar was (or the "Pindah!" bar
        // next to it), for at most 1.5 s: the catch card has text columns that read as a bar, and following one kept
        // a finished "minigame" alive for 24 s with the card never closed.
        if (btn == Detector.BTN_TARIK) noBtnSinceMs = -1;
        else if (inGame && noBtnSinceMs < 0) noBtnSinceMs = now;
        boolean sameBar = det.barFound && gameBarW > 0
                && Math.abs(det.barX0 - gameBarX0) <= 2.2f * gameBarW
                && Math.abs((det.trackBot - det.trackTop) - gameTrackH) <= 0.15f * gameTrackH;
        boolean barOk = det.barFound && (btn == Detector.BTN_TARIK
                || (inGame && btn == Detector.BTN_NONE && sameBar && now - noBtnSinceMs < 1500));
        if (barOk && btn == Detector.BTN_TARIK && gameBarW <= 0) {
            gameBarX0 = det.barX0;
            gameBarW = det.barX1 - det.barX0 + 1;
            gameTrackH = det.trackBot - det.trackTop;
        }
        if (btn == Detector.BTN_TARIK || barOk) {
            if (!inGame) startGame(now);
            if (barOk) {
                lastBarMs = now;
                control(now);
            } else {
                // Tarik visible but bar not readable (glow rings, lightning, glare...): keep steering on the model of
                // the zone (known acceleration + our own commands) for up to 1.5 s, then pulse
                if (!Float.isNaN(zc) && lastTrackTop >= 0 && now - lastBarMs < 1500) {
                    blindControl(now);
                } else if (now - lastBarMs > 350) {
                    // unknown for a while: pulse so the zone hovers instead of sticking to an end
                    setHold(((now / 120) & 1) == 0, now);
                    status = "Tarik (bar tidak terbaca)";
                } else status = "Tarik (bar tidak terbaca)";
            }
            lastKnownMs = now;
            return;
        }
        if (inGame) {
            // brief flicker; a long legendary fight switches scenes ("Rintangan!", "Ke darat!") behind a banner that
            // can hide the button and the bar for over a second: do not end (and restart) it on that
            long tol = now - gameStartMs > 8000 ? 1800 : 600;
            if (now - lastBarMs < tol && btn == Detector.BTN_NONE) {
                if (holding) setHold(false, now);
                return;
            }
            endGame(now);
        }
        if (holding) setHold(false, now);

        if (btn != Detector.BTN_NONE) {
            lastKnownMs = now;
            dismissTaps = 0;
            if (btn != Detector.BTN_TARIK) lastGameEndMs = -1;
        }
        else if (lastKnownMs > 0 && (now - lastKnownMs) / 1000f > cfg.idleStop) {
            stop("tidak ada tombol pancing terdeteksi selama " + (int) cfg.idleStop + " detik");
            return;
        }
        if (btn != Detector.BTN_LEPAS) lepasSinceMs = -1;
        if (btn != Detector.BTN_ANGKAT) angkatSinceMs = -1;

        switch (btn) {
            case Detector.BTN_MANCING:
                // right after a cast the button turns into a plain blue "..." disc (read as Mancing) for ~0.8 s, sometimes
                // >1.5 s on lag; tapping it re-casts, and the bot looped Mancing -> Lepas -> "..." forever. Wait for it.
                if (now - lastLepasSeenMs < 5000 && now - lastLepasSeenMs >= 0) {
                    status = "Menunggu lemparan";
                    break;
                }
                status = "Mulai mancing";
                if (cfg.maxCasts > 0 && casts >= cfg.maxCasts) {
                    stop("sudah " + casts + " kali lempar");
                    return;
                }
                if (now - lastTapMs > 1500) {
                    if (lastTapBtn == Detector.BTN_MANCING && !sawCastProgress) failedStarts++;
                    if (failedStarts >= cfg.maxFailedStarts) {
                        stop("tombol Mancing tidak merespons (umpan habis?)");
                        return;
                    }
                    sawCastProgress = false;
                    tap(btn, now);
                }
                break;
            case Detector.BTN_LEPAS:
                lastLepasSeenMs = now;
                sawCastProgress = true;
                failedStarts = 0;
                status = "Lempar umpan";
                if (lepasSinceMs < 0) lepasSinceMs = now;
                if ((now - lepasSinceMs) / 1000f >= cfg.castDelay && now - lastTapMs > 900) {
                    tap(btn, now);
                    casts++;
                }
                break;
            case Detector.BTN_ANGKAT:
                lastLepasSeenMs = -100000;
                mancingTaps = 0;
                sawCastProgress = true;
                failedStarts = 0;
                if (angkatSinceMs < 0) angkatSinceMs = now;
                float waited = (now - angkatSinceMs) / 1000f;
                status = String.format(java.util.Locale.US, "Menunggu ikan (%.0fs)", waited);
                if (cfg.maxWait > 0 && waited > cfg.maxWait && now - lastTapMs > 3000) {
                    act.log("Terlalu lama menunggu, angkat & lempar ulang");
                    tap(btn, now);
                    angkatSinceMs = now;
                }
                break;
            case Detector.BTN_STRIKE:
                status = "Strike!";
                if (now - lastTapMs > 250 || lastTapBtn != Detector.BTN_STRIKE) {
                    if (lastTapBtn != Detector.BTN_STRIKE) strikes++;
                    tap(btn, now);
                }
                break;
            default:
                status = "Menunggu...";
                // the catch card ("Ketuk untuk menutup") may hide the button: tap the card to close it
                if (lastGameEndMs > 0 && now - lastGameEndMs > 3500 && dismissTaps < 2 && now - lastTapMs > 2500) {
                    act.tap(0.5f, 0.42f);
                    lastTapMs = now;
                    lastTapBtn = -1;
                    dismissTaps++;
                    act.log("Tutup kartu hasil");
                }
        }
    }

    private void tap(int btn, long now) {
        if (btn == Detector.BTN_MANCING) mancingTaps++;
        act.tap(tx(), ty());
        lastTapMs = now;
        lastTapBtn = btn;
    }

    // ------------------------------------------------------------------ minigame
    private void startGame(long now) {
        inGame = true;
        minigames++;
        gameStartMs = now;
        lastBarMs = now;
        prevMs = -1;
        zc = Float.NaN;
        vz = vf = 0;
        fishKnown = false;
        warnCandidate = warnFrames = activeWarn = 0;
        markerFrames = 0;
        markerFrac = Float.NaN;
        lastBarCx = Float.NaN;
        perfectMisses = 0;
        lastInZone = true;
        hN = 0;
        zoneH = 0;
        aUp = aDown = -1;
        inverted = false;
        invEvidence = 0;
        pinSinceMs = -1;
        lastPinFlipMs = -1;
        pinBlockUntilMs = -1;
        winStartMs = -1;
        cmdN = 0;
        fishHalf = -1;
        gameBarW = -1;
        noBtnSinceMs = -1;
        lastTrackTop = lastTrackBot = -1;
        landFrac = Float.NaN;
        rintangan = false;
        gapHoldUntilMs = 0;
        act.log("Minigame #" + minigames + " mulai");
    }

    private void endGame(long now) {
        inGame = false;
        lastGameEndMs = now;
        setHold(false, now);
        det.resetMemory();
        act.log("Minigame selesai (" + (now - gameStartMs) / 1000f + " s, ikan keluar zona "
                + perfectMisses + "x" + (perfectMisses == 0 ? " = sempurna" : "") + ")");
    }

    private void setHold(boolean down, long now) {
        if (down == holding) return;
        holding = down;
        act.hold(down, tx(), ty());
        cmdT[cmdN % cmdT.length] = now;
        cmdV[cmdN % cmdV.length] = down;
        cmdN++;
    }

    /** Hold state that was commanded at time t (for latency compensation). */
    private boolean holdAt(long t) {
        boolean v = false;
        int start = Math.max(0, cmdN - cmdT.length);
        for (int i = start; i < cmdN; i++) {
            if (cmdT[i % cmdT.length] <= t) v = cmdV[i % cmdV.length];
            else break;
        }
        return v;
    }

    private float medianHeight() {
        int n = Math.min(hN, hHist.length);
        float[] c = new float[n];
        for (int i = 0; i < n; i++) c[i] = hHist[i];
        java.util.Arrays.sort(c);
        return c[n / 2];
    }

    private long lastNow;

    private void control(long now) {
        lastNow = now;
        Detector d = det;
        float trackH = Math.max(10, d.trackBot - d.trackTop);
        if (aUp <= 0) aUp = aDown = cfg.accel * trackH;
        lastTrackTop = d.trackTop;
        lastTrackBot = d.trackBot;
        if (d.fishVisible && !d.fishFromOutline && d.fishBot > d.fishTop) {
            float hh = Math.max(0.03f * trackH, Math.min(0.10f * trackH, (d.fishBot - d.fishTop) / 2f));
            fishHalf = fishHalf < 0 ? hh : fishHalf + 0.1f * (hh - fishHalf);
        }

        // --- robust zone centre (the fish icon can hide one end of the zone) ---
        float hMeas = d.zoneBot - d.zoneTop;
        // (in the "Rintangan!" scene the glow and the gap line often stretch the reading: keep the known height)
        if (!(rintangan && hN > 8 && hMeas > 1.2f * zoneH)) {
            hHist[hN % hHist.length] = hMeas;
            hN++;
        }
        zoneH = medianHeight();
        float center = (d.zoneTop + d.zoneBot) / 2f;
        if (hN > 4 && Math.abs(hMeas - zoneH) > 0.12f * zoneH) {
            float cTop = d.zoneTop + zoneH / 2f, cBot = d.zoneBot - zoneH / 2f;
            float pred = Float.isNaN(zc) ? center : zc + vz * Math.max(0, (now - prevMs) / 1000f);
            center = Math.abs(cTop - pred) < Math.abs(cBot - pred) ? cTop : cBot;
        }
        float half = Math.max(2, zoneH / 2f);
        center = Math.max(d.trackTop + half, Math.min(d.trackBot - half, center));

        // --- velocities ---
        float dt = prevMs < 0 ? 0 : (now - prevMs) / 1000f;
        boolean fishSeen = d.fishVisible && !Float.isNaN(d.fishY);
        if (!Float.isNaN(zc) && dt > 0.001f && dt < 0.25f) {
            float k = (float) (1 - Math.exp(-dt / 0.05));
            vz += k * ((center - zc) / dt - vz);
            if (fishSeen && fishKnown) {
                if (Math.abs(d.fishY - fy) > 0.22f * trackH) {
                    vf = 0; // the fish jumped (into the other "Pindah!" bar / a dash landed): not a speed
                } else {
                    float kf = (float) (1 - Math.exp(-dt / 0.08));
                    vf += kf * ((d.fishY - fy) / dt - vf);
                }
            }
        } else {
            vz = 0;
            vf = 0;
        }
        zc = center;
        if (fishSeen) {
            fy = d.fishY;
            fishKnown = true;
            lastFishMs = now;
        } else if (!Float.isNaN(landFrac) && now < landUntilMs) {
            // just jumped into the other "Pindah!" bar but invisible (ghost / glow): it lands at the ring's height
            fy = d.trackTop + landFrac * trackH;
            vf = 0;
            fishKnown = true;
        } else if (fishKnown && now - lastFishMs < 1500) {
            // fish faded out: keep following where it was heading, slowing down
            fy = Math.max(d.trackTop, Math.min(d.trackBot, fy + vf * dt));
            vf *= (float) Math.exp(-dt / 0.5);
        } else {
            // never seen / gone too long: hover in the middle of the track
            fy = (d.trackTop + d.trackBot) / 2f;
            vf = 0;
            fishKnown = false;
        }
        prevMs = now;

        // --- legendary dash warning (yellow line near the top/bottom end) ---
        if (d.warnDir != 0) {
            if (d.warnDir == warnCandidate) warnFrames++;
            else {
                warnCandidate = d.warnDir;
                warnFrames = 1;
            }
            if (warnFrames >= 2) {
                if (activeWarn != warnCandidate) act.log(warnCandidate > 0 ? "Awas: ikan mau melesat ke BAWAH" : "Awas: ikan mau melesat ke ATAS");
                activeWarn = warnCandidate; // switches immediately if the warning jumps to the other end (feint)
                warnSeenMs = now;
            }
        } else {
            warnFrames = 0;
            warnCandidate = 0;
        }
        if (activeWarn != 0 && now - warnSeenMs > 600) activeWarn = 0;

        // --- "Pindah!": a second bar with a yellow ring; the fish will jump there at the ring's height ---
        float barCx = (d.barX0 + d.barX1) / 2f;
        boolean barMoved = !Float.isNaN(lastBarCx) && Math.abs(barCx - lastBarCx) > 0.8f * (d.barX1 - d.barX0 + 1);
        lastBarCx = barCx;
        if (!Float.isNaN(d.markerY)) {
            markerFrames++;
            if (markerFrames >= 2) {
                if (Float.isNaN(markerFrac)) {
                    act.log("Pindah: ikan akan lompat ke bar sebelah");
                    markerFirstMs = now;
                }
                markerFrac = (d.markerY - d.trackTop) / trackH;
                markerSeenMs = now;
            }
        } else markerFrames = 0;
        if (!Float.isNaN(markerFrac) && barMoved) {
            landFrac = markerFrac;
            landUntilMs = now + 700;
        }
        if (!Float.isNaN(markerFrac) && (barMoved || now - markerSeenMs > 500)) markerFrac = Float.NaN;

        boolean atTop = d.zoneTop <= d.trackTop + 2, atBot = d.zoneBot >= d.trackBot - 2;

        // --- physics check over ~160ms windows: acceleration magnitude + direction (for "Balik!") ---
        boolean lagHold = holdAt(now - (long) (cfg.latency * 1000));
        if (winStartMs < 0) {
            winStartMs = now;
            winStartV = vz;
            winHoldFrames = winRelFrames = 0;
            winTouchedEnd = false;
        }
        if (lagHold) winHoldFrames++;
        else winRelFrames++;
        winTouchedEnd |= atTop || atBot;
        float wdt = (now - winStartMs) / 1000f;
        if (wdt >= 0.16f) {
            int tot = winHoldFrames + winRelFrames;
            boolean pure = tot >= 3 && (winHoldFrames == tot || winRelFrames == tot);
            if (pure && !winTouchedEnd && now - gameStartMs > 500) {
                float acc = (vz - winStartV) / wdt; // px/s^2, positive = downward
                float expectSign = winHoldFrames == tot ? -1 : 1; // hold -> up (negative y)
                if (inverted) expectSign = -expectSign;
                boolean holdWin = winHoldFrames == tot;
                float aRef = holdWin != inverted ? aUp : aDown;
                if (Math.abs(acc) > 0.35f * aRef) {
                    if (Math.signum(acc) != expectSign) invEvidence++;
                    else {
                        invEvidence = 0;
                        // refine acceleration estimate for this direction
                        float a = Math.abs(acc);
                        a = Math.max(0.4f * cfg.accel * trackH, Math.min(4f * cfg.accel * trackH, a));
                        if (expectSign < 0) aUp += 0.25f * (a - aUp);
                        else aDown += 0.25f * (a - aDown);
                    }
                    if (invEvidence >= 3) {
                        inverted = !inverted;
                        invEvidence = 0;
                        act.log(inverted ? "Kontrol terbalik terdeteksi (Balik!)" : "Kontrol normal lagi");
                    }
                }
            }
            winStartMs = -1;
        }

        // --- pinned against a wall while pushing away from it => controls are inverted ---
        boolean effUp = lagHold != inverted;
        if ((atBot && effUp) || (atTop && !effUp)) {
            if (pinSinceMs < 0) pinSinceMs = now;
            else if (now - pinSinceMs > 600 && now - gameStartMs > 1000 && now >= pinBlockUntilMs) {
                if (lastPinFlipMs > 0 && now - lastPinFlipMs < 1600) {
                    // still pinned after flipping: the zone does not respond either way (held/frozen by a boss
                    // event), it is not "Balik!". Flipping back and forth every 0.7 s only sent the zone the wrong
                    // way once it was free again, so restore the previous direction and wait.
                    inverted = pinPrevInv;
                    lastPinFlipMs = -1;
                    pinBlockUntilMs = now + 2500;
                    act.log("Zona tertahan di ujung (bukan Balik), kontrol " + (inverted ? "tetap terbalik" : "normal"));
                } else {
                    pinPrevInv = inverted;
                    inverted = !inverted;
                    lastPinFlipMs = now;
                    act.log(inverted ? "Kontrol terbalik terdeteksi (Balik!)" : "Kontrol normal lagi");
                }
                invEvidence = 0;
                pinSinceMs = -1;
            }
        } else pinSinceMs = -1;

        // --- legendary "Rintangan!": walls of fish scroll into the bar; the zone must be inside the gap ---
        if (d.gapFound) {
            float c = (d.gapTop + d.gapBot) / 2f, hg = (d.gapBot - d.gapTop) / 2f;
            // the nearest wall jumped far to the left = the previous wall is now passing over the bar: keep its gap
            // until it is through (~0.3 s at the walls' speed), only then turn to the next one
            if (rintangan && now - gapSeenMs < 400 && d.wallX1 < gapWallX1 - 60 && Math.abs(c - gapC) > 3)
                gapHoldUntilMs = now + 320;
            if (now >= gapHoldUntilMs) {
                gapC = c;
                gapHalf = hg;
            }
            gapWallX1 = d.wallX1;
            gapSeenMs = now;
            if (!rintangan) act.log("Rintangan: zona dibawa lewat celah tembok ikan");
            rintangan = true;
        } else if (rintangan && !d.skyMode && now - gapSeenMs > 1500) {
            rintangan = false;
            act.log("Rintangan selesai");
        }
        if (rintangan && now - gapSeenMs < 2500) {
            float target = now - gapSeenMs < 700 || now < gapHoldUntilMs ? gapC : (d.trackTop + d.trackBot) / 2f;
            float g = now - gapSeenMs < 700 ? Math.max(1f, gapHalf - zoneH / 2f - 2f) : Math.max(2f, 0.15f * trackH);
            lastLead = 0;
            boolean hold = decide(target, 0, 0, g);
            setHold(hold, now);
            lastInZone = true; // the fish is not what counts in this phase
            status = String.format(java.util.Locale.US, "Tarik %s%s  [rintangan] celah %.0f-%.0f", hold ? "TAHAN" : "lepas",
                    inverted ? " [terbalik]" : "", gapC - gapHalf, gapC + gapHalf);
            return;
        }

        // --- time-optimal bang-bang on the predicted state ---
        // While a dash is announced, aim the zone so the fish sits near the edge facing AWAY from the warned end:
        // the fish stays inside the zone (no progress lost if it is a feint) and the zone is already shifted
        // toward where the fish will dash.
        float maxLead = Math.max(0, zoneH / 2f - Math.max(3f, LEAD_EDGE * zoneH));
        float lead = (cfg.anticipate ? activeWarn : 0) * maxLead;
        float fh = fishHalf > 0 ? fishHalf : 0.07f * trackH;
        // how far the zone centre may be from the fish centre with the WHOLE fish still inside (star rule)
        float slack = Math.max(2f, zoneH / 2f - fh);
        if (cfg.anticipate && !Float.isNaN(markerFrac)) {
            // lean toward the height it will land at, but never so far that part of the fish leaves the zone
            float target = d.trackTop + markerFrac * trackH;
            float maxP = Math.max(0, guardOf(slack) - 2f);
            lead = Math.max(-maxP, Math.min(maxP, target - fy));
        }
        // the dash lead deliberately sits near the edge (legendary only, the game tolerates it): no guard then
        lastLead = lead;
        boolean hold = decide(fy, vf, lead, activeWarn == 0 ? guardOf(slack) : Float.MAX_VALUE);
        setHold(hold, now);

        float zTop = zc - zoneH / 2f, zBot = zc + zoneH / 2f;
        // the whole body must be inside (1 px tolerance for measuring noise); a faded fish only has its centre
        boolean inZone = d.fishFromOutline ? fy >= zTop && fy <= zBot : fy - fh >= zTop - 1 && fy + fh <= zBot + 1;
        if (fishSeen) {
            if (!inZone && lastInZone) perfectMisses++;
            lastInZone = inZone;
        }
        status = String.format(java.util.Locale.US, "Tarik %s%s%s%s  ikan %s", hold ? "TAHAN" : "lepas",
                inverted ? " [terbalik]" : "", activeWarn > 0 ? " [awas bawah]" : activeWarn < 0 ? " [awas atas]" : "",
                !Float.isNaN(markerFrac) ? " [pindah]" : "",
                !fishSeen ? "tak terlihat" : inZone ? "di zona" : "di luar");
    }

    /** Offset (zone centre - fish centre) beyond which the guard steps in: a safety margin inside the slack. */
    private static float guardOf(float slack) {
        return Math.max(1f, slack - Math.max(2f, 0.2f * slack));
    }

    /**
     * Time-optimal bang-bang toward fish+lead on the latency-predicted state, plus a guard: if the fish is about to
     * leave through an end of the zone and is not already coming back, push the zone toward it. Returns the hold state.
     */
    private boolean decide(float fish, float fishV, float lead, float guard) {
        // The first ~0.9 s overshot in 3 of 3 logged near-misses (zone rising past a fish that starts drifting
        // down; the first press reacts later than the rest): predict further ahead until the physics is measured.
        float L = cfg.latency + (lastNow - gameStartMs < 900 ? 0.08f : 0f);
        float relV = vz - fishV;
        float zP = zc + vz * L, fP = fish + fishV * (L + cfg.fishLead);
        float x = zP - (fP + lead); // >0: zone is below the target
        // braking acceleration: moving up (relV<0) is stopped by gravity, moving down by holding
        float aBrake = relV < 0 ? aDown : aUp;
        float s = x + relV * Math.abs(relV) / (2f * aBrake);
        float dead = 0.06f * zoneH;
        boolean wantUp;
        if (s > dead) wantUp = true;
        else if (s < -dead) wantUp = false;
        else wantUp = inverted != holding; // keep current
        float rel = zP - fP;
        dbg = String.format(java.util.Locale.US, "zc=%.1f vz=%.0f fy=%.1f vf=%.0f lead=%.1f x=%.1f s=%.1f rel=%.1f guard=%.1f aU=%.0f aD=%.0f inv=%b", zc, vz, fish, fishV, lead, x, s, rel, guard, aUp, aDown, inverted);
        if (rel > guard && relV >= 0) wantUp = true;
        else if (rel < -guard && relV <= 0) wantUp = false;
        return inverted ? !wantUp : wantUp;
    }

    /** Bar hidden by an effect: advance the zone model with our own (delayed) commands and keep steering. */
    private void blindControl(long now) {
        lastNow = now;
        float dt = prevMs < 0 ? 0 : Math.min(0.1f, (now - prevMs) / 1000f);
        prevMs = now;
        boolean effUp = holdAt(now - (long) (cfg.latency * 1000)) != inverted;
        vz += (effUp ? -aUp : aDown) * dt;
        zc += vz * dt;
        float half = Math.max(2, zoneH / 2f), trackH = Math.max(10, lastTrackBot - lastTrackTop);
        if (zc < lastTrackTop + half) {
            zc = lastTrackTop + half;
            vz = Math.max(0, vz);
        }
        if (zc > lastTrackBot - half) {
            zc = lastTrackBot - half;
            vz = Math.min(0, vz);
        }
        // the ring has been up long enough and is gone: the fish jumped to its height
        if (!Float.isNaN(markerFrac) && now - markerSeenMs > 150 && now - markerFirstMs > 700) {
            landFrac = markerFrac;
            landUntilMs = now + 700;
            markerFrac = Float.NaN;
        }
        if (!Float.isNaN(landFrac) && now < landUntilMs) {
            fy = lastTrackTop + landFrac * trackH;
            vf = 0;
        } else {
            fy = Math.max(lastTrackTop, Math.min(lastTrackBot, fy + vf * dt));
            vf *= (float) Math.exp(-dt / 0.5);
        }
        float fh = fishHalf > 0 ? fishHalf : 0.07f * trackH;
        lastLead = 0;
        if (rintangan && now - gapSeenMs < 700) // a wall over the bar hides it: keep the zone in that wall's gap
            setHold(decide(gapC, 0, 0, Math.max(1f, gapHalf - zoneH / 2f - 2f)), now);
        else setHold(decide(fy, vf, 0, guardOf(Math.max(2f, zoneH / 2f - fh))), now);
        status = "Tarik (bar tertutup efek, kendali perkiraan)";
    }

    private float lastLead;

    /** Compact controller state for the on-phone history: "zc vz fy*10 vf lead inv warn mark". */
    void traceTail(StringBuilder sb) {
        sb.append(Math.round(zc)).append(' ').append(Math.round(vz)).append(' ').append(Math.round(fy * 10)).append(' ')
                .append(Math.round(vf)).append(' ').append(Math.round(lastLead)).append(' ').append(inverted ? 1 : 0)
                .append(' ').append(activeWarn).append(' ')
                .append(Float.isNaN(markerFrac) ? "-" : String.valueOf(Math.round(markerFrac * 100)));
    }

    public boolean isInGame() {
        return inGame;
    }

    public boolean isHolding() {
        return holding;
    }

    public boolean isInverted() {
        return inverted;
    }
}
