#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use notify::{recommended_watcher, RecursiveMode, Watcher};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::{BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{mpsc::channel, Arc, Mutex},
    thread,
    time::Duration,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use sha1::{Digest, Sha1};
use tauri::{
    menu::{MenuBuilder, MenuItemBuilder},
    tray::TrayIconBuilder,
    AppHandle, Emitter, Manager, WindowEvent,
};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

const DEFAULT_SOURCE_DIR: &str = r"E:\SUBRO";
const ANDROID_DIR: &str = "/sdcard/Android/data/com.example.bridge/files/SUBRO/";
const ANDROID_PACKAGE: &str = "com.example.bridge";
const DEFAULT_SAMBA_DIR: &str = "/sambashare";
const MIN_FREE_KB: u64 = 25 * 1024 * 1024;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x08000000;

#[derive(Clone)]
struct Config {
    target_fingerprint: String,
    selected_fingerprint: Arc<Mutex<Option<String>>>,
    service: String,
    source_dir: Arc<Mutex<PathBuf>>,
    samba_dir: PathBuf,
    devices_cache: Arc<Mutex<Vec<DeviceInfo>>>,
}

#[derive(Serialize, Clone)]
struct DeviceInfo {
    id: String,
    model: String,
    fingerprint: String,
    available_storage: u64,
    battery_level: Option<u8>,
    battery_temperature: Option<f32>,
    ip_address: String,
    apk_installed: bool,
    is_selected_bridge: bool,
    is_target_bridge: bool,
}

#[derive(Serialize, Clone)]
struct LocalFile {
    name: String,
    size: u64,
    status: String,
    locked: bool,
}

#[derive(Serialize, Clone)]
struct TransferProgress {
    file: String,
    percent: u8,
    message: String,
}

#[derive(Serialize, Clone)]
struct NetworkSample {
    rx_bps: u64,
    tx_bps: u64,
}

#[derive(Deserialize)]
struct WebSocketSample {
    rx_bps: u64,
    tx_bps: u64,
}

#[derive(Serialize, Clone)]
struct AppInfo {
    platform: String,
    source_dir: String,
    samba_dir: String,
    target_fingerprint_set: bool,
    hostname: String,
}

fn get_adb_path() -> String {
    if let Ok(p) = std::env::var("ADB_PATH") {
        if Path::new(&p).exists() {
            return p;
        }
    }

    #[cfg(windows)]
    {
        let paths = [
            r"C:\platform-tools\adb.exe",
            r"C:\ATM-environment\adb.exe",
            r"C:\scrcpy\adb.exe",
        ];
        for path in &paths {
            if Path::new(path).exists() {
                return path.to_string();
            }
        }

        if let Ok(local_app_data) = std::env::var("LOCALAPPDATA") {
            let p = PathBuf::from(local_app_data)
                .join("Android")
                .join("Sdk")
                .join("platform-tools")
                .join("adb.exe");
            if p.exists() {
                return p.to_string_lossy().into_owned();
            }
        }

        if let Ok(current_exe) = std::env::current_exe() {
            if let Some(parent) = current_exe.parent() {
                let local_adb = parent.join("adb.exe");
                if local_adb.exists() {
                    return local_adb.to_string_lossy().into_owned();
                }
            }
        }
    }

    "adb".to_string()
}

fn command(program: &str) -> Command {
    let resolved = if program == "adb" {
        get_adb_path()
    } else {
        program.to_string()
    };

    #[cfg(windows)]
    {
        let mut cmd = Command::new(&resolved);
        cmd.creation_flags(CREATE_NO_WINDOW);
        cmd
    }
    #[cfg(not(windows))]
    {
        Command::new(&resolved)
    }
}

