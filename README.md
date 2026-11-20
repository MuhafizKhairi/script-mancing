# Auto Mancing (Wibu Fish)

> **Aplikasi ini buatan Hae** untuk mempermudah mancing di Wibu Fish.

Aplikasi Android sederhana yang otomatis mancing di game Wibu Fish:
membaca layar lewat rekam layar, lalu menekan tombol lewat layanan aksesibilitas. **Tidak perlu root dan tidak perlu PC** setelah terinstal.

## Dua versi APK (versi 1.9, ≈57 KB)

| File | Untuk siapa | Bedanya |
|---|---|---|
| `AutoMancing-dev.apk` | dipakai Hae sendiri, **tidak ada di git** | tanpa pop up, ada riwayat + tombol REC, hanya jalan di HP yang terdaftar |
| `AutoMancing-publik.apk` | dibagikan ke orang lain | tiap aplikasi dibuka muncul pop up *"Aplikasi ini buatan Hae… kalau ada bug atau usulan, bilang ke Hae"*, dan teks yang sama ada di layar utama |

Perbaikan bug botnya sama persis di kedua versi; riwayat, tombol REC, dan hapus riwayat hanya ada di versi dev. Keduanya memakai nama paket yang sama, jadi di satu HP cukup pasang salah satu.
Untuk orang lain, bagikan `AutoMancing-publik.apk` bersama `README-publik.md` (panduan singkat tanpa bagian teknis).

## Versi dev terkunci

`AutoMancing-dev.apk` dan `dev-devices.txt` ada di `.gitignore`, jadi orang yang dapat link git hanya bisa memasang versi publik.
Selain itu versi dev hanya mau jalan di HP yang kodenya ada di `dev-devices.txt` (satu kode 16 huruf per baris; kodenya dari hash ANDROID_ID, unik per HP).
Di HP lain aplikasinya hanya menampilkan "Versi dev ini terkunci" beserta kode HP tersebut, dan bot tidak bisa dijalankan.
Menambah HP baru: pasang versi dev, catat kode yang tampil (atau dari logcat `AutoMancing`), tambahkan ke `dev-devices.txt`, lalu `./build.sh dev` dan pasang ulang.
Kode HP berubah kalau HP di-reset pabrik.
Batasan: kode sumbernya tetap ada di git, jadi orang yang paham pemrograman bisa membuang kuncinya dan build sendiri. Kalau mau benar-benar tertutup, jadikan repo GitHub-nya private.

## Riwayat versi

- **1.9**: mekanik legend baru dari rekaman REC (Hiu Kristal, Legendary, 30 + 47 detik) dan perbaikan kecil "Rare tidak sempurna".
  - **"Rintangan! Lewati celah tembok ikan"**: layar berubah jadi langit, tembok ikan bergeser dari kiri ke bar. Saat tembok sampai di bar,
    **zona** (bukan ikan) harus ada di celahnya (tulisan TURUN/NAIK/PAS! ada di tinggi zona; kena tembok = "Tali -2/3%").
    Bot sekarang membaca tembok terdekat dan celahnya, lalu membawa zona ke tengah celah; selama tembok lewat di atas bar, celah itu dipegang dulu.
    Panel menampilkan `[rintangan] celah a-b`, deteksi `CELAH@…`.
  - Di latar langit, rumput hijau di bawah layar dulu membuat bar tidak terbaca sama sekali dan tombol Tarik juga tidak terbaca
    (rumput sewarna tombol). Minigame dianggap selesai lalu "mulai" lagi 5 kali dalam satu ikan. Sekarang bar terbaca di hampir semua frame Rintangan,
    tombol terbaca di semua frame, dan jeda pergantian adegan legend (spanduk "RINTANGAN!", "NAIK KE DARAT!") sampai 1,8 detik tidak mengakhiri minigame.
  - **"Pindah!" dengan 4 bar**: bar paling kiri lewat di bawah panel bot sendiri. Bagian yang tertutup panel sekarang diabaikan, dan ujung trek
    yang tertutup ikan/panel dihitung dari panjang trek yang sudah diketahui.
  - Cahaya di sekitar ikan legend dan garis celah tidak lagi ikut terbaca sebagai zona (zona dulu terbaca 2x lebih tinggi). Garis kuning tanda melesat
    di ujung bawah tidak lagi dikira ujung trek.
  - **Rare/Uncommon "keluar zona" di awal minigame**: semua kasus di log terjadi ±0,6 detik setelah mulai (zona kebablasan melewati ikan yang baru mulai bergerak).
    Di 0,9 detik pertama bot sekarang memperkirakan lebih jauh ke depan supaya tidak kebablasan.
  - Riwayat: flag `C<a-b>` (celah tembok) dan `L` (latar langit) di baris F.
  - Belum: fase "Ke darat! Hindari ikan-ikan itu" (bot tetap mengikuti ikan seperti biasa) dan kilau "Berkilau: silau sebentar" yang menutupi bar.
