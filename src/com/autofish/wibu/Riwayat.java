package com.autofish.wibu;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * On-phone history, DEV build only (Variant.PUBLIK == false): lets misses that happen while the phone is not
 * plugged in be read later (./ambil-riwayat.sh). Kept small on purpose:
 *
 *   riwayat/sesi-YYYYMMDD-HHMMSS.am.gz   compact text, gzip (sync-flushed every 3 s, so a killed app loses <= 3 s)
 *   riwayat/foto/*.jpg                    640px game frames, only from the minigame / its result
 *
 * Normal mode: trace 8x/s during the minigame, photos only when something went wrong (fish left the zone, bar
 * unreadable, result of a minigame that was not perfect). "Rekam semua" (REC button on the panel): every frame is
 * traced and a photo is taken 5x/s during the minigame (1x/s outside it), for studying new game mechanics; it turns
 * itself off after REC_MINUTES. Files older than 3 days or beyond MAX_BYTES are deleted, oldest first.
 *
 * Line format (ms = milliseconds since the session started), see FORMAT below.
 */
final class Riwayat {
    private Riwayat() {
    }

    static final boolean ENABLED = !Variant.PUBLIK;
    static final int REC_MINUTES = 10;
    private static final long KEEP_MS = 3L * 24 * 3600 * 1000;
    private static final long MAX_BYTES = 150L * 1024 * 1024;

    /** Written at the top of every session file, so the format is self-describing. */
    static final String FORMAT = "# E ms pesan | S ms tombol status | P ms foto | R ms 1/0 (rekam semua)\n"
            + "# F ms H/r btn  x0 x1 tTop tBot zTop zBot fishY*10 flags | zc vz fy*10 vf lead inv warn mark   (bar terbaca)\n"
            + "# F ms H/r btn  - kodeGagal | zc vz fy*10 vf lead inv warn mark                                (bar tidak terbaca)\n"
            + "# btn: 0- 1Mancing 2Lepas 3Angkat 4Strike 5Tarik; flags: R=REL P=pudar(outline) K<y>=cincin Pindah (-kiri/+kanan) "
            + "A=awas atas B=awas bawah N=ikan tak terlihat C<a-b>=celah tembok (Rintangan) L=latar langit; mode: b=bot kendali buta (perkiraan)\n";

    private static File dir, photoDir;
    private static ScheduledExecutorService io;
    private static Writer out;
    private static File curFile;
    private static long sessionStart;
    private static volatile boolean recAll;
    private static volatile long recUntil;
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    static synchronized void init(Context c) {
        if (!ENABLED || io != null) return;
        File base = c.getExternalFilesDir(null);
        if (base == null) return;
        dir = new File(base, "riwayat");
        photoDir = new File(dir, "foto");
        if (!photoDir.mkdirs() && !photoDir.isDirectory()) return;
        io = Executors.newSingleThreadScheduledExecutor();
        io.execute(Riwayat::cleanup);
        io.scheduleWithFixedDelay(Riwayat::flush, 3, 3, TimeUnit.SECONDS);
    }

    static boolean ready() {
        return io != null;
    }

    /** Starts a new session file (called when screen capture starts). */
    static void newSession(String header) {
        if (io == null) return;
        final long t = System.currentTimeMillis();
        io.execute(() -> {
            closeOut();
            sessionStart = t;
            write("# AM2 " + header + "\n" + FORMAT);
        });
    }

    private static long ms() {
        return System.currentTimeMillis() - sessionStart;
    }

    /** Raw line; the caller supplies the type letter. Time is added here. */
    static void line(final char type, final String rest) {
        if (io == null) return;
        final long t = System.currentTimeMillis();
        io.execute(() -> write(type + " " + (t - sessionStart) + " " + rest + "\n"));
    }

    static void event(String msg) {
        line('E', msg);
    }