fn adb(args: &[&str]) -> Result<String, String> {
    let out = command("adb").args(args).output().map_err(|e| e.to_string())?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

fn source_dir(config: &Config) -> PathBuf {
    config.source_dir.lock().map(|v| v.clone()).unwrap_or_else(|_| PathBuf::from(DEFAULT_SOURCE_DIR))
}

fn get_device_details(id: &str) -> (String, u64, String, bool) {
    let cmd = format!(
        "getprop ro.build.fingerprint; echo '==='; df -k /sdcard/Download; echo '==='; ip -f inet addr show wlan0; echo '==='; pm path {}",
        ANDROID_PACKAGE
    );
    let Ok(output) = command("adb")
        .args(["-s", id, "shell", &cmd])
        .output() else {
            return ("unknown".to_string(), 0, "-".to_string(), false);
        };

    let stdout = String::from_utf8_lossy(&output.stdout);
    let parts: Vec<&str> = stdout.split("===").collect();
    if parts.len() < 4 {
        return ("unknown".to_string(), 0, "-".to_string(), false);
    }

    let fingerprint = parts[0].trim().to_string();

    // Parse storage
    let df_out = parts[1];
    let available_storage = df_out.lines()
        .filter(|line| !line.trim().is_empty())
        .skip(1)
        .filter_map(|line| line.split_whitespace().nth(3)?.parse::<u64>().ok())
        .next()
        .unwrap_or(0);

    // Parse IP
    let ip_out = parts[2];
    let ip_address = ip_out.lines()
        .find_map(|line| {
            let trimmed = line.trim();
            if trimmed.starts_with("inet ") {
                trimmed.strip_prefix("inet ")?.split('/').next().map(str::to_string)
            } else {
                None
            }
        })
        .unwrap_or_else(|| "-".into());

    // Parse PM path
    let pm_out = parts[3];
    let apk_installed = pm_out.contains("package:");

    (fingerprint, available_storage, ip_address, apk_installed)
}

fn list_devices(config: &Config) -> Vec<DeviceInfo> {
    let Ok(out) = adb(&["devices", "-l"]) else {
        eprintln!("[bridge-tauri] adb devices failed");
        return vec![];
    };
    let selected = config.selected_fingerprint.lock().ok().and_then(|v| v.clone());
    let basic_devices: Vec<(String, String)> = out.lines()
        .skip(1)
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let id = parts.next()?.to_string();
            let status = parts.next()?;
            if status != "device" {
                return None;
            }
            let model = line
                .split_whitespace()
                .find_map(|part| part.strip_prefix("model:"))
                .unwrap_or("unknown")
                .to_string();
            Some((id, model))
        })
        .collect();

    let mut handles = vec![];
    for (id, model) in basic_devices {
        let selected_clone = selected.clone();
        let target_fingerprint = config.target_fingerprint.clone();
        
        let handle = thread::spawn(move || {
            let (fingerprint, available_storage, ip_address, apk_installed) = get_device_details(&id);
            let is_selected_bridge = selected_clone
                .as_deref()
                .map(|target| target == fingerprint)
                .unwrap_or_else(|| fingerprint == target_fingerprint);
            DeviceInfo {
                is_target_bridge: is_selected_bridge && available_storage >= MIN_FREE_KB,
                id,
                model,
                fingerprint,
                available_storage,
                battery_level: None,
                battery_temperature: None,
                ip_address,
                apk_installed,
                is_selected_bridge,
            }
        });
        handles.push(handle);
    }

    let mut devices = vec![];
    for handle in handles {
        if let Ok(device) = handle.join() {
            devices.push(device);
        }
    }

    println!(
        "[bridge-tauri] devices scanned: total={} selected={} ready={} apk_installed={}",
        devices.len(),
        devices.iter().filter(|d| d.is_selected_bridge).count(),
        devices.iter().filter(|d| d.is_target_bridge).count(),
        devices.iter().filter(|d| d.apk_installed).count()
    );
    devices
}

fn bridge_files(dir: &Path) -> Vec<LocalFile> {
    let Ok(entries) = fs::read_dir(dir) else { return vec![] };
    entries
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            let name = path.file_name()?.to_string_lossy().to_string();
            let lower = name.to_lowercase();
            if !lower.ends_with(".tar.md5") && !lower.ends_with(".tar.md5.part") {
                return None;
            }
            let meta = entry.metadata().ok()?;
            let locked = lower.ends_with(".tar.md5") && !file_is_available(&path);
            Some(LocalFile {
                status: if lower.ends_with(".part") { "downloading" } else if locked { "locked" } else { "ready" }.into(),
                name,
                size: meta.len(),
                locked,
            })
        })
        .collect()
}

fn file_is_available(path: &Path) -> bool {
    OpenOptions::new().read(true).write(true).open(path).is_ok()
}

