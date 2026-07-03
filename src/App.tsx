import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

type Device = {
  id: string;
  model: string;
  fingerprint: string;
  available_storage: number;
  is_target_bridge: boolean;
};

type LocalFile = {
  name: string;
  size: number;
  status: "downloading" | "ready" | "locked";
  locked: boolean;
};

type Transfer = { file: string; percent: number; message: string };

const gb = (kb: number) => `${(kb / 1024 / 1024).toFixed(1)} GB`;
const fileGb = (b: number) => `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;

export default function App() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [files, setFiles] = useState<LocalFile[]>([]);
  const [transfer, setTransfer] = useState<Transfer | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const unsubs = [
      listen<Device[]>("devices", (e) => setDevices(e.payload)),
      listen<LocalFile[]>("files", (e) => setFiles(e.payload)),
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

  const active = devices.some((d) => d.is_target_bridge);

  return (
    <main className="min-h-screen bg-zinc-950 p-6 text-zinc-100">
      <section className="mb-6">
        <div className="mb-3 flex items-center justify-between">
          <h1 className="text-2xl font-semibold">Cross-Network Android File Bridge</h1>
          <div className="flex items-center gap-3">
            <span className={active ? "text-green-400" : "text-red-400"}>{active ? "ADB bridge healthy" : "Waiting for target fingerprint"}</span>
            <button
              onClick={() => invoke("minimize_to_tray")}
              className="rounded border border-zinc-700 px-3 py-1 text-sm text-zinc-300 hover:bg-zinc-800"
            >
              Tray
            </button>
          </div>
        </div>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {devices.map((d) => (
            <article
              key={d.id}
              className={`rounded-lg border bg-zinc-900 p-4 ${d.is_target_bridge ? "border-green-500 ring-2 ring-green-500" : "border-zinc-800 opacity-50"}`}
            >
              <div className="mb-2 flex items-center justify-between">
                <h2 className="font-medium">{d.model}</h2>
                {d.is_target_bridge && <span className="rounded bg-green-500 px-2 py-1 text-xs font-bold text-zinc-950">Bridge Active</span>}
              </div>
              <p className="text-sm text-zinc-400">ID: {d.id}</p>
              <p className="text-sm text-zinc-400">Storage: {gb(d.available_storage)}</p>
              <p className="mt-2 break-all text-xs text-zinc-500">{d.fingerprint}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
          <h2 className="mb-3 text-lg font-semibold">E:\SUBRO</h2>
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
