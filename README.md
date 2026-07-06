# FireFiles (Android File Bridge)

FireFiles adalah aplikasi transfer berkas otomatis berkinerja tinggi antara PC (Tauri) dan HP Android (APK Bridge) menggunakan koneksi ADB (USB/Wi-Fi) yang disinkronkan ke folder Samba secara real-time.

---

## 🚀 Fitur Utama & Optimasi Terbaru

### 📱 1. Auto-Pairing Pintar & Deteksi USB Real-time
* **Deteksi USB Cabut-Colok**: Backend memantau status koneksi perangkat USB secara real-time (setiap 2 detik). Saat kabel USB dicolok atau dicabut, UI akan langsung memperbarui daftar perangkat secara instan.
* **Auto-Pairing via APK**: Aplikasi otomatis mendeteksi dan memilih perangkat yang sudah memiliki aplikasi Bridge terinstall tanpa perlu konfigurasi sidik jari (*fingerprint*) manual.
* **Auto-Pairing via WebSocket**: Jika perangkat terhubung via USB dan secara bersamaan aktif di WebSocket server, FireFiles akan otomatis melakukan *pairing* (koneksi).
* **Sederhanakan Pengaturan**: Input manual Wi-Fi dan file path APK di modal Settings telah dihapus karena proses koneksi dan deteksi APK kini berjalan 100% otomatis.

### ⚡ 2. Pemindaian ADB Paralel & Kinerja Tinggi
* **Proses Paralel**: Pemindaian 15+ perangkat ADB yang terhubung kini berjalan secara konkuren di thread terpisah.
* **Scan Kilat < 0.3s**: Menggabungkan berbagai query informasi (model, storage, IP, status APK) menjadi satu perintah shell, memotong waktu scan dari 10 detik menjadi kurang dari 0.3 detik serta menghilangkan masalah UI membeku (*Not Responding*).

### 🔄 3. Sinkronisasi Antrean & Proteksi Auto-Push
* **Sinkronisasi Antrean Aktual**: Jumlah antrean transfer dihitung secara dinamis di PC dan dikirimkan ke HP. Kolom *Queue Progress* di Web Monitor kini menampilkan data aktual yang sinkron (misal `2 / 4 (50%)`) tanpa terjadi bug hitungan ulang atau persentase berlebih.
* **Auto-Push dengan Sesi Cache**: Mendukung transfer otomatis begitu file `.tar.md5` baru terdeteksi di folder sumber. Dilengkapi dengan cache sesi `pushedFiles` untuk mencegah duplikasi atau loop pengiriman berulang saat file sedang menunggu proses unggah dari HP ke Samba.
* **Tampilan Bersih**: Menghilangkan indikator progress bar dan persentase yang mengganggu dari daftar berkas di Tauri untuk menciptakan antarmuka yang bersih dan fokus pada status berkas (`ready`, `pushed`, `uploaded`).

### 💻 4. Integrasi System Tray (Jalan di Background)
* **Background Mode**: Menutup jendela utama (`X`) tidak akan mematikan aplikasi, melainkan menyembunyikannya ke System Tray agar proses pemantauan file dan auto-push tetap berjalan di latar belakang.
* **Menu Tray**:
  * **Show**: Membuka kembali jendela utama aplikasi.
  * **Close**: Menyembunyikan jendela aplikasi ke System Tray.
  * **Quit**: Menutup aplikasi sepenuhnya.

---

## 🛠️ Konfigurasi Cepat

### Sistem Host (Windows / Linux)
Jalankan perintah adb untuk otorisasi perangkat pertama kali:
```bash
adb devices
```

Secara default, FireFiles akan memantau folder staging di:
* Windows: `E:\SUBRO` (atau folder yang ditentukan di Settings)
* Direktori tujuan Android: `/sdcard/Android/data/com.example.bridge/files/SUBRO/`

### Konfigurasi Samba (Guest/Anonymous Access)
Unggah file dari Android ke folder Samba di jaringan lokal menggunakan hak akses tamu:
```bash
SAMBA_DIR=/sambashare
```

---

## 📦 Build & Rilis

### Kompilasi React UI
```bash
npm run build
```

### Jalankan Tauri Desktop App (Development)
```bash
npm run tauri dev
```

### Rilis Versi Baru (Menggunakan script rilis otomatis)
```bash
./scripts/release.sh [patch|minor|major]
```
Skenario rilis ini akan memperbarui berkas versi Tauri, versi Gradle Android, melakukan kompilasi Rust backend, membuat tag git baru, dan melakukan push otomatis ke repositori.
