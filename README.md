# Nala HUD — asisten kacamata Rokid: PDF, catatan pribadi, dan tanya-jawab suara

Dua aplikasi Android dalam satu proyek:

| Modul | Dipasang di | Nama aplikasi | Fungsi |
|---|---|---|---|
| `phone` | HP Android | **Nala HUD** | Otak: PDF, catatan, suara, Claude API |
| `glasses` | Rokid Glasses (YodaOS-Sprite) | **Nala HUD** | Layar & tombol: tampilkan di HUD |
| `common` | (dipakai keduanya) | – | Protokol, koneksi, logika zoom |

## Cara kerja

1. HP membuka PDF, lalu mengirim **file-nya sekali** ke kacamata lewat Bluetooth.
   Kalau kacamata sudah pernah menerima file yang sama (dicek dengan sidik jari SHA-256), pengiriman dilewati.
2. Setiap Anda pinch-zoom atau menggeser di HP, HP hanya mengirim **posisi pandang**
   (halaman, x, y, lebar), sekitar 40 byte per pesan. Pesan beruntun digabung, jadi yang terkirim selalu posisi terbaru.
3. Kacamata merender PDF **langsung dari file vektornya** tepat di area tersebut, jadi teks tetap tajam.
   Warna dibalik: teks terang di latar hitam (hitam = transparen di layar Micro-LED).
4. Kotak hijau di layar HP = persis area yang terlihat di kacamata. Rasio kotak menyesuaikan ukuran layar kacamata secara otomatis.

### Supaya tidak menunggu lama (versi 0.2)

- **Pratinjau instan**: selama file masih dikirim, HP mengirim gambar area yang sedang dilihat
  (~20–50 KB, 16 tingkat abu-abu). PDF langsung tampil di kacamata dalam hitungan detik dan zoom/geser
  sudah bisa dipakai. Setelah file lengkap, tampilan otomatis berganti ke versi tajam.
- **Kirim lewat Wi-Fi**: bila kacamata dan HP ada di jaringan Wi-Fi yang sama (atau kacamata tersambung
  ke hotspot HP), file dikirim lewat Wi-Fi yang jauh lebih cepat. Bila tidak terjangkau, otomatis kembali ke Bluetooth.
- **Cache**: PDF yang sama tidak dikirim ulang (kacamata menyimpan 5 PDF terakhir).

Koneksi kendali memakai **Bluetooth Classic (SPP/RFCOMM)** biasa, tanpa SDK Rokid.
Alasannya, CXR-M versi terbaru mewajibkan file autentikasi dan *client secret* dari portal developer Rokid.
Cara SPP ini juga dipakai proyek komunitas seperti GlassTube dan rokid-Strava.

## Yang dibutuhkan

- Android Studio (versi terbaru; sudah berisi JDK 17/21).
- HP Android 9 ke atas, **sudah dipasangkan dengan kacamata lewat aplikasi Hi Rokid**.
- Rokid Glasses yang sudah tersambung ke aplikasi Hi Rokid (untuk memasang APK tanpa kabel).

## Build & pasang

1. Buka folder `NalaHUD` di Android Studio, lalu tunggu Gradle sync selesai (butuh internet pertama kali).
2. **Pasang ke HP:** colok HP, pilih konfigurasi run **phone**, lalu tekan Run ▶.
3. **Pasang ke kacamata (tanpa kabel):** simpan `2-PASANG-DI-KACAMATA.apk` di HP, lalu di aplikasi
   **Hi Rokid** buka **Kotak alat → Manajemen aplikasi kacamata → Instal aplikasi baru** dan pilih file tersebut.
   Izin Bluetooth diberikan otomatis saat instal, jadi tidak perlu ADB.
   (Alternatif dengan kabel development: jalankan `tools/pasang-ke-kacamata.bat`.)

## Cara pakai

