#!/usr/bin/env node
/*
 * Crooked Guess: LAN Versus server for the WEB version. No npm install needed.
 *
 *   node lan-server.js          -> http://<this computer's IP>:8080
 *   node lan-server.js 9000     -> use another port
 *
 * Run it on any computer on the same Wi-Fi as the players. It serves the game
 * (index.html next to this file) and a tiny WebSocket relay for Versus:
 *   1. Both players open the address printed below (phone or laptop browser).
 *   2. Tap Versus. One player taps "Create room" and reads out the 4-digit code;
 *      the other taps the room under "Join a game" (or types the code).
 * The relay stamps every message with an order number and echoes it to both
 * players (sender included), so both screens agree on who finished first.
 *
 * (The Android app doesn't need this: there the host phone runs the relay itself.)
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const os = require('os');

const PORT = Number(process.argv[2] || process.env.PORT || 8080);
const ROOT = __dirname;
const MAX_MSG = 16 * 1024;
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json', '.txt': 'text/plain; charset=utf-8',
};

/* ---------- rooms ---------- */
const rooms = new Map();   // code -> { code, name, level, hostId, clients: [{id, sock}], seq, nextId, made }

function roomList() {
  return [...rooms.values()].map(r => ({ code: r.code, name: r.name, level: r.level, players: r.clients.length, open: r.clients.length === 1 }));
}
function broadcast(room, from, rawJson) {
  const frame = encodeText(`{"s":${++room.seq},"f":${from},"m":${rawJson}}`);
  room.clients.forEach(c => { if (!c.sock.destroyed) c.sock.write(frame); });
}
function sendTo(sock, room, m) {
  sock.write(encodeText(JSON.stringify({ s: room ? ++room.seq : 0, f: 0, m })));
}

/* ---------- HTTP: the game files + /lan/info ---------- */
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/lan/info') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    return res.end(JSON.stringify({ app: 'crooked-guess-lan', rooms: roomList() }));
  }
  let rel = decodeURIComponent(url.pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  const file = path.normalize(path.join(ROOT, rel));
  const inside = file.startsWith(ROOT + path.sep) && !path.relative(ROOT, file).split(path.sep).some(p => p.startsWith('.') || p === 'node_modules');
  if (!inside) { res.writeHead(403); return res.end('Forbidden'); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
});

