#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use notify::{recommended_watcher, RecursiveMode, Watcher};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::{BufReader, Read},
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
const MIN_FREE_KB: u64 = 1 * 1024 * 1024;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x08000000;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
struct PersistedSettings {
    #[serde(default)]
    source_dir: Option<String>,
    #[serde(default)]
    samba_dir: Option<String>,
    #[serde(default)]
    auto_push: Option<bool>,
    #[serde(default)]
    force_transfer: Option<bool>,
    #[serde(default)]
    post_transfer_action: Option<String>,
    #[serde(default)]
    selected_fingerprint: Option<String>,
    #[serde(default)]
    theme: Option<String>,
    #[serde(default)]
    priority_files: Option<Vec<String>>,
    #[serde(default)]
    user_unchecked_priority: Option<Vec<String>>,
}

fn get_config_path() -> PathBuf {
    #[cfg(windows)]
    {
        if let Ok(app_data) = std::env::var("APPDATA") {
            let dir = PathBuf::from(app_data).join("FireFiles");
            let _ = std::fs::create_dir_all(&dir);
            return dir.join("config.json");
        }
    }
    #[cfg(not(windows))]
    {
        if let Ok(home) = std::env::var("HOME") {
            let dir = PathBuf::from(home).join(".config").join("firefiles");
            let _ = std::fs::create_dir_all(&dir);
            return dir.join("config.json");
        }
    }
    PathBuf::from("config.json")
}

fn load_persisted_settings() -> PersistedSettings {
    let path = get_config_path();
    if let Ok(data) = std::fs::read_to_string(&path) {
        if let Ok(settings) = serde_json::from_str::<PersistedSettings>(&data) {
            return settings;
        }
    }
    PersistedSettings::default()
}

fn save_persisted_settings(settings: &PersistedSettings) {
    let path = get_config_path();
    if let Ok(json) = serde_json::to_string_pretty(settings) {
        let _ = std::fs::write(&path, json);
    }
}

#[derive(Clone)]
struct Config {
    target_fingerprint: String,
    selected_fingerprint: Arc<Mutex<Option<String>>>,
    service: String,
    source_dir: Arc<Mutex<PathBuf>>,
    samba_dir: Arc<Mutex<PathBuf>>,
    devices_cache: Arc<Mutex<Vec<DeviceInfo>>>,
    settings: Arc<Mutex<PersistedSettings>>,
}

#[derive(Serialize, Clone)]
struct DeviceInfo {
    id: String,
    model: String,
    fingerprint: String,
    available_storage: u64,
    ip_address: String,
    apk_installed: bool,
    is_selected_bridge: bool,
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
    speed_bps: u64,
}

#[derive(Serialize, Clone)]
struct AppInfo {
    platform: String,
    source_dir: String,
    samba_dir: String,
    target_fingerprint_set: bool,
    hostname: String,
    auto_push: bool,
    force_transfer: bool,
    post_transfer_action: String,
    selected_fingerprint: Option<String>,
    theme: String,
    priority_files: Vec<String>,
    user_unchecked_priority: Vec<String>,
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

fn samba_dir(config: &Config) -> PathBuf {
    config.samba_dir.lock().map(|v| v.clone()).unwrap_or_else(|_| PathBuf::from(DEFAULT_SAMBA_DIR))
}

fn get_app_info_internal(config: &Config) -> AppInfo {
    let hostname = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "tauri".into());
    let src = source_dir(config);
    let smb = samba_dir(config);
    let settings = config.settings.lock().map(|s| s.clone()).unwrap_or_default();
    
    AppInfo {
        platform: std::env::consts::OS.into(),
        source_dir: src.display().to_string(),
        samba_dir: smb.display().to_string(),
        target_fingerprint_set: config.target_fingerprint != "PUT_TARGET_RO_BUILD_FINGERPRINT_HERE",
        hostname,
        auto_push: settings.auto_push.unwrap_or(true),
        force_transfer: settings.force_transfer.unwrap_or(false),
        post_transfer_action: settings.post_transfer_action.unwrap_or_else(|| "backup".into()),
        selected_fingerprint: config.selected_fingerprint.lock().ok().and_then(|fp| fp.clone()),
        theme: settings.theme.unwrap_or_else(|| "dark".into()),
        priority_files: settings.priority_files.unwrap_or_default(),
        user_unchecked_priority: settings.user_unchecked_priority.unwrap_or_default(),
    }
}