1. Buka **Nala HUD** (HP) di HP dan izinkan Bluetooth. Status akan menampilkan "Menunggu kacamata tersambung…".
2. Buka **Nala HUD** (kacamata) di kacamata. Aplikasi otomatis mencari HP dan menyambung.
3. Di HP, ketuk **Buka PDF**. File dikirim ke kacamata (ada persentase progres), lalu tampil.
4. Pinch untuk zoom dan geser satu jari untuk memindahkan area. Kacamata mengikuti secara realtime.
   Ketuk dua kali untuk zoom cepat atau kembali ke lebar penuh.

**Kontrol touchpad kacamata** (berguna saat HP di saku):

| Gerakan | Aksi |
|---|---|
| Geser maju / bawah | Gulir ke bawah; di dasar halaman otomatis pindah ke halaman berikut |
| Geser mundur / atas | Gulir ke atas; di puncak halaman kembali ke halaman sebelumnya |
| Ketuk | Ganti mode warna (terbalik ⇄ normal), berguna untuk PDF bergambar |

## Tanya-jawab suara ke Claude (versi 0.3)

Ketuk touchpad kacamata → bicara → ketuk lagi untuk berhenti → jawaban Claude mengalir di HUD.

Alur: mikrofon HP → pengenalan suara Android → Claude API (streaming) → HUD kacamata.
Model default **Haiku 4.5** (cepat & murah); bisa diganti ke **Sonnet 5** di Pengaturan untuk
pertanyaan yang lebih berat. Jawaban dibatasi 2 kalimat agar muat di layar kacamata.

**Sebelum dipakai:** buka aplikasi HP → tombol **⚙** → isi **API key** dari
https://console.anthropic.com (biaya API terpisah dari langganan Claude.ai).
Key disimpan di penyimpanan privat aplikasi di HP — tidak pernah masuk ke kode atau GitHub.

Kolom **Catatan/data** di Pengaturan diisi hal yang boleh dipakai Claude untuk menjawab
(mis. daftar harga, spesifikasi). Isinya ikut terkirim ke API setiap pertanyaan.

### Dua mode (versi 0.4)

- **Mode Tanya** — ketuk touchpad, Anda yang bicara, Claude menjawab.
- **Mode Dengar** — tahan touchpad. Mikrofon terus menyala menangkap ucapan **lawan bicara**.
  Setiap kalimatnya, HUD menampilkan dua baris:
  `MAKSUD:` inti ucapannya, dan `TANGGAPI:` bantahan atau pertanyaan balik yang bisa Anda pakai.
  Untuk hemat biaya, analisis hanya jalan bila ucapannya ≥ 4 kata dan ada jeda ≥ 4 detik dari analisis sebelumnya.

**Kontrol touchpad & tombol (versi 0.8):**

| Gerakan | Panel tanya tertutup | Panel tanya terbuka |
|---|---|---|
| Ketuk 1x tombol tengah | Mulai/berhenti bicara (Mode Tanya) | Mulai/berhenti bicara |
| Ketuk 2x tombol tengah | Hidup/matikan Mode Dengar | Hidup/matikan Mode Dengar |
| Tahan tombol tengah | Hidup/matikan Mode Dengar | Hidup/matikan Mode Dengar |
| Swipe maju | Gulir PDF | Gulir jawaban ke bawah |
| Swipe mundur | Gulir PDF | Gulir ke atas; di puncak → tutup panel |
| Volume + / − | Teks HUD lebih besar / lebih kecil | sama |

Catatan teknis pemetaan tombol (dipelajari dari proyek komunitas `Inplov/rokid-browser`):

- Tombol tengah bisa datang sebagai `DPAD_CENTER`, `ENTER`, `SPACE`, atau `MEDIA_PLAY_PAUSE`
  tergantung firmware — keempatnya kini diterima.
- Satu gerakan swipe sering terbaca berkali-kali, jadi ada penahan **700 ms**.
- Ketukan tunggal ditunda **300 ms** untuk membedakannya dari ketukan ganda.
- Tombol volume ditangkap di `dispatchKeyEvent` (bukan `onKeyDown`), karena sistem
  memprosesnya lebih dulu. Perubahan ukuran lewat volume berlaku sampai HP mengirim
  pengaturan baru.

