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

Android staging sekarang:

```text
/sdcard/Android/data/com.example.bridge/files/SUBRO/
```

Linux Samba viewer:

```bash
SAMBA_DIR=/sambashare android-file-bridge
```

SMB upload dari Android memakai anonymous/guest access.
