import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

type Device = {
  id: string;
  model: string;
  fingerprint: string;
  available_storage: number;
  battery_level?: number | null;
  battery_temperature?: number | null;
  ip_address: string;
  is_target_bridge: boolean;
};

type LocalFile = {
  name: string;
  size: number;
  status: "downloading" | "ready" | "locked";
  locked: boolean;
};

type Transfer = { file: string; percent: number; message: string };
type AppInfo = { platform: string; source_dir: string; samba_dir: string; target_fingerprint_set: boolean };

const gb = (kb: number) => `${(kb / 1024 / 1024).toFixed(1)} GB`;
const fileGb = (b: number) => `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;

export default function App() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [files, setFiles] = useState<LocalFile[]>([]);
  const [sambaFiles, setSambaFiles] = useState<LocalFile[]>([]);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [transfer, setTransfer] = useState<Transfer | null>(null);
  const [error, setError] = useState("");

  // Wifi and APK Configuration
  const [wifiSsid, setWifiSsid] = useState(() => localStorage.getItem("wifi_ssid") || "RTT / IEEE 802.11");
  const [wifiPassword, setWifiPassword] = useState(() => localStorage.getItem("wifi_password") || "1234qwer");
  const [apkPath, setApkPath] = useState(() => localStorage.getItem("apk_path") || "");
  const [showSettings, setShowSettings] = useState(false);
  const [actionStatus, setActionStatus] = useState("");
  const [actionLoading, setActionLoading] = useState(false);
  const [diagnostics, setDiagnostics] = useState("");
  const [diagLoading, setDiagLoading] = useState(false);

  useEffect(() => {
    invoke<AppInfo>("app_info").then(setInfo).catch((e) => setError(String(e)));
    const unsubs = [
      listen<Device[]>("devices", (e) => setDevices(e.payload)),
      listen<LocalFile[]>("files", (e) => setFiles(e.payload)),
      listen<LocalFile[]>("samba-files", (e) => setSambaFiles(e.payload)),
      listen<Transfer>("transfer", (e) => setTransfer(e.payload)),
    ];
    return () => void Promise.all(unsubs).then((fns) => fns.forEach((fn) => fn()));
  }, []);

  async function handleDiagnose() {
    setDiagLoading(true);
    setDiagnostics("Running ADB diagnostics...");
    try {
      const log = await invoke<string>("debug_adb");
      setDiagnostics(log);
    } catch (err) {
      setDiagnostics(`Diagnostics failed: ${err}`);
    } finally {
      setDiagLoading(false);
    }
  }

  async function push(name: string) {
    setError("");
    try {
      await invoke("push_file", { fileName: name });
    } catch (e) {
      setError(String(e));
    }
  }

  async function selectBridge(fingerprint: string) {
    setError("");
    try {
      await invoke("select_bridge", { fingerprint });
    } catch (e) {
      setError(String(e));
    }
  }

  async function handleInstallApk() {
    setActionStatus("Menginstall APK...");
    setActionLoading(true);
    try {
      const msg = await invoke<string>("push_install_apk", { apkPath });
      setActionStatus(`Success: ${msg}`);
    } catch (err) {
      setActionStatus(`Error: ${err}`);
    } finally {
      setActionLoading(false);
    }
  }

  async function handleConnectWifi() {
    setActionStatus(`Menghubungkan ke Wi-Fi ${wifiSsid}...`);
    setActionLoading(true);
    try {
      const msg = await invoke<string>("connect_wifi", { ssid: wifiSsid, password: wifiPassword });
      setActionStatus(`Success: ${msg}`);
    } catch (err) {
      setActionStatus(`Error: ${err}`);
    } finally {
      setActionLoading(false);
    }
  }

  async function browseApk() {
    try {
      const path = await invoke<string | null>("pick_apk_file");
      if (path) {
        setApkPath(path);
      }
    } catch (err) {
      setError(String(err));
    }
  }

  const active = devices.some((d) => d.is_target_bridge);
  const isLinux = info?.platform === "linux";

  if (isLinux) {
    return (
      <main className="min-h-screen bg-zinc-950 p-6 text-zinc-100">
        <section className="mb-4 flex items-center justify-between">
          <h1 className="text-2xl font-semibold">Samba Files</h1>
          <p className="text-sm text-zinc-400">{info?.samba_dir}</p>
        </section>
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-zinc-800 text-left">
          <thead className="bg-zinc-900 text-sm text-zinc-400">
            <tr>
              <th className="p-3">File</th>
              <th className="p-3">Size</th>
              <th className="p-3">Status</th>
            </tr>
          </thead>
          <tbody>
            {sambaFiles.map((f) => (
              <tr key={f.name} className="border-t border-zinc-800">
                <td className="p-3">{f.name}</td>
                <td className="p-3">{fileGb(f.size)}</td>
                <td className="p-3">{f.status}</td>
              </tr>
            ))}
            {!sambaFiles.length && (
              <tr>
                <td className="p-3 text-zinc-500" colSpan={3}>No .tar.md5 files found</td>
              </tr>
            )}
          </tbody>
        </table>
      </main>
    );
  }

  return (
    <main className="min-h-screen bg-zinc-950 p-6 text-zinc-100">
      <section className="mb-6">
        <div className="mb-3 flex items-center justify-between">
          <h1 className="text-2xl font-semibold">Cross-Network Android File Bridge</h1>
          <div className="flex items-center gap-4">
            <span className={active ? "text-green-400" : "text-red-400"}>
              {active ? "ADB bridge healthy" : "Waiting for target fingerprint"}
            </span>
            <button
              onClick={() => setShowSettings(true)}
              className="rounded bg-zinc-900 p-2 text-zinc-400 hover:bg-zinc-800 border border-zinc-800 transition"
              title="Settings"
            >
              <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fillRule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clipRule="evenodd" />
              </svg>
            </button>
          </div>
        </div>
        {!info?.target_fingerprint_set && <p className="mb-3 rounded border border-yellow-900 bg-yellow-950 p-3 text-sm text-yellow-100">Pilih satu device di tabel sebagai bridge. Identitas tetap divalidasi memakai fingerprint.</p>}
        <div className="overflow-x-auto rounded-lg border border-zinc-800">
          <table className="w-full min-w-[980px] border-collapse text-left">
            <thead className="bg-zinc-900 text-sm text-zinc-400">
              <tr>
                <th className="p-3">Bridge</th>
                <th className="p-3">Model</th>
                <th className="p-3">Device ID</th>
                <th className="p-3">IP</th>
                <th className="p-3">Battery</th>
                <th className="p-3">Temp</th>
                <th className="p-3">Storage</th>
                <th className="p-3">Fingerprint</th>
              </tr>
            </thead>
            <tbody>
              {devices.map((d) => (
                <tr key={d.id} className={`border-t border-zinc-800 ${d.is_target_bridge ? "bg-green-950/40" : "bg-zinc-950"}`}>
                  <td className="p-3">
                    <input
                      type="checkbox"
                      checked={d.is_target_bridge}
                      onChange={() => selectBridge(d.fingerprint)}
                      className="h-5 w-5 accent-green-500"
                    />
                  </td>
                  <td className="p-3">{d.model}</td>
                  <td className="p-3 text-zinc-400">{d.id}</td>
                  <td className="p-3">{d.ip_address}</td>
                  <td className="p-3">{d.battery_level == null ? "-" : `${d.battery_level}%`}</td>
                  <td className="p-3">{d.battery_temperature == null ? "-" : `${d.battery_temperature.toFixed(1)} C`}</td>
                  <td className="p-3">{gb(d.available_storage)}</td>
                  <td className="max-w-[360px] break-all p-3 text-xs text-zinc-500">{d.fingerprint}</td>
                </tr>
              ))}
              {!devices.length && (
                <tr>
                  <td className="p-3 text-zinc-500" colSpan={8}>No ADB devices detected. Check USB debugging and run `adb devices` once to authorize.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
          <h2 className="mb-3 text-lg font-semibold">{info?.source_dir ?? "E:\\SUBRO"}</h2>
          <div className="space-y-2">
            {files.map((f) => (
              <div key={f.name} className="flex items-center justify-between rounded border border-zinc-800 bg-zinc-950 p-3">
                <div>
                  <p className="font-medium">{f.status === "downloading" ? "[...]" : f.status === "ready" ? "[ok]" : "[lock]"} {f.name}</p>
                  <p className="text-sm text-zinc-500">{fileGb(f.size)} · {f.status}</p>
                </div>
                <button
                  disabled={!active || f.status !== "ready"}
                  onClick={() => push(f.name)}
                  className="rounded bg-green-500 px-3 py-2 text-sm font-bold text-zinc-950 disabled:cursor-not-allowed disabled:bg-zinc-700 disabled:text-zinc-400"
                >
                  Push
                </button>
              </div>
            ))}
            {!files.length && <p className="rounded border border-zinc-800 bg-zinc-950 p-3 text-sm text-zinc-500">No .tar.md5 files found</p>}
          </div>
        </div>

        <div className="space-y-6">
          <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
            <h2 className="mb-3 text-lg font-semibold">Transfer Status</h2>
            <p className="mb-4 text-sm text-zinc-400">Connection: {active ? "target bridge available" : "no validated target bridge"}</p>
            <div className="h-5 overflow-hidden rounded bg-zinc-800">
              <div className="h-full bg-green-500 transition-all" style={{ width: `${transfer?.percent ?? 0}%` }} />
            </div>
            <p className="mt-3 break-all text-sm text-zinc-300">{transfer ? `${transfer.file}: ${transfer.message}` : "No active transfer"}</p>
            {error && <p className="mt-3 rounded border border-red-900 bg-red-950 p-3 text-sm text-red-200">{error}</p>}
          </div>

          <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
            <h2 className="mb-3 text-lg font-semibold">Device Actions</h2>
            <p className="mb-4 text-sm text-zinc-400">Jalankan aksi pada perangkat Android jembatan aktif.</p>
            <div className="flex flex-wrap gap-3">
              <button
                disabled={!active || actionLoading || !apkPath}
                onClick={handleInstallApk}
                className="rounded bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-500 disabled:cursor-not-allowed disabled:bg-zinc-700 disabled:text-zinc-400 transition"
              >
                Push Install APK
              </button>
              <button
                disabled={!active || actionLoading}
                onClick={handleConnectWifi}
                className="rounded bg-teal-600 px-4 py-2 text-sm font-bold text-white hover:bg-teal-500 disabled:cursor-not-allowed disabled:bg-zinc-700 disabled:text-zinc-400 transition"
              >
                Connect Wi-Fi
              </button>
            </div>
            {actionStatus && (
              <p className="mt-3 rounded border border-zinc-700 bg-zinc-950 p-3 text-sm text-zinc-300 break-all">
                {actionStatus}
              </p>
            )}
          </div>
        </div>
      </section>

      {/* Settings Modal */}
      {showSettings && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="w-full max-w-md rounded-xl border border-zinc-800 bg-zinc-900 p-6 shadow-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-lg font-bold text-zinc-100">Settings & Sideload Config</h3>
              <button
                onClick={() => {
                  setWifiSsid(localStorage.getItem("wifi_ssid") || "RTT / IEEE 802.11");
                  setWifiPassword(localStorage.getItem("wifi_password") || "1234qwer");
                  setApkPath(localStorage.getItem("apk_path") || "");
                  setShowSettings(false);
                }}
                className="text-zinc-400 hover:text-zinc-200"
              >
                <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-zinc-400 uppercase mb-1">APK File Path</label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={apkPath}
                    onChange={(e) => setApkPath(e.target.value)}
                    placeholder="E.g. C:\Downloads\app-debug.apk"
                    className="flex-1 rounded border border-zinc-800 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-blue-500"
                  />
                  <button
                    onClick={browseApk}
                    className="rounded bg-zinc-800 px-3 py-2 text-sm font-semibold hover:bg-zinc-700 transition"
                  >
                    Browse
                  </button>
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-zinc-400 uppercase mb-1">Wi-Fi SSID</label>
                <input
                  type="text"
                  value={wifiSsid}
                  onChange={(e) => setWifiSsid(e.target.value)}
                  placeholder="SSID name"
                  className="w-full rounded border border-zinc-800 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-zinc-400 uppercase mb-1">Wi-Fi Password</label>
                <input
                  type="password"
                  value={wifiPassword}
                  onChange={(e) => setWifiPassword(e.target.value)}
                  placeholder="Password"
                  className="w-full rounded border border-zinc-800 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-blue-500"
                />
              </div>
            </div>

            <div className="mt-6 flex flex-wrap justify-between gap-3 border-t border-zinc-800 pt-4">
              <button
                disabled={diagLoading}
                onClick={handleDiagnose}
                className="rounded bg-zinc-850 border border-zinc-700 px-3 py-1.5 text-xs font-semibold hover:bg-zinc-850 text-blue-400 transition"
              >
                {diagLoading ? "Diagnosing..." : "🔧 Diagnose ADB"}
              </button>
              <div className="flex gap-2">
                <button
                  onClick={() => {
                    setWifiSsid(localStorage.getItem("wifi_ssid") || "RTT / IEEE 802.11");
                    setWifiPassword(localStorage.getItem("wifi_password") || "1234qwer");
                    setApkPath(localStorage.getItem("apk_path") || "");
                    setDiagnostics("");
                    setShowSettings(false);
                  }}
                  className="rounded bg-zinc-800 px-4 py-2 text-sm font-semibold hover:bg-zinc-700 text-zinc-300 transition"
                >
                  Cancel
                </button>
                <button
                  onClick={() => {
                    localStorage.setItem("wifi_ssid", wifiSsid);
                    localStorage.setItem("wifi_password", wifiPassword);
                    localStorage.setItem("apk_path", apkPath);
                    setDiagnostics("");
                    setShowSettings(false);
                    setActionStatus("Konfigurasi disimpan.");
                  }}
                  className="rounded bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-500 transition"
                >
                  Save Changes
                </button>
              </div>
            </div>

            {diagnostics && (
              <div className="mt-4 border-t border-zinc-800 pt-4">
                <h4 className="text-xs font-bold text-zinc-400 uppercase mb-2 text-left">Diagnostic Logs:</h4>
                <pre className="w-full text-left bg-zinc-950 border border-zinc-800 text-[10px] text-zinc-300 p-3 rounded max-h-48 overflow-y-auto whitespace-pre-wrap font-mono">
                  {diagnostics}
                </pre>
              </div>
            )}
          </div>
        </div>
      )}
    </main>
  );
}