### Tampilan mengikuti design system resmi Rokid (versi 0.9)

Diambil dari spesifikasi resmi `yodaos-project/AIUI` → `design/monochrome/design-system-green.md`
("Monochrome-Green", kanvas acuan **480x352**):

| Aturan | Nilai yang dipakai |
|---|---|
| Warna dasar | `#40ff5e` — satu kanal hijau, hierarki lewat tingkat cahaya |
| Teks utama | opasitas 72% |
| Teks sekunder | opasitas 48%, minimal 12sp |
| Nilai penting (judul catatan) | opasitas 100% |
| Skala huruf | display 22 · body 14 · body-sm 12 · label 11 · caption 10 |
| Tinggi baris | body 1.45 · body-sm 1.4 |
| Safe inset | 16px horizontal, 12px vertikal |
| Jarak | 2 / 4 / 8 / 12 / 16 / 24 / 32 |

Aturan penting lain yang diikuti: hitam berarti transparan (bukan panel gelap), jadi
massa visual dijaga seminimal mungkin; makna penting tidak pernah ditaruh di opasitas 24% ke bawah.

**Ukuran teks**: atur di Pengaturan HP (geser slider 8–26 sp, default 13). Perubahannya langsung
terlihat di kacamata. Makin kecil, makin banyak teks yang muat.
**Panjang jawaban**: ringkas / sedang / detail — sesuaikan dengan ukuran teks yang dipilih.

### Catatan pribadi = jawaban instan tanpa API (versi 0.5)

Tombol **Catatan** di HP untuk memuat satu file `.md`/`.txt`. Saat ada ucapan yang cocok dengan isi
catatan, baris `▸` langsung muncul di HUD — **lokal, tanpa internet, tanpa biaya**, dalam hitungan
milidetik (600 entri tercari dalam ~2 ms). Claude tetap dipakai hanya untuk penalaran (MAKSUD/TANGGAPI).

Format catatan yang dikenali:

```markdown
# Pasang 2 PK
Rp 600.000 per unit, termasuk bracket, belum termasuk pipa.

# Hotel Empress kamar 305
Daikin FTKQ25, kompresor terbakar, disurvei 12 Agustus.
```

Daftar sederhana juga bisa, satu baris satu entri:

```
Pasang 1 PK: Rp 450.000
Cuci AC: Rp 85.000
```

**Catatan tersimpan permanen** di penyimpanan privat aplikasi (`catatan.md`) dan dimuat ulang
otomatis setiap aplikasi dibuka — cukup unggah sekali. Unggah ulang file yang sama untuk memperbarui isinya.

**Jeda selesai bicara (default 5 detik).** Mesin suara Android sering memotong kalimat di jeda pendek.
Aplikasi mengumpulkan potongan-potongan itu dan baru menganggap ucapan selesai setelah benar-benar
sunyi selama jeda yang diatur, jadi HUD tidak menyela di tengah kalimat orang. Atur 0–10 detik
di Pengaturan (0 = tampil seketika, tanpa menunggu).

**Catatan hasil ekstrak PDF juga didukung (versi 0.7).** Deck yang diekstrak biasanya berjudul
"Halaman 12" dan penuh kop berulang. Aplikasi menanganinya otomatis:

- judul "Halaman N" diganti kalimat pertama yang bermakna;
- frasa yang berulang di banyak halaman (nama vendor, judul deck) dideteksi sendiri lalu dibuang,
  baik dari tampilan maupun dari indeks pencarian, supaya tidak memicu cocokan palsu;
- halaman yang isinya hanya pembatas/daftar isi dibuang seluruhnya;
- sisa penomoran slide ("… Project 1 2 A.") dipangkas.

