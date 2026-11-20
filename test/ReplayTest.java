import com.autofish.wibu.Bot;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/** Feeds a folder of recorded frames (30 fps) through the full Bot and prints its log + status changes. */
public class ReplayTest {
    public static void main(String[] a) throws Exception {
        File[] fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(fs);
        final long[] t = {0};
        Bot bot = new Bot(new Bot.Config(), new Bot.Actions() {
            public void tap(float x, float y) { System.out.printf("%6d ms  tap %.2f,%.2f%n", t[0], x, y); }
            public void hold(boolean d, float x, float y) { }
            public void log(String m) { System.out.printf("%6d ms  %s%n", t[0], m); }
        });
        String last = "";
        long step = a.length > 1 ? Long.parseLong(a[1]) : 33;
        for (File f : fs) {
            BufferedImage im = ImageIO.read(f);
            int w = im.getWidth(), h = im.getHeight();
            bot.onFrame(im.getRGB(0, 0, w, h, null, 0, w), w, h, t[0]);
            String st = bot.status.replaceAll("\\(\\d+s\\)", "");
            if (!st.equals(last) && (st.contains("awas") || st.contains("tak terlihat") || !st.startsWith("Tarik"))) {
                System.out.printf("%6d ms  status: %s%n", t[0], bot.status);
            }
            last = st;
            t[0] += step;
        }
    }
}
