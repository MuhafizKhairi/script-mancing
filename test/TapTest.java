import com.autofish.wibu.Detector;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/** Prints the "Ikan gede nyangkut!" tap targets found in every PNG of a folder (or the given files). */
public class TapTest {
    public static void main(String[] a) throws Exception {
        File[] fs;
        if (a.length == 1 && new File(a[0]).isDirectory()) fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".png") || n.endsWith(".jpg"));
        else { fs = new File[a.length]; for (int i = 0; i < a.length; i++) fs[i] = new File(a[i]); }
        Arrays.sort(fs);
        if (System.getenv("DBG") != null) Detector.DEBUG = true;
        Detector det = new Detector();
        String mk = System.getenv("MASK"); // x0,y0,x1,y1 as screen fractions (our panel)
        if (mk != null) { String[] m = mk.split(","); det.maskFx0 = Float.parseFloat(m[0]); det.maskFy0 = Float.parseFloat(m[1]); det.maskFx1 = Float.parseFloat(m[2]); det.maskFy1 = Float.parseFloat(m[3]); }
        long worst = 0;
        for (File f : fs) {
            BufferedImage im = ImageIO.read(f);
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            det.analyze(px, w, h);
            long t0 = System.nanoTime();
            det.findTapTargets(px, w, h);
            worst = Math.max(worst, (System.nanoTime() - t0) / 1000);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < det.tapN; i++) sb.append(String.format(" (%.0f,%.0f d=%.0f)", det.tapX[i], det.tapY[i], det.tapD[i]));
            System.out.println(f.getName() + " btn=" + Detector.BTN_NAMES[det.button] + " taps=" + det.tapN + sb);
        }
        System.out.println("slowest findTapTargets: " + worst + " us");
    }
}