fn emit_loop(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    thread::spawn(move || loop {
        println!("[bridge-tauri] emit_loop tick");
        let cached = config.devices_cache.lock().ok().map(|c| c.clone()).unwrap_or_default();
        let _ = app.emit("devices", cached);
        let _ = app.emit("files", bridge_files(&source_dir(&config)));
        let _ = app.emit("samba-files", bridge_files(&config.samba_dir));
        // ponytail: increase interval to 5s to drastically reduce background process spawning overhead
        thread::sleep(Duration::from_secs(5));
    });
}

fn websocket_loop(app: AppHandle) {
    thread::spawn(move || {
        let Ok(listener) = TcpListener::bind("0.0.0.0:1421") else {
            eprintln!("[bridge-tauri] websocket bind failed on 1421");
            return;
        };
        println!("[bridge-tauri] websocket listening on 0.0.0.0:1421");
        for stream in listener.incoming().flatten() {
            let app = app.clone();
            thread::spawn(move || handle_websocket(app, stream));
        }
    });
}

fn monitor_loop(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    let url = std::env::var("MONITOR_URL").unwrap_or_else(|_| "https://files.endrisusanto.my.id/tauri".into());
    thread::spawn(move || loop {
        let host = std::env::var("COMPUTERNAME")
            .or_else(|_| std::env::var("HOSTNAME"))
            .unwrap_or_else(|_| "tauri".into());
        let cached = config.devices_cache.lock().ok().map(|c| c.clone()).unwrap_or_default();
        let body = serde_json::json!({
            "id": host,
            "host": host,
            "platform": std::env::consts::OS,
            "source_dir": source_dir(&config).display().to_string(),
            "samba_dir": config.samba_dir.display().to_string(),
            "devices": cached,
        })
        .to_string();
        let _ = command("curl")
            .args(["-fsS", "-X", "POST", "-H", "Content-Type: application/json", "-d", &body, &url])
            .output();
        thread::sleep(Duration::from_secs(10));
    });
}

fn handle_websocket(app: AppHandle, mut stream: TcpStream) {
    if websocket_handshake(&mut stream).is_err() {
        return;
    }
    while let Ok(Some(text)) = read_websocket_text(&mut stream) {
        if let Ok(sample) = serde_json::from_str::<WebSocketSample>(&text) {
            let _ = app.emit("network", NetworkSample { rx_bps: sample.rx_bps, tx_bps: sample.tx_bps });
        }
    }
}

fn websocket_handshake(stream: &mut TcpStream) -> Result<(), String> {
    let mut req = Vec::new();
    let mut buf = [0u8; 512];
    while !req.windows(4).any(|v| v == b"\r\n\r\n") {
        let n = stream.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 || req.len() > 8192 {
            return Err("bad websocket request".into());
        }
        req.extend_from_slice(&buf[..n]);
    }
    let text = String::from_utf8_lossy(&req);
    let key = text
        .lines()
        .find_map(|line| line.strip_prefix("Sec-WebSocket-Key:").map(str::trim))
        .ok_or_else(|| "missing websocket key".to_string())?;
    let mut sha = Sha1::new();
    sha.update(key.as_bytes());
    sha.update(b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
    let accept = STANDARD.encode(sha.finalize());
    write!(
        stream,
        "HTTP/1.1 101 Switching Protocols\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Accept: {accept}\r\n\r\n"
    ).map_err(|e| e.to_string())
}

fn read_websocket_text(stream: &mut TcpStream) -> Result<Option<String>, String> {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).map_err(|e| e.to_string())?;
    let opcode = header[0] & 0x0f;
    if opcode == 8 {
        return Ok(None);
    }
    let masked = header[1] & 0x80 != 0;
    let mut len = (header[1] & 0x7f) as u64;
    if len == 126 {
        let mut b = [0u8; 2];
        stream.read_exact(&mut b).map_err(|e| e.to_string())?;
        len = u16::from_be_bytes(b) as u64;
    } else if len == 127 {
        let mut b = [0u8; 8];
        stream.read_exact(&mut b).map_err(|e| e.to_string())?;
        len = u64::from_be_bytes(b);
    }
    if len > 4096 {
        return Err("websocket message too large".into());
    }
    let mut mask = [0u8; 4];
    if masked {
        stream.read_exact(&mut mask).map_err(|e| e.to_string())?;
    }
    let mut data = vec![0u8; len as usize];
    stream.read_exact(&mut data).map_err(|e| e.to_string())?;
    if masked {
        for (i, byte) in data.iter_mut().enumerate() {
            *byte ^= mask[i % 4];
        }
    }
    Ok(String::from_utf8(data).ok())
}

