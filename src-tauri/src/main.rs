#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use notify::{recommended_watcher, RecursiveMode, Watcher};
use regex::Regex;
use serde::Serialize;
use std::{
    fs::{self, OpenOptions},
    io::{BufRead, BufReader, Read},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{mpsc::channel, Arc, Mutex},
    thread,
    time::{Duration, Instant},
};
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
    source_dir: PathBuf,
    samba_dir: PathBuf,
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

#[derive(Serialize, Clone)]
struct AppInfo {
    platform: String,
    source_dir: String,
    samba_dir: String,
    target_fingerprint_set: bool,
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

#[cfg(target_os = "linux")]
fn network_bytes() -> Option<(u64, u64)> {
    let text = fs::read_to_string("/proc/net/dev").ok()?;
    let mut rx = 0;
    let mut tx = 0;
    for line in text.lines().skip(2) {
        let (name, rest) = line.split_once(':')?;
        if name.trim() == "lo" {
            continue;
        }
        let fields: Vec<_> = rest.split_whitespace().collect();
        rx += fields.first()?.parse::<u64>().ok()?;
        tx += fields.get(8)?.parse::<u64>().ok()?;
    }
    Some((rx, tx))
}

#[cfg(target_os = "windows")]
fn network_bytes() -> Option<(u64, u64)> {
    let out = command("powershell")
        .args([
            "-NoProfile",
            "-Command",
            "(Get-NetAdapterStatistics | Measure-Object -Property ReceivedBytes -Sum).Sum; (Get-NetAdapterStatistics | Measure-Object -Property SentBytes -Sum).Sum",
        ])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let mut lines = String::from_utf8_lossy(&out.stdout).lines().filter_map(|v| v.trim().parse::<u64>().ok());
    Some((lines.next()?, lines.next()?))
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
fn network_bytes() -> Option<(u64, u64)> {
    None
}

fn list_devices(config: &Config) -> Vec<DeviceInfo> {
    let Ok(out) = adb(&["devices", "-l"]) else {
        eprintln!("[bridge-tauri] adb devices failed");
        return vec![];
    };
    let selected = config.selected_fingerprint.lock().ok().and_then(|v| v.clone());
    let devices: Vec<_> = out.lines()
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
            
            // Jangan gunakan .ok()? di sini agar device tetap terdaftar di tabel meskipun adb shell getprop gagal
            let fingerprint = adb(&["-s", &id, "shell", "getprop", "ro.build.fingerprint"])
                .unwrap_or_else(|_| "unknown".to_string());

            let available_storage = storage_kb(&id);
            let (battery_level, battery_temperature) = battery(&id);
            let ip_address = ip_address(&id);
            let apk_installed = adb(&["-s", &id, "shell", "pm", "path", ANDROID_PACKAGE]).is_ok();
            let is_selected_bridge = selected
                .as_deref()
                .map(|target| target == fingerprint)
                .unwrap_or_else(|| fingerprint == config.target_fingerprint);
            Some(DeviceInfo {
                is_target_bridge: is_selected_bridge && available_storage >= MIN_FREE_KB,
                id,
                model,
                fingerprint,
                available_storage,
                battery_level,
                battery_temperature,
                ip_address,
                apk_installed,
                is_selected_bridge,
            })
        })
        .collect();
    println!(
        "[bridge-tauri] devices scanned: total={} selected={} ready={} apk_installed={}",
        devices.len(),
        devices.iter().filter(|d| d.is_selected_bridge).count(),
        devices.iter().filter(|d| d.is_target_bridge).count(),
        devices.iter().filter(|d| d.apk_installed).count()
    );
    devices
}

fn storage_kb(id: &str) -> u64 {
    let Ok(out) = adb(&["-s", id, "shell", "df", "-k", "/sdcard/Download"]) else { return 0 };
    out.lines()
        .skip(1)
        .filter_map(|line| line.split_whitespace().nth(3)?.parse().ok())
        .next()
        .unwrap_or(0)
}

