import http from "node:http";
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const PORT = Number(process.env.PORT || 8080);
const WS_PORT = Number(process.env.WS_PORT || 1421);

const MIME_TYPES = {
  html: "text/html; charset=utf-8",
  css: "text/css; charset=utf-8",
  js: "application/javascript; charset=utf-8",
  svg: "image/svg+xml",
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  gif: "image/gif",
  ico: "image/x-icon",
  json: "application/json; charset=utf-8"
};

const clients = new Set();
const devices = new Map();
const tauri = new Map();
const androidSockets = new Map();

function send(socket, value) {
  if (!socket.writable || socket.destroyed) return;
  const data = Buffer.from(JSON.stringify(value));
  const len = data.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x81, len]);
  } else if (len <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  socket.write(Buffer.concat([header, data]));
}

function publicDevice(device) {
  return device;
}

function broadcast(value) {
  for (const client of clients) send(client, value);
}

function broadcastSnapshot() {
  broadcast({
    type: "snapshot",
    devices: [...devices.values()].map(publicDevice),
    tauri: [...tauri.values()],
  });
}

function broadcastTelemetry(device) {
  const { samples, last_sample, ...value } = device;
  broadcast({ type: "telemetry", device: value, sample: last_sample });
}

function handshake(req, socket) {
  const key = req.headers["sec-websocket-key"];
  if (!key) return socket.destroy();
  const accept = createHash("sha1").update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest("base64");
  socket.write([
    "HTTP/1.1 101 Switching Protocols",
    "Upgrade: websocket",
    "Connection: Upgrade",
    `Sec-WebSocket-Accept: ${accept}`,
    "",
    "",
  ].join("\r\n"));
}

function attachWebSocket(socket, onMessage) {
  socket.wsBuffer = Buffer.alloc(0);
  socket.on("data", (chunk) => {
    socket.wsBuffer = Buffer.concat([socket.wsBuffer, chunk]);
    while (socket.wsBuffer.length >= 2) {
      const firstByte = socket.wsBuffer[0];
      const secondByte = socket.wsBuffer[1];
      const opcode = firstByte & 0x0f;
      const masked = (secondByte & 0x80) !== 0;
      let payloadLen = secondByte & 0x7f;
      let offset = 2;

      if (payloadLen === 126) {
        if (socket.wsBuffer.length < 4) break;
        payloadLen = socket.wsBuffer.readUInt16BE(offset);
        offset += 2;
      } else if (payloadLen === 127) {
        if (socket.wsBuffer.length < 10) break;
        const bigLen = socket.wsBuffer.readBigUInt64BE(offset);
        payloadLen = Number(bigLen);
        offset += 8;
      }

      const maskSize = masked ? 4 : 0;
      const totalFrameLength = offset + maskSize + payloadLen;
      if (socket.wsBuffer.length < totalFrameLength) break;

      const frameData = socket.wsBuffer.subarray(offset, totalFrameLength);
      socket.wsBuffer = socket.wsBuffer.subarray(totalFrameLength);

      let payload = frameData;
      if (masked) {
        const maskKey = frameData.subarray(0, 4);
        payload = Buffer.from(frameData.subarray(4));
        for (let i = 0; i < payload.length; i++) {
          payload[i] ^= maskKey[i % 4];
        }
      }

      if (opcode === 0x8) {
        socket.destroy();
        return;
      } else if (opcode === 0x9) {
        const pongHeader = Buffer.from([0x8a, payload.length]);
        socket.write(Buffer.concat([pongHeader, payload]));
        continue;
      } else if (opcode === 0x1 || opcode === 0x2 || opcode === 0x0) {
        try {
          const text = payload.toString("utf8");
          onMessage(text);
        } catch (e) {
          console.error("[ws] Frame decode error:", e);
        }
      }
    }
  });
}