- **1.8**: perbaikan utama "banyak miss, sampah pun miss". Penyebabnya: rumput/pantai di kiri layar terbaca sebagai bar pancing.
  Bot lalu menahan tombol terus (zona nempel di atas), ikan keluar zona, bintang hilang, bahkan ikan lolos ("Lolos", Albino Epic).
  Di log 19 menit (55 minigame) terjadi di 16 minigame, kadang cuma 0,7 detik pertama (bintang tetap hilang), kadang sepanjang minigame.
  Sekarang bar harus punya garis tepi krem di **kedua sisi** trek, jadi rumput tertolak. Di 4 rekaman (±2.370 frame Tarik), bar asli terbaca di semua frame kecuali frame transisi ke kartu hasil.
  "Awas melesat" dan "Balik!" palsu yang berasal dari bar rumput ikut hilang.
  Minigame boss (bar bergeser kiri-kanan melintasi layar) sekarang bisa dikejar sampai ke pinggir kiri layar.
  Kalau zona tertahan di ujung ke arah mana pun bot menekan, bot tidak lagi membalik kontrol bolak-balik tiap 0,7 detik.
  **Khusus versi dev: riwayat di HP**, supaya miss saat HP tidak dicolok bisa dicek belakangan (`./ambil-riwayat.sh`, atau `--hapus` untuk sekalian menghapus di HP).
  - Normal (hemat): log ringkas berkode, digzip (±1 KB per menit minigame), plus foto 640px hanya saat ada masalah: ikan pertama kali keluar zona (+0,4 detik sebelumnya),
    bar tidak terbaca 0,5 detik, dan hasil minigame yang tidak sempurna atau lebih dari 15 detik (kartu / "Lolos").
  - Tombol **REC** di panel: rekam semua selama 10 menit (trace setiap frame, foto 5x/detik saat minigame, 1x/detik saat tombol pancing terlihat), untuk mempelajari mekanik baru. Mati sendiri.
  - Di aplikasi ada ukuran riwayat dan tombol **Hapus riwayat**. Riwayat lebih dari 3 hari atau di atas 150 MB terhapus sendiri.
  - Juga diperbaiki: setelah lempar, tombol biru "…" kadang bertahan >1,5 detik (lag). Bot dulu mengira itu Mancing, menekannya, dan lemparan terulang terus (macet). Sekarang bot menunggu 5 detik; kalau lemparan tetap macet, ada foto "macet".
  - Foto hanya diambil dari layar game (minigame, atau saat tombol pancing terlihat).
  Versi publik tidak menyimpan apa pun.
- **1.7**: kartu hasil (mis. "+1 Skill Point") tidak lagi dikira minigame. Sebelumnya bot bisa diam ~24 detik tanpa menutup kartu.
  "Pindah" palsu dari bar progres kuning dihilangkan. Ada log diagnosis kendali (logcat `AMDbg`). Tersedia versi pribadi dan publik.