fn battery(id: &str) -> (Option<u8>, Option<f32>) {
    let Ok(out) = adb(&["-s", id, "shell", "dumpsys", "battery"]) else { return (None, None) };
    let mut level = None;
    let mut temp = None;
    for line in out.lines() {
        let line = line.trim();
        if let Some(value) = line.strip_prefix("level:") {
            level = value.trim().parse().ok();
        }
        if let Some(value) = line.strip_prefix("temperature:") {
            temp = value.trim().parse::<f32>().ok().map(|v| v / 10.0);
        }
    }
    (level, temp)
}

fn ip_address(id: &str) -> String {
    adb(&["-s", id, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"])
        .ok()
        .and_then(|out| {
            out.lines()
                .find_map(|line| line.trim().strip_prefix("inet ")?.split('/').next().map(str::to_string))
        })
        .unwrap_or_else(|| "-".into())
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
        let _ = app.emit("devices", list_devices(&config));
        let _ = app.emit("files", bridge_files(&config.source_dir));
        let _ = app.emit("samba-files", bridge_files(&config.samba_dir));
        // ponytail: increase interval to 5s to drastically reduce background process spawning overhead
        thread::sleep(Duration::from_secs(5));
    });
}

fn network_loop(app: AppHandle) {
    thread::spawn(move || {
        let mut last = network_bytes();
        let mut last_at = Instant::now();
        loop {
            thread::sleep(Duration::from_secs(1));
            let now = Instant::now();
            let Some(current) = network_bytes() else { continue };
            if let Some(previous) = last {
                let secs = now.duration_since(last_at).as_secs().max(1);
                let _ = app.emit("network", NetworkSample {
                    rx_bps: current.0.saturating_sub(previous.0) / secs,
                    tx_bps: current.1.saturating_sub(previous.1) / secs,
                });
            }
            last = Some(current);
            last_at = now;
        }
    });
}

