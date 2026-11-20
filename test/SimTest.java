import com.autofish.wibu.Bot;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;

/** Closed-loop simulation: synthetic frames -> Bot -> delayed touch -> physics. */
public class SimTest {
    static boolean actualHold, pendingHold; static long pendingAt = -1;
    static int warnDir; static boolean feint, showWarn, dashing;
    static int barX = 386; static boolean showMarker; static double markerY;
    public static void main(String[] a) {
        long seed = a.length > 0 ? Long.parseLong(a[0]) : 1;
        double difficulty = a.length > 1 ? Double.parseDouble(a[1]) : 1.0;
        Random rnd = new Random(seed);
        int W = 640, H = 288;
        int tTop = 90, tBot = 233, trackH = tBot - tTop, zoneH = 54;
        double A = 2.2 * trackH, vmax = 220;
        double zc = 200, v = 0, fish = 150, fishTarget = 150, fishSpeed = 60;
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[W * H];
        final long[] now = {0};
        Bot.Config cfg = new Bot.Config();
        cfg.latency = Float.parseFloat(System.getProperty("lat", "0.07"));
        boolean events = !Boolean.getBoolean("noevents");
        boolean dash = Boolean.getBoolean("dash");
        boolean pindah = Boolean.getBoolean("pindah");
        int pinF = 0, pinIn = 0; long jumpAt = -100000;
        cfg.anticipate = !Boolean.getBoolean("noanticipate");
        if (System.getProperty("fl") != null) cfg.fishLead = Float.parseFloat(System.getProperty("fl"));
        Bot bot = new Bot(cfg, new Bot.Actions() {
            public void tap(float fx, float fy) {}
            public void hold(boolean d, float fx, float fy) { pendingHold = d; pendingAt = now[0] + 60 + rnd.nextInt(45); }
            public void log(String m) { if (Boolean.getBoolean("verbose")) System.out.println("  [" + now[0] + "ms] " + m); }
        });
        int dashF = 0, dashIn = 0;
        int strictIn = 0, exits = 0; boolean wasStrict = true; int inZone = 0, frames = 0, winF = 0, winIn = 0; long lastWin = 0; double progress = 0.3; long caughtAt = -1;
        boolean inverted = false; double gravMul = 1;
        double simDt = 0.002; long frameEvery = 33; long nextFrame = 0;
        for (long t = 0; t < 30000; t += 2) {
            now[0] = t;
            if (pendingAt >= 0 && t >= pendingAt) { actualHold = pendingHold; pendingAt = -1; }
            if (events && t == 10000) { inverted = true;  }
            if (events && t == 16000) { inverted = false; gravMul = 1.8;  }
            if (events && t == 22000) { gravMul = 1; difficulty *= 1.5;  }
            boolean up = actualHold != inverted;
            double acc = (up ? -A : A * gravMul);
            v += acc * simDt; v = Math.max(-vmax, Math.min(vmax, v));
            zc += v * simDt;
            if (zc - zoneH / 2.0 < tTop) { zc = tTop + zoneH / 2.0; v = Math.max(0, v); }
            if (zc + zoneH / 2.0 > tBot) { zc = tBot - zoneH / 2.0; v = Math.min(0, v); }
            // legendary dash: warning line for 0.7s (sometimes jumps to the other end = feint), then a fast dash
            if (dash) {
                long ph = t % 3000;
                if (ph == 0) { warnDir = rnd.nextBoolean() ? 1 : -1; feint = rnd.nextDouble() < 0.4; }
                if (ph == 400 && feint) warnDir = -warnDir;
                showWarn = ph < 700 + (feint ? 400 : 0);
                if (ph == 700 + (feint ? 400 : 0)) { fishTarget = warnDir > 0 ? tBot - 8 : tTop + 8; fishSpeed = 400; dashing = true; }
                if (ph == 1400 + (feint ? 400 : 0)) dashing = false;
            }
            // "Pindah!": a second bar with a ring marker for 1s, then the fish jumps there (the zone keeps its height)
            if (pindah) {
                long ph = (t + 2000) % 4500;
                if (ph == 0) { showMarker = true; markerY = tTop + 15 + rnd.nextDouble() * (trackH - 30); }
                if (ph == 1000) { showMarker = false; barX = barX == 386 ? 356 : 386; fish = markerY; fishTarget = fish; jumpAt = t; }
            }
            // fish: darts to random targets
            if (!dashing && rnd.nextDouble() < 0.0025 * difficulty) { fishTarget = tTop + 8 + rnd.nextDouble() * (trackH - 16); fishSpeed = (40 + rnd.nextDouble() * 160) * difficulty; }
            double df = fishTarget - fish; double step = Math.min(Math.abs(df), fishSpeed * simDt); fish += Math.signum(df) * step;
            if (t >= nextFrame) {
                nextFrame += frameEvery;
                render(im, W, H, tTop, tBot, zc, zoneH, fish, rnd);
                if (showMarker) {
                    Graphics2D g2 = im.createGraphics();
                    int ox = barX == 386 ? 356 : 386;
                    g2.setColor(new Color(200, 235, 225)); g2.fillRoundRect(ox - 3, tTop - 5, 24, tBot - tTop + 10, 8, 8);
                    g2.setColor(new Color(90, 170, 175)); g2.fillRect(ox, tTop, 18, tBot - tTop + 1);
                    g2.setColor(new Color(240, 214, 90)); g2.setStroke(new BasicStroke(2));
                    g2.drawOval(ox - 3, (int) markerY - 12, 24, 24); g2.fillRect(ox - 3, (int) markerY - 1, 24, 2);
                    g2.dispose();
                }
                if (showWarn) { Graphics2D g2 = im.createGraphics(); g2.setColor(new Color(237, 229, 118)); int wy = warnDir > 0 ? tBot - 6 : tTop + 6; g2.fillRect(386, wy, 18, 2); g2.dispose(); }
                im.getRGB(0, 0, W, H, px, 0, W);
                bot.onFrame(px, W, H, t);
                if (t >= Long.getLong("tr0", -1) && t <= Long.getLong("tr1", -1))
                    System.out.printf("  %5d true zone=%.0f fish=%.0f mk=%s | %s | %s%n", t, zc, fish, showMarker ? String.format("%.0f", markerY) : "-", bot.det.describe(), bot.status);
                frames++;
                boolean in = Math.abs(fish - zc) <= zoneH / 2.0;
                if (in) inZone++;
                boolean sIn = fish - 7 >= zc - zoneH / 2.0 && fish + 7 <= zc + zoneH / 2.0;
                if (sIn) strictIn++; else if (wasStrict && t > 1500) exits++;
                wasStrict = sIn;
                if (dashWin(t)) { dashF++; if (in) dashIn++; }
                if (t - jumpAt < 300) { pinF++; if (in) pinIn++; }
                progress += (in ? 0.012 : -0.008);
                progress = Math.max(0, Math.min(1, progress));
                winF++; if (in) winIn++;
                if (t / 2000 != lastWin && Boolean.getBoolean("verbose")) { System.out.printf("t=%5d..  in-zone %3.0f%%  zone=%.0f fish=%.0f%n", lastWin * 2000, 100.0 * winIn / winF, zc, fish); } if (t / 2000 != lastWin) { lastWin = t / 2000; winF = winIn = 0; }
            }
        }
        if (pindah) System.out.printf("PINDAH in-zone %.1f%% of %d frames right after jumps%n", 100.0 * pinIn / Math.max(1, pinF), pinF);
        if (dash) System.out.printf("DASH in-zone %.1f%% of %d frames during dashes%n", 100.0 * dashIn / Math.max(1, dashF), dashF);
        System.out.printf("RESULT seed=%d diff=%.1f fish-in-zone %.1f%% strict %.1f%% exits %d of %d frames%n", seed, difficulty, 100.0 * inZone / frames, 100.0 * strictIn / frames, exits, frames);
    }
    static boolean dashWin(long t) { long ph = t % 3000, st = 700 + (feint ? 400 : 0); return dashing || (ph >= st && ph < st + 900); }
    static void render(BufferedImage im, int W, int H, int tTop, int tBot, double zc, int zoneH, double fish, Random rnd) {
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(40, 122, 153)); g.fillRect(0, 0, W, H);
        int bxl = barX;
        g.setColor(new Color(253, 246, 228)); g.fillRoundRect(bxl - 3, tTop - 5, 24, tBot - tTop + 10, 8, 8);
        g.setColor(new Color(40, 138, 152)); g.fillRect(bxl, tTop, 18, tBot - tTop + 1);
        int z0 = (int) Math.round(zc - zoneH / 2.0);
        g.setColor(new Color(110, 182, 134)); g.fillRect(bxl, z0, 18, zoneH);
        g.setColor(new Color(225, 255, 250)); g.fillRect(bxl, z0, 18, 1);
        g.setColor(new Color(5, 22, 16)); g.fillOval(bxl - 4, (int) fish - 7, 26, 14);
        // progress bar (yellow) and Tarik button
        g.setColor(new Color(63, 42, 38)); g.fillRect(414, tTop, 8, tBot - tTop);
        g.setColor(new Color(244, 209, 90)); g.fillRect(414, 180, 8, tBot - 180);
        int bx = (int) (W - 0.141 * H), by = (int) (0.863 * H), r = 34;
        g.setColor(new Color(250, 248, 240)); g.fillOval(bx - r - 4, by - r - 4, 2 * r + 8, 2 * r + 8); // white outline like the game
        g.setColor(new Color(47, 122, 57)); g.fillOval(bx - r, by - r, 2 * r, 2 * r);
        g.setColor(Color.WHITE); g.drawString("Tarik", bx - 14, by + 4);
        g.dispose();
    }
}
