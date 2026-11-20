package com.autofish.wibu;

/**
 * Pure-Java screen analyser (no Android dependencies, so it can be unit-tested on a PC).
 * Input: ARGB pixels of a (downscaled) landscape screenshot.
 *
 * Finds:
 *  - the state of the big round action button in the bottom-right corner
 *    (Mancing / Lepas! / Angkat / Strike! / Tarik)
 *  - the fishing minigame bar: the light-green "catch zone", the dark fish icon, and the track bounds.
 */
public final class Detector {

    public static final int BTN_NONE = 0, BTN_MANCING = 1, BTN_LEPAS = 2, BTN_ANGKAT = 3, BTN_STRIKE = 4, BTN_TARIK = 5;
    public static final String[] BTN_NAMES = {"-", "Mancing", "Lepas", "Angkat", "Strike", "Tarik"};

    // Reference colours of the action button (sampled from the game), plus "not a button" colours.
    private static final int[][] PALETTE = {
            {47, 122, 57, BTN_TARIK},
            {136, 148, 157, BTN_ANGKAT},
            {241, 138, 111, BTN_STRIKE},
            {253, 164, 74, BTN_LEPAS},
            {14, 111, 210, BTN_MANCING},
            // non-button references (water, popup, sand...)
            {40, 122, 153, BTN_NONE},
            {31, 114, 131, BTN_NONE},
            {60, 150, 180, BTN_NONE},
            {250, 245, 235, BTN_NONE},
            {225, 200, 150, BTN_NONE},
            {30, 40, 100, BTN_NONE},
    };

    // ---- tunables (fractions) ----
    /** Button centre: distance from the RIGHT edge as a fraction of screen height. */
    public float btnRightOffH = 0.141f;
    /** Button centre: y as a fraction of screen height. */
    public float btnFy = 0.863f;
    /** Button radius as a fraction of screen height. */
    public float btnRadiusH = 0.105f;

    // ---- results ----
    public int button;
    public int btnR, btnG, btnB, btnDist;
    public float btnCx, btnCy; // in pixels

    public boolean barFound;
    public int barX0, barX1, trackTop, trackBot, zoneTop, zoneBot, fishTop, fishBot, fishCount;
    public float fishY;
    /** False when the bar was found but the fish icon is not visible (e.g. faded); fishY is NaN then. */
    public boolean fishVisible;
    /** Legendary dash warning: yellow line near the bottom (+1) or top (-1) of the track, 0 = none. */
    public int warnDir;
    /**
     * "Pindah!" (epic and above): a second bar appears next to the active one with a yellow ring marker; the fish
     * then jumps into that bar at the marker's height (the zone keeps its height). markerY = the marker's y in
     * pixels (NaN = none), markerSide = -1 left / +1 right of the active bar.
     */
    public float markerY = Float.NaN;
    public int markerSide;
    /** True when the fish position came from the shadow it casts over the bar's outline (faded/glowing fish). */
    public boolean fishFromOutline;
    public String fail = "";

    /** True when the last bar result came from the tint-robust fallback. */
    public boolean relMode;

    // last bar geometry seen with normal colours (kept across minigames)
    private boolean geoValid;
    private int geoX0, geoX1, geoTop, geoBot, geoW, geoH;

    private boolean memValid;
    private int memX0, memX1, memTop, memBot;
    /** Track length of the last bar read with both ends visible (kept across minigames; all bars share it). */
    private int knownTrackH = -1;

    /** Forget the remembered track position (call when a minigame ends). */
    public void resetMemory() {
        memValid = false;
    }

    private int[] colCount = new int[0];
    private int[] ghostRow = new int[0];
    private int[] rowFlag = new int[0];

    static boolean isZone(int r, int g, int b) {
        return g >= 140 && g - r >= 38 && g - b >= 18 && b >= 85 && r >= 55;
    }

    static boolean isDark(int r, int g, int b) {
        return r < 75 && g < 75 && b < 75;
    }

    /** Translucent "ghost" fish: greyish (small g-r), blue >= green, not dark, not the warm-white outline. */
    static boolean isGhost(int r, int g, int b) {
        int l = (r * 3 + g * 6 + b) / 10;
        return l > 80 && g - r < 36 && b >= g - 4 && !(r > 200 && r >= b - 10) && !isTeal(r, g, b);
    }

    /** Yellow warning-line pixel (pure yellow over the teal track, or yellow-green over the zone). */
    static boolean isWarnYellow(int r, int g, int b) {
        return r >= 145 && g >= 150 && b <= Math.min(r, g) - 40;
    }

    /**
     * Legendary fish warn before dashing: a thin yellow line appears ~6px inside the top or bottom end of the
     * track. Only rows near the ends are checked, and the line must be thin (1-3 rows), so the zone's broad
     * yellowish shimmer is not mistaken for it.
     */
    private int findWarning(int[] px, int w, int cx0, int cx1, int tTop, int tBot) {
        int span = Math.max(8, (tBot - tTop) / 9);
        int top = warnBand(px, w, cx0, cx1, tTop + 1, tTop + span);
        int bot = warnBand(px, w, cx0, cx1, tBot - span, tBot - 1);
        if (top == bot) return 0;
        return bot > top ? 1 : -1;
    }

    private int warnBand(int[] px, int w, int cx0, int cx1, int y0, int y1) {
        int cw = cx1 - cx0 + 1, run = 0, best = 0;
        boolean found = false;
        for (int y = y0; y <= y1; y++) {
            int n = 0;
            for (int x = cx0; x <= cx1; x++) {
                int c = px[y * w + x];
                if (isWarnYellow((c >> 16) & 255, (c >> 8) & 255, c & 255)) n++;
            }
            if (n >= 0.6f * cw) {
                run++;
                found = true;
            } else {
                if (run > 0 && run <= 3) best = 1;
                if (run > 3) return 0; // thick band = shimmer, not the warning line
                run = 0;
            }
        }
        if (run > 0 && run <= 3) best = 1;
        return found ? best : 0;
    }

    /** Yellow of the "Pindah!" ring marker (also when dimmed or tinted); excludes cream, orange and green. */
    static boolean isMarkerYellow(int r, int g, int b) {
        return g >= 90 && g * 100 >= r * 78 && r * 100 >= g * 88 && Math.min(r, g) - b >= 45 && b * 100 <= g * 62;
    }

    private int yellowCount(int[] px, int w, int y, int ax0, int ax1) {
        int n = 0;
        for (int x = ax0; x <= ax1; x++) {
            int c = px[y * w + x];
            if (isMarkerYellow((c >> 16) & 255, (c >> 8) & 255, c & 255)) n++;
        }
        return n;
    }

    /** Width of the track interior as measured with normal colours (the "Pindah!" bar can read wider). */
    private int refWidth(int cw) {
        return geoValid ? geoX1 - geoX0 + 1 : cw;
    }

    /** Distance between the two bars of "Pindah!" (one outer bar width), measured: 30px for an 18px track at 640. */
    private int barPitch(int cw) {
        return Math.round(refWidth(cw) * 1.67f);
    }