fn watch_source(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    thread::spawn(move || {
        let (tx, rx) = channel();
        let Ok(mut watcher) = recommended_watcher(tx) else { return };
        let dir = source_dir(&config);
        if watcher.watch(&dir, RecursiveMode::NonRecursive).is_err() {
            return;
        }
        while rx.recv().is_ok() {
            let _ = app.emit("files", bridge_files(&source_dir(&config)));
        }
    });
}

fn watch_samba(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    thread::spawn(move || {
        let (tx, rx) = channel();
        let Ok(mut watcher) = recommended_watcher(tx) else { return };
        if watcher.watch(&config.samba_dir, RecursiveMode::NonRecursive).is_err() {
            return;
        }
        while rx.recv().is_ok() {
            let _ = app.emit("samba-files", bridge_files(&config.samba_dir));
        }
    });
}

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn hide_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.hide();
    }
}

fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let show = MenuItemBuilder::with_id("show", "Show").build(app)?;
    let close = MenuItemBuilder::with_id("close", "Close").build(app)?;
    let quit = MenuItemBuilder::with_id("quit", "Quit").build(app)?;
    let menu = MenuBuilder::new(app)
        .item(&show)
        .item(&close)
        .item(&quit)
        .build()?;
    let icon = app.default_window_icon().cloned().unwrap();

    TrayIconBuilder::new()
        .tooltip("FireFiles")
        .icon(icon)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show" => show_main_window(app),
            "close" => hide_main_window(app),
            "quit" => std::process::exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, _| show_main_window(tray.app_handle()))
        .build(app)?;
    Ok(())
}

fn pipe_progress<R: Read + Send + 'static>(app: AppHandle, file: String, stream: R, last_line: Arc<Mutex<String>>) {
    thread::spawn(move || {
        let re = Regex::new(r"(\d{1,3})%").unwrap();
        let mut reader = BufReader::new(stream);
        let mut line_buf = Vec::new();
        loop {
            let mut byte_buf = [0u8; 1];
            match reader.read(&mut byte_buf) {
                Ok(0) => break, // EOF
                Ok(_) => {
                    let b = byte_buf[0];
                    if b == b'\n' || b == b'\r' {
                        if !line_buf.is_empty() {
                            if let Ok(line) = String::from_utf8(line_buf.clone()) {
                                let trimmed = line.trim();
                                if !trimmed.is_empty() {
                                    let percent = re.captures(trimmed)
                                        .and_then(|c| c[1].parse().ok())
                                        .unwrap_or(0);
                                    if percent > 0 {
                                        let _ = app.emit("transfer", TransferProgress {
                                            file: file.clone(),
                                            percent,
                                            message: trimmed.to_string(),
                                        });
                                    }
                                    if let Ok(mut guard) = last_line.lock() {
                                        *guard = trimmed.to_string();
                                    }
                                }
                            }
                            line_buf.clear();
                        }
                    } else {
                        line_buf.push(b);
                    }
                }
                Err(_) => break,
            }
        }
    });
}

fn get_remote_file_size(device_id: &str, path: &str) -> Option<u64> {
    let out = command("adb")
        .args(["-s", device_id, "shell", "stat", "-c", "%s", path])
        .output()
        .ok()?;
    if out.status.success() {
        let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if let Ok(size) = text.parse::<u64>() {
            return Some(size);
        }
    }
    None
}

