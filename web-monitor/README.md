# Bridge Monitor

```bash
docker compose up -d bridge-monitor
```

Web:

```text
http://127.0.0.1:8081
https://files.endrisusanto.my.id/
```

Android stream:

```text
ws://192.168.10.221:1421/network
wss://files.endrisusanto.my.id/network
```

Tauri Windows publisher:

```powershell
setx MONITOR_URL "https://files.endrisusanto.my.id/tauri"
```

Restart Tauri setelah `setx`.
