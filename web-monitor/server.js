import http from "node:http";
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const PORT = Number(process.env.PORT || 8080);

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
const WS_PORT = Number(process.env.WS_PORT || 1421);
const clients = new Set();
const devices = new Map();
const tauri = new Map();
const androidSockets = new Map();

function send(socket, value) {
  const data = Buffer.from(JSON.stringify(value));
  const header = data.length < 126 ? Buffer.from([0x81, data.length]) : Buffer.from([0x81, 126, data.length >> 8, data.length & 255]);
  socket.write(Buffer.concat([header, data]));
}

function broadcast() {
  const payload = { type: "state", devices: [...devices.values()], tauri: [...tauri.values()] };
  for (const client of clients) send(client, payload);
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

function readFrame(buffer) {
  if (buffer.length < 6) return null;
  let offset = 2;
  let len = buffer[1] & 127;
  if (len === 126) {
    len = buffer.readUInt16BE(offset);
    offset += 2;
  }
  if (len === 127) return null;
  const masked = buffer[1] & 128;
  const mask = masked ? buffer.subarray(offset, offset + 4) : null;
  offset += masked ? 4 : 0;
  if (buffer.length < offset + len) return null;
  const data = Buffer.from(buffer.subarray(offset, offset + len));
  if (mask) for (let i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
  return data.toString();
}

function attachAndroid(req, socket) {
  handshake(req, socket);
  socket.on("data", (chunk) => {
    const text = readFrame(chunk);
    if (!text) return;
    try {
      const sample = JSON.parse(text);
      const id = sample.id || sample.fingerprint || sample.model || randomUUID();
      const current = devices.get(id) || { id, samples: [] };
      devices.set(id, {
        ...current,
        ...sample,
        id,
        connected: true,
        last_seen: Date.now(),
        samples: [...current.samples.slice(-299), { t: Date.now(), rx_bps: sample.rx_bps || 0, tx_bps: sample.tx_bps || 0 }],
      });
      socket.deviceId = id;
      androidSockets.set(id, socket);
      broadcast();
    } catch {}
  });
  socket.on("close", () => {
    if (socket.deviceId) {
      androidSockets.delete(socket.deviceId);
      const current = devices.get(socket.deviceId);
      if (current) devices.set(socket.deviceId, { ...current, connected: false });
    }
    broadcast();
  });
}

function attachBrowser(req, socket) {
  handshake(req, socket);
  clients.add(socket);
  send(socket, { type: "state", devices: [...devices.values()], tauri: [...tauri.values()] });
  
  socket.on("data", (chunk) => {
    const text = readFrame(chunk);
    if (!text) return;
    try {
      const msg = JSON.parse(text);
      if (msg.type === "tauri_status") {
        const id = msg.id || msg.host || "tauri";
        socket.tauriId = id;
        tauri.set(id, { ...msg, id, last_seen: Date.now() });
        broadcast();
      } else if (msg.type === "command" && msg.target && msg.command) {
        const androidSocket = androidSockets.get(msg.target);
        if (androidSocket) {
          send(androidSocket, msg);
        }
      }
    } catch {}
  });

  socket.on("close", () => {
    clients.delete(socket);
    if (socket.tauriId) {
      tauri.delete(socket.tauriId);
    }
    broadcast();
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
        broadcast();
        res.end("ok");
      } catch {
        res.writeHead(400).end("bad json");
      }
    });
    return;
  }
  const file = req.url === "/" ? "index.html" : req.url.slice(1);
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
  if (req.url === "/network") attachAndroid(req, socket);
  else attachBrowser(req, socket);
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`web monitor http://0.0.0.0:${PORT}`);
});

http.createServer().on("upgrade", attachAndroid).listen(WS_PORT, "0.0.0.0", () => {
  console.log(`android websocket ws://0.0.0.0:${WS_PORT}/network`);
});