fn push_file_blocking(app: AppHandle, file_name: String, force: bool, queue_total: i32, queue_success: i32) -> Result<(), String> {
    println!("[bridge-tauri] push_file start file={file_name} force={force} queue_total={queue_total} queue_success={queue_success}");
    let config = app.state::<Config>().inner().clone();
    let mut cached_devices = config.devices_cache.lock().ok().map(|c| c.clone()).unwrap_or_default();
    if cached_devices.is_empty() {
        cached_devices = list_devices(&config);
        if let Ok(mut cache) = config.devices_cache.lock() {
            *cache = cached_devices.clone();
        }
    }
    let device = cached_devices
        .into_iter()
        .find(|d| if force { d.is_selected_bridge } else { d.is_target_bridge })
        .ok_or_else(|| {
            eprintln!("[bridge-tauri] push_file no ready bridge");
            "target bridge fingerprint not connected or lacks 25GB free storage"
        })?;
    let source = source_dir(&config).join(&file_name);
    if !source.is_file() || !file_name.ends_with(".tar.md5") || (!force && !file_is_available(&source)) {
        eprintln!("[bridge-tauri] push_file rejected source={}", source.display());
        return Err("file is not a ready .tar.md5".into());
    }

    if let Err(e) = adb(&["-s", &device.id, "shell", "mkdir", "-p", ANDROID_DIR]) {
        eprintln!("[bridge-tauri] mkdir failed: {e}");
        return Err(format!("Failed to create destination directory: {e}"));
    }

    let total_size = match fs::metadata(&source) {
        Ok(m) => m.len(),
        Err(_) => 1,
    };

    let mut child = command("adb")
        .args(["-s", &device.id, "push"])
        .arg(&source)
        .arg(ANDROID_DIR)
        .stderr(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .map_err(|e| e.to_string())?;

    let last_err_line = Arc::new(Mutex::new(String::new()));
    if let Some(stream) = child.stderr.take() {
        pipe_progress(app.clone(), file_name.clone(), stream, last_err_line.clone());
    }
    if let Some(stream) = child.stdout.take() {
        pipe_progress(app.clone(), file_name.clone(), stream, Arc::new(Mutex::new(String::new())));
    }

    // Spin up remote progress checker thread
    let is_running = Arc::new(Mutex::new(true));
    let ir_clone = is_running.clone();
    let device_id = device.id.clone();
    let remote_path = format!("{}{}", ANDROID_DIR, file_name);
    let app_handle = app.clone();
    let file_name_clone = file_name.clone();

    thread::spawn(move || {
        while *ir_clone.lock().unwrap() {
            thread::sleep(Duration::from_millis(500));
            if let Some(remote_size) = get_remote_file_size(&device_id, &remote_path) {
                let percent = ((remote_size as f64 / total_size as f64) * 100.0) as u8;
                let percent = std::cmp::min(99, percent);
                let _ = app_handle.emit("transfer", TransferProgress {
                    file: file_name_clone.clone(),
                    percent,
                    message: format!("Pushed {}/{} bytes ({}%)", remote_size, total_size, percent),
                });
            }
        }
    });

    let status = child.wait().map_err(|e| e.to_string())?;
    *is_running.lock().unwrap() = false;

    if !status.success() {
        let err_msg = last_err_line.lock().map(|g| g.clone()).unwrap_or_default();
        eprintln!("[bridge-tauri] adb push failed file={file_name} error={err_msg}");
        return Err(format!("adb push failed: {}", err_msg));
    }
    let _ = app.emit("transfer", TransferProgress { file: file_name.clone(), percent: 100, message: "push complete".into() });
    adb(&[
        "-s",
        &device.id,
        "shell",
        "am",
        "start-foreground-service",
        "-n",
        &config.service,
        "--es",
        "file",
        &file_name,
        "--ei",
        "queue_total",
        &queue_total.to_string(),
        "--ei",
        "queue_success",
        &queue_success.to_string(),
    ])?;
    println!("[bridge-tauri] push_file done file={file_name} device={}", device.id);
    Ok(())
}

#[tauri::command]
async fn push_file(app: AppHandle, file_name: String, force: bool, queue_total: i32, queue_success: i32) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || push_file_blocking(app, file_name, force, queue_total, queue_success))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn select_bridge(app: AppHandle, fingerprint: String) -> Result<(), String> {
    println!("[bridge-tauri] select_bridge fingerprint={fingerprint}");
    let config = app.state::<Config>().inner().clone();
    *config.selected_fingerprint.lock().map_err(|e| e.to_string())? = Some(fingerprint);
    // ponytail: blocking adb scan, keep selection click from freezing the UI.
    tauri::async_runtime::spawn_blocking(move || {
        let devices = list_devices(&config);
        let _ = app.emit("devices", devices);
    });
    Ok(())
}