    /** Looks for the yellow ring marker of a second bar one bar-pitch left/right of the active bar. */
    private void findMarker(int[] px, int w, int h, int x0, int x1, int tTop, int tBot) {
        markerY = Float.NaN;
        markerSide = 0;
        int cw = x1 - x0 + 1, pitch = barPitch(cw);
        int mid = (x0 + x1) / 2;
        x0 = mid - (Math.min(cw, refWidth(cw)) - 1) / 2;
        x1 = x0 + Math.min(cw, refWidth(cw)) - 1;
        int bestCov = 0;
        // From the HOME bar the second bar only ever appears on the left (right of it is the yellow progress bar,
        // which produced false "Pindah" rings); from the left bar the way back is to the right.
        boolean atHome = geoValid && Math.abs(mid - (geoX0 + geoX1) / 2) <= Math.max(4, pitch / 3);
        boolean atLeft = geoValid && mid < (geoX0 + geoX1) / 2 - pitch / 2;
        for (int side = -1; side <= 1; side += 2) {
            if ((atHome && side > 0) || (atLeft && side < 0)) continue;
            int ax0 = x0 + side * pitch + 2, ax1 = x1 + side * pitch - 2;
            if (ax0 < 0 || ax1 >= w || ax1 - ax0 < 3) continue;
            int aw = ax1 - ax0 + 1, run = 0, runStart = -1, runCov = 0;
            for (int y = tTop + 3; y <= tBot - 2; y++) {
                int cnt = y <= tBot - 3 ? yellowCount(px, w, y, ax0, ax1) : 0;
                if (cnt >= 0.7f * aw) {
                    if (run == 0) {
                        runStart = y;
                        runCov = 0;
                    }
                    run++;
                    runCov = Math.max(runCov, cnt);
                    continue;
                }
                // a thin, full-width horizontal line (the ring's bar) with ring sides above and below it;
                // keep the fullest one (the ring's top/bottom arcs can also look like short lines)
                if (run >= 1 && run <= 4 && runCov > bestCov) {
                    int yc = runStart + run / 2;
                    int up = yc - 6 >= 0 ? yellowCount(px, w, yc - 6, ax0, ax1) : 0;
                    int dn = yc + 6 < h ? yellowCount(px, w, yc + 6, ax0, ax1) : 0;
                    if (up < 0.35f * aw && dn < 0.35f * aw && ringAround(px, w, h, ax0, ax1, runStart, runStart + run - 1)) {
                        bestCov = runCov;
                        markerY = runStart + (run - 1) / 2f;
                        markerSide = side;
                    }
                }
                run = 0;
            }
        }
    }

    private static boolean looseYellow(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return g > 70 && r * 10 >= g * 9 && g * 100 >= r * 75 && Math.min(r, g) - b >= 35;
    }

