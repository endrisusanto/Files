# Android File Bridge

## Setup cepat

Windows:

```powershell
adb devices -l
adb -s DEVICE_ID shell getprop ro.build.fingerprint
setx TARGET_BRIDGE_FINGERPRINT "PASTE_FINGERPRINT_DI_SINI"
setx SOURCE_DIR "E:\SUBRO"
```

Restart app setelah `setx`.

Kalau tidak mau pakai env fingerprint, buka app Windows lalu centang device di tabel `Bridge`.
Kalau tabel kosong, unlock Android dan jalankan:

```powershell
adb devices
```

Terima prompt USB debugging di Android.

Android staging sekarang:

```text
/sdcard/Android/data/com.example.bridge/files/SUBRO/
```

Linux Samba viewer:

```bash
SAMBA_DIR=/sambashare android-file-bridge
```

SMB upload dari Android memakai anonymous/guest access.