#[tauri::command]
async fn app_info(app: AppHandle) -> AppInfo {
    let config = app.state::<Config>().inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let hostname = std::env::var("COMPUTERNAME")
            .or_else(|_| std::env::var("HOSTNAME"))
            .unwrap_or_else(|_| "tauri".into());
        
        let path = source_dir(&config);
        
        println!(
            "[bridge-tauri] app_info platform={} source={} samba={} hostname={}",
            std::env::consts::OS,
            path.display(),
            config.samba_dir.display(),
            hostname
        );
        AppInfo {
            platform: std::env::consts::OS.into(),
            source_dir: path.display().to_string(),
            samba_dir: config.samba_dir.display().to_string(),
            target_fingerprint_set: config.target_fingerprint != "PUT_TARGET_RO_BUILD_FINGERPRINT_HERE",
            hostname,
        }
    })
    .await
    .unwrap_or_else(|_| AppInfo {
        platform: std::env::consts::OS.into(),
        source_dir: "".into(),
        samba_dir: "".into(),
        target_fingerprint_set: false,
        hostname: "tauri".into(),
    })
}

#[tauri::command]
async fn set_source_dir(app: AppHandle, path: String) -> Result<Vec<LocalFile>, String> {
    let config = app.state::<Config>().inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let dir = PathBuf::from(path);
        if !dir.is_dir() {
            return Err(format!("source folder not found: {}", dir.display()));
        }
        *config.source_dir.lock().map_err(|e| e.to_string())? = dir.clone();
        let files = bridge_files(&dir);
        println!("[bridge-tauri] source_dir set {} files={}", dir.display(), files.len());
        let _ = app.emit("files", files.clone());
        Ok(files)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn pick_source_dir() -> Option<String> {
    rfd::FileDialog::new()
        .pick_folder()
        .map(|p| p.to_string_lossy().into_owned())
}

#[tauri::command]
async fn pick_apk_file() -> Option<String> {
    rfd::FileDialog::new()
        .add_filter("Android APK", &["apk"])
        .pick_file()
        .map(|p| p.to_string_lossy().into_owned())
}

fn push_install_apk_blocking(app: AppHandle, apk_path: String) -> Result<String, String> {
    println!("[bridge-tauri] push_install_apk path={apk_path}");
    let config = app.state::<Config>().inner().clone();
    let device = list_devices(&config)
        .into_iter()
        .find(|d| d.is_selected_bridge)
        .ok_or_else(|| {
            eprintln!("[bridge-tauri] push_install_apk no selected bridge");
            "No target bridge connected or selected"
        })?;
    let path = PathBuf::from(&apk_path);
    if !path.is_file() {
        eprintln!("[bridge-tauri] push_install_apk missing apk path={apk_path}");
        return Err("File APK tidak ditemukan atau path tidak valid".into());
    }
    let out = command("adb")
        .args(["-s", &device.id, "install", "-r", path.to_str().unwrap()])
        .output()
        .map_err(|e| e.to_string())?;
    
    let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
    if out.status.success() {
        println!("[bridge-tauri] push_install_apk done device={}", device.id);
        Ok("APK berhasil diinstall".into())
    } else {
        eprintln!("[bridge-tauri] push_install_apk failed stdout={stdout} stderr={stderr}");
        Err(format!("Gagal menginstall APK: {} {}", stdout, stderr))
    }
}

#[tauri::command]
async fn push_install_apk(app: AppHandle, apk_path: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || push_install_apk_blocking(app, apk_path))
        .await
        .map_err(|e| e.to_string())?
}

fn connect_wifi_blocking(app: AppHandle, ssid: String, password: String) -> Result<String, String> {
    println!("[bridge-tauri] connect_wifi ssid={ssid}");
    let config = app.state::<Config>().inner().clone();
    let device = list_devices(&config)
        .into_iter()
        .find(|d| d.is_selected_bridge)
        .ok_or_else(|| {
            eprintln!("[bridge-tauri] connect_wifi no selected bridge");
            "No target bridge connected or selected"
        })?;
    
    let out = command("adb")
        .args(["-s", &device.id, "shell", "cmd", "wifi", "connect-network", &ssid, "wpa2", &password])
        .output()
        .map_err(|e| e.to_string())?;

    let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();

    if out.status.success() && !stdout.contains("Failed") && !stderr.contains("Failed") {
        println!("[bridge-tauri] connect_wifi done ssid={ssid} device={}", device.id);
        Ok(format!("Berhasil menghubungkan ke Wi-Fi {}", ssid))
    } else {
        eprintln!("[bridge-tauri] connect_wifi failed stdout={stdout} stderr={stderr}");
        Err(format!("Gagal menghubungkan ke Wi-Fi: {} {}", stdout, stderr))
    }
}

