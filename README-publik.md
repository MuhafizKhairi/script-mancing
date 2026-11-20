# Auto Mancing untuk Wibu Fish

> **Aplikasi ini buatan Hae** untuk mempermudah mancing di Wibu Fish.
> Kalau menemukan **bug** atau punya **usulan** untuk aplikasi ini, silakan bilang ke **Hae**.

Auto Mancing memancing otomatis di Wibu Fish: membaca layar lewat rekam layar, lalu menekan tombol lewat layanan aksesibilitas.
Tidak perlu root dan tidak perlu PC.

File: `AutoMancing-publik.apk` (versi 1.7)

## Cara pasang

1. Kirim `AutoMancing-publik.apk` ke HP, buka, lalu instal. Kalau diminta, izinkan "Instal aplikasi tidak dikenal".
2. Buka **Auto Mancing**. Akan muncul pesan "buatan Hae", tekan **Oke**.
3. Tekan **1. Buka setelan Aksesibilitas** → *Aplikasi terinstal / Downloaded apps* → **Auto Mancing** → aktifkan.
   - **Kalau ditolak ("Setelan terbatas" / "Untuk keamanan Anda, setelan ini tidak tersedia")**: ini normal di Android 13 ke atas
     untuk aplikasi yang dipasang dari WhatsApp, Telegram, browser, atau pengelola file. Bukan karena aplikasinya rusak. Caranya:
     1. Coba aktifkan sekali sampai muncul pesan penolakan itu (wajib, karena menunya baru muncul setelah ditolak sekali), lalu tekan OK.
     2. Buka *Setelan → Aplikasi → Auto Mancing* (halaman info aplikasi).
     3. Tekan **⋮** di pojok kanan atas → **Izinkan setelan terbatas** → masukkan PIN/sidik jari.
     4. Ulangi langkah 3 di atas. Sekarang tombolnya bisa diaktifkan.
   - Kalau menu ⋮ tidak ada atau tetap ditolak, kirim ke Hae screenshot pesannya plus merek HP dan versi Android.
4. Kembali ke aplikasi, tekan **2. Mulai rekam layar**. Pilih **Bagikan seluruh layar** (*Share entire screen*), **bukan** "satu aplikasi", lalu *Mulai*.

## Cara pakai

1. Buka Wibu Fish dan berdiri di spot mancing.
2. Tekan **▶** di panel kecil di kiri layar. Bot akan melempar, menunggu, strike, dan menarik ikan sendiri.
3. Tekan **■** untuk berhenti.

- Panel bisa digeser dengan menyeret tulisannya. Jangan taruh di atas bar pancing atau tombol kanan bawah.
- Setelah HP restart atau aplikasi ditutup paksa, ulangi langkah **2. Mulai rekam layar** (Android memang meminta izin ini setiap sesi).
- Bot berhenti sendiri kalau umpan habis (tombol Mancing tidak merespons) atau 2 menit tidak terlihat tombol pancing.

## Pengaturan

- **Jeda sebelum tekan Lepas!**: makin lama, lemparan makin jauh (default 0,45 detik).
- **Maks. menunggu ikan**: kalau ikan belum menggigit selama ini, bot angkat lalu lempar ulang (default 75 detik, 0 = tidak pernah).
- **Berhenti setelah N kali lempar**: 0 = tanpa batas.
- **Kompensasi delay**: kalau zona sering kelewatan (naik terlalu tinggi lalu jatuh terlalu jauh), naikkan ke 0,13–0,15.
  Kalau zona selalu telat mengejar ikan, turunkan ke 0,06–0,08.

## Kalau ada masalah

- Bot diam padahal tombol pancing terlihat: biasanya rekam layar memilih "satu aplikasi". Hentikan rekam layar, mulai ulang, lalu pilih **seluruh layar**.
- Tap tidak terjadi sama sekali: cek layanan aksesibilitas masih aktif. Di beberapa HP (Xiaomi/Oppo/Vivo), matikan *penghemat baterai* untuk Auto Mancing.
- Masalah lain, atau punya ide fitur: **bilang ke Hae**. Akan sangat membantu kalau kamu sertakan screenshot atau rekaman layar saat masalahnya terjadi,
  plus tulisan abu-abu kecil di panel (itu yang sedang dilihat bot).

## Perlu diketahui

- Ikan biasa hampir selalu tertangkap sempurna. Epic dengan "Pindah!" (2 bar) yang sekaligus kena efek hantu atau berkilau,
  dan legendary yang melesat di tengah efek kilau, kadang masih kehilangan bintang.
- Bot otomatis seperti ini bisa melanggar aturan game. Risiko akun kena sanksi ada di pemain.

Dibuat oleh Hae.