    /**
     * The marker is a ring crossed by the line: just above and below the line there must be yellow ring sides at
     * the left and right (at the bar's edges) but not in the middle. A yellow badge/label fails this.
     */
    private boolean ringAround(int[] px, int w, int h, int ax0, int ax1, int lineTop, int lineBot) {
        int cx = (ax0 + ax1) / 2, left = 0, right = 0, mid = 0, above = 0, below = 0;
        for (int d = 2; d <= 6; d++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int y = sgn < 0 ? lineTop - d : lineBot + d;
                if (y < 0 || y >= h) continue;
                boolean l = false, r = false, m = false;
                for (int x = Math.max(0, ax0 - 7); x <= ax0 + 1; x++) l |= looseYellow(px[y * w + x]);
                for (int x = ax1 - 1; x <= Math.min(w - 1, ax1 + 7); x++) r |= looseYellow(px[y * w + x]);
                for (int x = cx - 1; x <= cx + 1; x++) m |= looseYellow(px[y * w + x]);
                if (l) left++;
                if (r) right++;
                if (m) mid++;
                if (l || r) {
                    if (sgn < 0) above++;
                    else below++;
                }
            }
        }
        return left >= 2 && right >= 2 && mid <= 2 && above >= 1 && below >= 1;
    }

    private final float[] occProf = new float[2048];

    /**
     * Faded ("Hantu") or glowing ("Berkilau", light mutations) fish lose their dark colour, but the icon is wider
     * than the bar and still covers the bright outline to the right of the track. Returns the centre of that
     * fish-sized dip in outline brightness, or NaN.
     */
    private float outlineFish(int[] px, int w, int h, int x0, int x1, int tTop, int tBot) {
        int y0 = tTop + 2, y1 = tBot - 2, n = y1 - y0 + 1;
        if (n < 10 || n > occProf.length) return Float.NaN;
        int cw = x1 - x0 + 1, span = Math.max(4, cw / 3);
        float[] colMed = new float[span];
        int[] tmp = new int[(n + 1) / 2 + 1];
        float mx = 0;
        for (int i = 0; i < span; i++) {
            int x = x1 + 1 + i;
            if (x >= w) return Float.NaN;
            int k = 0;
            for (int y = y0; y <= y1; y += 2) tmp[k++] = luma(px[y * w + x]);
            colMed[i] = median(tmp, k);
            mx = Math.max(mx, colMed[i]);
        }
        // the outline must be clearly brighter than the track interior
        int k = 0;
        int cx = (x0 + x1) / 2;
        for (int y = y0; y <= y1; y += 2) tmp[k++] = luma(px[y * w + cx]);
        if (mx < 1.2f * median(tmp, k) || mx < 40) return Float.NaN;
        for (int y = y0; y <= y1; y++) {
            float s = 0;
            int m = 0;
            for (int i = 0; i < span; i++)
                if (colMed[i] >= 0.85f * mx) {
                    s += luma(px[y * w + x1 + 1 + i]);
                    m++;
                }
            occProf[y - y0] = s / m;
        }
        float[] sorted = java.util.Arrays.copyOf(occProf, n);
        java.util.Arrays.sort(sorted);
        float med = sorted[n / 2], thr = 0.85f * med;
        if (DEBUG) {
            StringBuilder sb = new StringBuilder("occ mx=" + mx + " med=" + med + " cols=" + java.util.Arrays.toString(colMed) + "\n");
            for (int q = 0; q < n; q += 2) sb.append(y0 + q).append(':').append((int) occProf[q]).append(' ');
            System.out.println(sb);
        }
        float best = 0, bestC = Float.NaN;
        int i = 0;
        while (i < n) {
            if (occProf[i] >= thr) {
                i++;
                continue;
            }
            int s0 = i, last = i, gap = 0;
            float deficit = 0, minV = Float.MAX_VALUE;
            double wsum = 0;
            while (i < n) {
                if (occProf[i] < thr) {
                    minV = Math.min(minV, occProf[i]);
                    float d = med - occProf[i];
                    deficit += d;
                    wsum += (double) d * i;
                    last = i;
                    gap = 0;
                } else if (++gap > 2) break;
                i++;
            }
            int hgt = last - s0 + 1;
            if (hgt >= 0.015f * h && hgt <= 0.16f * h && minV < 0.75f * med && deficit > best) {
                best = deficit;
                bestC = (float) (y0 + wsum / deficit);
            }
        }
        return bestC;
    }

    static boolean isBorder(int r, int g, int b) {
        return r > 215 && g > 210 && b > 195;
    }

    static boolean isTeal(int r, int g, int b) {
        return b >= g - 8 && g - r >= 50 && b > 90;
    }

    /** Are most rows y0..y1 covered by the fish (dark, faded grey, or white glare) rather than empty track? */
    private static boolean gapIsFish(int[] px, int w, int x0, int x1, int y0, int y1) {
        int cw = x1 - x0 + 1, fishy = 0;
        int[] xs = {x0 + cw / 4, (x0 + x1) / 2, x1 - cw / 4};
        for (int y = y0; y <= y1; y++) {
            int n = 0;
            for (int x : xs) {
                int c = px[y * w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if (isDark(r, g, b) || isGhost(r, g, b) || isEdge(r, g, b)) n++;
            }
            if (n >= 2) fishy++;
        }
        return fishy * 2 >= y1 - y0 + 1;
    }

    /** Empty track: neither zone nor fish (teal, or the zone's fading glow). */
    private static boolean plainAt(int[] px, int w, int h, int x, int y) {
        if (y < 0 || y >= h) return false;
        int c = px[y * w + x];
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return !isZone(r, g, b) && !isDark(r, g, b) && !isEdge(r, g, b);
    }

    /**
     * Our own floating panel as fractions of the screen (set by the app, -1 = unknown). It is captured with the game
     * and darkens whatever it covers (the far-left "Pindah!" bars run under it): those pixels are ignored.
     */
    public volatile float maskFx0 = -1, maskFy0, maskFx1, maskFy1;
    private int mX0 = -1, mY0, mX1, mY1;

    private boolean masked(int x, int y) {
        return mX0 >= 0 && x >= mX0 && x <= mX1 && y >= mY0 && y <= mY1;
    }

    public void analyze(int[] px, int w, int h) {
        float f0 = maskFx0;
        if (f0 >= 0) {
            mX0 = (int) (f0 * w) - 2;
            mY0 = (int) (maskFy0 * h) - 2;
            mX1 = (int) (maskFx1 * w) + 2;
            mY1 = (int) (maskFy1 * h) + 2;
        } else mX0 = -1;
        classifyButton(px, w, h);
        findBar(px, w, h);
        relMode = false;
        // Map events (Aurora, red, rainbow, purple ...) tint the bar so fixed colours no longer match.
        // During the minigame, fall back to reading the bar at its known position with relative comparisons.
        if (!barFound && button == BTN_TARIK) findBarRelative(px, w, h);
        skyMode = gapFound = false;
        if (barFound) findWall(px, w, h);
    }

    // ------------------------------------------------------------------ legendary "Rintangan!" (fish walls)
    /**
     * Legendary phase "Rintangan! Lewati celah tembok ikan": the scene turns into a sky, and walls (columns of fish
     * icons on dark teal, cream caps at the gap) scroll from the left into the bar. When a wall reaches the bar the
     * ZONE must be inside its gap ("TURUN/NAIK/PAS!" is drawn at the zone's height; a hit costs "Tali -2..3%").
     * skyMode = the sky scene is behind the bar; gapFound = the nearest wall left of the bar and its open gap (px).
     */
    public boolean skyMode, gapFound;
    public int wallX0, wallX1, gapTop, gapBot;

    static boolean isSky(int r, int g, int b) {
        return r >= 75 && r <= 140 && g >= 165 && b >= 180 && b - r >= 55;
    }

    static boolean isCloud(int r, int g, int b) {
        return r >= 170 && g >= 205 && b >= 195 && b >= r + 8 && g >= r + 12;
    }

    static boolean isWallTeal(int r, int g, int b) {
        return r <= 75 && g >= 55 && g <= 130 && b >= 60 && b <= 145 && g - r >= 25 && Math.abs(b - g) <= 30;
    }

    static boolean isCap(int r, int g, int b) {
        return r >= 225 && g >= 210 && b >= 165 && b <= 225 && r - b >= 25;
    }

    private final int[] wallCol = new int[4096];

    private void findWall(int[] px, int w, int h) {
        int y0 = trackTop, y1 = trackBot, th = y1 - y0;
        int xs = Math.max(2, (int) (0.012f * w)), xe = barX0 - 6;
        if (xe - xs < 0.1f * w || th < 20 || xe >= wallCol.length) return;
        int sky = 0, tot = 0;
        for (int y = y0; y <= y1; y += 3)
            for (int x = xs; x <= xe; x += 3) {
                int c = px[y * w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                tot++;
                if (isSky(r, g, b) || isCloud(r, g, b)) sky++;
            }
        if (sky < 0.30f * tot) return;
        skyMode = true;
        int need = Math.max(4, (int) (0.12f * th));
        for (int x = xs; x <= xe; x++) {
            int n = 0;
            for (int y = y0; y <= y1; y++) {
                int c = px[y * w + x];
                if (isWallTeal((c >> 16) & 255, (c >> 8) & 255, c & 255)) n++;
            }
            wallCol[x] = n;
        }
        // nearest wall to the bar first; one that is just reaching the bar may be cut off at xe (keep >= 4 columns)
        int minWall = Math.max(8, (int) (0.02f * w)), maxWall = (int) (0.10f * w);
        int x = xe;
        while (x >= xs) {
            if (wallCol[x] < need) {
                x--;
                continue;
            }
            int b = x, a = x, gap = 0;
            while (x >= xs) {
                if (wallCol[x] >= need) {
                    a = x;
                    gap = 0;
                } else if (++gap > 6) break;
                x--;
            }
            int ww = b - a + 1;
            boolean cut = b >= xe - 1;
            if (ww > maxWall || (ww < minWall && !(cut && ww >= 4))) continue;
            // the nearest real wall decides: if its gap is unreadable, a farther wall's gap must not be used
            if (wallGap(px, w, h, a, b, y0, y1)) {
                wallX0 = a;
                wallX1 = b;
                gapFound = true;
            }
            return;
        }
    }

    private final byte[] rowCls = new byte[2048];

    /** Open rows (sky/cloud) inside the wall's columns between hard rows (dark teal body, cream cap). */
    private boolean wallGap(int[] px, int w, int h, int a, int b, int y0, int y1) {
        int ia = b - a >= 8 ? a + 2 : a, ib = b - a >= 8 ? b - 2 : b, cw = ib - ia + 1;
        if (y1 - y0 + 1 > rowCls.length) return false;
        for (int y = y0; y <= y1; y++) {
            int open = 0, teal = 0, cap = 0;
            for (int x = ia; x <= ib; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                if (isSky(r, g, bl) || isCloud(r, g, bl)) open++;
                else if (isWallTeal(r, g, bl)) teal++;
                else if (isCap(r, g, bl)) cap++;
            }
            // 0 = soft (fish icon, text, our own panel), 1 = open, 2 = hard
            if (masked(ia, y) || masked(ib, y)) {
                rowCls[y - y0] = 0;
                continue;
            }
            rowCls[y - y0] = (byte) (open >= 0.5f * cw ? 1 : teal >= 0.35f * cw || cap >= 0.5f * cw ? 2 : 0);
        }
        int n = y1 - y0 + 1, bridge = Math.max(4, (int) (0.05f * h));
        // short soft stretches between open rows are text ("TURUN", "PAS!") over the gap
        for (int i = 0; i < n; i++) {
            if (rowCls[i] != 0) continue;
            int e = i;
            while (e + 1 < n && rowCls[e + 1] == 0) e++;
            if (i > 0 && e + 1 < n && rowCls[i - 1] == 1 && rowCls[e + 1] == 1 && e - i + 1 <= bridge)
                for (int k = i; k <= e; k++) rowCls[k] = 1;
            i = e;
        }
        int best = 0, bs = -1;
        for (int i = 0; i < n; i++) {
            if (rowCls[i] != 1) continue;
            int e = i;
            while (e + 1 < n && rowCls[e + 1] == 1) e++;
            if (e - i + 1 > best) {
                best = e - i + 1;
                bs = i;
            }
            i = e;
        }
        if (best < 0.10f * h) return false;
        int be = bs + best - 1;
        // both ends must be real wall (or the track end), not a long soft stretch (e.g. our panel covering it)
        // (a few soft rows of anti-aliasing at the cap are fine)
        int k = bs - 1;
        while (k >= 0 && k >= bs - 5 && rowCls[k] == 0) k--;
        if (k >= 0 && rowCls[k] != 2) return false;
        if (k < 0 && bs > 5) return false;
        k = be + 1;
        while (k < n && k <= be + 5 && rowCls[k] == 0) k++;
        if (k < n && rowCls[k] != 2) return false;
        if (k >= n && be < n - 6) return false;
        if (bs == 0 && be == n - 1) return false; // no wall body at all
        gapTop = y0 + bs;
        gapBot = y0 + be;
        return true;
    }

    // ------------------------------------------------------------------ button
    public static boolean DEBUG = false;
    private final int[] cnt = new int[6];
    private final long[] sx = new long[6], sy = new long[6];

    /**
     * Finds the big round action button by colour in the bottom-right area (robust to notches, nav bars and
     * different aspect ratios): every pixel is matched to the nearest palette colour, and the button colour
     * that covers the largest area wins. Its centroid is used as the tap position.
     */
    private void classifyButton(int[] px, int w, int h) {
        int x0 = (int) Math.max(0, w - 0.42f * h), y0 = (int) (0.55f * h);
        java.util.Arrays.fill(cnt, 0);
        java.util.Arrays.fill(sx, 0);
        java.util.Arrays.fill(sy, 0);
        int maxD2 = 42 * 42;
        for (int y = y0; y < h; y += 2) {
            int row = y * w;
            for (int x = x0; x < w; x += 2) {
                int c = px[row + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int best = -1, bestD = Integer.MAX_VALUE;
                for (int[] pp : PALETTE) {
                    int dr = r - pp[0], dg = g - pp[1], db = b - pp[2];
                    int d = dr * dr + dg * dg + db * db;
                    if (d < bestD) {
                        bestD = d;
                        best = pp[3];
                    }
                }
                if (bestD <= maxD2 && best > 0) {
                    cnt[best]++;
                    sx[best] += x;
                    sy[best] += y;
                }
            }
        }
        // expected button area ~ pi*(0.1h)^2, sampled every 2px in both directions
        float rr = btnRadiusH * h;
        int minCount = (int) (Math.PI * rr * rr / 4 * 0.22f);
        // try candidate colours from the largest area down; the first one that forms a round button wins
        // (tinted water during events can match a button colour, but it is never a filled disk)
        int best = BTN_NONE, bestCnt = 0;
        boolean[] tried = new boolean[cnt.length];
        for (int k = 1; k < cnt.length; k++) {
            int cand = -1, cc = 0;
            for (int i = 1; i < cnt.length; i++)
                if (!tried[i] && cnt[i] > cc) {
                    cc = cnt[i];
                    cand = i;
                }
            if (cand < 0 || cc < minCount) break;
            tried[cand] = true;
            // start from the colour's centroid, then from the usual button spot
            if (buttonAt(px, w, h, cand, (float) sx[cand] / cc, (float) sy[cand] / cc, rr)
                    || buttonAt(px, w, h, cand, w - btnRightOffH * h, btnFy * h, rr)) {
                best = cand;
                bestCnt = cc;
                break;
            }
        }
        btnDist = bestCnt;
        if (best != BTN_NONE) {
            button = best;
            btnCx = foundCx;
            btnCy = foundCy;
            int c = px[(int) btnCy * w + (int) btnCx];
            btnR = (c >> 16) & 255;
            btnG = (c >> 8) & 255;
            btnB = c & 255;
        } else {
            button = BTN_NONE;
            btnCx = w - btnRightOffH * h;
            btnCy = btnFy * h;
        }
    }

    private float foundCx, foundCy;
    /** Centre of the last button whose ring was found (the button stays put). */
    private float lastRingCx, lastRingCy, lastRingR = -1;

    private int classOf(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int bestCls = -1, bestD = Integer.MAX_VALUE;
        for (int[] pp : PALETTE) {
            int d = (r - pp[0]) * (r - pp[0]) + (g - pp[1]) * (g - pp[1]) + (b - pp[2]) * (b - pp[2]);
            if (d < bestD) {
                bestD = d;
                bestCls = pp[3];
            }
        }
        return bestD <= 42 * 42 ? bestCls : -1;
    }

    /**
     * Is there a button of colour class cls near (cx,cy)? Re-centres on that colour (mean shift), then
     * requires the button's white outline: rays from the centre must hit a bright ring at a consistent radius.
     * The outline is UI and is not recoloured by map events, while tinted water never has it.
     */
    private boolean buttonAt(int[] px, int w, int h, int cls, float cx, float cy, float rr) {
        for (int it = 0; it < 4; it++) {
            float R = rr * 1.15f;
            long sx2 = 0, sy2 = 0;
            int n = 0;
            for (int y = (int) (cy - R); y <= cy + R; y += 2) {
                if (y < 0 || y >= h) continue;
                for (int x = (int) (cx - R); x <= cx + R; x += 2) {
                    if (x < 0 || x >= w) continue;
                    float dx = x - cx, dy = y - cy;
                    if (dx * dx + dy * dy > R * R) continue;
                    if (classOf(px[y * w + x]) == cls) {
                        sx2 += x;
                        sy2 += y;
                        n++;
                    }
                }
            }
            if (n < 8) return false;
            cx = (float) sx2 / n;
            cy = (float) sy2 / n;
        }
        // fill: enough of the inner disk has the colour (icons/text cover the rest)
        int inside = 0, samples = 0;
        float Ri = rr * 0.75f;
        for (int y = (int) (cy - Ri); y <= cy + Ri; y += 2) {
            if (y < 0 || y >= h) continue;
            for (int x = (int) (cx - Ri); x <= cx + Ri; x += 2) {
                if (x < 0 || x >= w) continue;
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > Ri * Ri) continue;
                samples++;
                if (classOf(px[y * w + x]) == cls) inside++;
            }
        }
        if (samples == 0 || inside < 0.25f * samples) return false;
        // white outline ring: scan each ray from OUTSIDE inwards (so white text inside is never reached first).
        // Two passes: the second is centred on the ring points found by the first (the colour centroid is
        // biased by the white icon/text inside the button).
        boolean ok = false;
        float med = 0;
        int consistent = 0;
        for (int pass = 0; pass < 2; pass++) {
            final int RAYS = 12;
            float[] rad = new float[RAYS], hx = new float[RAYS], hy = new float[RAYS];
            int hits = 0;
            for (int k = 0; k < RAYS; k++) {
                double a = 2 * Math.PI * k / RAYS;
                double ux = Math.cos(a), uy = Math.sin(a);
                for (float d = rr * 1.8f; d >= rr * 0.55f; d -= 1f) {
                    int x = (int) Math.round(cx + ux * d), y = (int) Math.round(cy + uy * d);
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    int c = px[y * w + x];
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                    // cream-white outline, or the yellow progress arc that grows over it (~255,204,55);
                    // the arc's green channel keeps it apart from the orange "Lepas" fill (g~164)
                    boolean white = mn > 200 && mx - mn < 45;
                    boolean arc = r > 230 && g >= 182 && g <= 228 && b < 115;
                    if (white || arc) {
                        rad[hits] = d;
                        hx[hits] = x;
                        hy[hits] = y;
                        hits++;
                        break;
                    }
                }
            }
            if (hits < 7) break;
            float[] sr = java.util.Arrays.copyOf(rad, hits);
            java.util.Arrays.sort(sr);
            med = sr[hits / 2];
            consistent = 0;
            float mxs = 0, mys = 0;
            for (int k = 0; k < hits; k++)
                if (Math.abs(rad[k] - med) <= Math.max(2.5f, 0.15f * med)) {
                    consistent++;
                    mxs += hx[k];
                    mys += hy[k];
                }
            ok = consistent >= 7;
            if (ok || consistent < 4) break;
            // re-centre on the consistent ring points and try once more
            cx = mxs / consistent;
            cy = mys / consistent;
        }
        if (!ok && lastRingR > 0 && Math.abs(lastRingCx - cx) < 0.2f * h && Math.abs(lastRingCy - cy) < 0.2f * h) {
            // the button never moves: green grass behind it drags the colour centroid away, use where it last was
            cx = lastRingCx;
            cy = lastRingCy;
        }
        if (!ok) {
            // Light background (the legendary "Rintangan!" sky, clouds, sand): scanning in from outside hits the
            // background before the ring. Scan outwards instead: the ring is the first white right after the
            // button's own colour (white text inside is followed by more colour, not by the end of the button).
            final int RAYS = 12;
            float[] rad = new float[RAYS];
            int hits = 0;
            for (int k = 0; k < RAYS; k++) {
                double a = 2 * Math.PI * k / RAYS;
                double ux = Math.cos(a), uy = Math.sin(a);
                int run = 0;
                for (float d = rr * 0.55f; d <= rr * 1.6f; d += 1f) {
                    int x = (int) Math.round(cx + ux * d), y = (int) Math.round(cy + uy * d);
                    if (x < 0 || y < 0 || x >= w || y >= h) break;
                    int c = px[y * w + x];
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                    boolean white = mn > 200 && mx - mn < 45, arc = r > 230 && g >= 182 && g <= 228 && b < 115;
                    if ((white || arc) && run >= 3) {
                        rad[hits++] = d;
                        break;
                    }
                    run = classOf(c) == cls ? run + 1 : 0;
                }
            }
            if (DEBUG) System.out.printf("  [btn inside-out c=%.0f,%.0f rr=%.0f hits=%d %s]%n", cx, cy, rr, hits, java.util.Arrays.toString(java.util.Arrays.copyOf(rad, hits)));
            // (grass behind the button has the button's green, so part of the ring can be missing there)
            if (hits >= 7) {
                float[] sr = java.util.Arrays.copyOf(rad, hits);
                java.util.Arrays.sort(sr);
                med = sr[hits / 2];
                consistent = 0;
                for (int k = 0; k < hits; k++) if (Math.abs(rad[k] - med) <= Math.max(3f, 0.18f * med)) consistent++;
                ok = consistent >= 7 && med >= rr * 0.7f;
            }
        }
        if (DEBUG) System.out.printf("  [btn cls=%d fill=%.2f ringR=%.0f consistent=%d -> %s]%n", cls, inside / (float) samples, med, consistent, ok);
        if (ok) {
            foundCx = cx;
            foundCy = cy;
            if (med > 0) {
                lastRingCx = cx;
                lastRingCy = cy;
                lastRingR = med;
            }
        }
        return ok;
    }

    /** The matched pixels must form a filled disk of roughly the button's size around their centroid. */
    private boolean isRoundBlob(int[] px, int w, int h, int cls, float cx, float cy, float rr, int total) {
        int maxD2 = 42 * 42, inside = 0, samples = 0;
        float R = rr * 1.25f;
        for (int y = (int) (cy - R); y <= cy + R; y += 2) {
            if (y < 0 || y >= h) continue;
            for (int x = (int) (cx - R); x <= cx + R; x += 2) {
                if (x < 0 || x >= w) continue;
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > R * R) continue;
                samples++;
                int c = px[y * w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int bestCls = -1, bestD = Integer.MAX_VALUE;
                for (int[] pp : PALETTE) {
                    int d = (r - pp[0]) * (r - pp[0]) + (g - pp[1]) * (g - pp[1]) + (b - pp[2]) * (b - pp[2]);
                    if (d < bestD) {
                        bestD = d;
                        bestCls = pp[3];
                    }
                }
                if (bestCls == cls && bestD <= maxD2) inside++;
            }
        }
        // most matches lie inside the disk, and the disk is reasonably filled
        if (DEBUG) System.out.printf("  [round cls=%d in/total=%.2f in/samples=%.2f]%n", cls, inside / (float) total, inside / (float) samples);
        return samples > 0 && inside >= 0.65f * total && inside >= 0.15f * samples;
    }

    private static int median(int[] a, int n) {
        int[] c = java.util.Arrays.copyOf(a, n);
        java.util.Arrays.sort(c);
        return c[n / 2];
    }

    // ------------------------------------------------------------------ bar
    private void findBar(int[] px, int w, int h) {
        barFound = false;
        fail = "";
        markerY = Float.NaN;
        markerSide = 0;
        fishFromOutline = false;
        if (colCount.length < w) colCount = new int[w];
        if (rowFlag.length < h) rowFlag = new int[h];
        if (ghostRow.length < h) ghostRow = new int[h];
        int xs = (int) (0.04f * w), xe = (int) Math.min(w - 1, w - 0.30f * h); // stay left of the action button
        int ys = (int) (0.06f * h), ye = (int) (0.96f * h);

        int minCol = Math.max(3, (int) (0.035f * h / 2));
        int minW = Math.max(4, (int) (0.015f * w)), maxW = (int) Math.min(0.10f * w, 0.14f * h); // real bar ~0.06-0.075 h wide
        // Rows where zone-green runs across much more than one bar are background (the grass strip of the legendary
        // "Rintangan!" sky scene): counted, it joins every column into one cluster far too wide to be the bar.
        java.util.Arrays.fill(colCount, xs, xe + 1, 0);
        int bgRow = (int) (1.5f * maxW);
        for (int y = ys; y <= ye; y += 2) {
            int row = y * w, n = 0;
            for (int x = xs; x <= xe; x++) {
                int c = px[row + x];
                if (isZone((c >> 16) & 255, (c >> 8) & 255, c & 255)) n++;
            }
            if (n == 0 || n > bgRow) continue;
            for (int x = xs; x <= xe; x++) {
                int c = px[row + x];
                if (isZone((c >> 16) & 255, (c >> 8) & 255, c & 255)) colCount[x]++;
            }
        }

        // collect column clusters, evaluate best-scoring first
        int[][] clusters = new int[48][3];
        int nc = 0;
        int x = xs;
        while (x <= xe && nc < clusters.length) {
            if (colCount[x] < minCol) {
                x++;
                continue;
            }
            int s = x, score = 0, gap = 0, last = x;
            while (x <= xe) {
                if (colCount[x] >= minCol) {
                    score += colCount[x];
                    last = x;
                    gap = 0;
                } else if (++gap > 2) break;
                x++;
            }
            int cw = last - s + 1;
            if (cw >= minW && cw <= maxW) {
                clusters[nc][0] = s;
                clusters[nc][1] = last;
                clusters[nc][2] = score;
                nc++;
            }
        }
        // sort by score desc
        for (int i = 0; i < nc; i++)
            for (int j = i + 1; j < nc; j++)
                if (clusters[j][2] > clusters[i][2]) {
                    int[] t = clusters[i];
                    clusters[i] = clusters[j];
                    clusters[j] = t;
                }
        for (int i = 0; i < nc; i++) {
            if (evalCluster(px, w, h, clusters[i][0], clusters[i][1], ys, ye)) {
                barFound = true;
                return;
            }
        }
    }

    private boolean evalCluster(int[] px, int w, int h, int cx0, int cx1, int ys, int ye) {
        int cw = cx1 - cx0 + 1;
        int need = Math.max(2, (int) (cw * 0.35f));
        // zone rows
        for (int y = ys; y <= ye; y++) {
            int cnt = 0;
            for (int x = cx0; x <= cx1; x++) {
                int c = px[y * w + x];
                if (isZone((c >> 16) & 255, (c >> 8) & 255, c & 255)) cnt++;
            }
            rowFlag[y] = cnt >= need ? 1 : 0;
        }
        // "Rintangan!" draws a thin green line across the track (edge of the next gap); with teal on both sides it
        // is not the zone and would stretch it (joined within maxGap). Rows next to the dark fish are kept.
        {
            int mx = (cx0 + cx1) / 2;
            for (int y = ys; y <= ye; y++) {
                if (rowFlag[y] == 0 || (y > ys && rowFlag[y - 1] == 1)) continue;
                int e = y;
                while (e + 1 <= ye && rowFlag[e + 1] == 1) e++;
                if (e - y + 1 <= 3 && plainAt(px, w, h, mx, y - 2) && plainAt(px, w, h, mx, y - 3)
                        && plainAt(px, w, h, mx, e + 2) && plainAt(px, w, h, mx, e + 3))
                    for (int k = y; k <= e; k++) rowFlag[k] = 0;
                y = e;
            }
        }
        // biggest group of zone rows, allowing gaps (the fish icon overlaps the zone)
        int maxGap = (int) (0.14f * h);
        int bestTop = -1, bestBot = -1, bestRows = 0;
        int gTop = -1, gBot = -1, gRows = 0;
        for (int y = ys; y <= ye; y++) {
            if (rowFlag[y] == 0) continue;
            // join across a gap only when it is the fish lying over the zone (or a thin line); a stretch of empty
            // track means a separate green patch (the glow around a legendary fish, the "Rintangan!" gap line)
            if (gTop >= 0 && y - gBot <= maxGap && (y - gBot <= 5 || gapIsFish(px, w, cx0, cx1, gBot + 1, y - 1))) {
                gBot = y;
                gRows++;
            } else {
                if (gRows > bestRows) {
                    bestRows = gRows;
                    bestTop = gTop;
                    bestBot = gBot;
                }
                gTop = y;
                gBot = y;
                gRows = 1;
            }
        }
        if (gRows > bestRows) {
            bestRows = gRows;
            bestTop = gTop;
            bestBot = gBot;
        }
        if (bestRows < 0.03f * h) return fail("e1@%d-%d", cx0, cx1);

        int cx = (cx0 + cx1) / 2;
        // track bounds: scan the centre column to the white outline (>=3px of white;
        // the zone's own highlight line is only 1-2px and is skipped)
        int limit = (int) (0.85f * h);
        int tTop = scanEdge(px, w, h, cx, bestTop, -1, limit);
        int tBot = scanEdge(px, w, h, cx, bestBot, +1, limit);
        int sTop = tTop, sBot = tBot;
        // The fish icon can cover the outline at the very top/bottom. The track does not change size
        // during one minigame, so fall back to the remembered bounds when this looks wrong.
        if (memValid && Math.abs(cx - (memX0 + memX1) / 2) <= Math.max(3, cw / 2)) {
            int tol = (int) (0.04f * h);
            if (tTop < 0 || Math.abs(tTop - memTop) > tol) tTop = memTop;
            if (tBot < 0 || Math.abs(tBot - memBot) > tol) tBot = memBot;
            if (bestTop < tTop) bestTop = tTop;
            if (bestBot > tBot) bestBot = tBot;
        }
        // One end hidden (the fish at the very end, or our own panel over a far-left "Pindah!" bar) and this is a
        // different bar than the remembered one: all bars have the same track length, so take it from the other end.
        if ((tTop < 0) != (tBot < 0) && knownTrackH > 0) {
            if (tTop < 0) tTop = tBot - knownTrackH;
            else tBot = tTop + knownTrackH;
            if (tTop < 1 || tBot >= h - 1) return fail("e2@%d-%d", cx0, cx1);
            // (green background above/below the bar can join the zone through the fish lying on the track end)
            bestTop = Math.max(bestTop, tTop);
            bestBot = Math.min(bestBot, tBot);
            if (bestBot - bestTop < 0.03f * h) return fail("e2@%d-%d", cx0, cx1);
        }
        if (tTop < 0 || tBot < 0) return fail("e2@%d-%d", cx0, cx1);
        int th = tBot - tTop;
        if (th < 0.28f * h || th > 0.85f * h) return fail("e3@%d-%d", cx0, cx1);
        // the real bar has a cream outline running down BOTH sides of the track; green grass next to teal water
        // (the beach on the left of the screen) can pass every colour test above but never has it
        if (!sideOutlines(px, w, cx0, cx1, tTop, tBot)) return fail("e10@%d-%d", cx0, cx1);

        // the rest of the track (outside the zone and the fish) must be mostly teal
        int teal = 0, other = 0;
        for (int y = tTop + 2; y <= tBot - 2; y++) {
            if (y >= bestTop - 1 && y <= bestBot + 1) continue;
            int c = px[y * w + cx];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            if (isTeal(r, g, b)) teal++;
            else if (!isDark(r, g, b) && !isZone(r, g, b)) other++;
        }
        if (teal < Math.max(3, 0.12f * th)) return fail("e4@%d-%d", cx0, cx1);
        if (teal < 0.5f * (teal + other)) return fail("e5@%d-%d", cx0, cx1);
        if (bestBot - bestTop > 0.85f * th) return fail("e6@%d-%d", cx0, cx1);

        // fish = dark pixels inside the track
        int fx0 = Math.max(0, cx0 - 2), fx1 = Math.min(w - 1, cx1 + 2);
        long sumY = 0, gSumY = 0;
        int cnt = 0, fTop = -1, fBot = -1, gCnt = 0, ghTop = -1, ghBot = -1;
        for (int y = tTop; y <= tBot; y++) {
            int rowCnt = 0, rowG = 0;
            for (int x = fx0; x <= fx1; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if (isDark(r, g, b) && !masked(x, y)) rowCnt++;
                else if (x >= cx0 && x <= cx1 && isGhost(r, g, b)) rowG++;
            }
            if (rowCnt > 0) {
                cnt += rowCnt;
                sumY += (long) y * rowCnt;
                if (rowCnt >= 2) {
                    if (fTop < 0) fTop = y;
                    fBot = y;
                }
            }
            ghostRow[y] = (y > tTop + 3 && y < tBot - 3 && rowG >= Math.max(2, cw / 3)) ? rowG : 0;
        }
        // densest group of ghost rows (gaps <= 3), so stray rows at the zone/track edges are ignored
        {
            int bestTot = 0, curTot = 0, curTop = -1, curBot = -1, last = -100;
            long curSum = 0;
            for (int y = tTop; y <= tBot + 1; y++) {
                int v = y <= tBot ? ghostRow[y] : 0;
                if (v > 0 && y - last <= 4) {
                    curTot += v;
                    curSum += (long) y * v;
                    curBot = y;
                    last = y;
                } else if (v > 0) {
                    if (curTot > bestTot) { bestTot = curTot; gCnt = curTot; gSumY = curSum; ghTop = curTop; ghBot = curBot; }
                    curTot = v;
                    curSum = (long) y * v;
                    curTop = y;
                    curBot = y;
                    last = y;
                }
            }
            if (curTot > bestTot) { gCnt = curTot; gSumY = curSum; ghTop = curTop; ghBot = curBot; }
        }
        int minFish = Math.max(6, (int) (cw * h * 0.002f));
        // The zone + teal track already prove this is the bar. If the fish is invisible (faded), report the bar
        // anyway with fishVisible=false so the bot can keep tracking the fish's predicted position.
        // "Hantu: sisik memudar": the fish becomes a translucent grey-blue silhouette. Use those pixels only when
        // there is no dark fish, and only if they form a fish-sized blob (rainbow stripes run the whole track).
        if (cnt < minFish && gCnt >= minFish && ghBot - ghTop <= 0.12f * h) {
            cnt = gCnt;
            sumY = gSumY;
            fTop = ghTop;
            fBot = ghBot;
        }
        fishVisible = cnt >= minFish;
        fishFromOutline = false;
        float occY = Float.NaN;
        if (!fishVisible) {
            occY = outlineFish(px, w, h, cx0, cx1, tTop, tBot);
            if (!Float.isNaN(occY)) {
                fishVisible = true;
                fishFromOutline = true;
            }
        }
        if (!fishVisible) {
            // only trust a fish-less reading if the track looks complete: no dark fish pixels just beyond its ends
            // (that would mean the fish hides the real end and the bounds are wrong)
            if (th < 0.4f * h) return fail("e8@%d-%d", cx0, cx1);
            int ext = (int) (0.07f * h), beyond = 0;
            for (int y = Math.max(0, tTop - ext); y < tTop; y++)
                for (int x = cx0; x <= cx1; x++) {
                    int c = px[y * w + x];
                    if (isDark((c >> 16) & 255, (c >> 8) & 255, c & 255) && !masked(x, y)) beyond++;
                }
            for (int y = tBot + 1; y <= Math.min(h - 1, tBot + ext); y++)
                for (int x = cx0; x <= cx1; x++) {
                    int c = px[y * w + x];
                    if (isDark((c >> 16) & 255, (c >> 8) & 255, c & 255) && !masked(x, y)) beyond++;
                }
            if (beyond >= minFish) return fail("e9@%d-%d", cx0, cx1);
        }
        warnDir = findWarning(px, w, cx0, cx1, tTop, tBot);
        findMarker(px, w, h, cx0, cx1, tTop, tBot);

        barX0 = cx0;
        barX1 = cx1;
        trackTop = tTop;
        trackBot = tBot;
        zoneTop = bestTop;
        zoneBot = bestBot;
        fishCount = cnt;
        fishY = !fishVisible ? Float.NaN : fishFromOutline ? occY : (float) sumY / cnt;
        fishTop = fTop;
        fishBot = fBot;
        if (!fishVisible || fishFromOutline) return true; // do not remember geometry from a fish-less reading
        memValid = true;
        memX0 = cx0;
        memX1 = cx1;
        memTop = tTop;
        memBot = tBot;
        // (only from a frame where both ends were really scanned, not remembered or inferred)
        if (sTop == tTop && sBot == tBot && fBot < tBot - 3 && fTop > tTop + 3) knownTrackH = tBot - tTop;
        // keep the HOME bar as the reference for the tint fallback: the "Pindah!" bar one pitch to the left is not it
        int pitchNow = barPitch(cw);
        // Only learn the reference geometry from a real minigame bar: outside the minigame (catch card, Mancing
        // screen) other UI can look like a bar, and a wrong reference makes the tint fallback read the wrong spot
        // for the whole next event (seen in "Badai Petir": a 9px-wide, 200px-tall false bar while "Mancing" showed).
        boolean plausible = button == BTN_TARIK && tBot - tTop >= 0.40f * h && tBot - tTop <= 0.58f * h
                && cw >= 0.04f * h && cw <= 0.10f * h;
        if (!plausible) return true;
        if (geoValid && geoW == w && Math.abs((cx0 + cx1) / 2 - ((geoX0 + geoX1) / 2 - pitchNow)) <= 4
                && Math.abs(tTop - geoTop) <= 4) return true;
        geoValid = true;
        geoX0 = cx0;
        geoX1 = cx1;
        geoTop = tTop;
        geoBot = tBot;
        geoW = w;
        geoH = h;
        memValid = true;
        memX0 = cx0;
        memX1 = cx1;
        memTop = tTop;
        memBot = tBot;
        return true;
    }

    /** Fraction of track rows with an outline pixel just left AND just right of the zone columns. */
    public float lastSideFrac;

    private boolean sideOutlines(int[] px, int w, int cx0, int cx1, int tTop, int tBot) {
        int rows = 0, both = 0;
        for (int y = tTop + 2; y <= tBot - 2; y += 2) {
            if (masked(cx0, y) || masked(cx1, y)) continue;
            boolean l = false, r = false;
            for (int x = Math.max(0, cx0 - 5); x <= cx0 && !l; x++) {
                int c = px[y * w + x];
                l = isEdge((c >> 16) & 255, (c >> 8) & 255, c & 255);
            }
            for (int x = cx1; x <= Math.min(w - 1, cx1 + 5) && !r; x++) {
                int c = px[y * w + x];
                r = isEdge((c >> 16) & 255, (c >> 8) & 255, c & 255);
            }
            rows++;
            if (l && r) both++;
        }
        lastSideFrac = rows == 0 ? 0 : both / (float) rows;
        // the fish icon and speech bubbles cover the outline over part of the track
        return lastSideFrac >= 0.45f;
    }

    private boolean fail(String f, int a, int b) {
        fail = fail + String.format(f, a, b) + " ";
        return false;
    }

    /** Bright, low-saturation, warm pixel (white/grey outline of the bar), not part of the catch zone. */
    static boolean isEdge(int r, int g, int b) {
        if (isZone(r, g, b)) return false;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        // the outline is warm white/cream; the zone's highlight line is cyan-white (b > r)
        return (r * 3 + g * 6 + b) / 10 > 160 && mx - mn < 75 && r >= b - 10;
    }

    private int scanEdge(int[] px, int w, int h, int x, int startY, int dir, int limit) {
        int need = Math.max(2, h / 150);
        int run = 0, lastInside = startY;
        for (int i = 0, y = startY; i <= limit && y >= 0 && y < h; i++, y += dir) {
            if (masked(x, y)) return -1; // our own panel covers this end: unknown
            int c = px[y * w + x];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            // (the legendary dash warning is a pale yellow line 2 rows thick just inside the track end: not the outline)
            if (isEdge(r, g, b) && !isWarnYellow(r, g, b)) {
                if (++run >= need) {
                    // a highlight line inside the track is followed by more teal track; the real outline is not
                    int teal = 0;
                    for (int k = 1; k <= 3; k++) {
                        int yy = y + dir * k;
                        if (yy < 0 || yy >= h) break;
                        int cc = px[yy * w + x];
                        if (isTeal((cc >> 16) & 255, (cc >> 8) & 255, cc & 255)) teal++;
                    }
                    if (teal < 2) return lastInside;
                    run = 0;
                }
            } else {
                run = 0;
                lastInside = y;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ tint-robust fallback
    private float[] rowQ = new float[0], rowL = new float[0];

    private static int edgeLuma(int[] px, int w, int cx, int off, int y) {
        return (luma(px[y * w + cx - off]) + luma(px[y * w + cx]) + luma(px[y * w + cx + off])) / 3;
    }

    private static int luma(int c) {
        return (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10;
    }

    /**
     * Reads the bar at its known position using only relative comparisons, which survive colour overlays:
     *  - the outline just outside the track is brighter than the track,
     *  - the fish is much darker than the track,
     *  - the zone is "greener vs blue" (higher g/b ratio) than the empty track. A multiplicative tint scales
     *    g/b of both by the same factor, so their order is kept.
     */
    private int relShift;
    private boolean relAllowOutline;

    private void findBarRelative(int[] px, int w, int h) {
        int x0, x1, tTop, tBot;
        if (geoValid && geoW == w && geoH == h) {
            x0 = geoX0;
            x1 = geoX1;
            tTop = geoTop;
            tBot = geoBot;
        } else { // default layout measured from the game (same on the video and on a 20:9 phone)
            x0 = Math.round(0.603f * w);
            x1 = Math.round(0.630f * w);
            tTop = Math.round(0.3125f * h);
            tBot = Math.round(0.809f * h);
        }
        // "Pindah!" moves the game into a second bar one bar-pitch to the left (and back): try the last position
        // that worked first, then the home position and its neighbours.
        int pitch = Math.round((x1 - x0 + 1) * 1.67f);
        // (the second bar only ever appears to the LEFT of the home bar; right of it is the progress bar)
        if (relShift != 0 && relShift != -pitch) relShift = 0;
        int[] shifts = {relShift, 0, -pitch};
        String f0 = fail;
        for (int i = 0; i < shifts.length; i++) {
            int s = shifts[i];
            boolean dup = false;
            for (int j = 0; j < i; j++) dup |= shifts[j] == s;
            if (dup || x0 + s < 2 || x1 + s > w - 3) continue;
            fail = f0;
            relAllowOutline = s == 0;
            if (relAt(px, w, h, x0 + s, x1 + s, tTop, tBot)) {
                relShift = s;
                return;
            }
            if (i == 0) f0 = fail; // report the failure reason of the preferred position
        }
        fail = f0;
    }

    private boolean relAt(int[] px, int w, int h, int x0, int x1, int tTop, int tBot) {
        int cw = x1 - x0 + 1;
        if (cw < 4 || tBot - tTop < 0.2f * h || tTop < 6 || tBot > h - 7) {
            fail += "r0 ";
            return false;
        }
        int cx = (x0 + x1) / 2, off = Math.max(1, cw / 4);
        // the bar can sit a few pixels higher/lower than last time: snap both ends to the outline edge
        {
            int[] tmp = new int[(tBot - tTop) / 2 + 2];
            int k = 0;
            for (int y = tTop; y <= tBot; y += 2) tmp[k++] = edgeLuma(px, w, cx, off, y);
            int m0 = median(tmp, k);
            int bestD = 99, nt = tTop;
            for (int y = tTop - 6; y <= tTop + 6; y++) {
                if (y < 1 || y + 2 >= h) continue;
                // outline row above, two track rows below
                if (edgeLuma(px, w, cx, off, y - 1) >= 1.3f * m0 && edgeLuma(px, w, cx, off, y) < 1.15f * m0
                        && edgeLuma(px, w, cx, off, y + 1) < 1.15f * m0 && Math.abs(y - tTop) < bestD) {
                    bestD = Math.abs(y - tTop);
                    nt = y;
                }
            }
            bestD = 99;
            int nb = tBot;
            for (int y = tBot - 6; y <= tBot + 6; y++) {
                if (y < 2 || y + 1 >= h) continue;
                if (edgeLuma(px, w, cx, off, y + 1) >= 1.3f * m0 && edgeLuma(px, w, cx, off, y) < 1.15f * m0
                        && edgeLuma(px, w, cx, off, y - 1) < 1.15f * m0 && Math.abs(y - tBot) < bestD) {
                    bestD = Math.abs(y - tBot);
                    nb = y;
                }
            }
            tTop = nt;
            tBot = nb;
            if (tTop < 6 || tBot > h - 7) {
                fail += "r0 ";
                return false;
            }
        }
        int[] cols = {cx - off, cx, cx + off};
        int n = tBot - tTop + 1;
        if (rowQ.length < n) {
            rowQ = new float[n];
            rowL = new float[n];
        }
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            int y = tTop + i;
            float g = 0, b = 0, l = 0;
            for (int x : cols) {
                int c = px[y * w + x];
                g += (c >> 8) & 255;
                b += c & 255;
                l += luma(c);
            }
            rowQ[i] = (g + 3) / (b + 3);
            rowL[i] = l / cols.length;
            lum[i] = rowL[i];
        }
        float[] sorted = java.util.Arrays.copyOf(lum, n);
        java.util.Arrays.sort(sorted);
        float med = sorted[n / 2];
        if (med < 12) {
            fail += "r1 ";
            return false;
        }
        // fish = rows/pixels much darker than the track
        float darkT = med * 0.55f;
        int fx0 = Math.max(0, x0 - 2), fx1 = Math.min(w - 1, x1 + 2);
        // densest group of dark rows (gaps <= 3) = the fish; stray dark pixels elsewhere are ignored
        long sumY = 0, curSum = 0;
        int cnt = 0, fTop = -1, fBot = -1, curCnt = 0, curTop = -1, curBot = -1, lastY = -100;
        for (int y = tTop; y <= tBot + 4; y++) {
            int rc = 0;
            if (y <= tBot) for (int x = fx0; x <= fx1; x++) if (luma(px[y * w + x]) < darkT && !masked(x, y)) rc++;
            if (rc >= 2) {
                if (y - lastY > 4) {
                    if (curCnt > cnt) { cnt = curCnt; sumY = curSum; fTop = curTop; fBot = curBot; }
                    curCnt = 0;
                    curSum = 0;
                    curTop = y;
                }
                curCnt += rc;
                curSum += (long) y * rc;
                curBot = y;
                lastY = y;
            }
        }
        if (curCnt > cnt) { cnt = curCnt; sumY = curSum; fTop = curTop; fBot = curBot; }
        int minFish = Math.max(6, (int) (cw * h * 0.002f));
        boolean darkFish = cnt >= minFish;
        if (darkFish && fBot - fTop > 0.2f * h) {
            fail += "r7@" + fTop + "-" + fBot + " "; // a tall dark strip is not the fish (e.g. the progress bar next to a wrong position)
            return false;
        }
        // outline check: brighter rows right outside both ends of the track. The fish icon may cover one end.
        float above = 0, below = 0;
        for (int k = 2; k <= 4; k++) {
            above = Math.max(above, luma(px[(tTop - k) * w + (x0 + x1) / 2]));
            below = Math.max(below, luma(px[(tBot + k) * w + (x0 + x1) / 2]));
        }
        int near = (int) (0.07f * h);
        // (1.15: a white glow over the bar ("Berkilau") brightens the track too)
        boolean topOk = above >= med * 1.15f || (darkFish && fTop >= 0 && fTop <= tTop + near);
        boolean botOk = below >= med * 1.15f || (darkFish && fBot >= 0 && fBot >= tBot - near);
        boolean bothCovered = above < med * 1.15f && below < med * 1.15f;
        if (!topOk || !botOk || bothCovered) {
            fail += "r2 ";
            return false;
        }
        float occY = Float.NaN;
        if (!darkFish) {
            // faded / glowing fish: use the shadow it casts over the bright outline
            occY = relAllowOutline ? outlineFish(px, w, h, x0, x1, tTop, tBot) : Float.NaN;
            if (Float.isNaN(occY)) {
                fail += "r3 ";
                return false;
            }
        }

        // zone = rows with the higher g/b ratio (survives colour tints). Golden tints squash g/b, so fall back to
        // brightness: the zone is always lighter than the empty track.
        float[] feat = new float[n];
        for (int i = 0; i < n; i++) feat[i] = (float) Math.log(rowQ[i]);
        int th = tBot - tTop;
        String code = zoneSplit(feat, n, darkT, tTop, h, th);
        if (code != null) {
            for (int i = 0; i < n; i++) feat[i] = (float) Math.log(rowL[i] + 3);
            String code2 = zoneSplit(feat, n, darkT, tTop, h, th);
            if (code2 != null) {
                fail += code + " ";
                return false;
            }
        }
        int bestTop = zsTop, bestBot = zsBot;
        barFound = true;
        relMode = true;
        fishVisible = true;
        // no dash warning under a tint: golden tints turn the bar's inner edge into a thin "yellow" line
        warnDir = 0;
        barX0 = x0;
        barX1 = x1;
        trackTop = tTop;
        trackBot = tBot;
        zoneTop = bestTop;
        zoneBot = bestBot;
        fishCount = cnt;
        fishFromOutline = !darkFish;
        fishY = darkFish ? (float) sumY / cnt : occY;
        fishTop = fTop;
        fishBot = fBot;
        findMarker(px, w, h, x0, x1, tTop, tBot);
        return true;
    }

    private int zsTop, zsBot;

    /** 2-means split of the per-row feature into zone / track (with optional linear detrend). null = ok. */
    private String zoneSplit(float[] lq, int n, float darkT, int tTop, int h, int th) {
        float[] res = new float[n];
        boolean[] isTrack = new boolean[n];
        for (int i = 0; i < n; i++) isTrack[i] = rowL[i] >= darkT;
        float c1 = 0, c2 = 0;
        float[] bestRes = new float[n];
        float bc1 = 0, bc2 = 0;
        boolean separated = false;
        // pass 0: flat (no trend) on all rows; later passes fit the trend only through rows judged as empty
        // track. If pass 0 cannot separate (strong gradient), retry once with a trend through all rows.
        boolean trendAll = false;
        for (int pass = 0; pass < 4; pass++) {
            boolean flat = pass == 0 && !trendAll;
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (!(pass == 0 ? rowL[i] >= darkT : isTrack[i])) continue;
                sx += i;
                sy += lq[i];
                sxx += (double) i * i;
                sxy += i * lq[i];
                m++;
            }
            if (m < 6) break;
            double den = m * sxx - sx * sx;
            double slope = (flat || den == 0) ? 0 : (m * sxy - sx * sy) / den;
            double icpt = (sy - slope * sx) / m;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                res[i] = (float) (lq[i] - (icpt + slope * i));
                if (rowL[i] < darkT) continue;
                lo = Math.min(lo, res[i]);
                hi = Math.max(hi, res[i]);
            }
            if (hi <= lo) break;
            c1 = lo;
            c2 = hi;
            for (int it = 0; it < 8; it++) {
                float s1 = 0, s2 = 0;
                int n1 = 0, n2 = 0;
                float md = (c1 + c2) / 2;
                for (int i = 0; i < n; i++) {
                    if (rowL[i] < darkT) continue;
                    if (res[i] > md) { s2 += res[i]; n2++; } else { s1 += res[i]; n1++; }
                }
                if (n1 == 0 || n2 == 0) break;
                c1 = s1 / n1;
                c2 = s2 / n2;
            }
            boolean sep = c2 - c1 > 0.16f; // ~ ratio 1.18
            if (!sep) {
                if (pass == 0 && !trendAll) {
                    trendAll = true;
                    pass = -1; // retry pass 0 with a trend through all rows
                    continue;
                }
                break; // keep the last separated result
            }
            separated = true;
            System.arraycopy(res, 0, bestRes, 0, n);
            bc1 = c1;
            bc2 = c2;
            float md = (c1 + c2) / 2;
            for (int i = 0; i < n; i++) isTrack[i] = rowL[i] >= darkT && res[i] <= md;
        }
        if (!separated) return "r5";
        float mid = (bc1 + bc2) / 2;
        int maxGap = (int) (0.14f * h);
        int bestTop = -1, bestBot = -1, bestRows = 0, gTop = -1, gBot = -1, gRows = 0;
        for (int i = 0; i < n; i++) {
            if (rowL[i] < darkT || bestRes[i] <= mid) continue;
            int y = tTop + i;
            if (gTop >= 0 && y - gBot <= maxGap) {
                gBot = y;
                gRows++;
            } else {
                if (gRows > bestRows) {
                    bestRows = gRows;
                    bestTop = gTop;
                    bestBot = gBot;
                }
                gTop = y;
                gBot = y;
                gRows = 1;
            }
        }
        if (gRows > bestRows) {
            bestRows = gRows;
            bestTop = gTop;
            bestBot = gBot;
        }
        if (bestRows < 0.03f * h || bestBot - bestTop > 0.85f * th) return "r6";
        zsTop = bestTop;
        zsBot = bestBot;
        return null;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("btn=").append(BTN_NAMES[button]).append(" n=").append(btnDist)
                .append(" @").append((int) btnCx).append(',').append((int) btnCy);
        if (barFound) {
            sb.append(relMode ? " REL" : "").append(" bar x=").append(barX0).append('-').append(barX1)
                    .append(" track=").append(trackTop).append('-').append(trackBot)
                    .append(" zone=").append(zoneTop).append('-').append(zoneBot)
                    .append(" fish=").append(fishVisible ? String.format(java.util.Locale.US, "%.1f", fishY) : "hilang")
                    .append(" n=").append(fishCount).append(fishFromOutline ? " PUDAR" : "");
            if (!Float.isNaN(markerY)) sb.append(" PINDAH").append(markerSide < 0 ? "-KIRI@" : "-KANAN@").append((int) markerY);
            if (warnDir != 0) sb.append(warnDir > 0 ? " AWAS-BAWAH" : " AWAS-ATAS");
            if (gapFound) sb.append(" CELAH@").append(wallX0).append('-').append(wallX1).append(':').append(gapTop).append('-').append(gapBot);
            else if (skyMode) sb.append(" LANGIT");
        } else sb.append(" bar=none ").append(fail);
        return sb.toString();
    }
}