#[tauri::command]
async fn connect_wifi(app: AppHandle, ssid: String, password: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || connect_wifi_blocking(app, ssid, password))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn debug_adb() -> String {
    println!("[bridge-tauri] debug_adb");
    tauri::async_runtime::spawn_blocking(move || {
        let adb_path = get_adb_path();
        let mut result = format!("=== ADB DIAGNOSTICS ===\n");
        result.push_str(&format!("Resolved path: {}\n", adb_path));
        result.push_str(&format!("File exists: {}\n", std::path::Path::new(&adb_path).exists()));

        // 1. Try running version
        let mut cmd1 = std::process::Command::new(&adb_path);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd1.creation_flags(0x08000000);
        }
        match cmd1.arg("--version").output() {
            Ok(out) => {
                result.push_str(&format!(
                    "\n1. adb --version (Success: {}):\nStdout:\n{}\nStderr:\n{}\n",
                    out.status.success(),
                    String::from_utf8_lossy(&out.stdout),
                    String::from_utf8_lossy(&out.stderr)
                ));
            }
            Err(e) => {
                result.push_str(&format!("\n1. adb --version failed: {}\n", e));
            }
        }

        // 2. Try running devices -l
        let mut cmd2 = std::process::Command::new(&adb_path);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd2.creation_flags(0x08000000);
        }
        match cmd2.args(["devices", "-l"]).output() {
            Ok(out) => {
                result.push_str(&format!(
                    "\n2. adb devices -l (Success: {}):\nStdout:\n{}\nStderr:\n{}\n",
                    out.status.success(),
                    String::from_utf8_lossy(&out.stdout),
                    String::from_utf8_lossy(&out.stderr)
                ));
            }
            Err(e) => {
                result.push_str(&format!("\n2. adb devices -l failed: {}\n", e));
            }
        }

        result
    })
    .await
    .unwrap_or_else(|_| "Diagnostics failed".to_string())
}

#[tauri::command]
async fn get_devices(app: AppHandle) -> Result<Vec<DeviceInfo>, String> {
    println!("[bridge-tauri] get_devices");
    let config = app.state::<Config>().inner().clone();
    // ponytail: run list_devices on a tokio blocking thread pool to keep the main UI thread responsive
    let devices = tauri::async_runtime::spawn_blocking(move || {
        let list = list_devices(&config);
        if let Ok(mut cache) = config.devices_cache.lock() {
            *cache = list.clone();
        }
        list
    }).await.map_err(|e| e.to_string())?;
    Ok(devices)
}

fn main() {
    let config = Config {
        target_fingerprint: std::env::var("TARGET_BRIDGE_FINGERPRINT").unwrap_or_else(|_| "PUT_TARGET_RO_BUILD_FINGERPRINT_HERE".into()),
        selected_fingerprint: Arc::new(Mutex::new(None)),
        service: std::env::var("ANDROID_BRIDGE_SERVICE").unwrap_or_else(|_| "com.example.bridge/.BridgeService".into()),
        source_dir: Arc::new(Mutex::new(PathBuf::from(std::env::var("SOURCE_DIR").unwrap_or_else(|_| DEFAULT_SOURCE_DIR.into())))),
        samba_dir: PathBuf::from(std::env::var("SAMBA_DIR").unwrap_or_else(|_| DEFAULT_SAMBA_DIR.into())),
        devices_cache: Arc::new(Mutex::new(vec![])),
    };
    println!(
        "[bridge-tauri] startup source={} samba={} service={}",
        source_dir(&config).display(),
        config.samba_dir.display(),
        config.service
    );

    let app = tauri::Builder::default()
        .manage(config)
        .invoke_handler(tauri::generate_handler![
            push_file,
            app_info,
            select_bridge,
            set_source_dir,
            pick_source_dir,
            pick_apk_file,
            push_install_apk,
            connect_wifi,
            debug_adb,
            get_devices
        ])
        .setup(|app| {
            setup_tray(app)?;
            if let Some(window) = app.get_webview_window("main") {
                let window_for_close = window.clone();
                window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = window_for_close.hide();
                    }
                });
            }
            emit_loop(app.handle().clone());
            websocket_loop(app.handle().clone());
            monitor_loop(app.handle().clone());
            watch_source(app.handle().clone());
            watch_samba(app.handle().clone());
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("failed to build app");

    app.run(|_app_handle, event| {
        if let tauri::RunEvent::ExitRequested { api, .. } = event {
            api.prevent_exit();
        }
    });
}