- **1.6**: antisipasi "Pindah!" tidak lagi menaruh ikan mepet di pinggir zona. Saat bar tertutup efek (lingkaran kilau, petir),
  bot tetap mengendalikan zona dengan perkiraan selama maksimal 1,5 detik. Log "keluar zona" sekarang menghitung badan ikan.
  Cincin Pindah yang redup saat gelap ikut terbaca.
- **1.5**: event gelap "Badai Petir": bar terbaca lagi (98% frame). Sebelumnya bot bisa salah mengingat posisi bar dan "buta" sepanjang event.
- **1.4**: bot tidak lagi berhenti mancing saat garis progres kuning di pinggir tombol Mancing makin panjang.

## Yang dilakukan bot

| Tombol di kanan bawah | Aksi bot |
|---|---|
| **Mancing** (biru) | tap untuk mulai |
| **Lepas!** (oranye) | tunggu sebentar (power meter), lalu tap |
| **Angkat** (abu-abu) | tunggu ikan. Kalau terlalu lama (default 75 dtk), angkat lalu lempar ulang |
| **Strike!** (merah muda) | langsung tap |
| **Tarik** (hijau) | minigame: tahan/lepas supaya zona hijau selalu menutupi ikan |
| kartu hasil menutupi tombol | tap kartunya untuk menutup |

Minigame Tarik: zona hijau dicari ulang di **setiap frame**, jadi bar yang pindah posisi ("Pindah!") tetap terkejar.
Kontrolnya memperhitungkan kecepatan zona dan gerak ikan, bukan cuma "ikan di atas → tahan".
Untuk ikan **Legendary ke atas**:
- **Balik!** (kontrol terbalik): bot mendeteksi sendiri kalau "tahan" malah bikin zona turun, lalu membalik kontrolnya (sekitar 1 detik), dan kembali normal saat event selesai.
- **Gravitasi! / Melaju! / Goyang!**: percepatan naik dan turun diukur terus selama main, jadi bot ikut menyesuaikan.

**Tanda kuning (ikan legend mau melesat)**: begitu garis kuning muncul di bawah/atas trek, bot menggeser zona ke arah itu
tapi ikan tetap di dalam zona (di dekat tepi seberangnya). Kalau ikan benar melesat, zona sudah setengah jalan; kalau itu tipuan
dan tandanya pindah ke ujung lain, bot langsung ikut pindah arah tanpa kehilangan progres. Panel menampilkan `AWAS-BAWAH` / `AWAS-ATAS`.

**"Pindah!" (2 bar, Epic ke atas)**: bar kedua muncul di kiri dengan cincin kuning, lalu ~1 detik kemudian ikan lompat ke bar itu
di ketinggian cincin (zona tetap di ketinggiannya). Begitu cincin terlihat, bot menggeser zona supaya menutupi ikan **dan** sebisa
mungkin ketinggian cincin, lalu langsung mengikuti bar yang baru. Panel menampilkan `PINDAH-KIRI@y`.

**Ikan memudar / berkilau** (mutasi "Hantu: sisik memudar", "Berkilau", efek cahaya): ikan berubah jadi siluet abu-abu atau tertutup
kilatan putih. Bot tetap menemukan ikannya dari **bayangan yang menutupi garis tepi bar** (ikon ikan selalu lebih lebar dari bar).
Panel menampilkan `PUDAR`. Kalau ikan benar-benar hilang, bot meneruskan posisi dari arah geraknya (`fish=hilang`).

**Tangkapan sempurna (bintang)**: menurut pengamatan Hae, di bawah tier Legendary bintang hilang begitu zona lepas dari ikan sedikit saja,
sedangkan Legendary ke atas punya toleransi (di rekaman, legendary tetap dapat bintang walau sering keluar zona).
Bot mengarahkan zona supaya ikan ada di tengahnya. Di log aplikasi setiap minigame ditutup dengan
`ikan keluar zona Nx` (`0x = sempurna`). Sejak 1.6 yang dihitung adalah **seluruh badan ikan**, jadi angkanya lebih ketat daripada versi lama.
Belum pasti apakah game memakai badan atau titik tengah ikan: di satu rekaman, ikan Rare tetap dapat bintang walau separuh badannya di luar zona.