fn get_device_details(id: &str) -> (String, u64, String, bool) {
    let cmd = format!(
        "getprop ro.build.fingerprint; echo '==='; df -k /sdcard; echo '==='; ip -f inet addr show wlan0; echo '==='; pm path {}",
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
        let handle = thread::spawn(move || {
            let (fingerprint, available_storage, ip_address, apk_installed) = get_device_details(&id);
            (id, model, fingerprint, available_storage, ip_address, apk_installed)
        });
        handles.push(handle);
    }

    let mut devices = vec![];
    for handle in handles {
        if let Ok((id, model, fingerprint, available_storage, ip_address, apk_installed)) = handle.join() {
            if apk_installed {
                // Auto reverse port 1421 through USB ADB so offline phone can talk to Tauri
                let _ = adb(&["-s", &id, "reverse", "tcp:1421", "tcp:1421"]);
            }
            devices.push((id, model, fingerprint, available_storage, ip_address, apk_installed));
        }
    }

    let target_fingerprint = config.target_fingerprint.clone();
    let selected_target = if let Some(target) = selected {
        Some(target)
    } else if let Some(dev_with_apk) = devices.iter().find(|d| d.5) {
        println!("[bridge-tauri] Auto-pairing with connected device having bridge APK: {}", dev_with_apk.0);
        Some(dev_with_apk.2.clone())
    } else {
        None
    };

    let result_devices: Vec<DeviceInfo> = devices.into_iter().map(|(id, model, fingerprint, available_storage, ip_address, apk_installed)| {
        let is_selected = if let Some(ref target) = selected_target {
            fingerprint == *target
        } else {
            fingerprint == target_fingerprint
        };
        DeviceInfo {
            is_selected_bridge: is_selected,
            id,
            model,
            fingerprint,
            available_storage,
            ip_address,
            apk_installed,
        }
    }).collect();

    println!(
        "[bridge-tauri] devices scanned: total={} selected={}",
        result_devices.len(),
        result_devices.iter().filter(|d| d.is_selected_bridge).count(),
    );
    result_devices
}

fn bridge_files(dir: &Path) -> Vec<LocalFile> {
    let Ok(entries) = fs::read_dir(dir) else { return vec![] };
    entries
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            let name = path.file_name()?.to_string_lossy().to_string();
            let lower = name.to_lowercase();
            let is_part = lower.ends_with(".part")
                || lower.ends_with(".crdownload")
                || lower.ends_with(".downloading")
                || lower.ends_with(".tmp");
            let is_supported = lower.ends_with(".md5")
                || lower.ends_with(".zip")
                || lower.ends_with(".txt")
                || is_part;
            if !is_supported {
                return None;
            }
            let meta = entry.metadata().ok()?;
            let locked = !is_part && !file_is_available(&path);
            Some(LocalFile {
                status: if is_part { "downloading" } else if locked { "locked" } else { "ready" }.into(),
                name,
                size: meta.len(),
                locked,
            })
        })
        .collect()
}
fn watch_adb_devices(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    thread::spawn(move || {
        let mut last_ids = Vec::<String>::new();
        // Force an initial scan to populate the cache immediately on start
        let list = list_devices(&config);
        if let Ok(mut cache) = config.devices_cache.lock() {
            *cache = list.clone();
        }
        let _ = app.emit("devices", list);

        loop {
            let ids = match adb(&["devices"]) {
                Ok(out) => {
                    out.lines()
                        .skip(1)
                        .filter_map(|line| {
                            let mut parts = line.split_whitespace();
                            let id = parts.next()?;
                            let status = parts.next()?;
                            if status == "device" {
                                Some(id.to_string())
                            } else {
                                None
                            }
                        })
                        .collect::<Vec<String>>()
                }
                Err(_) => vec![],
            };

            if ids != last_ids {
                println!("[bridge-tauri] ADB device list changed: old={:?}, new={:?}", last_ids, ids);
                last_ids = ids.clone();
                for id in &ids {
                    let _ = adb(&["-s", id, "reverse", "tcp:1421", "tcp:1421"]);
                }
                
                let list = list_devices(&config);
                if let Ok(mut cache) = config.devices_cache.lock() {
                    *cache = list.clone();
                }
                let _ = app.emit("devices", list);
            }

            thread::sleep(Duration::from_secs(2));
        }
    });
}

fn file_is_available(path: &Path) -> bool {
    OpenOptions::new().read(true).open(path).is_ok()
}

