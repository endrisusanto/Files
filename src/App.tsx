import { useEffect, useState, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import logo from "./logo.svg";

type Device = {
  id: string;
  model: string;
  fingerprint: string;
  available_storage: number;
  ip_address: string;
  apk_installed: boolean;
  is_selected_bridge: boolean;
};

type LocalFile = {
  name: string;
  size: number;
  status: "downloading" | "ready" | "locked";
  locked: boolean;
};

type Transfer = { file: string; percent: number; message: string };
type NetworkSample = { rx_bps: number; tx_bps: number };
type AppInfo = { platform: string; source_dir: string; samba_dir: string; target_fingerprint_set: boolean; hostname: string };

const fileGb = (b: number) => `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
const speed = (b: number) => `${(b / 1024 / 1024).toFixed(2)} MB/s`;
const statusClass = (status: string | undefined | null) => {
  if (!status || typeof status !== "string") {
    return "border-zinc-800 bg-zinc-900 text-zinc-400";
  }
  if (status.includes("inprogress staging push")) {
    return "border-blue-800 bg-blue-950 text-blue-300 animate-pulse";
  }
  if (status.includes("inprogress transfer to samba")) {
    return "border-cyan-800 bg-cyan-950 text-cyan-300 animate-pulse";
  }
  switch (status) {
    case "ready":
      return "border-zinc-800 bg-zinc-900 text-zinc-400";
    case "pushed to android successful":
      return "border-amber-800 bg-amber-950 text-amber-300";
    case "transfer samba complete":
      return "border-green-800 bg-green-950 text-green-300";
    case "locked":
      return "border-red-800 bg-red-950 text-red-300";
    default:
      return "border-zinc-800 bg-zinc-900 text-zinc-400";
  }
};

function NetworkChart({ samples }: { samples: NetworkSample[] }) {
  const width = 600;
  const height = 80;
  const points = samples.slice(-60);
  const max = Math.max(1, ...points.flatMap((p) => [p.rx_bps, p.tx_bps]));
  
  const path = (key: keyof NetworkSample) => {
    if (points.length === 0) return "";
    let d = "";
    points.forEach((p, i) => {
      const x = points.length <= 1 ? 0 : (i / (points.length - 1)) * width;
      const y = height - (p[key] / max) * height;
      if (i === 0) {
        d += `M ${x.toFixed(1)} ${y.toFixed(1)}`;
      } else {
        const prevX = ((i - 1) / (points.length - 1)) * width;
        const prevY = height - (points[i - 1][key] / max) * height;
        const cpX1 = prevX + (x - prevX) / 2;
        const cpY1 = prevY;
        const cpX2 = prevX + (x - prevX) / 2;
        const cpY2 = y;
        d += ` C ${cpX1.toFixed(1)} ${cpY1.toFixed(1)}, ${cpX2.toFixed(1)} ${cpY2.toFixed(1)}, ${x.toFixed(1)} ${y.toFixed(1)}`;
      }
    });
    return d;
  };
  const last = points[points.length - 1] ?? { rx_bps: 0, tx_bps: 0 };

  return (
    <section className="mb-3 rounded border border-zinc-800 bg-zinc-900 p-2">
      <div className="mb-1 flex items-center justify-between">
        <h2 className="text-xs font-semibold text-zinc-400">Network (Realtime Curve)</h2>
        <div className="flex gap-3 text-[10px]">
          <span className="text-green-300 font-mono">Down: {speed(last.rx_bps)}</span>
          <span className="text-blue-300 font-mono">Up: {speed(last.tx_bps)}</span>
        </div>
      </div>
      <svg className="h-20 w-full" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
        <rect width={width} height={height} fill="#09090b" rx="4" />
        <path d={path("rx_bps")} fill="none" stroke="#86efac" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        <path d={path("tx_bps")} fill="none" stroke="#93c5fd" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
      </svg>
    </section>
  );
}

export default function App() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [files, setFiles] = useState<LocalFile[]>([]);
  const [sambaFiles, setSambaFiles] = useState<LocalFile[]>([]);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [transfer, setTransfer] = useState<Transfer | null>(null);
  const [network, setNetwork] = useState<NetworkSample[]>([]);
  const [error, setError] = useState("");
  const [debugLog, setDebugLog] = useState("");
  const [ws, setWs] = useState<WebSocket | null>(null);
  const [remoteDevices, setRemoteDevices] = useState<any[]>([]);
  const [autoPush, setAutoPush] = useState(() => {
    const val = localStorage.getItem("auto_push");
    if (val === null) {
      localStorage.setItem("auto_push", "true");
      return true;
    }
    return val === "true";
  });
  const [pushedFiles, setPushedFiles] = useState<Set<string>>(new Set());
  const [phoneFiles, setPhoneFiles] = useState<Set<string>>(new Set());
  const filesRef = useRef<LocalFile[]>([]);
  useEffect(() => {
    filesRef.current = files;
  }, [files]);
  const pushedFilesRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    pushedFilesRef.current = pushedFiles;
  }, [pushedFiles]);
  const isPushingRef = useRef(false);
  const sambaFilesRef = useRef<LocalFile[]>([]);
  useEffect(() => {
    sambaFilesRef.current = sambaFiles;
  }, [sambaFiles]);

  // Source Configuration
  const [sourcePath, setSourcePath] = useState(() => localStorage.getItem("source_path") || "");
  const [forceTransfer, setForceTransfer] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [diagnostics, setDiagnostics] = useState("");
  const [diagLoading, setDiagLoading] = useState(false);
  const [filelistOpen, setFilelistOpen] = useState(true);
  const [debugOpen, setDebugOpen] = useState(false);

  function appendLog(line: string) {
    const text = `${new Date().toLocaleTimeString()} ${line}`;
    setDebugLog((value) => `${text}\n${value}`.slice(0, 12000));
  }

  useEffect(() => {
    console.info("[bridge-ui] startup");
    appendLog("startup");
    invoke<AppInfo>("app_info")
      .then((value) => {
        console.info("[bridge-ui] app_info", value);
        const savedSource = localStorage.getItem("source_path");
        setInfo(value);
        setSourcePath(savedSource || value.source_dir);
        appendLog(`app_info source=${value.source_dir} samba=${value.samba_dir}`);
        if (savedSource && savedSource !== value.source_dir) {
          invoke<LocalFile[]>("set_source_dir", { path: savedSource })
            .then((list) => {
              setFiles(list);
              setInfo((current) => current && { ...current, source_dir: savedSource });
              appendLog(`restore source ok files=${list.length}`);
            })
            .catch((err) => appendLog(`restore source failed ${String(err)}`));
        }
      })
      .catch((e) => {
        console.error("[bridge-ui] app_info failed", e);
        appendLog(`app_info failed ${String(e)}`);
        setError(String(e));
      });

    const interval = setInterval(() => {
      invoke<AppInfo>("app_info")
        .then((value) => {
          setInfo((current) => {
            if (!current) return value;
            return {
              ...value,
              source_dir: current.source_dir,
            };
          });
        })
        .catch((e) => console.error("periodic app_info fetch failed", e));
    }, 10000);

    const phoneFilesInterval = setInterval(() => {
      invoke<string[]>("get_phone_files")
        .then((list) => {
          setPhoneFiles(new Set(list));
        })
        .catch(() => {});
    }, 5000);

    const devicesInterval = setInterval(() => {
      refreshDevices();
    }, 4000);


    const unsubs = [
      listen<Device[]>("devices", (e) => {
        console.info("[bridge-ui] devices event", e.payload.length);
        appendLog(`devices event count=${e.payload.length}`);
        setDevices(e.payload);
      }).catch(err => { appendLog(`listen devices err: ${err}`); return () => {}; }),
      listen<LocalFile[]>("files", (e) => {
        console.info("[bridge-ui] files event", e.payload.length);
        appendLog(`files event count=${e.payload.length}`);
        filesRef.current = e.payload;
        setFiles(e.payload);

        // Clean up pushedFiles that are no longer in source
        const currentNames = new Set(e.payload.map((f) => f.name));
        setPushedFiles((prev) => {
          let changed = false;
          const next = new Set(prev);
          for (const name of next) {
            if (!currentNames.has(name)) {
              next.delete(name);
              changed = true;
            }
          }
          return changed ? next : prev;
        });

        if (localStorage.getItem("auto_push") === "true") {
          pushAllPending();
        }
      }).catch(err => { appendLog(`listen files err: ${err}`); return () => {}; }),
      listen<LocalFile[]>("samba-files", (e) => {
        console.info("[bridge-ui] samba-files event", e.payload.length);
        appendLog(`samba-files event count=${e.payload.length}`);
        sambaFilesRef.current = e.payload;
        setSambaFiles(e.payload);
      }).catch(err => { appendLog(`listen samba-files err: ${err}`); return () => {}; }),
      listen<Transfer>("transfer", (e) => {
        console.info("[bridge-ui] transfer event", e.payload);
        appendLog(`transfer ${e.payload.file}: ${e.payload.message}`);
        setTransfer(e.payload);
      }).catch(err => { appendLog(`listen transfer err: ${err}`); return () => {}; }),
      listen<NetworkSample>("network", (e) => {
        setNetwork((list) => [...list.slice(-59), e.payload]);
      }).catch(err => { appendLog(`listen network err: ${err}`); return () => {}; }),
    ];
    refreshDevices();
    return () => {
      clearInterval(interval);
      clearInterval(phoneFilesInterval);
      clearInterval(devicesInterval);
      void Promise.all(unsubs).then((fns) => fns.forEach((fn) => fn()));
    };
  }, []);

  useEffect(() => {
    const alreadySelected = devices.some((d) => d.is_selected_bridge);
    if (alreadySelected) return;

    for (const d of devices) {
      const isRemoteConnected = remoteDevices.some((rd) => rd.id === d.fingerprint);
      if (isRemoteConnected) {
        console.info("[bridge-ui] Auto-pairing matching WebSocket & USB device:", d.model, d.fingerprint);
        appendLog(`Auto-pairing matching device: ${d.model} (${d.fingerprint})`);
        selectBridge(d.fingerprint);
        break;
      }
    }
  }, [devices, remoteDevices]);

  useEffect(() => {
    const wsUrl = "wss://files.endrisusanto.my.id/";
    console.info(`[bridge-ui] Connecting to public WebSocket: ${wsUrl}`);
    appendLog(`Connecting to public WebSocket: ${wsUrl}`);
    
    let socket: WebSocket;
    let reconnectTimeout: number;
    
    function connect() {
      socket = new WebSocket(wsUrl);
      
      socket.onopen = () => {
        console.info(`[bridge-ui] Connected to public WebSocket: ${wsUrl}`);
        appendLog(`Connected to public WebSocket: ${wsUrl}`);
        setWs(socket);
      };
      
      socket.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === "state") {
            setRemoteDevices(msg.devices || []);
            const selectedRemoteId = localStorage.getItem("selected_remote_id");
            const selectedRemote = (msg.devices || []).find((d: any) => d.id === selectedRemoteId);
            if (selectedRemote && selectedRemote.samples && selectedRemote.samples.length > 0) {
              const lastSample = selectedRemote.samples[selectedRemote.samples.length - 1];
              setNetwork((list) => [...list.slice(-59), { rx_bps: lastSample.rx_bps, tx_bps: lastSample.tx_bps }]);
            }
          }
        } catch (err) {
          console.error("Error parsing ws message", err);
        }
      };
      
      socket.onclose = () => {
        console.warn(`[bridge-ui] Public WebSocket disconnected, retrying in 3s...`);
        appendLog(`Public WebSocket disconnected, retrying in 3s...`);
        setWs(null);
        reconnectTimeout = window.setTimeout(connect, 3000);
      };
      
      socket.onerror = (err) => {
        console.error("WS error", err);
      };
    }
    
    connect();
    
    return () => {
      if (socket) {
        socket.close();
      }
      clearTimeout(reconnectTimeout);
    };
  }, []);

  useEffect(() => {
    if (!ws || !info) return;
    
    function sendStatus() {
      if (ws.readyState === WebSocket.OPEN) {
        const selectedDevice = devices.find((d) => d.is_selected_bridge);
        const activeRemote = remoteDevices.find((rd) => rd.id === selectedDevice?.fingerprint);

        const mappedFiles = (files || []).map((f) => {
          const inSamba = sambaFilesRef.current ? sambaFilesRef.current.some((sf) => sf.name === f.name) : false;
          const isPushed = pushedFilesRef.current ? pushedFilesRef.current.has(f.name) : false;
          const isPushingThis = transfer?.file === f.name && transfer?.percent < 100;
          const isUploadingThis = activeRemote?.current_file === f.name;
          const isUploaded = inSamba || (isPushed && phoneFiles ? !phoneFiles.has(f.name) : false);

          let displayStatus = f.status;
          if (isPushingThis) {
            displayStatus = `inprogress staging push to android (${transfer.percent}%)`;
          } else if (isUploadingThis) {
            displayStatus = `inprogress transfer to samba (${activeRemote.upload_percent}%)`;
          } else if (isUploaded) {
            displayStatus = "transfer samba complete";
          } else if (isPushed) {
            displayStatus = "pushed to android successful";
          }

          return {
            ...f,
            status: displayStatus,
          };
        });

        const payload = {
          type: "tauri_status",
          id: info.hostname || "tauri",
          host: info.hostname || "tauri",
          platform: info.platform,
          source_dir: info.source_dir,
          samba_dir: info.samba_dir,
          devices: devices,
          files: mappedFiles
        };
        ws.send(JSON.stringify(payload));
      }
    }
    
    sendStatus();
    const interval = setInterval(sendStatus, 5000);
    
    return () => clearInterval(interval);
  }, [ws, info, devices, files, remoteDevices, transfer, phoneFiles]);

  async function refreshDevices() {
    setError("");
    console.info("[bridge-ui] refresh devices");
    appendLog("refresh devices");
    try {
      const list = await invoke<Device[]>("get_devices");
      console.info("[bridge-ui] refresh devices ok", list.length);
      appendLog(`refresh devices ok count=${list.length}`);
      setDevices(list);
    } catch (e) {
      console.error("[bridge-ui] refresh devices failed", e);
      appendLog(`refresh devices failed ${String(e)}`);
      setError(String(e));
    }
  }

  async function handleDiagnose() {
    setDiagLoading(true);
    setDiagnostics("Running ADB diagnostics...");
    console.info("[bridge-ui] diagnose adb");
    appendLog("diagnose adb");
    try {
      const log = await invoke<string>("debug_adb");
      console.info("[bridge-ui] diagnose adb ok");
      appendLog("diagnose adb ok");
      setDiagnostics(log);
    } catch (err) {
      console.error("[bridge-ui] diagnose adb failed", err);
      appendLog(`diagnose adb failed ${String(err)}`);
      setDiagnostics(`Diagnostics failed: ${err}`);
    } finally {
      setDiagLoading(false);
    }
  }

  function pendingFiles() {
    return filesRef.current.filter((f) => {
      if (f.status !== "ready") return false;
      if (sambaFilesRef.current.some((sf) => sf.name === f.name)) return false;
      return !pushedFilesRef.current.has(f.name);
    });
  }

  async function push(name?: string) {
    if (isPushingRef.current) {
      console.warn("[bridge-ui] Transfer already in progress, skipping push");
      return;
    }
    isPushingRef.current = true;
    setError("");
    let ok = true;

    try {
      const names = name ? [name] : pendingFiles().map((f) => f.name);
      for (const current of names) {
        console.info("[bridge-ui] push file", current);
        const queueTotal = filesRef.current.length;
        const queueSuccess = filesRef.current.filter((f) => {
          const inSamba = sambaFilesRef.current.some((sf) => sf.name === f.name);
          const isPushed = pushedFilesRef.current.has(f.name);
          return inSamba || (isPushed && f.name !== current);
        }).length;

        await invoke("push_file", {
          file_name: current,
          force: forceTransfer,
          queue_total: queueTotal,
          queue_success: queueSuccess
        });
        console.info("[bridge-ui] push file ok", current);
        appendLog(`push ok ${current}`);
        pushedFilesRef.current = new Set(pushedFilesRef.current).add(current);
        setPushedFiles(new Set(pushedFilesRef.current));
      }
    } catch (e) {
      ok = false;
      console.error("[bridge-ui] push file failed", e);
      appendLog(`push failed: ${String(e)}`);
      setError(String(e));
    } finally {
      isPushingRef.current = false;
      if (ok && !name && localStorage.getItem("auto_push") === "true" && pendingFiles().length) {
        pushAllPending();
      }
    }
  }

  function pushAllPending() {
    if (pendingFiles().length) {
      appendLog("Auto-push queue started");
      push();
    }
  }

  async function selectBridge(fingerprint: string) {
    setError("");
    setDevices((list) => list.map((d) => ({ ...d, is_selected_bridge: d.fingerprint === fingerprint })));
    console.info("[bridge-ui] select bridge", fingerprint);
    appendLog(`select bridge ${fingerprint}`);
    try {
      await invoke("select_bridge", { fingerprint });
      console.info("[bridge-ui] select bridge ok");
      appendLog("select bridge ok");
    } catch (e) {
      console.error("[bridge-ui] select bridge failed", e);
      appendLog(`select bridge failed ${String(e)}`);
      setError(String(e));
    }
  }




  async function browseSource() {
    appendLog("browse source folder");
    try {
      const path = await invoke<string | null>("pick_source_dir");
      if (path) {
        setSourcePath(path);
        await applySource(path);
        appendLog(`source picked ${path}`);
      }
    } catch (err) {
      appendLog(`browse source failed ${String(err)}`);
      setError(String(err));
    }
  }

  async function applySource(path = sourcePath) {
    appendLog(`set source ${path}`);
    try {
      const list = await invoke<LocalFile[]>("set_source_dir", { path });
      setFiles(list);
      setInfo((value) => value && { ...value, source_dir: path });
      localStorage.setItem("source_path", path);
      appendLog(`set source ok files=${list.length}`);
    } catch (err) {
      appendLog(`set source failed ${String(err)}`);
      setError(String(err));
    }
  }

  const active = devices.some((d) => d.is_selected_bridge);
  const selected = devices.some((d) => d.is_selected_bridge);
  const selectedDevice = devices.find((d) => d.is_selected_bridge);
  const activeRemote = remoteDevices.find((rd) => rd.id === selectedDevice?.fingerprint);
  const deviceActionReady = Boolean(selectedDevice);
  const isLinux = info?.platform === "linux";

  if (isLinux) {
    return (
      <main className="min-h-screen bg-zinc-950 p-6 text-zinc-100">
        <section className="mb-4 flex items-center justify-between">
          <h1 className="text-2xl font-semibold">Samba Files</h1>
          <p className="text-sm text-zinc-400">{info?.samba_dir}</p>
        </section>
        <NetworkChart samples={network} />
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
          <div className="flex items-center gap-3">
            <img src={logo} alt="FireFiles Logo" className="h-9 w-9" />
            <h1 className="text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-orange-500 to-red-500">FireFiles</h1>
          </div>
          <div className="flex items-center gap-4">
            <span className={`rounded border px-2 py-1 text-xs font-semibold ${active ? "border-green-800 bg-green-950 text-green-300" : "border-zinc-800 bg-zinc-900 text-zinc-400"}`}>
              Tauri: {active ? "ready" : selected ? "storage low" : "select device"}
            </span>
            <span className={`rounded border px-2 py-1 text-xs font-semibold ${selectedDevice?.apk_installed ? "border-green-800 bg-green-950 text-green-300" : "border-zinc-800 bg-zinc-900 text-zinc-400"}`}>
              APK: {selectedDevice?.apk_installed ? "installed" : "missing"}
            </span>
            <span className={active ? "text-green-400" : "text-zinc-400"}>
              {active ? "ADB bridge healthy" : selected ? "Bridge selected, storage low" : "No bridge selected"}
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
      </section>

      {/* Remote WebSocket Devices */}
      <section className="mb-6">
        <div className="mb-3 flex items-center justify-between">
          <h1 className="text-2xl font-semibold">Remote WebSocket Devices</h1>
          <span className={`rounded border px-2 py-1 text-xs font-semibold ${ws ? "border-green-800 bg-green-950 text-green-300" : "border-zinc-800 bg-zinc-900 text-zinc-400"}`}>
            Server: {ws ? "Connected" : "Disconnected"}
          </span>
        </div>
        <div className="overflow-x-auto rounded-lg border border-zinc-800">
          <table className="w-full min-w-[980px] border-collapse text-left">
            <thead className="bg-zinc-900 text-sm text-zinc-400">
              <tr>
                <th className="p-3">Model</th>
                <th className="p-3">Device ID</th>
                <th className="p-3">Samba Target</th>
                <th className="p-3">Samba Status</th>
                <th className="p-3">USB/Tauri</th>
                <th className="p-3">Target</th>
                <th className="p-3">Latest File</th>
                <th className="p-3">WebSocket Status</th>
                <th className="p-3">Actions</th>
              </tr>
            </thead>
            <tbody>
              {remoteDevices.map((d) => {
                const isOnline = d.connected !== false && Date.now() - d.last_seen < 10000;
                // Check if this remote WebSocket device is also connected via USB/ADB locally
                const usbOnline = devices.some((localDev) => localDev.fingerprint === d.id);
                const isSelected = localStorage.getItem("selected_remote_id") === d.id;
                return (
                  <tr key={d.id} className={`border-t border-zinc-800 ${isSelected ? "bg-green-950/10" : "bg-zinc-950 hover:bg-zinc-900/50"}`}>
                    <td className="p-3 font-medium">{d.model || "-"}</td>
                    <td className="p-3 text-zinc-400 text-xs max-w-[200px] truncate" title={d.id}>{d.id}</td>
                    <td className="p-3 text-zinc-300 text-xs">{d.target || "-"}</td>
                    <td className="p-3">
                      <span className={`rounded border px-2 py-0.5 text-xs ${d.samba === "connected" ? "border-green-800 bg-green-950 text-green-300" : "border-red-800 bg-red-950 text-red-300"}`}>
                        Samba {d.samba || "not connected"}
                      </span>
                    </td>
                    <td className="p-3">
                      <span className={`rounded border px-2 py-0.5 text-xs ${usbOnline ? "border-green-800 bg-green-950 text-green-300" : "border-zinc-800 bg-zinc-900 text-zinc-500"}`}>
                        {usbOnline ? "Connected" : "Disconnected"}
                      </span>
                    </td>
                    <td className="p-3">
                      <span className={`rounded border px-2 py-0.5 text-xs ${isSelected ? "border-blue-800 bg-blue-950 text-blue-300" : "border-zinc-800 bg-zinc-900 text-zinc-500"}`}>
                        {isSelected ? "Selected" : "Idle"}
                      </span>
                    </td>
                    <td className="p-3 text-xs text-zinc-400 max-w-[200px] truncate" title={d.latest}>{d.latest || "-"}</td>
                    <td className="p-3">
                      <span className={`rounded border px-2 py-0.5 text-xs ${isOnline ? "border-green-800 bg-green-950 text-green-300" : "border-zinc-800 bg-zinc-900 text-zinc-500"}`}>
                        {isOnline ? "Online" : "Offline"}
                      </span>
                    </td>
                    <td className="p-3 flex gap-2">
                      <button
                        onClick={() => {
                          localStorage.setItem("selected_remote_id", d.id);
                          // Auto-select USB/ADB bridge locally when user manually clicks Monitor
                          selectBridge(d.id);
                          appendLog(`Selected remote device for monitoring: ${d.model} (${d.id})`);
                        }}
                        className="w-28 rounded bg-blue-600 hover:bg-blue-500 px-2 py-1 text-xs font-bold text-white transition text-center"
                      >
                        Monitor
                      </button>
                      <button
                        disabled={!isOnline || !ws}
                        onClick={() => {
                          if (ws) {
                            ws.send(JSON.stringify({
                              type: "command",
                              target: d.id,
                              command: "upload_all"
                            }));
                            appendLog(`Sent remote upload all command to device ${d.id}`);
                          }
                        }}
                        className="w-28 rounded bg-blue-600 hover:bg-blue-500 px-2 py-1 text-xs font-bold text-white transition disabled:opacity-50 text-center"
                      >
                        Remote Upload All
                      </button>
                      <button
                        disabled={!isOnline || !ws}
                        onClick={() => {
                          const host = window.prompt("Enter Samba Host IP:", d.target?.split("//")[1]?.split("/")[0] || "192.168.10.221");
                          const share = window.prompt("Enter Samba Share Name:", d.target?.split("//")[1]?.split("/")[1] || "sambashare");
                          if (host && share && ws) {
                            ws.send(JSON.stringify({
                              type: "command",
                              target: d.id,
                              command: "settings",
                              host: host,
                              share: share
                            }));
                            appendLog(`Sent remote settings command to device ${d.id}`);
                          }
                        }}
                        className="w-28 rounded bg-blue-600 hover:bg-blue-500 px-2 py-1 text-xs font-bold text-white transition disabled:opacity-50 text-center"
                      >
                        Remote Settings
                      </button>
                    </td>
                  </tr>
                );
              })}
              {!remoteDevices.length && (
                <tr>
                  <td className="p-3 text-zinc-500" colSpan={9}>No remote WebSocket devices registered on server.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <NetworkChart samples={network} />

      <section className="space-y-3">
        {/* Accordion File List */}
        <div className="rounded border border-zinc-800 bg-zinc-900 overflow-hidden">
          <button 
            onClick={() => setFilelistOpen(!filelistOpen)}
            className="w-full flex items-center justify-between p-3 bg-zinc-900 hover:bg-zinc-800 text-left font-semibold text-sm transition"
          >
            <span>📁 Staging Directory: {info?.source_dir ?? "E:\\SUBRO"}</span>
            <span className="text-zinc-500 font-mono text-xs">{filelistOpen ? "▲ Collapse" : "▼ Expand"}</span>
          </button>

          {filelistOpen && (
            <div className="p-3 border-t border-zinc-800 bg-zinc-950/40 space-y-2">
              <div className="flex justify-between items-center bg-zinc-900/60 p-2 rounded mb-2 border border-zinc-800">
                <div className="flex gap-4">
                  <label className="flex items-center gap-2 text-xs text-zinc-300">
                    <input
                      type="checkbox"
                      checked={autoPush}
                      onChange={(e) => {
                        setAutoPush(e.target.checked);
                        localStorage.setItem("auto_push", e.target.checked ? "true" : "false");
                        appendLog(`Auto push ${e.target.checked ? "enabled" : "disabled"}`);
                        if (e.target.checked) pushAllPending();
                      }}
                      className="h-3 w-3 accent-blue-500"
                    />
                    Auto Push
                  </label>
                  <label className="flex items-center gap-2 text-xs text-zinc-300">
                    <input
                      type="checkbox"
                      checked={forceTransfer}
                      onChange={(e) => setForceTransfer(e.target.checked)}
                      className="h-3 w-3 accent-blue-500"
                    />
                    Force transfer
                  </label>
                </div>
              </div>
              
              <div className="space-y-1.5 max-h-[300px] overflow-y-auto">
                {(files || []).map((f) => {
                  const inSamba = (sambaFiles || []).some((sf) => sf.name === f.name);
                  const isPushed = pushedFiles ? pushedFiles.has(f.name) : false;
                  
                  const isPushingThis = transfer?.file === f.name && transfer?.percent < 100;
                  const isUploadingThis = activeRemote?.current_file === f.name;
                  const isUploaded = inSamba || (isPushed && phoneFiles ? !phoneFiles.has(f.name) : false);

                  let displayStatus = f.status;
                  if (isPushingThis) {
                    displayStatus = `inprogress staging push (${transfer.percent}%)`;
                  } else if (isUploadingThis) {
                    displayStatus = `inprogress transfer to samba (${activeRemote.upload_percent}%)`;
                  } else if (isUploaded) {
                    displayStatus = "transfer samba complete";
                  } else if (isPushed) {
                    displayStatus = "pushed to android successful";
                  }

                  const progress = transfer?.file === f.name ? Math.max(0, Math.min(100, transfer.percent)) : 0;
                  return (
                    <div key={f.name} className="rounded border border-zinc-800 bg-zinc-950 p-2 text-xs">
                      <div className="flex items-center justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <p className="break-all font-medium text-zinc-200">{f.name}</p>
                            <span className={`rounded border px-1.5 py-0.2 text-[9px] font-semibold ${statusClass(displayStatus)}`}>
                              {displayStatus}
                            </span>
                          </div>
                          <p className="text-[10px] text-zinc-500">{fileGb(f.size)}</p>
                          {isPushingThis && (
                            <div className="mt-1 w-full bg-zinc-800 rounded-full h-1 overflow-hidden">
                              <div 
                                className="bg-blue-500 h-1 rounded-full transition-all duration-300" 
                                style={{ width: `${progress}%` }}
                              />
                            </div>
                          )}
                        </div>
                        <button
                          disabled={!selectedDevice || (displayStatus !== "ready" && !forceTransfer)}
                          onClick={() => push(f.name)}
                          className="w-24 rounded bg-blue-600 hover:bg-blue-500 px-2 py-1 text-[10px] font-bold text-white transition disabled:cursor-not-allowed disabled:opacity-40 text-center"
                        >
                          {forceTransfer ? "Force Push" : "Push"}
                        </button>
                      </div>
                    </div>
                  );
                })}
                {!files.length && <p className="rounded border border-zinc-800 bg-zinc-950 p-2 text-xs text-zinc-500 text-center">No .md5 files found</p>}
              </div>

              <div className="mt-2 border-t border-zinc-800 pt-2 text-[10px] text-zinc-400 space-y-1">
                <p>Connection: {active ? "target bridge available" : "no validated target bridge"}</p>
                <p className="break-all">Active Transfer: {transfer ? `${transfer.file} (${transfer.message})` : "None"}</p>
                {error && <p className="text-red-400 font-semibold">{error}</p>}
              </div>
            </div>
          )}
        </div>

        {/* Accordion Debug Log */}
        <div className="rounded border border-zinc-800 bg-zinc-900 overflow-hidden">
          <button 
            onClick={() => setDebugOpen(!debugOpen)}
            className="w-full flex items-center justify-between p-3 bg-zinc-900 hover:bg-zinc-800 text-left font-semibold text-sm transition"
          >
            <span>📜 System Diagnostics & Log</span>
            <span className="text-zinc-500 font-mono text-xs">{debugOpen ? "▲ Collapse" : "▼ Expand"}</span>
          </button>
          
          {debugOpen && (
            <div className="p-3 border-t border-zinc-800 bg-zinc-950/40">
              <textarea
                readOnly
                value={debugLog}
                className="h-40 w-full resize-none rounded border border-zinc-800 bg-zinc-950 p-2 font-mono text-[10px] text-zinc-400 outline-none"
              />
            </div>
          )}
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
                  setSourcePath(info?.source_dir || "");
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
                <label className="block text-xs font-semibold text-zinc-400 uppercase mb-1">Source Folder</label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={sourcePath}
                    onChange={(e) => setSourcePath(e.target.value)}
                    placeholder="E:\SUBRO"
                    className="flex-1 rounded border border-zinc-800 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-blue-500"
                  />
                  <button
                    onClick={browseSource}
                    className="rounded bg-zinc-800 px-3 py-2 text-sm font-semibold hover:bg-zinc-700 transition"
                  >
                    Browse
                  </button>
                </div>
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
                    setSourcePath(info?.source_dir || "");
                    setDiagnostics("");
                    setShowSettings(false);
                  }}
                  className="rounded bg-zinc-800 px-4 py-2 text-sm font-semibold hover:bg-zinc-700 text-zinc-300 transition"
                >
                  Cancel
                </button>
                <button
                  onClick={() => {
                    localStorage.setItem("source_path", sourcePath);
                    applySource();
                    setDiagnostics("");
                    setShowSettings(false);
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
