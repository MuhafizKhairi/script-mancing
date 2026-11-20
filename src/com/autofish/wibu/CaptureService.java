package com.autofish.wibu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Foreground service that mirrors the screen (MediaProjection) into a small 640px-wide ImageReader and
 * feeds every frame to the {@link Bot}.
 */
public class CaptureService extends Service {

    public static volatile CaptureService instance;

    private static final String CHANNEL = "automancing";
    private static final int CAP_W = 640;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;

    private volatile boolean active;
    private Bot bot;
    private final Detector idleDet = new Detector();
    private int[] pixels;
    private byte[] buf;
    private long lastIdleAnalyse, lastDbgMs;

    private volatile String debug = "";
    private int frames;
    private long fpsStart;
    private volatile float fps;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (projection != null) return START_NOT_STICKY;
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            Prefs.log("Gagal memulai rekam layar: " + e);
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Prefs.log("Rekam layar dihentikan oleh sistem");
                stopSelf();
            }
        }, handler);
        setupDisplay();
        instance = this;
        Prefs.log("Rekam layar aktif (" + reader.getWidth() + "x" + reader.getHeight() + ")");
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Auto Mancing", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Auto Mancing")
                .setContentText("Rekam layar aktif. Tekan ▶ di panel melayang.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
    }

    private void setupDisplay() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        // the game is landscape-only: always capture in landscape
        int W = Math.max(dm.widthPixels, dm.heightPixels), H = Math.min(dm.widthPixels, dm.heightPixels);
        int capH = Math.round((float) CAP_W * H / W) & ~1;
        reader = ImageReader.newInstance(CAP_W, capH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);
        display = projection.createVirtualDisplay("automancing", CAP_W, capH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        fpsStart = System.currentTimeMillis();
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = img.getTimestamp() / 1_000_000L;
            boolean run = active && bot != null;
            if (!run) {
                // idle: analyse twice per second so the panel can show what the bot would see
                if (now - lastIdleAnalyse < 500) return;
                lastIdleAnalyse = now;
            }
            int w = img.getWidth(), h = img.getHeight();
            convert(img, w, h);
            frames++;
            long t = System.currentTimeMillis();
            if (t - fpsStart >= 1000) {
                fps = frames * 1000f / (t - fpsStart);
                frames = 0;
                fpsStart = t;
            }
            if (run) {
                bot.onFrame(pixels, w, h, now);
                debug = bot.det.describe() + String.format(Locale.US, "  %.0ffps", fps);
                // diagnosis trace (logcat tag AMDbg, 8x per second during the minigame): what the bot sees + decides
                if (bot.status.startsWith("Tarik") && now - lastDbgMs >= 125) {
                    lastDbgMs = now;
                    android.util.Log.d("AMDbg", (bot.isHolding() ? "HOLD " : "rel  ") + bot.status + " | " + bot.det.describe().replace('\n', ' ') + " | " + bot.dbg);
                }
                if (bot.stopped) {
                    active = false;
                    Prefs.log(bot.status);
                }
            } else {
                idleDet.analyze(pixels, w, h);
                debug = idleDet.describe();
            }
        } catch (Exception e) {
            Prefs.log("Error: " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    private void convert(Image img, int w, int h) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer bb = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int n = bb.remaining();
        if (buf == null || buf.length < n) buf = new byte[n];
        bb.get(buf, 0, n);
        if (pixels == null || pixels.length != w * h) pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int o = y * rs, i = y * w;
            for (int x = 0; x < w; x++, o += ps) {
                pixels[i + x] = 0xFF000000 | ((buf[o] & 255) << 16) | ((buf[o + 1] & 255) << 8) | (buf[o + 2] & 255);
            }
        }
    }

    // ------------------------------------------------------------------ control from the panel
    public boolean isActive() {
        return active;
    }

    public void setActive(final boolean on) {
        handler.post(() -> {
            if (on) {
                if (TouchService.instance == null) {
                    Prefs.log("Aktifkan dulu layanan aksesibilitas Auto Mancing");
                    return;
                }
                bot = new Bot(Prefs.load(this), new Bot.Actions() {
                    @Override
                    public void tap(float fx, float fy) {
                        TouchService t = TouchService.instance;
                        if (t != null) t.tap(fx, fy);
                    }

                    @Override
                    public void hold(boolean down, float fx, float fy) {
                        TouchService t = TouchService.instance;
                        if (t != null) t.setHold(down, fx, fy);
                    }

                    @Override
                    public void log(String msg) {
                        Prefs.log(msg);
                    }
                });
                active = true;
                Prefs.log("Bot mulai");
            } else {
                active = false;
                if (bot != null && !bot.stopped) bot.stop("dihentikan");
            }
        });
    }

    public String statusLine() {
        Bot b = bot;
        if (b == null) return "";
        return b.status + String.format(Locale.US, "\nLempar %d · Strike %d · Tarik %d", b.casts, b.strikes, b.minigames);
    }

    public String debugLine() {
        return debug;
    }

    @Override
    public void onDestroy() {
        active = false;
        instance = null;
        TouchService t = TouchService.instance;
        if (t != null) t.setHold(false, 0, 0);
        try {
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