function attachAndroid(req, socket) {
  handshake(req, socket);
  attachWebSocket(socket, (text) => {
    try {
      const sample = JSON.parse(text);
      const id = sample.id || sample.fingerprint || sample.model || randomUUID();
      const current = devices.get(id) || { id };
      const telemetry = { t: Date.now(), rx_bps: sample.rx_bps || 0, tx_bps: sample.tx_bps || 0 };
      const currentSamples = current.samples || [];
      const updatedSamples = [...currentSamples.slice(-59), telemetry];
      devices.set(id, {
        ...current,
        ...sample,
        id,
        connected: true,
        last_seen: Date.now(),
        last_sample: telemetry,
        samples: updatedSamples,
      });
      socket.deviceId = id;
      androidSockets.set(id, socket);
      broadcastTelemetry(devices.get(id));
    } catch {}
  });

  socket.on("close", () => {
    if (socket.deviceId && androidSockets.get(socket.deviceId) === socket) {
      androidSockets.delete(socket.deviceId);
      const current = devices.get(socket.deviceId);
      if (current) devices.set(socket.deviceId, { ...current, connected: false });
      broadcastSnapshot();
    }
  });
}

function attachBrowser(req, socket) {
  handshake(req, socket);
  clients.add(socket);
  send(socket, {
    type: "snapshot",
    devices: [...devices.values()].map(publicDevice),
    tauri: [...tauri.values()],
  });

  attachWebSocket(socket, (text) => {
    try {
      const msg = JSON.parse(text);
      if (msg.type === "tauri_status") {
        const id = msg.id || msg.host || "tauri";
        socket.tauriId = id;
        tauri.set(id, { ...msg, id, last_seen: Date.now() });
        broadcast({ type: "tauri", tauri: [...tauri.values()] });
      } else if (msg.type === "command" && msg.target && msg.command) {
        if (msg.target === "all") {
          for (const s of androidSockets.values()) send(s, msg);
        } else {
          const androidSocket = androidSockets.get(msg.target);
          if (androidSocket) send(androidSocket, msg);
        }
        for (const client of clients) {
          if (client.tauriId && (client.tauriId === msg.target || msg.target === "all")) {
            send(client, msg);
          }
        }
      }
    } catch {}
  });

  socket.on("close", () => {
    clients.delete(socket);
    if (socket.tauriId) {
      tauri.delete(socket.tauriId);
    }
    broadcastSnapshot();
  });
}

const app = http.createServer((req, res) => {
  if (req.method === "POST" && req.url === "/tauri") {
    let body = "";
    req.on("data", (chunk) => body += chunk);
    req.on("end", () => {
      try {
        const data = JSON.parse(body);
        const id = data.id || data.host || "tauri";
        tauri.set(id, { ...data, id, last_seen: Date.now() });
        broadcast({ type: "tauri", tauri: [...tauri.values()] });
        res.end("ok");
      } catch {
        res.writeHead(400).end("bad json");
      }
    });
    return;
  }
  const pathname = new URL(req.url, "http://localhost").pathname;
  const file = pathname === "/" ? "index.html" : pathname.slice(1);
  try {
    const ext = file.split(".").pop().toLowerCase();
    const contentType = MIME_TYPES[ext] || "application/octet-stream";
    const body = readFileSync(join(import.meta.dirname, "public", file));
    res.writeHead(200, { "Content-Type": contentType });
    res.end(body);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain" }).end("not found");
  }
});

app.on("upgrade", (req, socket) => {
  const pathname = new URL(req.url, "http://localhost").pathname;
  if (pathname.startsWith("/network")) attachAndroid(req, socket);
  else attachBrowser(req, socket);
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`web monitor http://0.0.0.0:${PORT}`);
});

http.createServer().on("upgrade", (req, socket) => {
  attachAndroid(req, socket);
}).listen(WS_PORT, "0.0.0.0", () => {
  console.log(`android websocket ws://0.0.0.0:${WS_PORT}/network`);
});

// Periodic stale cleanup to remove offline hosts after 30s
setInterval(() => {
  const now = Date.now();
  let changed = false;
  for (const [id, host] of tauri.entries()) {
    if (now - host.last_seen > 30000) {
      tauri.delete(id);
      changed = true;
    }
  }
  for (const [id, dev] of devices.entries()) {
    if (now - dev.last_seen > 30000 && dev.connected) {
      devices.set(id, { ...dev, connected: false });
      changed = true;
    }
  }
  if (changed) broadcastSnapshot();
}, 10000);
