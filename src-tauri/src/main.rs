use notify::{recommended_watcher, RecursiveMode, Watcher};
use regex::Regex;
use serde::Serialize;
use std::{
    fs::{self, OpenOptions},
    io::{BufRead, BufReader, Read},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::mpsc::channel,
    thread,
    time::Duration,
};
use tauri::{AppHandle, Emitter, Manager};

const SOURCE_DIR: &str = r"E:\SUBRO";
const ANDROID_DIR: &str = "/sdcard/Download/SUBRO/";
const MIN_FREE_KB: u64 = 25 * 1024 * 1024;

#[derive(Clone)]
struct Config {
    target_fingerprint: String,
    service: String,
}

#[derive(Serialize, Clone)]
struct DeviceInfo {
    id: String,
    model: String,
    fingerprint: String,
    available_storage: u64,
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

fn adb(args: &[&str]) -> Result<String, String> {
    let out = Command::new("adb").args(args).output().map_err(|e| e.to_string())?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

fn list_devices(config: &Config) -> Vec<DeviceInfo> {
    let Ok(out) = adb(&["devices", "-l"]) else { return vec![] };
    out.lines()
        .skip(1)
        .filter(|line| line.contains(" device"))
        .filter_map(|line| {
            let id = line.split_whitespace().next()?.to_string();
            let model = line
                .split_whitespace()
                .find_map(|part| part.strip_prefix("model:"))
                .unwrap_or("unknown")
                .to_string();
            let fingerprint = adb(&["-s", &id, "shell", "getprop", "ro.build.fingerprint"]).ok()?;
            let available_storage = storage_kb(&id);
            Some(DeviceInfo {
                is_target_bridge: fingerprint == config.target_fingerprint && available_storage >= MIN_FREE_KB,
                id,
                model,
                fingerprint,
                available_storage,
            })
        })
        .collect()
}

fn storage_kb(id: &str) -> u64 {
    let Ok(out) = adb(&["-s", id, "shell", "df", "-k", "/sdcard/Download"]) else { return 0 };
    out.lines()
        .skip(1)
        .filter_map(|line| line.split_whitespace().nth(3)?.parse().ok())
        .next()
        .unwrap_or(0)
}

fn local_files() -> Vec<LocalFile> {
    let Ok(entries) = fs::read_dir(SOURCE_DIR) else { return vec![] };
    entries
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            let name = path.file_name()?.to_string_lossy().to_string();
            if !name.ends_with(".tar.md5") && !name.ends_with(".tar.md5.part") {
                return None;
            }
            let meta = entry.metadata().ok()?;
            let locked = name.ends_with(".tar.md5") && !file_is_available(&path);
            Some(LocalFile {
                status: if name.ends_with(".part") { "downloading" } else if locked { "locked" } else { "ready" }.into(),
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
        let _ = app.emit("devices", list_devices(&config));
        let _ = app.emit("files", local_files());
        thread::sleep(Duration::from_secs(2));
    });
}

fn watch_source(app: AppHandle) {
    thread::spawn(move || {
        let (tx, rx) = channel();
        let Ok(mut watcher) = recommended_watcher(tx) else { return };
        if watcher.watch(Path::new(SOURCE_DIR), RecursiveMode::NonRecursive).is_err() {
            return;
        }
        while rx.recv().is_ok() {
            let _ = app.emit("files", local_files());
        }
    });
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
    let config = app.state::<Config>().inner().clone();
    let device = list_devices(&config)
        .into_iter()
        .find(|d| d.is_target_bridge)
        .ok_or("target bridge fingerprint not connected or lacks 25GB free storage")?;
    let source = PathBuf::from(SOURCE_DIR).join(&file_name);
    if !source.is_file() || !file_name.ends_with(".tar.md5") || !file_is_available(&source) {
        return Err("file is not a ready .tar.md5".into());
    }

    let _ = adb(&["-s", &device.id, "shell", "mkdir", "-p", ANDROID_DIR]);
    let mut child = Command::new("adb")
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
    Ok(())
}

fn main() {
    let config = Config {
        target_fingerprint: std::env::var("TARGET_BRIDGE_FINGERPRINT").unwrap_or_else(|_| "PUT_TARGET_RO_BUILD_FINGERPRINT_HERE".into()),
        service: std::env::var("ANDROID_BRIDGE_SERVICE").unwrap_or_else(|_| "com.example.bridge/.BridgeService".into()),
    };

    tauri::Builder::default()
        .manage(config)
        .invoke_handler(tauri::generate_handler![push_file])
        .setup(|app| {
            emit_loop(app.handle().clone());
            watch_source(app.handle().clone());
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("failed to run app");
}
