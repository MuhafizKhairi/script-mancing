import com.autofish.wibu.Detector;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** OverlayTest dir from to step out.png [cols]: runs the detector over all frames in [from,to] and tiles crops with the
 *  readings drawn (track=white, zone=green, fish=red, second bar=magenta, marker=yellow) for every step-th frame. */
public class OverlayTest {
    public static void main(String[] a) throws Exception {
        File[] fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(fs);
        int from = Integer.parseInt(a[1]), to = Integer.parseInt(a[2]), step = Integer.parseInt(a[3]);
        int cols = a.length > 5 ? Integer.parseInt(a[5]) : 12;
        Detector det = new Detector();
        java.util.List<BufferedImage> tiles = new ArrayList<>();
        int k = 0;
        for (File f : fs) {
            int n = Integer.parseInt(f.getName().replace(".png", ""));
            if (n < from || n > to) continue;
            BufferedImage im = ImageIO.read(f);
            int w = im.getWidth(), h = im.getHeight();
            det.analyze(im.getRGB(0, 0, w, h, null, 0, w), w, h);
            boolean want = a.length > 6 ? det.describe().contains(a[6]) : true;
            if (!want || k++ % step != 0) continue;
            int cx0 = (int) (0.50 * w), cw = (int) (0.17 * w);
            BufferedImage t = new BufferedImage(cw * 2, h * 2 + 24, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = t.createGraphics();
            g.drawImage(im.getSubimage(cx0, 0, cw, h), 0, 0, cw * 2, h * 2, null);
            if (det.barFound) {
                int x0 = (det.barX0 - cx0) * 2, x1 = (det.barX1 - cx0) * 2;
                g.setColor(Color.WHITE);
                g.drawLine(x0 - 10, det.trackTop * 2, x1 + 10, det.trackTop * 2);
                g.drawLine(x0 - 10, det.trackBot * 2, x1 + 10, det.trackBot * 2);
                g.setColor(Color.GREEN);
                g.setStroke(new BasicStroke(2));
                g.drawRect(x1 + 6, det.zoneTop * 2, 6, (det.zoneBot - det.zoneTop) * 2);
                if (!Float.isNaN(det.markerY)) {
                    int pitch = Math.round((det.barX1 - det.barX0 + 1) * 1.67f) * 2 * det.markerSide;
                    g.setColor(Color.MAGENTA);
                    g.setStroke(new BasicStroke(3));
                    g.drawLine(x0 + pitch, (int) (det.markerY * 2), x1 + pitch, (int) (det.markerY * 2));
                }
                if (det.fishVisible) {
                    g.setColor(Color.RED);
                    g.fillRect(x0 - 16, (int) (det.fishY * 2) - 2, 12, 5);
                }
            }
            g.setColor(Color.BLACK);
            g.fillRect(0, h * 2, cw * 2, 24);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            String d = det.describe().replaceAll("btn=\\w+ n=\\d+ @[\\d,]+ ", "");
            g.drawString(n + " " + (det.barFound ? (det.relMode ? "REL " : "") : d), 2, h * 2 + 10);
            g.drawString(det.barFound ? "" : "", 2, h * 2 + 21);
            tiles.add(t);
        }
        if (tiles.isEmpty()) return;
        int tw = tiles.get(0).getWidth(), tht = tiles.get(0).getHeight();
        int rows = (tiles.size() + cols - 1) / cols;
        BufferedImage out = new BufferedImage(tw * Math.min(cols, tiles.size()), tht * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        for (int i = 0; i < tiles.size(); i++) g.drawImage(tiles.get(i), (i % cols) * tw, (i / cols) * tht, null);
        ImageIO.write(out, "png", new File(a[4]));
    }
}