fn watch_source(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    thread::spawn(move || {
        let (tx, rx) = channel();
        let Ok(mut watcher) = recommended_watcher(tx) else { return };
        if watcher.watch(&config.source_dir, RecursiveMode::NonRecursive).is_err() {
            return;
        }
        while rx.recv().is_ok() {
            let _ = app.emit("files", bridge_files(&config.source_dir));
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

fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let show = MenuItemBuilder::with_id("show", "Show").build(app)?;
    let quit = MenuItemBuilder::with_id("quit", "Quit").build(app)?;
    let menu = MenuBuilder::new(app).item(&show).item(&quit).build()?;
    let icon = app.default_window_icon().cloned().unwrap();

    TrayIconBuilder::new()
        .tooltip("Android File Bridge")
        .icon(icon)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show" => show_main_window(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, _| show_main_window(tray.app_handle()))
        .build(app)?;
    Ok(())
}

fn pipe_progress<R: Read + Send + 'static>(app: AppHandle, file: String, stream: R) {
    thread::spawn(move || {
        let re = Regex::new(r"(\d{1,3})%").unwrap();
        for line in BufReader::new(stream).lines().flatten() {
            let percent = re.captures(&line).and_then(|c| c[1].parse().ok()).unwrap_or(0);
            let _ = app.emit("transfer", TransferProgress { file: file.clone(), percent, message: line });
        }
    });
}

#[tauri::command]
fn push_file(app: AppHandle, file_name: String) -> Result<(), String> {
    println!("[bridge-tauri] push_file start file={file_name}");
    let config = app.state::<Config>().inner().clone();
    let device = list_devices(&config)
        .into_iter()
        .find(|d| d.is_target_bridge)
        .ok_or_else(|| {
            eprintln!("[bridge-tauri] push_file no ready bridge");
            "target bridge fingerprint not connected or lacks 25GB free storage"
        })?;
    let source = config.source_dir.join(&file_name);
    if !source.is_file() || !file_name.ends_with(".tar.md5") || !file_is_available(&source) {
        eprintln!("[bridge-tauri] push_file rejected source={}", source.display());
        return Err("file is not a ready .tar.md5".into());
    }

    let _ = adb(&["-s", &device.id, "shell", "mkdir", "-p", ANDROID_DIR]);
    let mut child = command("adb")
        .args(["-s", &device.id, "push"])
        .arg(&source)
        .arg(ANDROID_DIR)
        .stderr(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .map_err(|e| e.to_string())?;

    if let Some(stream) = child.stderr.take() {
        pipe_progress(app.clone(), file_name.clone(), stream);
    }
    if let Some(stream) = child.stdout.take() {
        pipe_progress(app.clone(), file_name.clone(), stream);
    }

    let status = child.wait().map_err(|e| e.to_string())?;
    if !status.success() {
        eprintln!("[bridge-tauri] adb push failed file={file_name}");
        return Err("adb push failed".into());
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
    ])?;
    println!("[bridge-tauri] push_file done file={file_name} device={}", device.id);
    Ok(())
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
fn app_info(app: AppHandle) -> AppInfo {
    let config = app.state::<Config>().inner();
    println!(
        "[bridge-tauri] app_info platform={} source={} samba={}",
        std::env::consts::OS,
        config.source_dir.display(),
        config.samba_dir.display()
    );
    AppInfo {
        platform: std::env::consts::OS.into(),
        source_dir: config.source_dir.display().to_string(),
        samba_dir: config.samba_dir.display().to_string(),
        target_fingerprint_set: config.target_fingerprint != "PUT_TARGET_RO_BUILD_FINGERPRINT_HERE",
    }
}

#[tauri::command]
async fn pick_apk_file() -> Option<String> {
    rfd::FileDialog::new()
        .add_filter("Android APK", &["apk"])
        .pick_file()
        .map(|p| p.to_string_lossy().into_owned())
}

#[tauri::command]
fn push_install_apk(app: AppHandle, apk_path: String) -> Result<String, String> {
    println!("[bridge-tauri] push_install_apk path={apk_path}");
    let config = app.state::<Config>().inner().clone();
    let device = list_devices(&config)
        .into_iter()
        .find(|d| d.is_target_bridge)
        .ok_or_else(|| {
            eprintln!("[bridge-tauri] push_install_apk no ready bridge");
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
fn connect_wifi(app: AppHandle, ssid: String, password: String) -> Result<String, String> {
    println!("[bridge-tauri] connect_wifi ssid={ssid}");
    let config = app.state::<Config>().inner().clone();
    let device = list_devices(&config)
        .into_iter()
        .find(|d| d.is_target_bridge)
        .ok_or_else(|| {
            eprintln!("[bridge-tauri] connect_wifi no ready bridge");
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
fn debug_adb() -> String {
    println!("[bridge-tauri] debug_adb");
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
}

#[tauri::command]
async fn get_devices(app: AppHandle) -> Result<Vec<DeviceInfo>, String> {
    println!("[bridge-tauri] get_devices");
    let config = app.state::<Config>().inner().clone();
    // ponytail: run list_devices on a tokio blocking thread pool to keep the main UI thread responsive
    let devices = tauri::async_runtime::spawn_blocking(move || {
        list_devices(&config)
    }).await.map_err(|e| e.to_string())?;
    Ok(devices)
}

fn main() {
    let config = Config {
        target_fingerprint: std::env::var("TARGET_BRIDGE_FINGERPRINT").unwrap_or_else(|_| "PUT_TARGET_RO_BUILD_FINGERPRINT_HERE".into()),
        selected_fingerprint: Arc::new(Mutex::new(None)),
        service: std::env::var("ANDROID_BRIDGE_SERVICE").unwrap_or_else(|_| "com.example.bridge/.BridgeService".into()),
        source_dir: PathBuf::from(std::env::var("SOURCE_DIR").unwrap_or_else(|_| DEFAULT_SOURCE_DIR.into())),
        samba_dir: PathBuf::from(std::env::var("SAMBA_DIR").unwrap_or_else(|_| DEFAULT_SAMBA_DIR.into())),
    };
    println!(
        "[bridge-tauri] startup source={} samba={} service={}",
        config.source_dir.display(),
        config.samba_dir.display(),
        config.service
    );

    tauri::Builder::default()
        .manage(config)
        .invoke_handler(tauri::generate_handler![
            push_file,
            app_info,
            select_bridge,
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
            network_loop(app.handle().clone());
            watch_source(app.handle().clone());
            watch_samba(app.handle().clone());
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("failed to run app");
}