/* ---------- WebSocket relay ---------- */
server.on('upgrade', (req, sock) => {
  const key = req.headers['sec-websocket-key'];
  if (!key || String(req.headers.upgrade).toLowerCase() !== 'websocket') return sock.destroy();
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  sock.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
             `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
  sock.setNoDelay(true);

  const url = new URL(req.url, 'http://x');
  const code = (url.searchParams.get('room') || '').replace(/\D/g, '').slice(0, 6);
  const isHost = url.searchParams.get('host') === '1';
  const reject = why => { sendTo(sock, null, { t: 'nope', why }); closeSock(sock); };
  let room;
  if (isHost) {
    if (!code) return reject('Missing room code.');
    if (rooms.has(code)) return reject('That room code is taken. Try again.');
    const level = ['easy', 'medium', 'hard'].includes(url.searchParams.get('level')) ? url.searchParams.get('level') : 'easy';
    room = { code, name: String(url.searchParams.get('name') || 'Player').slice(0, 16), level, hostId: 0, clients: [], seq: 0, nextId: 0, made: Date.now() };
    rooms.set(code, room);
  } else if (code) {
    room = rooms.get(code);
    if (!room) return reject(`There's no room ${code}. Check the code on your friend's screen.`);
  } else {
    // no code (e.g. someone typed this computer's IP in the app): join whichever room is waiting
    room = [...rooms.values()].filter(r => r.clients.length === 1).sort((a, b) => b.made - a.made)[0];
    if (!room) return reject('No game is waiting on that computer yet.');
  }
  if (room.clients.length >= 2) { sendTo(sock, null, { t: 'full' }); return closeSock(sock); }

  const me = { id: ++room.nextId, sock };
  if (isHost) room.hostId = me.id;
  room.clients.push(me);
  sendTo(sock, room, { t: 'welcome', id: me.id, peers: room.clients.filter(c => c !== me).map(c => c.id) });
  broadcast(room, 0, JSON.stringify({ t: 'join', id: me.id }));
  log(`room ${room.code}: player ${me.id} joined (${room.clients.length}/2)`);

  let buf = Buffer.alloc(0), frag = [], gone = false;
  const leave = () => {
    if (gone) return;
    gone = true;
    room.clients = room.clients.filter(c => c !== me);
    if (room.clients.length) broadcast(room, 0, JSON.stringify({ t: 'left', id: me.id }));
    else if (rooms.get(room.code) === room) rooms.delete(room.code);
    log(`room ${room.code}: player ${me.id} left`);
  };
  sock.on('data', chunk => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      const f = decodeFrame(buf);
      if (!f) break;
      if (f.error) { closeSock(sock); return leave(); }
      buf = buf.subarray(f.used);
      if (f.op === 0x8) { closeSock(sock); return leave(); }
      if (f.op === 0x9) { sock.write(encodeFrame(0xA, f.data)); continue; }
      if (f.op === 0xA) continue;
      if (f.op === 0x1 || f.op === 0x2 || f.op === 0x0) {
        frag.push(f.data);
        if (!f.fin) { if (frag.reduce((n, b) => n + b.length, 0) > MAX_MSG) { closeSock(sock); return leave(); } continue; }
        const text = Buffer.concat(frag).toString('utf8');
        frag = [];
        onText(text);
      }
    }
  });
  function onText(text) {
    text = text.trim();
    if (text.length > MAX_MSG || text[0] !== '{' || text[text.length - 1] !== '}') return;
    let m;
    try { m = JSON.parse(text); } catch (e) { return; }
    if (me.id === room.hostId) {   // keep the room list tidy: host's name and level
      if (m.t === 'hello' && typeof m.name === 'string') room.name = m.name.slice(0, 16);
      if ((m.t === 'hello' || m.t === 'level') && ['easy', 'medium', 'hard'].includes(m.level)) room.level = m.level;
    }
    broadcast(room, me.id, JSON.stringify(m));
  }
  sock.on('close', leave);
  sock.on('error', leave);
});

/* ---------- minimal RFC 6455 framing ---------- */
function decodeFrame(b) {
  if (b.length < 2) return null;
  const fin = !!(b[0] & 0x80), op = b[0] & 0x0f, masked = !!(b[1] & 0x80);
  let len = b[1] & 0x7f, off = 2;
  if (len === 126) { if (b.length < 4) return null; len = b.readUInt16BE(2); off = 4; }
  else if (len === 127) { if (b.length < 10) return null; const big = b.readBigUInt64BE(2); if (big > BigInt(MAX_MSG)) return { error: true }; len = Number(big); off = 10; }
  if (len > MAX_MSG) return { error: true };
  if (!masked) return { error: true };                 // clients must mask
  if (b.length < off + 4 + len) return null;
  const mask = b.subarray(off, off + 4), data = Buffer.from(b.subarray(off + 4, off + 4 + len));
  for (let i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
  return { fin, op, data, used: off + 4 + len };
}
function encodeFrame(op, payload) {
  const len = payload.length;
  const head = len < 126 ? Buffer.from([0x80 | op, len])
    : len < 65536 ? Buffer.from([0x80 | op, 126, len >> 8, len & 255])
    : (() => { const h = Buffer.alloc(10); h[0] = 0x80 | op; h[1] = 127; h.writeBigUInt64BE(BigInt(len), 2); return h; })();
  return Buffer.concat([head, payload]);
}
const encodeText = s => encodeFrame(0x1, Buffer.from(s, 'utf8'));
function closeSock(sock) { try { sock.end(encodeFrame(0x8, Buffer.alloc(0))); } catch (e) {} setTimeout(() => sock.destroy(), 500); }
function log(s) { console.log(new Date().toLocaleTimeString(), s); }

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat().filter(i => i && i.family === 'IPv4' && !i.internal).map(i => i.address);
  console.log('\n  Crooked Guess LAN server is running.\n');
  console.log('  Open one of these on BOTH devices (same Wi-Fi), then tap Versus:');
  (ips.length ? ips : ['localhost']).forEach(ip => console.log(`    http://${ip}:${PORT}`));
  console.log('\n  Press Ctrl+C to stop.\n');
});
