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
type NetworkSample = { rx_bps: number; tx_bps: number; adb_push_bps?: number };
type AppInfo = { platform: string; source_dir: string; samba_dir: string; target_fingerprint_set: boolean; hostname: string };

const fileGb = (b: number) => `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
const speed = (b: number) => `${(b / 1024 / 1024).toFixed(2)} MB/s`;
const statusClass = (status: string | undefined | null) => {
  if (!status || typeof status !== "string") {
    return "border-zinc-800 bg-zinc-900 text-zinc-400";
  }
  switch (status.toLowerCase()) {
    case "active":
    case "connected":
    case "ok":
    case "ready":
      return "border-emerald-800 bg-emerald-950 text-emerald-300";
    case "connecting":
    case "syncing":
    case "pushing":
      return "border-amber-800 bg-amber-950 text-amber-300";
    case "error":
    case "failed":
    case "offline":
    case "unreachable":
      return "border-red-800 bg-red-950 text-red-300";
    default:
      return "border-zinc-800 bg-zinc-900 text-zinc-400";
  }
};

function NetworkChart({ samples }: { samples: NetworkSample[] }) {
  const width = 600;
  const height = 80;
  const points = [
    ...Array.from({ length: 300 }, () => ({ rx_bps: 0, tx_bps: 0, adb_push_bps: 0 })),
    ...samples
  ].slice(-300);
  const max = Math.max(1, ...points.map((p) => Math.max(p.tx_bps || 0, p.adb_push_bps || 0)));
  
  const path = (key: keyof NetworkSample) => {
    if (points.length === 0) return "";
    let d = "";
    points.forEach((p, i) => {
      const val = (p[key] || 0) as number;
      const x = points.length <= 1 ? 0 : (i / (points.length - 1)) * width;
      const y = height - (val / max) * height;
      if (i === 0) {
        d += `M ${x.toFixed(1)} ${y.toFixed(1)}`;
      } else {
        const prevVal = (points[i - 1][key] || 0) as number;
        const prevX = ((i - 1) / (points.length - 1)) * width;
        const prevY = height - (prevVal / max) * height;
        const cpX1 = prevX + (x - prevX) / 2;
        const cpY1 = prevY;
        const cpX2 = prevX + (x - prevX) / 2;
        const cpY2 = y;
        d += ` C ${cpX1.toFixed(1)} ${cpY1.toFixed(1)}, ${cpX2.toFixed(1)} ${cpY2.toFixed(1)}, ${x.toFixed(1)} ${y.toFixed(1)}`;
      }
    });
    return d;
  };
  const fillPath = (key: keyof NetworkSample) => {
    const linePath = path(key);
    if (!linePath) return "";
    return `${linePath} L ${width} ${height} L 0 ${height} Z`;
  };
  const last = points[points.length - 1] ?? { rx_bps: 0, tx_bps: 0, adb_push_bps: 0 };

  return (
    <section className="mb-3 rounded border border-zinc-800 bg-zinc-900 p-2 font-mono">
      <div className="mb-1 flex items-center justify-between">
        <h2 className="text-xs font-semibold text-zinc-400">Realtime Network Traffic</h2>
        <div className="flex gap-4 text-[10px] tracking-wider">
          <span className="text-blue-300">Samba Upload: {speed(last.tx_bps)}</span>
          <span className="text-orange-400">ADB Push: {speed(last.adb_push_bps || 0)}</span>
        </div>
      </div>
      <svg className="h-24 w-full" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
        <rect width={width} height={height} fill="#09090b" rx="2" />
        <defs>
          <linearGradient id="chartGradSamba" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#93c5fd" stopOpacity="0.25" />
            <stop offset="100%" stopColor="#93c5fd" stopOpacity="0.0" />
          </linearGradient>
          <linearGradient id="chartGradAdb" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#f97316" stopOpacity="0.25" />
            <stop offset="100%" stopColor="#f97316" stopOpacity="0.0" />
          </linearGradient>
        </defs>
        <path d={fillPath("tx_bps")} fill="url(#chartGradSamba)" />
        <path d={path("tx_bps")} fill="none" stroke="#93c5fd" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        <path d={fillPath("adb_push_bps")} fill="url(#chartGradAdb)" />
        <path d={path("adb_push_bps")} fill="none" stroke="#f97316" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
      </svg>
    </section>
  );
}

function ProgressRing({ percent, label }: { percent: number; label: string }) {
  const radius = 22;
  const stroke = 3;
  const normalizedRadius = radius - stroke * 2;
  const circumference = normalizedRadius * 2 * Math.PI;
  const strokeDashoffset = circumference - (percent / 100) * circumference;

  return (
    <div className="flex flex-col items-center justify-center w-14">
      <div className="relative flex items-center justify-center h-11 w-11">
        <svg height={radius * 2} width={radius * 2} className="transform -rotate-90">
          <circle
            stroke="rgba(156, 163, 175, 0.15)"
            fill="transparent"
            strokeWidth={stroke}
            r={normalizedRadius}
            cx={radius}
            cy={radius}
          />
          <circle
            stroke={percent >= 100 ? "#16a34a" : "#2563eb"}
            fill="transparent"
            strokeWidth={stroke}
            strokeDasharray={circumference + ' ' + circumference}
            style={{ strokeDashoffset }}
            strokeLinecap="round"
            r={normalizedRadius}
            cx={radius}
            cy={radius}
            className="transition-all duration-300 ease-out"
          />
        </svg>
        <span className="absolute text-[8px] font-bold text-gray-800 dark:text-zinc-300">{Math.round(percent)}%</span>
      </div>
      <span className="text-[9px] text-gray-500 dark:text-zinc-400 text-center font-medium mt-0.5 leading-none">{label}</span>
    </div>
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
  const [remoteDevices, setRemoteDevices] = useState<any[]>([]);
  const [ws, setWs] = useState<WebSocket | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  useEffect(() => {
    wsRef.current = ws;
  }, [ws]);
  const [remoteDevicesOpen, setRemoteDevicesOpen] = useState(false);
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
  const [pushSpeed, setPushSpeed] = useState("");
  const adbPushBpsRef = useRef(0);
  const lastTime = useRef(0);
  const lastBytes = useRef(0);
  const filesRef = useRef<LocalFile[]>([]);
  useEffect(() => {
    filesRef.current = files;
  }, [files]);
  const pushedFilesRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    pushedFilesRef.current = pushedFiles;
  }, [pushedFiles]);
  const isPushingRef = useRef(false);
  const isRefreshingDevicesRef = useRef(false);
  const isRefreshingPhoneFilesRef = useRef(false);
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
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    return (localStorage.getItem("theme") as 'light' | 'dark') || "dark";
  });
  const [debugTab, setDebugTab] = useState<'log' | 'progress'>('log');

  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    localStorage.setItem("theme", next);
  };

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
      if (isRefreshingPhoneFilesRef.current) return;
      isRefreshingPhoneFilesRef.current = true;
      invoke<string[]>("get_phone_files")
        .then((list) => {
          setPhoneFiles(new Set(list));
        })
        .catch(() => {})
        .finally(() => { isRefreshingPhoneFilesRef.current = false; });
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
        
        // Calculate speed
        const now = Date.now();
        const fileObj = filesRef.current.find(f => f.name === e.payload.file);
        if (fileObj) {
          const currentBytes = (e.payload.percent / 100) * fileObj.size;
          const timeDiff = now - lastTime.current;
          if (timeDiff > 500 && currentBytes > lastBytes.current) {
            const bytesPerSec = ((currentBytes - lastBytes.current) * 1000) / timeDiff;
            const mbPerSec = bytesPerSec / (1024 * 1024);
            setPushSpeed(mbPerSec >= 0.01 ? ` . ${mbPerSec.toFixed(2)} MB/s` : "");
            adbPushBpsRef.current = mbPerSec >= 0.01 ? bytesPerSec : 0;
            lastTime.current = now;
            lastBytes.current = currentBytes;
          }
        }
        if (e.payload.percent >= 100) {
          setPushSpeed("");
          adbPushBpsRef.current = 0;
          lastTime.current = 0;
          lastBytes.current = 0;
        }

        setTransfer((prev) => {
          if (prev && prev.file === e.payload.file && prev.percent === 100 && e.payload.percent < 100) {
            return prev;
          }
          return e.payload;
        });
      }).catch(err => { appendLog(`listen transfer err: ${err}`); return () => {}; }),
      listen<NetworkSample>("network", (e) => {
        setNetwork((list) => [...list.slice(-299), e.payload]);
      }).catch(err => { appendLog(`listen network err: ${err}`); return () => {}; }),
      listen<any>("usb_telemetry", (e) => {
        const sample = e.payload;
        if (!sample) return;
        // 1. Forward USB telemetry directly to Cloud Web Monitor
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({
            type: "telemetry",
            device: {
              id: sample.id,
              model: sample.model,
              rx_bps: sample.rx_bps || 0,
              tx_bps: sample.tx_bps || 0,
              samba: sample.samba || "connected",
              target: sample.target || "",
              latest: sample.latest || "-",
              current_file: sample.current_file || "",
              upload_percent: sample.upload_percent || 0,
              queue_success: sample.queue_success || 0,
              queue_total: sample.queue_total || 0,
              connected: true,
              last_seen: Date.now()
            },
            sample: {
              t: Date.now(),
              rx_bps: sample.rx_bps || 0,
              tx_bps: sample.tx_bps || 0
            }
          }));
        }
        // 2. Update local remote devices state
        setRemoteDevices((current) => {
          const index = current.findIndex((d) => d.id === sample.id);
          const existing = index >= 0 ? current[index] : { id: sample.id };
          const telemetrySample = { t: Date.now(), rx_bps: sample.rx_bps || 0, tx_bps: sample.tx_bps || 0 };
          const samples = [...((existing as any).samples || []).slice(-59), telemetrySample];
          const updated = {
            ...existing,
            ...sample,
            samples,
            last_seen: Date.now()
          };
          if (index >= 0) {
            return current.map((d, i) => i === index ? updated : d);
          } else {
            return [...current, updated];
          }
        });
      }).catch(err => { appendLog(`listen usb_telemetry err: ${err}`); return () => {}; }),
    ];
    refreshDevices();
    return () => {
      clearInterval(interval);
      clearInterval(phoneFilesInterval);
      clearInterval(devicesInterval);
      void Promise.all(unsubs).then((fns) => fns.forEach((fn) => fn()));
    };
  }, []);

  // Periodic network chart sampler for ADB Push speed
  useEffect(() => {
    const interval = setInterval(() => {
      const activeAdbBps = adbPushBpsRef.current;
      setNetwork((current) => {
        const last = current[current.length - 1] || { rx_bps: 0, tx_bps: 0, adb_push_bps: 0 };
        const newSample: NetworkSample = {
          rx_bps: last.rx_bps,
          tx_bps: last.tx_bps,
          adb_push_bps: activeAdbBps
        };
        return [...current.slice(-299), newSample];
      });
    }, 1000);
    return () => clearInterval(interval);
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
          if (msg.type === "snapshot" || msg.type === "state") {
            setRemoteDevices(msg.devices || []);
            const selectedRemoteId = localStorage.getItem("selected_remote_id");
            const selectedRemote = (msg.devices || []).find((d: any) => d.id === selectedRemoteId);
            if (selectedRemote) {
              setNetwork(selectedRemote.samples || []);
            }
          } else if (msg.type === "telemetry") {
            const device = msg.device || {};
            const sample = msg.sample;
            setRemoteDevices((current) => {
              const index = current.findIndex((d) => d.id === device.id);
              const previous = index >= 0 ? current[index] : {};
              const samples = sample
                ? [...((previous as any).samples || []).slice(-59), sample]
                : (previous as any).samples || [];
              const next = { ...previous, ...device, samples };
              if (index < 0) return [...current, next];
              return current.map((item, i) => i === index ? next : item);
            });
            if (device.id === localStorage.getItem("selected_remote_id") && sample) {
              setNetwork((current) => [...current.slice(-59), sample]);
            }
          } else if (msg.type === "command" && (msg.command === "tauri_refresh" || msg.command === "refresh")) {
            // Web monitor requested a device refresh
            console.info("[bridge-ui] Remote refresh requested via web monitor");
            appendLog("Remote refresh requested via web monitor");
            invoke<Device[]>("get_devices")
              .then((list) => {
                setDevices(list);
                appendLog(`Remote refresh ok count=${list.length}`);
              })
              .catch((e) => appendLog(`Remote refresh failed ${String(e)}`));
          } else if (msg.type === "command" && (msg.command === "upload" || msg.command === "upload_all" || msg.command === "tauri_push_all")) {
            console.info("[bridge-ui] Remote upload all / push requested via web monitor");
            appendLog("Remote upload all / push requested via web monitor");
            pushAllPending(true);
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
      if (ws && info && ws.readyState === WebSocket.OPEN) {
        const selectedDevice = devices.find((d) => d.is_selected_bridge);
        const activeRemote = remoteDevices.find((rd) => rd.id === selectedDevice?.fingerprint);

        const mappedFiles = (files || []).map((f) => {
          const inSamba = sambaFilesRef.current ? sambaFilesRef.current.some((sf) => sf.name === f.name) : false;
          const isPushed = pushedFilesRef.current ? pushedFilesRef.current.has(f.name) : false;
          const isPushingThis = transfer?.file === f.name && transfer?.percent < 100;
          const isUploadingThis = activeRemote?.current_file === f.name;
          const isUploaded = inSamba || (isPushed && phoneFiles ? !phoneFiles.has(f.name) : false);

          let displayStatus = f.status === "ready" ? "Ready" : f.status === "locked" ? "Locked" : f.status;
          if (isPushingThis) {
            displayStatus = `Pushing to Phone (${transfer.percent}%)`;
          } else if (isUploadingThis) {
            displayStatus = `Uploading to Samba (${activeRemote.upload_percent}%)`;
          } else if (inSamba && !isPushed) {
            // ponytail: file already in samba folder before FireFiles pushed it
            displayStatus = "Already in Destination";
          } else if (isUploaded) {
            displayStatus = "Transfer Complete";
          } else if (isPushed) {
            displayStatus = "Staged on Phone";
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

  // Send active adb push progress to the Android device via WebSocket
  useEffect(() => {
    const selectedDevice = devices.find((d) => d.is_selected_bridge);
    const activeRemote = remoteDevices.find((rd) => rd.id === selectedDevice?.fingerprint);
    if (ws && ws.readyState === WebSocket.OPEN && transfer && activeRemote) {
      const fObj = (files || []).find((f) => f.name === transfer.file);
      const totalSize = fObj ? fObj.size : 0;
      
      let speedMbps = 0;
      if (pushSpeed) {
        const match = pushSpeed.match(/([\d.]+)\s*MB\/s/);
        if (match) {
          speedMbps = parseFloat(match[1]);
        }
      }

      ws.send(JSON.stringify({
        type: "command",
        target: activeRemote.id,
        command: "transfer_progress",
        file: transfer.file,
        percent: transfer.percent,
        total_size: totalSize,
        speed_mbps: speedMbps
      }));
    }
  }, [ws, transfer, devices, remoteDevices, pushSpeed, files]);

  async function refreshDevices() {
    if (isRefreshingDevicesRef.current) return;
    isRefreshingDevicesRef.current = true;
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
    } finally {
      isRefreshingDevicesRef.current = false;
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

  function pendingFiles(force = forceTransfer) {
    return filesRef.current.filter((f) => {
      if (f.status === "downloading") return false;
      if (!force && f.status === "locked") return false;
      if (sambaFilesRef.current.some((sf) => sf.name === f.name)) return false;
      if (force) return true;
      if (phoneFiles.has(f.name)) return false;
      return true;
    });
  }

  async function push(name?: string, force = forceTransfer) {
    if (isPushingRef.current) {
      console.warn("[bridge-ui] Transfer already in progress, skipping push");
      return;
    }
    isPushingRef.current = true;
    setError("");
    let ok = true;

    try {
      const names = name ? [name] : pendingFiles(force).map((f) => f.name);
      for (const current of names) {
        console.info("[bridge-ui] push file", current);
        const queueTotal = filesRef.current.length;
        const queueSuccess = filesRef.current.filter((f) => {
          const inSamba = sambaFilesRef.current.some((sf) => sf.name === f.name);
          const isStaged = phoneFiles.has(f.name);
          return inSamba || (isStaged && f.name !== current);
        }).length;

        await invoke("push_file", {
          file_name: current,
          force: force,
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
        setTimeout(() => pushAllPending(), 1000);
      }
    }
  }

  function pushAllPending(force = forceTransfer) {
    const list = pendingFiles(force);
    if (list.length) {
      appendLog(`Push queue started count=${list.length}`);
      push(undefined, force);
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

  return (
    <main data-theme={theme} className="min-h-screen p-0 transition-colors duration-300">
      {/* Topbar: 76px tall, backdrop blur, semi-transparent */}
      <header className="ff-topbar sticky top-0 z-40 flex items-center justify-between px-6">
        <div className="flex items-center gap-3">
          <img src={logo} alt="FireFiles Logo" className="h-9 w-9" />
          <h1 className="text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-orange-500 to-red-500">FireFiles</h1>
        </div>
        <div className="flex items-center gap-4 text-xs font-semibold">
          <span className={`rounded-full px-3 py-1 border transition-colors duration-200 ${
            active 
              ? "border-[#16a34a] bg-[#16a34a]/10 text-[#16a34a]" 
              : "border-gray-250 dark:border-zinc-800 bg-gray-50 dark:bg-zinc-900 text-gray-500 dark:text-zinc-400"
          }`}>
            Bridge Service: {active ? "Ready" : selected ? "Storage Low" : "Idle"}
          </span>
          <span className={`rounded-full px-3 py-1 border transition-colors duration-200 ${
            selectedDevice?.apk_installed 
              ? "border-[#16a34a] bg-[#16a34a]/10 text-[#16a34a]" 
              : "border-gray-250 dark:border-zinc-800 bg-gray-50 dark:bg-zinc-900 text-gray-500 dark:text-zinc-400"
          }`}>
            Android App: {selectedDevice?.apk_installed ? "Installed" : "Not Installed"}
          </span>
          <span className={`transition-colors duration-200 ${active ? "text-[#16a34a] font-bold" : "text-gray-500 dark:text-zinc-400"}`}>
            {active ? "USB Link: Connected" : selected ? "Warning: Storage Low" : "USB Link: Offline"}
          </span>
          
          {/* Theme Toggle Button */}
          <button
            onClick={toggleTheme}
            className="ff-btn p-2 border border-gray-200 dark:border-zinc-800 hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-600 dark:text-zinc-300 transition-colors duration-200"
            title={theme === 'dark' ? "Switch to Light Mode" : "Switch to Dark Mode"}
          >
            {theme === 'dark' ? (
              <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fillRule="evenodd" d="M10 2a1 1 0 011 1v1a1 1 0 11-2 0V3a1 1 0 011-1zm4 8a4 4 0 11-8 0 4 4 0 018 0zm-.464 4.95l.707.707a1 1 0 001.414-1.414l-.707-.707a1 1 0 00-1.414 1.414zm2.12-10.607a1 1 0 010 1.414l-.706.707a1 1 0 11-1.414-1.414l.707-.707a1 1 0 011.414 0zM17 11a1 1 0 100-2h-1a1 1 0 100 2h1zm-7 4a1 1 0 011 1v1a1 1 0 11-2 0v-1a1 1 0 011-1zM5.05 6.464A1 1 0 106.46 5.05l-.707-.707a1 1 0 00-1.414 1.414l.707.707zm1.414 8.486l-.707.707a1 1 0 01-1.414-1.414l.707-.707a1 1 0 011.414 1.414zM4 11a1 1 0 100-2H3a1 1 0 100 2h1z" clipRule="evenodd" />
              </svg>
            ) : (
              <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path d="M17.293 13.293A8 8 0 016.707 2.707a8.001 8.001 0 1010.586 10.586z" />
              </svg>
            )}
          </button>

          <button
            onClick={() => setShowSettings(true)}
            className="ff-btn p-2 border border-gray-200 dark:border-zinc-800 hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-600 dark:text-zinc-300 transition-colors duration-200"
            title="Settings"
          >
            <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
              <path fillRule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clipRule="evenodd" />
            </svg>
          </button>
        </div>
      </header>

      <div className="p-6 space-y-6 w-full">
        <NetworkChart samples={network} />

        {/* Local Staging Folder Card */}
        <section className="ff-card overflow-hidden">
          <div 
            className="flex cursor-pointer items-center justify-between p-4 hover:bg-gray-50 dark:hover:bg-zinc-900/40 transition" 
            onClick={() => setFilelistOpen(!filelistOpen)}
          >
            <div className="flex items-center gap-2">
              <span className="text-xl">📁</span>
              <h2 className="text-sm font-bold text-gray-900 dark:text-zinc-100">
                Local Staging Folder <span className="text-gray-500 dark:text-zinc-400 font-normal text-xs ml-2">Path: {info?.source_dir || "N/A"}</span>
              </h2>
            </div>
            <span className="text-xs text-gray-400 dark:text-zinc-500">{filelistOpen ? "▲ Collapse" : "▼ Expand"}</span>
          </div>
          
          {filelistOpen && (
            <div className="border-t border-gray-150 dark:border-zinc-800 p-4">
              <div className="mb-4 flex flex-wrap gap-4 items-center justify-between border-b border-gray-150 dark:border-zinc-800 pb-4">
                <div className="flex gap-6 text-xs font-semibold text-gray-600 dark:text-zinc-400">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input 
                      type="checkbox" 
                      className="ff-checkbox h-4 w-4" 
                      checked={autoPush} 
                      onChange={(e) => {
                        setAutoPush(e.target.checked);
                        localStorage.setItem("auto_push", e.target.checked ? "true" : "false");
                      }} 
                    />
                    Auto Push
                  </label>
                  <label className="flex items-center gap-2 cursor-pointer text-[#fbbf24] dark:text-[#fbbf24]">
                    <input 
                      type="checkbox" 
                      className="ff-checkbox h-4 w-4 accent-[#fbbf24]" 
                      checked={forceTransfer} 
                      onChange={(e) => setForceTransfer(e.target.checked)} 
                    />
                    Force Transfer (Overwrite)
                  </label>
                </div>
                <div className="flex gap-2">
                  <button
                    disabled={!deviceActionReady || (!forceTransfer && !files.some((f) => f.status !== "downloading" && !sambaFiles.some((sf) => sf.name === f.name)))}
                    onClick={() => pushAllPending(true)}
                    className={`ff-btn px-4 py-2 text-xs transition disabled:opacity-40 disabled:cursor-not-allowed ${
                      theme === 'dark' 
                        ? "bg-[#f4f4f5] text-[#09090b] hover:bg-white" 
                        : "bg-[#2563eb] text-white hover:bg-blue-700"
                    }`}
                  >
                    Push All Pending
                  </button>
                  <button
                    onClick={browseSource}
                    className="ff-btn border border-gray-200 dark:border-zinc-800 hover:bg-gray-100 dark:hover:bg-zinc-800 px-4 py-2 text-xs text-gray-700 dark:text-zinc-300 font-semibold"
                  >
                    Change Folder...
                  </button>
                </div>
              </div>
                
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-[360px] overflow-y-auto pr-1">
                {(files || []).map((f) => {
                  const inSamba = (sambaFiles || []).some((sf) => sf.name === f.name);
                  const isStagedOnPhone = phoneFiles ? phoneFiles.has(f.name) : false;
                  
                  const isPushingThis = transfer?.file === f.name && transfer?.percent < 100;
                  const isUploadingThis = activeRemote?.current_file === f.name;
                  const isUploaded = inSamba;

                  let displayStatus = f.status === "ready" ? "Ready" : f.status === "locked" ? "Locked" : f.status;
                  if (isPushingThis) {
                    displayStatus = `Pushing to Phone (${transfer.percent}%)`;
                  } else if (isUploadingThis) {
                    displayStatus = `Uploading to Samba (${activeRemote.upload_percent}%)`;
                  } else if (isUploaded) {
                    displayStatus = "Transfer Complete";
                  } else if (isStagedOnPhone) {
                    displayStatus = "Staged on Phone";
                  }

                  const isCompleted = displayStatus === "Transfer Complete" || displayStatus === "Already in Destination";
                  
                  let phoneProgress = 0;
                  if (isStagedOnPhone || isUploaded) {
                    phoneProgress = 100;
                  } else if (isPushingThis) {
                    phoneProgress = transfer.percent;
                  }

                  let sambaProgress = 0;
                  if (isUploaded) {
                    sambaProgress = 100;
                  } else if (isUploadingThis) {
                    sambaProgress = activeRemote.upload_percent;
                  }

                  return (
                    <div 
                      key={f.name} 
                      className={`ff-card p-3 flex flex-col justify-between transition-all duration-200 border ${
                        isCompleted 
                          ? "bg-[#16a34a] text-white border-transparent shadow-[0_4px_12px_rgba(22,163,74,0.2)]" 
                          : "bg-white dark:bg-[#141416] border-gray-150 dark:border-zinc-800"
                      }`}
                    >
                      <div className="min-w-0 mb-2">
                        <p className={`break-all font-semibold text-xs leading-tight ${isCompleted ? "text-white" : "text-gray-900 dark:text-zinc-200"}`}>{f.name}</p>
                        <p className={`text-[10px] mt-0.5 ${isCompleted ? "text-green-100" : "text-gray-400 dark:text-zinc-500"}`}>
                          {isPushingThis ? (
                            `${fileGb((transfer.percent / 100) * f.size)} / ${fileGb(f.size)}${pushSpeed}`
                          ) : isUploadingThis ? (
                            `${fileGb((activeRemote.upload_percent / 100) * f.size)} / ${fileGb(f.size)}`
                          ) : (
                            fileGb(f.size)
                          )}
                        </p>
                      </div>

                      {phoneProgress > 0 && phoneProgress < 100 && (
                        <div className="mb-2 w-full">
                          <div className="flex justify-between text-[9px] mb-0.5 text-gray-400 dark:text-zinc-500 font-bold uppercase tracking-wider">
                            <span>Push to Phone</span>
                            <span>{phoneProgress}%</span>
                          </div>
                          <div className="bg-gray-150 dark:bg-zinc-800/80 rounded-full h-1 overflow-hidden">
                            <div 
                              className="bg-[#2563eb] h-1 rounded-full transition-all duration-300" 
                              style={{ width: `${phoneProgress}%` }}
                            />
                          </div>
                        </div>
                      )}

                      {sambaProgress > 0 && sambaProgress < 100 && (
                        <div className="mb-2 w-full">
                          <div className="flex justify-between text-[9px] mb-0.5 text-gray-400 dark:text-zinc-500 font-bold uppercase tracking-wider">
                            <span>Upload to Samba</span>
                            <span>{sambaProgress}%</span>
                          </div>
                          <div className="bg-gray-150 dark:bg-zinc-800/80 rounded-full h-1 overflow-hidden">
                            <div 
                              className="bg-[#16a34a] h-1 rounded-full transition-all duration-300" 
                              style={{ width: `${sambaProgress}%` }}
                            />
                          </div>
                        </div>
                      )}

                      <div className="flex items-center justify-between gap-3 mt-1 pt-2 border-t border-dashed border-gray-150 dark:border-zinc-800/80">
                        <span className={`rounded-full px-2.5 py-0.5 text-[9px] font-bold uppercase tracking-wider ${
                          isCompleted
                            ? "bg-white/20 text-white"
                            : statusClass(displayStatus)
                        }`}>
                          {displayStatus}
                        </span>
                        
                        <button
                          disabled={!selectedDevice || (displayStatus !== "Ready" && !forceTransfer) || isPushingThis}
                          onClick={() => push(f.name)}
                          className={`ff-btn px-3 py-1.5 text-[10px] uppercase tracking-wider font-bold transition disabled:opacity-40 disabled:cursor-not-allowed ${
                            isCompleted
                              ? "bg-white/25 text-white hover:bg-white/35"
                              : theme === 'dark' 
                                ? "bg-[#f4f4f5] text-[#09090b] hover:bg-white" 
                                : "bg-[#2563eb] text-white hover:bg-blue-700"
                          }`}
                        >
                          {forceTransfer ? "Force Push" : "Push"}
                        </button>
                      </div>
                    </div>
                  );
                })}
                {!files.length && (
                  <p className="col-span-full py-6 text-xs text-gray-400 dark:text-zinc-500 text-center font-medium">No .md5 files found</p>
                )}
              </div>

              <div className="mt-3 border-t border-gray-150 dark:border-zinc-800 pt-3 text-[10px] text-gray-500 dark:text-zinc-400 space-y-1">
                <p>Connection: {active ? "target bridge available" : "no validated target bridge"}</p>
                <p className="break-all">Active Transfer: {transfer ? `${transfer.file} (${transfer.message})` : "None"}</p>
                {error && <p className="text-[#ef4444] font-semibold">{error}</p>}
              </div>
            </div>
          )}
        </section>

        {/* 2-Tab Accordion Card (System Log & Transfer Progress) */}
        <section className="ff-card overflow-hidden">
          <div 
            className="flex cursor-pointer items-center justify-between p-4 hover:bg-gray-50 dark:hover:bg-zinc-900/40 transition" 
            onClick={() => setDebugOpen(!debugOpen)}
          >
            <div className="flex items-center gap-2">
              <span className="text-xl">📜</span>
              <h2 className="text-sm font-bold text-gray-900 dark:text-zinc-100">System Log & Diagnostics</h2>
            </div>
            <span className="text-xs text-gray-400 dark:text-zinc-500">{debugOpen ? "▲ Collapse" : "▼ Expand"}</span>
          </div>
          
          {debugOpen && (
            <div className="border-t border-gray-150 dark:border-zinc-800 p-4">
              {/* Tab Headers */}
              <div className="flex gap-4 border-b border-gray-150 dark:border-zinc-800 pb-3 mb-4">
                <button 
                  onClick={() => setDebugTab('log')}
                  className={`pb-2 text-xs font-bold border-b-2 transition ${
                    debugTab === 'log' 
                      ? 'border-[#2563eb] text-[#2563eb]' 
                      : 'border-transparent text-gray-400 dark:text-zinc-500 hover:text-gray-700 dark:hover:text-zinc-200'
                  }`}
                >
                  System Logs
                </button>
                <button 
                  onClick={() => setDebugTab('progress')}
                  className={`pb-2 text-xs font-bold border-b-2 transition ${
                    debugTab === 'progress' 
                      ? 'border-[#2563eb] text-[#2563eb]' 
                      : 'border-transparent text-gray-400 dark:text-zinc-500 hover:text-gray-700 dark:hover:text-zinc-200'
                  }`}
                >
                  Transfer Progress List
                </button>
              </div>

              {/* Tab 1 Content: Textarea log */}
              {debugTab === 'log' && (
                <div className="rounded-[10px] overflow-hidden border border-gray-150 dark:border-zinc-800">
                  <textarea
                    readOnly
                    value={debugLog}
                    className="h-44 w-full resize-none bg-gray-50 dark:bg-zinc-950 p-3 font-mono text-[10px] text-gray-600 dark:text-zinc-400 outline-none"
                  />
                </div>
              )}

              {/* Tab 2 Content: Files list with radial progress rings */}
              {debugTab === 'progress' && (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-h-[300px] overflow-y-auto pr-1">
                  {(files || []).map((f) => {
                    const inSamba = (sambaFiles || []).some((sf) => sf.name === f.name);
                    const isPushed = pushedFiles ? pushedFiles.has(f.name) : false;
                    
                    const isPushingThis = transfer?.file === f.name && transfer?.percent < 100;
                    const isUploadingThis = activeRemote?.current_file === f.name;
                    const isUploaded = inSamba || (isPushed && phoneFiles ? !phoneFiles.has(f.name) : false);

                    // Calculate push to phone percentage
                    let phoneProgress = 0;
                    if (isPushed || isUploaded) {
                      phoneProgress = 100;
                    } else if (isPushingThis) {
                      phoneProgress = transfer.percent;
                    }

                    // Calculate push to samba percentage
                    let sambaProgress = 0;
                    if (isUploaded) {
                      sambaProgress = 100;
                    } else if (isUploadingThis) {
                      sambaProgress = activeRemote.upload_percent;
                    }

                    return (
                      <div 
                        key={f.name} 
                        className="flex items-center justify-between p-3 rounded-[10px] bg-gray-50 dark:bg-zinc-900/40 border border-gray-150 dark:border-zinc-800/80 transition"
                      >
                        <div className="min-w-0 flex-1 pr-3">
                          <p className="text-xs font-bold text-gray-900 dark:text-zinc-200 truncate" title={f.name}>{f.name}</p>
                          <p className="text-[10px] text-gray-400 dark:text-zinc-500 mt-0.5">
                            {isPushingThis ? (
                              `${fileGb((transfer.percent / 100) * f.size)} / ${fileGb(f.size)}${pushSpeed}`
                            ) : isUploadingThis ? (
                              `${fileGb((activeRemote.upload_percent / 100) * f.size)} / ${fileGb(f.size)}`
                            ) : (
                              fileGb(f.size)
                            )}
                          </p>
                        </div>
                        <div className="flex gap-3 flex-shrink-0">
                          <ProgressRing percent={phoneProgress} label="Push Phone" />
                          <ProgressRing percent={sambaProgress} label="Push Samba" />
                        </div>
                      </div>
                    );
                  })}
                  {!files.length && (
                    <p className="col-span-full py-8 text-xs text-gray-455 dark:text-zinc-500 text-center font-medium">
                      No staging files available
                    </p>
                  )}
                </div>
              )}
            </div>
          )}
        </section>

        {/* Connected Remote Devices Accordion */}
        <section className="ff-card overflow-hidden">
          <div 
            className="flex cursor-pointer items-center justify-between p-4 hover:bg-gray-50 dark:hover:bg-zinc-900/40 transition"
            onClick={() => setRemoteDevicesOpen(!remoteDevicesOpen)}
          >
            <div className="flex items-center gap-2">
              <span className="text-xl">🌐</span>
              <h2 className="text-sm font-bold text-gray-900 dark:text-zinc-100">Connected Remote Devices</h2>
            </div>
            <div className="flex items-center gap-3">
              <span className={`rounded-full px-2.5 py-0.5 border text-[10px] font-semibold ${
                ws 
                  ? "border-[#16a34a] bg-[#16a34a]/10 text-[#16a34a]" 
                  : "border-[#ef4444] bg-[#ef4444]/10 text-[#ef4444]"
              }`}>
                Cloud: {ws ? "Online" : "Offline"}
              </span>
              <span className="text-xs text-gray-400 dark:text-zinc-500">{remoteDevicesOpen ? "▲ Collapse" : "▼ Expand"}</span>
            </div>
          </div>

          {remoteDevicesOpen && (
            <div className="border-t border-gray-150 dark:border-zinc-800 p-4">
              <div className="overflow-x-auto rounded-[10px] border border-gray-150 dark:border-zinc-800">
                <table className="w-full border-collapse text-left text-sm">
                  <thead className="bg-gray-50 dark:bg-zinc-900 text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-zinc-400">
                    <tr>
                      <th className="p-3">Device Model</th>
                      <th className="p-3">Device ID</th>
                      <th className="p-3">Samba Target</th>
                      <th className="p-3">Samba Status</th>
                      <th className="p-3">USB Status</th>
                      <th className="p-3">Selection</th>
                      <th className="p-3">Latest File</th>
                      <th className="p-3">Cloud Status</th>
                      <th className="p-3">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-150 dark:divide-zinc-800 text-gray-700 dark:text-zinc-300">
                    {remoteDevices.map((d) => {
                      const isOnline = d.connected !== false && Date.now() - d.last_seen < 15000;
                      const usbOnline = devices.some((localDev) => localDev.fingerprint === d.id);
                      const isSelected = localStorage.getItem("selected_remote_id") === d.id;
                      return (
                        <tr key={d.id} className={`transition ${isSelected ? "bg-[#2563eb]/5 dark:bg-[#2563eb]/10" : "hover:bg-gray-50 dark:hover:bg-zinc-900/50"}`}>
                          <td className="p-3 font-semibold text-gray-900 dark:text-zinc-100">{d.model || "-"}</td>
                          <td className="p-3 text-xs max-w-[200px] truncate text-gray-400 dark:text-zinc-500" title={d.id}>{d.id}</td>
                          <td className="p-3 text-xs text-gray-500 dark:text-zinc-400">{d.target || "-"}</td>
                          <td className="p-3">
                            {d.samba === "connected" ? (
                              <span className="rounded px-2 py-0.5 text-xs font-medium border border-[#16a34a] bg-[#16a34a]/10 text-[#16a34a]">
                                Connected
                              </span>
                            ) : d.samba && d.samba.toLowerCase().includes("error") ? (
                              <span className="rounded px-2 py-0.5 text-xs font-medium border border-[#ef4444] bg-[#ef4444]/10 text-[#ef4444]">
                                Error
                              </span>
                            ) : (
                              <span className="rounded px-2 py-0.5 text-xs font-medium border border-gray-200 dark:border-zinc-800 bg-gray-50 dark:bg-zinc-900 text-gray-400 dark:text-zinc-500">
                                Disconnected
                              </span>
                            )}
                          </td>
                          <td className="p-3">
                            <span className={`rounded px-2 py-0.5 text-xs font-medium border ${
                              usbOnline 
                                ? "border-[#16a34a] bg-[#16a34a]/10 text-[#16a34a]" 
                                : "border-gray-250 dark:border-zinc-800 bg-gray-50 dark:bg-zinc-900 text-gray-450 dark:text-zinc-500"
                            }`}>
                              {usbOnline ? "Connected" : "Disconnected"}
                            </span>
                          </td>
                          <td className="p-3">
                            <span className={`rounded px-2 py-0.5 text-xs font-medium border ${
                              isSelected 
                                ? "border-[#2563eb] bg-[#2563eb]/10 text-[#2563eb]" 
                                : "border-gray-250 dark:border-zinc-800 bg-gray-50 dark:bg-zinc-900 text-gray-450 dark:text-zinc-500"
                            }`}>
                              {isSelected ? "Selected" : "Idle"}
                            </span>
                          </td>
                          <td className="p-3 text-xs max-w-[200px] truncate text-gray-500 dark:text-zinc-400" title={d.latest}>{d.latest || "-"}</td>
                          <td className="p-3">
                            <span className={`rounded px-2 py-0.5 text-xs font-medium border ${
                              isOnline 
                                ? "border-[#16a34a] bg-[#16a34a]/10 text-[#16a34a]" 
                                : "border-[#ef4444] bg-[#ef4444]/10 text-[#ef4444]"
                            }`}>
                              {isOnline ? "Online" : "Offline"}
                            </span>
                          </td>
                          <td className="p-3 flex gap-2">
                            <button
                              onClick={() => {
                                localStorage.setItem("selected_remote_id", d.id);
                                selectBridge(d.id);
                                appendLog(`Selected remote device for monitoring: ${d.model} (${d.id})`);
                              }}
                              className={`ff-btn px-3 py-1 text-xs text-center transition ${
                                theme === 'dark' 
                                  ? "bg-[#f4f4f5] text-[#09090b] hover:bg-white" 
                                  : "bg-[#2563eb] text-white hover:bg-blue-700"
                              }`}
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
                              className={`ff-btn px-3 py-1 text-xs text-center transition disabled:opacity-40 disabled:cursor-not-allowed ${
                                theme === 'dark' 
                                  ? "bg-[#f4f4f5] text-[#09090b] hover:bg-white" 
                                  : "bg-[#2563eb] text-white hover:bg-blue-700"
                              }`}
                            >
                              Upload All
                            </button>
                            <button
                              disabled={!isOnline || !ws}
                              onClick={() => {
                                const host = window.prompt("Enter Samba Host IP:", d.target?.split("//")[1]?.split("/")[0] || "192.168.10.177");
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
                              className="ff-btn border border-gray-200 dark:border-zinc-800 hover:bg-gray-100 dark:hover:bg-zinc-800 px-3 py-1 text-xs font-semibold transition disabled:opacity-40 disabled:cursor-not-allowed text-center text-gray-700 dark:text-zinc-300"
                            >
                              Settings
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                    {!remoteDevices.length && (
                      <tr>
                        <td className="p-3 text-gray-400 dark:text-zinc-500" colSpan={9}>No remote WebSocket devices registered on server.</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </section>
      </div>

      {/* Settings Modal */}
      {showSettings && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="w-full max-w-md rounded-[14px] border border-gray-150 dark:border-zinc-800 bg-white dark:bg-[#141416] p-6 shadow-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-lg font-bold text-gray-900 dark:text-zinc-100">Settings & Sideload Config</h3>
              <button
                onClick={() => {
                  setSourcePath(info?.source_dir || "");
                  setShowSettings(false);
                }}
                className="text-gray-400 hover:text-gray-600 dark:hover:text-zinc-200 transition"
              >
                <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            
            <div className="space-y-4">
              <div>
                <label className="block text-[10px] font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider mb-1">Source Folder</label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={sourcePath}
                    onChange={(e) => setSourcePath(e.target.value)}
                    placeholder="E:\SUBRO"
                    className="flex-1 rounded-[10px] border border-gray-200 dark:border-zinc-800 bg-gray-50 dark:bg-zinc-950 px-3 py-2 text-sm text-gray-900 dark:text-zinc-100 outline-none focus:border-[#2563eb]"
                  />
                  <button
                    onClick={browseSource}
                    className="ff-btn bg-white hover:bg-gray-100 text-gray-900 border border-gray-200 dark:border-zinc-800 px-3 py-2 text-sm font-semibold transition"
                  >
                    Browse
                  </button>
                </div>
              </div>
            </div>

            <div className="mt-6 flex flex-wrap justify-between gap-3 border-t border-gray-150 dark:border-zinc-800 pt-4">
              <button
                disabled={diagLoading}
                onClick={handleDiagnose}
                className="ff-btn bg-white hover:bg-gray-100 text-[#2563eb] border border-gray-200 dark:border-zinc-800 px-3 py-1.5 text-xs font-semibold transition"
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
                  className="ff-btn bg-white hover:bg-gray-100 text-gray-900 border border-gray-200 dark:border-zinc-800 px-4 py-2 text-sm font-semibold transition"
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
                  className="ff-btn bg-white hover:bg-gray-100 text-gray-900 border border-gray-200 dark:border-zinc-800 px-4 py-2 text-sm font-bold transition"
                >
                  Save Changes
                </button>
              </div>
            </div>

            {diagnostics && (
              <div className="mt-4 border-t border-gray-150 dark:border-zinc-800 pt-4">
                <h4 className="text-xs font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider mb-2 text-left">Diagnostic Logs:</h4>
                <pre className="w-full text-left bg-gray-50 dark:bg-zinc-950 border border-gray-150 dark:border-zinc-800 text-[10px] text-gray-650 dark:text-zinc-400 p-3 rounded-[10px] max-h-48 overflow-y-auto whitespace-pre-wrap font-mono">
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