**Event map** (Aurora, merah, pelangi, ungu, dll.) yang mewarnai/menggelapkan layar: kalau warna bar tidak cocok lagi,
bot membaca bar di posisi terakhirnya dengan **perbandingan relatif** (zona selalu lebih "hijau-dibanding-biru" daripada trek,
ikan selalu jauh lebih gelap, tepi bar selalu lebih terang). Panel menampilkan `REL` kalau mode ini sedang dipakai.
Tombol dikenali dari warnanya **plus cincin putih** di tepinya, yang tidak ikut terwarnai event.

Bot berhenti sendiri kalau tombol Mancing tidak merespons 6 kali berturut-turut (biasanya karena umpan habis),
kalau 2 menit tidak ada tombol pancing yang terlihat, atau setelah N kali lempar (bisa diatur).

## Instalasi

1. Kirim `AutoMancing-publik.apk` (Hae sendiri: `AutoMancing-dev.apk`, dipasang lewat adb) ke HP (WhatsApp ke diri sendiri, Google Drive, kabel USB, dll.), lalu buka dan instal.
   Kalau diminta, izinkan "Instal aplikasi tidak dikenal".
2. Buka aplikasi **Auto Mancing**, tekan **1. Buka setelan Aksesibilitas** → *Aplikasi terinstal / Downloaded apps* → **Auto Mancing** → aktifkan.
   - **Android 13 ke atas**: kalau tombolnya abu-abu/"Setelan terbatas", buka *Setelan → Aplikasi → Auto Mancing → ⋮ (pojok kanan atas) → Izinkan setelan terbatas*, lalu ulangi langkah ini.
3. Kembali ke aplikasi, tekan **2. Mulai rekam layar**. Pada dialog, pilih **Bagikan seluruh layar** (*Share entire screen*), **bukan** "satu aplikasi", lalu *Mulai*.
4. Buka Wibu Fish, berdiri di spot mancing, lalu tekan **▶** di panel kecil sebelah kiri layar.
5. Tekan **■** untuk berhenti.

Panel bisa digeser dengan menyeret teksnya. Taruh di tempat yang tidak menutupi bar pancing atau tombol kanan bawah.

Setelah HP restart atau aplikasi ditutup paksa, ulangi langkah 3 (izin rekam layar memang harus diberikan ulang setiap sesi oleh Android).

## Pengaturan (di dalam aplikasi)

- **Jeda sebelum tekan Lepas!**: default 0,45 dtk. Makin lama, power meter makin penuh (lemparan lebih jauh).
- **Maks. menunggu ikan**: default 75 dtk, 0 = tidak pernah angkat paksa.
- **Berhenti setelah N kali lempar**: 0 = tanpa batas.
- **Kompensasi delay**: default 0,10 dtk. Kalau zona sering *kelewatan* (naik terlalu tinggi lalu jatuh terlalu jauh), naikkan ke 0,13–0,15. Kalau zona *selalu telat* mengejar ikan, turunkan ke 0,06–0,08.

## Kalau tidak jalan

Baris abu-abu kecil di panel memperlihatkan apa yang dilihat bot, misalnya:

```
btn=Angkat n=414 @602,248 bar=none
btn=Tarik n=380 @590,239 bar x=386-403 track=90-233 zone=131-186 fish=157.3
```

- `btn=-` padahal tombolnya kelihatan → biasanya izin rekam layar memilih "satu aplikasi". Stop rekam layar, mulai ulang, lalu pilih **seluruh layar**.
- `bar=none` saat minigame → kirim screenshot minigame-nya (kode setelah `bar=none`, misalnya `r3`/`r5`, menunjukkan langkah yang gagal), nanti detektornya disesuaikan.
- Tap tidak terjadi sama sekali → cek layanan aksesibilitas masih aktif (beberapa HP Xiaomi/Oppo/Vivo mematikannya otomatis; matikan *penghemat baterai* untuk Auto Mancing).
- Log lengkap ada di bagian bawah aplikasi Auto Mancing.

## Batasan yang perlu diketahui