    private static void write(String s) {
        try {
            if (out == null) {
                if (sessionStart == 0) sessionStart = System.currentTimeMillis();
                File f = new File(dir, "sesi-" + STAMP.format(new Date(sessionStart)) + ".am.gz");
                boolean fresh = !f.exists();
                if (!dir.isDirectory()) dir.mkdirs();
                OutputStream os = new GZIPOutputStream(new FileOutputStream(f, true), 8192, true);
                out = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                curFile = f;
                // file deleted meanwhile (Hapus riwayat / adb): keep the format description at the top
                if (fresh && !s.startsWith("# AM2")) out.write("# AM2 (lanjutan)\n" + FORMAT);
            }
            out.write(s);
        } catch (IOException e) {
            closeOut();
        }
    }

    private static void flush() {
        if (out != null && curFile != null && !curFile.exists()) closeOut(); // deleted from outside: reopen
        try {
            if (out != null) out.flush();
        } catch (IOException ignored) {
        }
        if (recAll && System.currentTimeMillis() > recUntil) setRecAll(false);
    }

    private static void closeOut() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** Saves the frame as JPEG; tag goes into the file name (e.g. "g12-keluar1"). */
    static void photo(int[] px, int w, int h, String tag) {
        if (io == null || px == null) return;
        final int[] copy = Arrays.copyOf(px, w * h);
        final long t = System.currentTimeMillis();
        io.execute(() -> {
            String name = (t - sessionStart) + "-" + tag + ".jpg";
            File sub = new File(photoDir, STAMP.format(new Date(sessionStart)));
            if (!sub.isDirectory() && !sub.mkdirs()) return;
            Bitmap bm = Bitmap.createBitmap(copy, w, h, Bitmap.Config.ARGB_8888);
            try (FileOutputStream fo = new FileOutputStream(new File(sub, name))) {
                bm.compress(Bitmap.CompressFormat.JPEG, recAll ? 60 : 72, fo);
            } catch (IOException ignored) {
            } finally {
                bm.recycle();
            }
            write("P " + (t - sessionStart) + " " + name + "\n");
        });
    }

    // ---------------------------------------------------------------- "rekam semua"
    static boolean recAll() {
        return recAll;
    }

    static void setRecAll(boolean on) {
        if (io == null) return;
        recAll = on;
        recUntil = System.currentTimeMillis() + REC_MINUTES * 60_000L;
        line('R', on ? "1" : "0");
        Prefs.log(on ? "Rekam semua AKTIF (" + REC_MINUTES + " menit)" : "Rekam semua mati");
    }

    /** Minutes left in "rekam semua" (0 when off). */
    static int recMinutesLeft() {
        return recAll ? (int) Math.max(0, (recUntil - System.currentTimeMillis() + 59_999) / 60_000) : 0;
    }

    // ---------------------------------------------------------------- size / delete
    private static void listAll(File d, List<File> acc) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) listAll(f, acc);
            else acc.add(f);
        }
    }

    /** Total size in bytes (call off the UI thread for big folders; it is fast for a few thousand files). */
    static long sizeBytes() {
        if (dir == null) return 0;
        List<File> all = new ArrayList<>();
        listAll(dir, all);
        long s = 0;
        for (File f : all) s += f.length();
        return s;
    }

    /** Deletes the whole history (user's "Hapus riwayat"). The current session continues in a new file. */
    static void deleteAll(final Runnable done) {
        if (io == null) return;
        io.execute(() -> {
            closeOut();
            List<File> all = new ArrayList<>();
            listAll(dir, all);
            for (File f : all) f.delete();
            File[] subs = photoDir.listFiles();
            if (subs != null) for (File f : subs) f.delete();
            if (done != null) done.run();
        });
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        List<File> all = new ArrayList<>();
        listAll(dir, all);
        File[] arr = all.toArray(new File[0]);
        Arrays.sort(arr, (a, b) -> Long.compare(b.lastModified(), a.lastModified())); // newest first
        long total = 0;
        for (File f : arr) {
            total += f.length();
            if (now - f.lastModified() > KEEP_MS || total > MAX_BYTES) f.delete();
        }
        File[] subs = photoDir.listFiles();
        if (subs != null) for (File s : subs) {
            String[] left = s.list();
            if (s.isDirectory() && left != null && left.length == 0) s.delete();
        }
    }

    static void trim() {
        if (io != null) io.execute(Riwayat::cleanup);
    }
}
