# 🔥 FireFiles (Cross-Network Android File Bridge)

FireFiles adalah sistem transfer dan pemantauan berkas otomatis berkinerja tinggi antara **PC Desktop (Tauri + Rust + React)** dan **HP Android (Bridge APK & Monitor APK)** menggunakan koneksi ADB (USB/Wi-Fi) yang disinkronkan ke server **Samba (SMB)** serta dipantau secara realtime melalui **Web Monitor Dashboard**.

---

## 🌟 Arsitektur Sistem & Fitur Utama

```
┌─────────────────────────┐          USB (ADB Push / Reverse Relay)          ┌─────────────────────────┐
│   Windows Desktop App   │ ◄──────────────────────────────────────────────► │   Android Bridge APK    │
│      (Tauri + Rust)     │                                                  │  (Transfer Engine SMB)  │
└────────────┬────────────┘                                                  └────────────┬────────────┘
             │                                                                            │
   WebSocket │ wss://files.endrisusanto.my.id/                                   Hotspot  │ 5GHz WiFi (SMB)
             ▼                                                                            ▼
┌─────────────────────────┐                                                  ┌─────────────────────────┐
│   Cloud Web Monitor     │ ◄─────────────────────────────────────────────── │   Samba Storage Server  │
│   (Docker + Node.js)    │             (Live Telemetry Relay)               │      (/sambashare)      │
└─────────────────────────┘                                                  └─────────────────────────┘
```

---

### 🚀 1. Otomasi Transfer & Staging
* **Smart Auto-Push**: Otomatis mendeteksi berkas `.md5`, `.zip`, dan `.txt` yang selesai diunduh di folder staging PC (`E:\SUBRO` atau folder kustom) dan langsung mem-push ke direktori HP Android via ADB.
* **Deteksi Download Part-File**: Mengenali status unduhan sementara (`.part`, `.crdownload`, `.downloading`, `.tmp`) sehingga antrean hanya mengeksekusi file yang telah 100% selesai diunduh tanpa risiko *file lock*.
* **Sinkronisasi Status Sejati**: Memverifikasi keberadaan berkas secara fisik di Samba (`inSamba`) dan memori HP (`phoneFiles`) untuk mencegah *false-completion cache locks*.
* **Double-Buffering Staging**: Membatasi antrean berkas di HP (maksimal 2–3 file aktif) agar penyimpanan internal HP tidak penuh sebelum file terunggah ke Samba.

### ⚡ 2. Offline 5GHz Hotspot Sync (Automatic ADB Reverse Relay)
* **Sinkronisasi Tanpa Internet**: HP Android dapat terhubung ke WiFi Hotspot 5GHz lokal untuk mengunggah ke Samba dengan kecepatan tinggi (offline), sementara koneksi telemetri dan status tetap tersambung ke Cloud Web Monitor.
* **Auto ADB Port Reverse**: Desktop Tauri secara otomatis menjalankan `adb reverse tcp:1421 tcp:1421` saat HP dicolokkan ke USB.
* **Dual-Path Telemetri**: Data kecepatan upload Samba, progres file, dan status antrean dikirim lewat kabel USB ke PC Windows, lalu PC Windows meneruskannya (*relay*) ke Cloud Web Monitor (`wss://files.endrisusanto.my.id/`).

### 📱 3. Aplikasi Android Bridge APK (`com.example.bridge`)
* **Background Foreground Service**: `BridgeService` berjalan stabil di latar belakang dengan WakeLock & WifiLock berkinerja tinggi.
* **Accordion Chart Traffic**: Grafik *Realtime Network Traffic* dapat di-*collapse* atau di-*expand* untuk menghemat ruang tampilan log.
* **Animasi Confetti**: Animasi perayaan confetti otomatis berputar saat seluruh antrean staging selesai ditransfer.
* **Home Screen Widgets**:
  * **Traffic Chart Widget**: Menampilkan kurva grafik realtime traffic jaringan langsung di layar utama HP.
  * **Staging Status Widget**: Menampilkan jumlah file staging dan status kesiapan koneksi Samba.

### 📊 4. Android Monitor APK (`com.example.bridge.monitor`)
* **Executive Staging Dashboard**: Dashboard mandiri khusus pemantauan multi-host dan multi-device secara realtime.
* **Single Sticky Topbar**: Header atas permanen yang elegan dengan penyesuaian *Window Insets* (bebas potongan poni layar / notch).
* **Remote Trigger Controls**: Mendukung trigger *Upload All*, pengaturan host/share Samba remote, dan rescan perangkat.

### 🌐 5. Web Monitor Dashboard (`web-monitor`)
* **Glassmorphic Dark UI**: Antarmuka modern berbasis CSS HSL, font Inter, dan kartu status eksekutif.
* **No-Wrap Status Badges**: Badge status fleksibel (`white-space: nowrap`) yang tidak memotong teks panjang.
* **Multi-Host Overview**: Memantau beberapa PC host Tauri (`ENDRI-S02`, `ENDRI-S03`) dan perangkat Android sekaligus.
* **WebSocket Framing 64-bit**: Engine WebSocket node.js mendukung framing BigInt 64-bit dan heartbeat Ping/Pong otomatis.

---

## 🛠️ Konfigurasi Cepat

### 1. Persyaratan Sistem
* **PC**: Windows 10/11 atau Linux dengan Android Platform Tools (ADB).
* **Android**: Android 8.0+ (Oreo) hingga Android 14+ dengan USB Debugging aktif.
* **Node.js**: v18+ & Rust (Cargo) untuk kompilasi lokal.

### 2. Folder Staging Default
* **PC Windows**: `E:\SUBRO` (dapat diubah via tombol *Change Folder...*)
* **Android Internal**: `/sdcard/Android/data/com.example.bridge/files/SUBRO/`
* **Target Samba**: `smb://<IP_SAMBA>/<SHARE_NAME>/` (Default: `smb://192.168.10.15/SSD/` atau `SAMBA_DIR=/sambashare`)

---

## 📦 Build & Deployment

### 🖥️ 1. Desktop Tauri App
```bash
# Install dependensi frontend
npm install

# Jalankan mode development
npm run tauri dev

# Build installer desktop (MSI / EXE / AppImage)
npm run tauri build
```

### 📱 2. Android APK (Bridge & Monitor)
```bash
# Masuk ke direktori android
cd android

# Build Bridge APK
./gradlew assembleBridgeRelease

# Build Monitor APK
./gradlew assembleMonitorRelease
```

### 🐳 3. Web Monitor Server (Docker)
```bash
# Build dan jalankan container
docker compose build --no-cache
docker compose up -d

# Akses Web Dashboard di browser:
http://localhost:8081/  atau  https://files.endrisusanto.my.id/
```

### 🚀 4. Skrip Rilis Otomatis
```bash
# Rilis versi baru (otomatis update Tauri, Cargo, Gradle, Git Tag & Push)
./scripts/release.sh [patch|minor|major]
```

---

## 📄 Lisensi
Hak Cipta © 2026 FireFiles Project. Dikembangkan untuk efisiensi transfer data berkinerja tinggi.