- Mode event diuji pada 2 screenshot Aurora asli (posisi zona & ikan tepat) dan 116 frame minigame yang diberi 7 macam efek warna buatan
  (gelap, Aurora, merah, ungu, pelangi bergradien, overlay pink 35%, emas): 92–97% frame terbaca akurat.
- Video legendary (Hiu Kristal) dan video Kabut Misterius kamu: bar terbaca di 381/383 dan 91/91 frame minigame, tanda kuning
  terdeteksi tepat waktu (bawah di detik 4,3, atas di detik 9,2), tanpa tanda palsu di semua video lain.
  Di simulasi dengan ikan melesat + 40% tipuan, antisipasi menaikkan "ikan di zona saat melesat" dari 54% ke 67%.
- Video Gerhana Bulan dan Senja Keemasan (±1.800 dan ±1.500 frame minigame): bar terbaca di **99,2%** dan **98,3%** frame
  (sebelumnya 93% dan 82,5%), termasuk saat event + "Pindah!", ikan hantu, dan kilatan. Semua kejadian "Pindah!" terdeteksi, tanpa tanda palsu.
- Di simulasi "Pindah!" dengan posisi cincin acak, ikan langsung ada di zona setelah lompat di 56% kasus (tanpa antisipasi 50%).
  Kalau jarak ikan ke cincin lebih dari tinggi zona, zona memang tidak bisa menutupi keduanya sekaligus.
- Tanda kuning (melesat) belum dibaca saat event mewarnai layar: di warna keemasan, tepi bar ikut terlihat seperti garis kuning.
- Diuji pada frame dari video gameplay dan di emulator dengan game tiruan. Sejak 1.4 juga diuji langsung di game asli
  (ASUS ROG, ratusan tangkapan). Ikan biasa hampir selalu sempurna.
- Masih bermasalah (per 1.7): epic "Pindah!" yang sekaligus kena efek **hantu** atau **berkilau** saat gelap (cincin tidak terbaca,
  ikan tak terlihat ~1 detik), dan legendary **Berkilau** yang melesat di tengah lingkaran cahaya (zona telat ~1 detik).
  Tema malam (18:00–06:00) belum diuji khusus.
- Klip legendary di video resolusinya sangat kecil. Fase **Rintangan! / Lewat celah / Hindari ikannya!** belum jelas mekaniknya;
  di fase itu bot tetap menjaga zona menutupi ikan. Kalau ternyata caranya beda, kirim rekaman layar fase itu dengan resolusi penuh.
- Bot otomatis seperti ini bisa melanggar aturan game. Risiko akun kena sanksi ada di pemain.

## Untuk developer

```
src/com/autofish/wibu/
  Detector.java      deteksi tombol + bar (Java murni, bisa dites di PC)
  Bot.java           state machine + kontroler minigame (Java murni)
  CaptureService.java  MediaProjection → ImageReader 640px → Bot
  TouchService.java    AccessibilityService: tap, tahan (continued strokes), panel melayang
  MainActivity.java    layar pengaturan
test/
  DetectTest.java    jalankan detektor pada folder PNG (frame 640px)
  SimTest.java       simulasi tertutup: frame sintetis + fisika + delay sentuh
  TintTest.java      uji deteksi pada frame minigame dengan efek warna event buatan
  ReplayTest.java    putar ulang frame rekaman lewat Bot lengkap (log reaksi bot)
  fakegame/          game tiruan untuk uji ujung-ke-ujung di emulator
build.sh             build APK hanya dengan Android SDK (tanpa Gradle)
```

Build ulang: `./build.sh` membuat kedua APK. Bisa juga `./build.sh dev` atau `./build.sh publik` saja
(butuh Android SDK di `~/Library/Android/sdk` dan JDK). Bedanya cuma konstanta `Variant.PUBLIK` yang dibuat oleh `build.sh`
(`build/gen/com/autofish/wibu/Variant.java`). Pop up dan teks kredit ada di `MainActivity.java` (`CREDIT`).

Dibuat oleh Hae.
