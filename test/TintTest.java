import com.autofish.wibu.Detector;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/** Applies synthetic event tints to minigame frames and checks the detector still finds zone + fish. */
public class TintTest {
    interface Tint { int apply(int r, int g, int b, int x, int y, int w, int h); }
    static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
    static int rgb(double r, double g, double b) { return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b); }

    public static void main(String[] a) throws Exception {
        File[] fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(fs);
        String[] names = {"none", "aurora(dark-blue)", "red", "purple", "rainbow", "overlay-pink-35%", "dark-50%", "gold"};
        Tint[] tints = {
            (r, g, b, x, y, w, h) -> rgb(r, g, b),
            (r, g, b, x, y, w, h) -> rgb(r * .5, g * .6, b * .68 + 5),
            (r, g, b, x, y, w, h) -> rgb(r * .95 + 30, g * .55, b * .5),
            (r, g, b, x, y, w, h) -> rgb(r * .8 + 20, g * .5, b * .9 + 25),
            (r, g, b, x, y, w, h) -> { double t = (x + y) / 120.0; return rgb(r * (.7 + .3 * Math.sin(t)), g * (.7 + .3 * Math.sin(t + 2.1)), b * (.7 + .3 * Math.sin(t + 4.2))); },
            (r, g, b, x, y, w, h) -> rgb(r * .65 + 255 * .35, g * .65 + 120 * .35, b * .65 + 200 * .35),
            (r, g, b, x, y, w, h) -> rgb(r * .5, g * .5, b * .5),
            (r, g, b, x, y, w, h) -> rgb(r * .9 + 20, g * .8 + 10, b * .45),
        };
        Detector ref = new Detector();
        int[] found = new int[tints.length], good = new int[tints.length], rel = new int[tints.length];
        int total = 0;
        for (File f : fs) {
            BufferedImage im = ImageIO.read(f);
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            ref.analyze(px, w, h);
            if (!ref.barFound || ref.button != Detector.BTN_TARIK) continue;
            total++;
            float rz = (ref.zoneTop + ref.zoneBot) / 2f, rf = ref.fishY;
            for (int t = 0; t < tints.length; t++) {
                int[] q = new int[px.length];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int c = px[y * w + x];
                    // the action button is UI drawn above the event overlay: keep it untouched
                    boolean btn = Math.hypot(x - ref.btnCx, y - ref.btnCy) < 0.165 * h;
                    q[y * w + x] = btn ? c : tints[t].apply((c >> 16) & 255, (c >> 8) & 255, c & 255, x, y, w, h);
                }
                Detector d = new Detector();
                d.analyze(q, w, h);
                if (d.barFound) {
                    found[t]++;
                    if (d.relMode) rel[t]++;
                    float z = (d.zoneTop + d.zoneBot) / 2f;
                    if (Math.abs(z - rz) <= 6 && Math.abs(d.fishY - rf) <= 6) good[t]++;
                    else if (Boolean.getBoolean("v")) System.out.printf("  %s %s zone %.0f vs %.0f fish %.0f vs %.0f%n", f.getName(), names[t], z, rz, d.fishY, rf);
                } else if (Boolean.getBoolean("v")) System.out.println("  miss " + f.getName() + " " + names[t] + " " + d.fail);
            }
        }
        System.out.println("minigame frames: " + total);
        for (int t = 0; t < tints.length; t++)
            System.out.printf("%-20s found %3d/%d  accurate %3d  (relative mode %d)%n", names[t], found[t], total, good[t], rel[t]);
    }
}
