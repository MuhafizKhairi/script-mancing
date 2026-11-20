import com.autofish.wibu.Detector;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
public class OneTest { public static void main(String[] a) throws Exception {
    Detector.DEBUG = true; Detector d = new Detector();
    for (String f : a) { BufferedImage im = ImageIO.read(new File(f)); int w = im.getWidth(), h = im.getHeight();
        d.analyze(im.getRGB(0, 0, w, h, null, 0, w), w, h); System.out.println(f + " " + d.describe()); } } }
