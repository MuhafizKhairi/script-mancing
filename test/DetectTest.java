import com.autofish.wibu.Detector;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

public class DetectTest {
    public static void main(String[] a) throws Exception {
        File[] fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(fs);
        Detector det = new Detector();
        for (File f : fs) {
            BufferedImage im = ImageIO.read(f);
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            long t0 = System.nanoTime();
            det.analyze(px, w, h);
            long us = (System.nanoTime() - t0) / 1000;
            System.out.println(f.getName() + " " + det.describe() + " (" + us + "us)");
        }
    }
}
