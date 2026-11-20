package com.autofish.wibu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PROJECTION = 1;
    /** Credit shown in the public build (pop up + main screen). */
    static final String CREDIT = "Aplikasi ini buatan Hae untuk mempermudah mancing di Wibu Fish.\n"
            + "Kalau menemukan bug atau punya usulan untuk aplikasi ini, silakan bilang ke Hae.";

    private TextView accStatus, capStatus, logView;
    private EditText castDelay, maxWait, maxCasts, latency;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Auto Mancing");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Cara pakai:\n"
                + "1. Aktifkan layanan aksesibilitas \"Auto Mancing\" (sekali saja).\n"
                + "2. Tekan \"Mulai rekam layar\" dan pilih SELURUH LAYAR (bukan satu aplikasi).\n"
                + "3. Buka game, berdiri di spot mancing, lalu tekan ▶ di panel kecil sebelah kiri.\n"
                + "4. Tekan ■ untuk berhenti. Bot juga berhenti sendiri kalau tombol Mancing tidak merespons (umpan habis).\n\n"
                + "Panel bisa digeser dengan menyeret teksnya. Baris abu-abu di panel menunjukkan apa yang dilihat bot "
                + "(btn=Mancing / Lepas / Angkat / Strike / Tarik).");
        info.setPadding(0, dp(8), 0, dp(8));
        root.addView(info);

        if (Variant.PUBLIK) {
            TextView credit = new TextView(this);
            credit.setText(CREDIT);
            credit.setTextColor(0xFF1565C0);
            credit.setPadding(0, 0, 0, dp(8));
            root.addView(credit);
            // shown every time the app is opened (not on screen rotation)
            if (savedInstanceState == null) {
                new AlertDialog.Builder(this)
                        .setTitle("Auto Mancing \u2014 buatan Hae")
                        .setMessage(CREDIT)
                        .setPositiveButton("Oke", null)
                        .show();
            }
        }

        accStatus = new TextView(this);
        root.addView(accStatus);
        Button acc = new Button(this);
        acc.setText("1. Buka setelan Aksesibilitas");
        acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc);

        capStatus = new TextView(this);
        capStatus.setPadding(0, dp(8), 0, 0);
        root.addView(capStatus);
        Button cap = new Button(this);
        cap.setText("2. Mulai rekam layar");
        cap.setOnClickListener(v -> {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        });
        root.addView(cap);
        Button stop = new Button(this);
        stop.setText("Stop rekam layar");
        stop.setOnClickListener(v -> stopService(new Intent(this, CaptureService.class)));
        root.addView(stop);

        TextView st = new TextView(this);
        st.setText("\nPengaturan");
        st.setTextSize(18);
        st.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(st);

        Bot.Config cfg = Prefs.load(this);
        castDelay = field(root, "Jeda sebelum tekan Lepas! (detik, makin lama makin jauh lemparan)", fmt(cfg.castDelay), true);
        maxWait = field(root, "Maks. menunggu ikan sebelum angkat & lempar ulang (detik, 0 = tidak pernah)", fmt(cfg.maxWait), true);
        maxCasts = field(root, "Berhenti setelah N kali lempar (0 = tanpa batas)", String.valueOf(cfg.maxCasts), false);
        latency = field(root, "Kompensasi delay (detik). Naikkan jika zona sering kelewatan, turunkan jika telat", fmt(cfg.latency), true);
        Button save = new Button(this);
        save.setText("Simpan pengaturan");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);

        TextView lt = new TextView(this);
        lt.setText("\nLog");
        lt.setTextSize(18);
        lt.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lt);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        root.addView(logView);

        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    private static String fmt(float f) {
        return String.format(Locale.US, "%.2f", f);
    }

    private EditText field(LinearLayout root, String label, String value, boolean decimal) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(8), 0, 0);
        root.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        root.addView(e);
        return e;
    }

    private void saveSettings() {
        Bot.Config cfg = Prefs.load(this);
        try {
            cfg.castDelay = Float.parseFloat(castDelay.getText().toString());
            cfg.maxWait = Float.parseFloat(maxWait.getText().toString());
            cfg.maxCasts = Integer.parseInt(maxCasts.getText().toString());
            cfg.latency = Float.parseFloat(latency.getText().toString());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Angka tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.save(this, cfg);
        Toast.makeText(this, "Tersimpan (berlaku saat ▶ ditekan lagi)", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Izin rekam layar ditolak", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class);
        svc.putExtra("code", resultCode);
        svc.putExtra("data", data);
        startForegroundService(svc);
        Toast.makeText(this, "Sekarang buka game lalu tekan ▶ di panel", Toast.LENGTH_LONG).show();
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            boolean accOn = TouchService.instance != null;
            accStatus.setText(accOn ? "✔ Aksesibilitas aktif" : "✘ Aksesibilitas belum aktif");
            accStatus.setTextColor(accOn ? 0xFF2E7D32 : Color.RED);
            boolean capOn = CaptureService.instance != null;
            capStatus.setText(capOn ? "✔ Rekam layar aktif" : "✘ Rekam layar belum aktif");
            capStatus.setTextColor(capOn ? 0xFF2E7D32 : Color.RED);
            logView.setText(Prefs.logText());
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }
}
