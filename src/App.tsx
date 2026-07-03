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
          <span className={active ? "text-green-400" : "text-red-400"}>{active ? "ADB bridge healthy" : "Waiting for target fingerprint"}</span>
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

        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
          <h2 className="mb-3 text-lg font-semibold">Transfer Status</h2>
          <p className="mb-4 text-sm text-zinc-400">Connection: {active ? "target bridge available" : "no validated target bridge"}</p>
          <div className="h-5 overflow-hidden rounded bg-zinc-800">
            <div className="h-full bg-green-500 transition-all" style={{ width: `${transfer?.percent ?? 0}%` }} />
          </div>
          <p className="mt-3 break-all text-sm text-zinc-300">{transfer ? `${transfer.file}: ${transfer.message}` : "No active transfer"}</p>
          {error && <p className="mt-3 rounded border border-red-900 bg-red-950 p-3 text-sm text-red-200">{error}</p>}
        </div>
      </section>
    </main>
  );
}