fn emit_loop(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    thread::spawn(move || loop {
        if let Err(e) = app.emit("files", bridge_files(&source_dir(&config))) {
            eprintln!("[bridge-tauri] emit files error: {}", e);
        }
        if let Err(e) = app.emit("samba-files", bridge_files(&samba_dir(&config))) {
            eprintln!("[bridge-tauri] emit samba-files error: {}", e);
        }
        thread::sleep(Duration::from_secs(5));
    });
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
            thread::sleep(Duration::from_millis(200));
            while rx.try_recv().is_ok() {}
            let _ = app.emit("files", bridge_files(&source_dir(&config)));
        }
    });
}

fn watch_samba(app: AppHandle) {
    let config = app.state::<Config>().inner().clone();
    thread::spawn(move || {
        let (tx, rx) = channel();
        let Ok(mut watcher) = recommended_watcher(tx) else { return };
        let dir = samba_dir(&config);
        if watcher.watch(&dir, RecursiveMode::NonRecursive).is_err() {
            return;
        }
        while rx.recv().is_ok() {
            thread::sleep(Duration::from_millis(200));
            while rx.try_recv().is_ok() {}
            let _ = app.emit("samba-files", bridge_files(&samba_dir(&config)));
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
    let menu = MenuBuilder::new(app)
        .item(&show)
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
            "quit" => std::process::exit(0),
            _ => {}
        })
        .build(app)?;
    Ok(())
}

fn shell_escape(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
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
                                        let capped_percent = std::cmp::min(99, percent);
                                        let _ = app.emit("transfer", TransferProgress {
                                            file: file.clone(),
                                            percent: capped_percent,
                                            message: trimmed.to_string(),
                                            speed_bps: 0,
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
    let quoted_path = shell_escape(path);
    let out = command("adb")
        .args(["-s", device_id, "shell", "stat", "-c", "%s", &quoted_path])
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

fn get_staged_files_count(device_id: &str) -> Result<usize, String> {
    let output = command("adb")
        .args(["-s", device_id, "shell", "ls", "-1", ANDROID_DIR])
        .output()
        .map_err(|e| e.to_string())?;
    
    let stdout = String::from_utf8_lossy(&output.stdout);
    if stdout.contains("No such file or directory") {
        return Ok(0);
    }
    let count = stdout
        .lines()
        .filter(|line| {
            let l = line.trim().to_lowercase();
            !l.is_empty() && (l.ends_with(".md5") || l.ends_with(".zip") || l.ends_with(".txt"))
        })
        .count();
    Ok(count)
}

fn push_file_blocking(
    app: AppHandle,
    file_name: String,
    force: bool,
    queue_total: i32,
    queue_success: i32,
    cleanup_mode: Option<String>,
) -> Result<(), String> {
    println!("[bridge-tauri] push_file start file={file_name} force={force} queue_total={queue_total} queue_success={queue_success} cleanup_mode={:?}", cleanup_mode);
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
        .find(|d| d.is_selected_bridge)
        .ok_or_else(|| {
            eprintln!("[bridge-tauri] push_file no bridge selected");
            "No device selected. Connect a device with the bridge APK installed."
        })?;

    // Poll/wait if there is already an active upload/staged files on the phone (double-buffered: max 2 files)
    loop {
        let staged_count = get_staged_files_count(&device.id).unwrap_or(0);
        if staged_count < 3 {
            break;
        }
        println!("[bridge-tauri] Device staging folder has {} files. Waiting 2s for Samba upload...", staged_count);
        let _ = app.emit("transfer", TransferProgress {
            file: file_name.clone(),
            percent: 0,
            message: format!("Waiting for previous upload to complete ({} files remaining on phone)...", staged_count),
            speed_bps: 0,
        });
        thread::sleep(Duration::from_secs(2));
    }

    // Soft storage check — warn but don't block (MIN_FREE_KB = 1GB)
    if !force && device.available_storage < MIN_FREE_KB {
        eprintln!("[bridge-tauri] push_file low storage: {} KB free", device.available_storage);
        return Err(format!(
            "Device storage too low: {} MB free (need 1 GB). Use Force Transfer to override.",
            device.available_storage / 1024
        ));
    }
    let source = source_dir(&config).join(&file_name);
    let lower_name = file_name.to_lowercase();
    let is_supported = lower_name.ends_with(".md5") || lower_name.ends_with(".zip") || lower_name.ends_with(".txt");
    if !source.is_file() || !is_supported || (!force && !file_is_available(&source)) {
        eprintln!("[bridge-tauri] push_file rejected source={}", source.display());
        return Err("file is not a ready (.md5, .zip, .txt) file".into());
    }

    if let Err(e) = adb(&["-s", &device.id, "shell", "mkdir", "-p", ANDROID_DIR]) {
        eprintln!("[bridge-tauri] mkdir failed: {e}");
        return Err(format!("Failed to create destination directory: {e}"));
    }

    let total_size = match fs::metadata(&source) {
        Ok(m) => m.len(),
        Err(_) => 1,
    };

    let meta_path = format!("{}{}.meta", ANDROID_DIR, file_name);
    let escaped_meta = shell_escape(&meta_path);
    let _ = adb(&[
        "-s",
        &device.id,
        "shell",
        &format!("echo {} > {}", total_size, escaped_meta),
    ]);

    let _ = app.emit("transfer", TransferProgress {
        file: file_name.clone(),
        percent: 0,
        message: "Starting adb push...".into(),
        speed_bps: 0,
    });

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
        let mut last_check = Instant::now();
        let mut last_remote_size: u64 = 0;
        let mut last_speed_bps: u64 = 0;

        while *ir_clone.lock().unwrap() {
            thread::sleep(Duration::from_millis(500));
            if !*ir_clone.lock().unwrap() {
                break;
            }
            if let Some(remote_size) = get_remote_file_size(&device_id, &remote_path) {
                if !*ir_clone.lock().unwrap() {
                    break;
                }
                let now = Instant::now();
                let elapsed = now.duration_since(last_check).as_secs_f64();
                if elapsed >= 0.3 && remote_size >= last_remote_size {
                    let diff = remote_size - last_remote_size;
                    last_speed_bps = ((diff as f64) / elapsed) as u64;
                    last_remote_size = remote_size;
                    last_check = now;
                }
                let percent = ((remote_size as f64 / total_size as f64) * 100.0) as u8;
                let percent = std::cmp::min(99, percent);
                let _ = app_handle.emit("transfer", TransferProgress {
                    file: file_name_clone.clone(),
                    percent,
                    message: format!("Pushed {}/{} bytes ({}%)", remote_size, total_size, percent),
                    speed_bps: last_speed_bps,
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
    let _ = app.emit("transfer", TransferProgress { file: file_name.clone(), percent: 100, message: "push complete".into(), speed_bps: 0 });
    let escaped_file = shell_escape(&file_name);
    let q_tot = queue_total.to_string();
    let q_succ = queue_success.to_string();
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
        &escaped_file,
        "--ei",
        "queue_total",
        &q_tot,
        "--ei",
        "queue_success",
        &q_succ,
    ])?;
    println!("[bridge-tauri] push_file done file={file_name} device={}", device.id);

    // Post-transfer action: "delete" (permanently delete) or "backup" (move to BACKUP folder)
    let mode = cleanup_mode.as_deref().unwrap_or("backup");
    if mode == "delete" {
        if let Err(e) = fs::remove_file(&source) {
            eprintln!("[bridge-tauri] failed to permanently delete {}: {e}", source.display());
        } else {
            println!("[bridge-tauri] permanently deleted {file_name} from source directory");
        }
    } else {
        let backup_dir = source_dir(&config).join("BACKUP");
        if let Err(e) = fs::create_dir_all(&backup_dir) {
            eprintln!("[bridge-tauri] failed to create BACKUP dir: {e}");
        } else {
            let backup_path = backup_dir.join(&file_name);
            if let Err(e) = fs::rename(&source, &backup_path) {
                eprintln!("[bridge-tauri] failed to move file to BACKUP: {e}");
            } else {
                println!("[bridge-tauri] moved {file_name} to BACKUP");
            }
        }
    }

    Ok(())
}

#[tauri::command(rename_all = "snake_case")]
async fn push_file(
    app: AppHandle,
    file_name: String,
    force: bool,
    queue_total: i32,
    queue_success: i32,
    cleanup_mode: Option<String>,
) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        push_file_blocking(app, file_name, force, queue_total, queue_success, cleanup_mode)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn get_phone_files(app: AppHandle) -> Result<Vec<String>, String> {
    let config = app.state::<Config>().inner().clone();
    let cached_devices = config.devices_cache.lock().ok().map(|c| c.clone()).unwrap_or_default();
    let device_id = cached_devices.iter().find(|d| d.is_selected_bridge).map(|d| d.id.clone());
    if let Some(id) = device_id {
        tauri::async_runtime::spawn_blocking(move || {
            let Ok(out) = adb(&["-s", &id, "shell", "ls", "-1", ANDROID_DIR]) else {
                return Ok(vec![]);
            };
            let files: Vec<String> = out.lines()
                .map(|l| l.trim().to_string())
                .filter(|l| {
                    let lower = l.to_lowercase();
                    !lower.is_empty() && (lower.ends_with(".md5") || lower.ends_with(".zip") || lower.ends_with(".txt"))
                })
                .collect();
            Ok(files)
        })
        .await
        .map_err(|e| e.to_string())?
    } else {
        Ok(vec![])
    }
}

#[tauri::command(rename_all = "snake_case")]
async fn select_bridge(app: AppHandle, fingerprint: String) -> Result<(), String> {
    println!("[bridge-tauri] select_bridge fingerprint={fingerprint}");
    let config = app.state::<Config>().inner().clone();
    let fp_val = if fingerprint.is_empty() { None } else { Some(fingerprint.clone()) };
    *config.selected_fingerprint.lock().map_err(|e| e.to_string())? = fp_val.clone();
    if let Ok(mut s) = config.settings.lock() {
        s.selected_fingerprint = fp_val;
        save_persisted_settings(&s);
    }
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
        get_app_info_internal(&config)
    })
    .await
    .unwrap_or_else(|_| AppInfo {
        platform: std::env::consts::OS.into(),
        source_dir: "".into(),
        samba_dir: "".into(),
        target_fingerprint_set: false,
        hostname: "tauri".into(),
        auto_push: true,
        force_transfer: false,
        post_transfer_action: "backup".into(),
        selected_fingerprint: None,
        theme: "dark".into(),
        priority_files: vec![],
        user_unchecked_priority: vec![],
    })
}

#[tauri::command(rename_all = "snake_case")]
async fn set_source_dir(app: AppHandle, path: String) -> Result<Vec<LocalFile>, String> {
    let config = app.state::<Config>().inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let dir = PathBuf::from(&path);
        if !dir.is_dir() {
            return Err(format!("source folder not found: {}", dir.display()));
        }
        *config.source_dir.lock().map_err(|e| e.to_string())? = dir.clone();
        if let Ok(mut s) = config.settings.lock() {
            s.source_dir = Some(path.clone());
            save_persisted_settings(&s);
        }
        let files = bridge_files(&dir);
        println!("[bridge-tauri] source_dir set {} files={}", dir.display(), files.len());
        let _ = app.emit("files", files.clone());
        Ok(files)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command(rename_all = "snake_case")]
async fn save_settings(app: AppHandle, settings: PersistedSettings) -> Result<AppInfo, String> {
    let config = app.state::<Config>().inner().clone();
    
    if let Some(ref src) = settings.source_dir {
        let p = PathBuf::from(src);
        if p.is_dir() {
            if let Ok(mut l) = config.source_dir.lock() {
                *l = p;
            }
        }
    }
    if let Some(ref smb) = settings.samba_dir {
        let p = PathBuf::from(smb);
        if let Ok(mut l) = config.samba_dir.lock() {
            *l = p;
        }
    }
    if let Some(ref fp) = settings.selected_fingerprint {
        if let Ok(mut l) = config.selected_fingerprint.lock() {
            *l = if fp.is_empty() { None } else { Some(fp.clone()) };
        }
    }
    
    if let Ok(mut current) = config.settings.lock() {
        if settings.source_dir.is_some() { current.source_dir = settings.source_dir.clone(); }
        if settings.samba_dir.is_some() { current.samba_dir = settings.samba_dir.clone(); }
        if settings.auto_push.is_some() { current.auto_push = settings.auto_push; }
        if settings.force_transfer.is_some() { current.force_transfer = settings.force_transfer; }
        if settings.post_transfer_action.is_some() { current.post_transfer_action = settings.post_transfer_action.clone(); }
        if settings.selected_fingerprint.is_some() { current.selected_fingerprint = settings.selected_fingerprint.clone(); }
        if settings.theme.is_some() { current.theme = settings.theme.clone(); }
        if settings.priority_files.is_some() { current.priority_files = settings.priority_files.clone(); }
        if settings.user_unchecked_priority.is_some() { current.user_unchecked_priority = settings.user_unchecked_priority.clone(); }
        save_persisted_settings(&current);
    }

    Ok(get_app_info_internal(&config))
}

#[tauri::command]
async fn pick_source_dir() -> Option<String> {
    rfd::FileDialog::new()
        .pick_folder()
        .map(|p| p.to_string_lossy().into_owned())
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

#[tauri::command]
async fn open_url(url: String) -> Result<(), String> {
    println!("[bridge-tauri] open_url: {}", url);
    tauri::async_runtime::spawn_blocking(move || {
        #[cfg(target_os = "windows")]
        {
            use std::os::windows::process::CommandExt;
            let mut cmd = std::process::Command::new("cmd");
            cmd.creation_flags(0x08000000);
            cmd.args(["/C", "start", "", &url])
                .spawn()
                .map_err(|e| e.to_string())?;
        }
        #[cfg(target_os = "macos")]
        {
            std::process::Command::new("open")
                .arg(&url)
                .spawn()
                .map_err(|e| e.to_string())?;
        }
        #[cfg(target_os = "linux")]
        {
            std::process::Command::new("xdg-open")
                .arg(&url)
                .spawn()
                .map_err(|e| e.to_string())?;
        }
        Ok(())
    })
    .await
    .map_err(|e| e.to_string())?
}

fn start_usb_relay(app: AppHandle) {
    thread::spawn(move || {
        let listener = match std::net::TcpListener::bind("0.0.0.0:1421") {
            Ok(l) => l,
            Err(e) => {
                eprintln!("[bridge-tauri] usb relay bind 0.0.0.0:1421 failed ({e}), trying 127.0.0.1:1421");
                match std::net::TcpListener::bind("127.0.0.1:1421") {
                    Ok(l) => l,
                    Err(e2) => {
                        eprintln!("[bridge-tauri] usb relay bind 127.0.0.1:1421 failed: {e2}");
                        return;
                    }
                }
            }
        };
        println!("[bridge-tauri] USB Reverse Relay listening on port 1421");
        for stream in listener.incoming() {
            if let Ok(socket) = stream {
                let app_clone = app.clone();
                thread::spawn(move || {
                    let mut reader = std::io::BufReader::new(socket);
                    let mut line = String::new();
                    use std::io::BufRead;
                    while let Ok(n) = reader.read_line(&mut line) {
                        if n == 0 { break; }
                        let trimmed = line.trim();
                        if !trimmed.is_empty() {
                            if let Ok(json_val) = serde_json::from_str::<serde_json::Value>(trimmed) {
                                let _ = app_clone.emit("usb_telemetry", json_val);
                            }
                        }
                        line.clear();
                    }
                });
            }
        }
    });
}

fn main() {
    let persisted = load_persisted_settings();
    
    let default_source = std::env::var("SOURCE_DIR")
        .map(PathBuf::from)
        .ok()
        .or_else(|| persisted.source_dir.as_ref().map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from(DEFAULT_SOURCE_DIR));

    let default_samba = std::env::var("SAMBA_DIR")
        .map(PathBuf::from)
        .ok()
        .or_else(|| persisted.samba_dir.as_ref().map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from(DEFAULT_SAMBA_DIR));

    let initial_fp = persisted.selected_fingerprint.clone();

    let config = Config {
        target_fingerprint: std::env::var("TARGET_BRIDGE_FINGERPRINT").unwrap_or_else(|_| "PUT_TARGET_RO_BUILD_FINGERPRINT_HERE".into()),
        selected_fingerprint: Arc::new(Mutex::new(initial_fp)),
        service: std::env::var("ANDROID_BRIDGE_SERVICE").unwrap_or_else(|_| "com.example.bridge/.BridgeService".into()),
        source_dir: Arc::new(Mutex::new(default_source)),
        samba_dir: Arc::new(Mutex::new(default_samba)),
        devices_cache: Arc::new(Mutex::new(vec![])),
        settings: Arc::new(Mutex::new(persisted)),
    };
    println!(
        "[bridge-tauri] startup source={} samba={} service={}",
        source_dir(&config).display(),
        samba_dir(&config).display(),
        config.service
    );

    let app = tauri::Builder::default()
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(config)
        .invoke_handler(tauri::generate_handler![
            push_file,
            get_phone_files,
            app_info,
            select_bridge,
            set_source_dir,
            pick_source_dir,
            debug_adb,
            get_devices,
            open_url,
            save_settings
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
            start_usb_relay(app.handle().clone());
            emit_loop(app.handle().clone());
            watch_source(app.handle().clone());
            watch_samba(app.handle().clone());
            watch_adb_devices(app.handle().clone());
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
