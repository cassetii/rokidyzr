# PDF ke Rokid — tampilkan PDF dari HP di Rokid Glasses secara realtime

Dua aplikasi Android dalam satu proyek:

| Modul | Dipasang di | Nama aplikasi | Fungsi |
|---|---|---|---|
| `phone` | HP Android | **PDF ke Rokid** | Pilih PDF, zoom/geser, kirim posisi pandang |
| `glasses` | Rokid Glasses (YodaOS-Sprite) | **PDF HUD** | Terima PDF, render area yang sama, tampil di HUD |
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

1. Buka folder `RokidPdfMirror` di Android Studio, lalu tunggu Gradle sync selesai (butuh internet pertama kali).
2. **Pasang ke HP:** colok HP, pilih konfigurasi run **phone**, lalu tekan Run ▶.
3. **Pasang ke kacamata (tanpa kabel):** simpan `2-PASANG-DI-KACAMATA.apk` di HP, lalu di aplikasi
   **Hi Rokid** buka **Kotak alat → Manajemen aplikasi kacamata → Instal aplikasi baru** dan pilih file tersebut.
   Izin Bluetooth diberikan otomatis saat instal, jadi tidak perlu ADB.
   (Alternatif dengan kabel development: jalankan `tools/pasang-ke-kacamata.bat`.)

## Cara pakai

1. Buka **PDF ke Rokid** di HP dan izinkan Bluetooth. Status akan menampilkan "Menunggu kacamata tersambung…".
2. Buka **PDF HUD** di kacamata. Aplikasi otomatis mencari HP dan menyambung.
3. Di HP, ketuk **Buka PDF**. File dikirim ke kacamata (ada persentase progres), lalu tampil.
4. Pinch untuk zoom dan geser satu jari untuk memindahkan area. Kacamata mengikuti secara realtime.
   Ketuk dua kali untuk zoom cepat atau kembali ke lebar penuh.

**Kontrol touchpad kacamata** (berguna saat HP di saku):

| Gerakan | Aksi |
|---|---|
| Geser maju / bawah | Gulir ke bawah; di dasar halaman otomatis pindah ke halaman berikut |
| Geser mundur / atas | Gulir ke atas; di puncak halaman kembali ke halaman sebelumnya |
| Ketuk | Ganti mode warna (terbalik ⇄ normal), berguna untuk PDF bergambar |

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