Yang tampil di HUD bukan potongan awal halaman, melainkan **kalimat yang paling relevan dengan ucapan**,
dengan judul ditebalkan dan isi sebagai butir. Diuji dengan deck 88 halaman: 89 entri → 78 entri berguna,
parsing ±0,3 detik, pencarian ±3 milidetik.

Pencocokan mengerti imbuhan ("garansinya" → "garansi") dan membedakan angka ("1 PK" vs "2 PK").
Obrolan biasa tidak memicu apa-apa karena ada ambang skor.

**Etika & hukum:** Mode Dengar merekam suara lawan bicara. Untuk urusan bisnis sendiri umumnya wajar,
apalagi bila Anda memberi tahu. Untuk rapat internal bank atau pihak ketiga, rekaman menyangkut
kerahasiaan dan UU PDP — pakai Mode Tanya saja.

**Catatan kecepatan:** total jeda ±1,5–3 detik dari selesai bicara sampai kata pertama muncul.
Pengenalan suara memakai mesin bawaan Android dan butuh internet di HP.

## Troubleshooting

- **Kacamata terus "Mencari aplikasi di HP…"**: pastikan aplikasi di HP terbuka dan statusnya "Menunggu kacamata",
  lalu cek bahwa HP dan kacamata sudah dipasangkan (paired) lewat Hi Rokid.
- **Swipe touchpad tidak berpengaruh**: kode tombol touchpad bisa berbeda antar firmware. Lihat kode yang masuk dengan
  `adb logcat | grep -i key` atau `adb shell getevent -l`, lalu sesuaikan `onKeyDown` di
  `glasses/.../MainActivity.kt`.
- **Pengiriman file lambat**: berarti jalur Wi-Fi tidak terjangkau dan file dikirim lewat Bluetooth
  (~100–250 KB/detik). Pratinjau tetap tampil selama menunggu. Supaya cepat, sambungkan kacamata ke Wi-Fi
  yang sama dengan HP, atau ke hotspot HP. Wi-Fi publik/kantor sering memblokir komunikasi antar-perangkat;
  hotspot HP paling andal.
- **"Aplikasi tidak terpasang" saat update**: sejak versi 0.2 APK ditandatangani dengan kunci tetap
  (`keystore/`). Hapus sekali aplikasi versi lama, lalu pasang yang baru. Update berikutnya bisa langsung ditimpa.
- **PDF berpassword** tidak bisa dibuka (batasan `PdfRenderer` bawaan Android).
- **"API key belum diisi"**: isi lewat tombol ⚙ di aplikasi HP.
- **"Pengenalan suara tidak tersedia"**: HP tidak punya mesin suara (biasanya perlu Google app aktif).
- **Ketukan touchpad tidak memicu tanya**: lihat kode tombol dengan `adb logcat | grep -i key`,
  lalu sesuaikan `onKeyUp`/`onKeyLongPress` di `glasses/.../MainActivity.kt`.

## Pemeriksa kode sebelum build (tanpa Android SDK)

`tools/cek-kode.py` memindai kesalahan yang sering lolos saat menyunting kode secara terprogram:
konstanta yang dipakai tapi tak didefinisikan, import ganda atau hilang, anggota interface yang
belum di-override, dan fungsi lokal yang dipanggil tapi sudah terhapus.

```bash
python3 tools/cek-kode.py    # keluar dengan kode 1 bila ada masalah
```

## Menguji logika bersama (tanpa perangkat)

Protokol, pengiriman file, penggabungan pesan, dan perhitungan zoom bisa diuji di komputer:
```bash
kotlinc common/src/main/kotlin/id/nala/rokidpdf/common/*.kt common/src/test/kotlin/Test.kt -include-runtime -d t.jar
java -jar t.jar
```

## Ide pengembangan berikutnya

- Transfer cepat lewat Wi-Fi (HP & kacamata di jaringan yang sama) untuk PDF besar.
- Mode "presentasi": HP sebagai remote, kacamata sebagai catatan pembicara.
- Kunci zoom per dokumen dan penanda halaman.
