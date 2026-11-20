import com.autofish.wibu.Detector;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

/** ffmpeg -i v.mp4 -vf fps=F,scale=640:288 -f rawvideo -pix_fmt rgb24 - | java PipeTest W H FPS [dumpDir] */
public class PipeTest {
    public static void main(String[] a) throws Exception {
        int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
        double fps = Double.parseDouble(a[2]);
        File dump = a.length > 3 ? new File(a[3]) : null;
        if (dump != null) dump.mkdirs();
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 1 << 20));
        byte[] buf = new byte[w * h * 3];
        int[] px = new int[w * h];
        Detector det = new Detector();
        for (int n = 0; ; n++) {
            try { in.readFully(buf); } catch (EOFException e) { break; }
            for (int i = 0, j = 0; i < px.length; i++, j += 3)
                px[i] = 0xff000000 | (buf[j] & 255) << 16 | (buf[j + 1] & 255) << 8 | (buf[j + 2] & 255);
            det.analyze(px, w, h);
            String d = det.describe();
            System.out.printf("%06d %.2f %s%n", n, n / fps, d);
            if (dump != null && (d.contains("btn=Tarik") || d.contains(" bar x"))) {
                BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                im.setRGB(0, 0, w, h, px, 0, w);
                ImageIO.write(im, "png", new File(dump, String.format("%06d.png", n)));
            }
        }
    }
}
