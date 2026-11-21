import com.autofish.wibu.Bot;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/** Feeds a PNG frame sequence (fps given) to the Bot and prints every tap / hold / log it makes. */
public class BotTapTest {
    public static void main(String[] a) throws Exception {
        File[] fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(fs);
        float fps = Float.parseFloat(a[1]);
        final long[] now = {0};
        Bot bot = new Bot(new Bot.Config(), new Bot.Actions() {
            public void tap(float fx, float fy) { System.out.printf("%6d TAP %.3f,%.3f%n", now[0], fx, fy); }
            public void hold(boolean down, float fx, float fy) { System.out.printf("%6d HOLD %b%n", now[0], down); }
            public void log(String m) { System.out.printf("%6d LOG %s%n", now[0], m); }
        });
        if (a.length > 2) { String[] m = a[2].split(","); bot.det.maskFx0 = Float.parseFloat(m[0]); bot.det.maskFy0 = Float.parseFloat(m[1]); bot.det.maskFx1 = Float.parseFloat(m[2]); bot.det.maskFy1 = Float.parseFloat(m[3]); }
        String last = "";
        for (int i = 0; i < fs.length; i++) {
            now[0] = 1000 + Math.round(i * 1000 / fps);
            BufferedImage im = ImageIO.read(fs[i]);
            int w = im.getWidth(), h = im.getHeight();
            bot.onFrame(im.getRGB(0, 0, w, h, null, 0, w), w, h, now[0]);
            if (!bot.status.equals(last)) { System.out.printf("%6d [%s] %s%n", now[0], fs[i].getName(), bot.status); last = bot.status; }
        }
    }
}
