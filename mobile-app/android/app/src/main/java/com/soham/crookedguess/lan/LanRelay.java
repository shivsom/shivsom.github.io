package com.soham.crookedguess.lan;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny WebSocket relay for LAN Versus. Plain java.net, no libraries, and no Android
 * classes, so it can also be run and tested on a desktop JVM.
 *
 * Protocol (the same one lan-server.js speaks for the web version):
 *  - A new player gets   {"s":n,"f":0,"m":{"t":"welcome","id":ID}}
 *  - everyone then gets  {"s":n,"f":0,"m":{"t":"join","id":ID}}
 *  - every text message a player sends is stamped with an order number and echoed to
 *    everyone in the room, the sender too: {"s":n,"f":fromId,"m":<the message>}
 *  - when a player disconnects: {"s":n,"f":0,"m":{"t":"left","id":ID}}
 *  - a third player gets {"t":"full"} and is disconnected.
 * Because both players act only on the echoed, numbered messages, they always agree on
 * the order of events, e.g. who cracked the code first.
 */
public final class LanRelay {

    /** Told whenever the number of connected players changes. Called on a background thread. */
    public interface Listener {
        void onPlayersChanged(int count);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_PLAYERS = 2;
    private static final int MAX_MSG = 16 * 1024;

    private final int port;
    private final Listener listener;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private volatile boolean running;
    private long seq = 0;
    private int nextId = 0;

    public LanRelay(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));   // all interfaces: Wi-Fi, hotspot and localhost
        server = s;
        running = true;
        Thread t = new Thread(this::acceptLoop, "lan-relay-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients) c.close();
        clients.clear();
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        ServerSocket s = server;
        return s != null ? s.getLocalPort() : port;
    }

    public int playerCount() {
        return clients.size();
    }

    // ------------------------------------------------------------------ connections

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(() -> handle(s), "lan-relay-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        Client c = null;
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String key = readHandshake(in);
            if (key == null) {   // a plain HTTP visit (e.g. a browser): answer with a little status JSON
                String body = "{\"app\":\"crooked-guess\",\"players\":" + clients.size() + "}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n"
                        + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(b);
                out.flush();
                s.close();
                return;
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            s.setSoTimeout(0);
            c = new Client(s, out);
            synchronized (this) {
                if (clients.size() >= MAX_PLAYERS) {
                    c.sendText("{\"s\":0,\"f\":0,\"m\":{\"t\":\"full\"}}");
                    c.close();
                    c = null;
                    return;
                }
                c.id = ++nextId;
                clients.add(c);
                c.sendText(wrap(0, "{\"t\":\"welcome\",\"id\":" + c.id + "}"));
                broadcast(0, "{\"t\":\"join\",\"id\":" + c.id + "}");
            }
            notifyCount();
            readLoop(c, in);
        } catch (IOException ignored) {
            // connection dropped
        } finally {
            if (c != null) remove(c);
            else {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void remove(Client c) {
        boolean removed;
        synchronized (this) {
            removed = clients.remove(c);
            if (removed) broadcast(0, "{\"t\":\"left\",\"id\":" + c.id + "}");
        }
        c.close();
        if (removed) notifyCount();
    }

    private void notifyCount() {
        if (listener != null) {
            try {
                listener.onPlayersChanged(clients.size());
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ messages

    /** Must be called while holding the lock: the order number and the send order stay the same. */
    private String wrap(int from, String message) {
        return "{\"s\":" + (++seq) + ",\"f\":" + from + ",\"m\":" + message + "}";
    }

    private void broadcast(int from, String message) {
        String frame = wrap(from, message);
        for (Client c : clients) c.sendText(frame);
    }

    private void onText(Client c, String text) {
        text = text.trim();
        if (text.isEmpty() || text.charAt(0) != '{' || text.charAt(text.length() - 1) != '}') return;
        synchronized (this) {
            if (clients.contains(c)) broadcast(c.id, text);
        }
    }

    private void readLoop(Client c, InputStream in) throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (running && !c.closed) {
            int b0 = in.read();
            if (b0 < 0) return;
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (len < 0 || len > MAX_MSG) return;
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask);
            byte[] data = new byte[(int) len];
            readFully(in, data);
            if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            switch (op) {
                case 0x8:   // close
                    c.sendFrame(0x8, new byte[0]);
                    return;
                case 0x9:   // ping -> pong
                    c.sendFrame(0xA, data);
                    break;
                case 0xA:   // pong
                    break;
                case 0x0:
                case 0x1:
                case 0x2:
                    if (msg.size() + data.length > MAX_MSG) return;
                    msg.write(data, 0, data.length);
                    if (fin) {
                        String text = new String(msg.toByteArray(), StandardCharsets.UTF_8);
                        msg.reset();
                        onText(c, text);
                    }
                    break;
                default:
                    return;
            }
        }
    }

    // ------------------------------------------------------------------ handshake helpers

    /** Reads the HTTP request head. Returns the Sec-WebSocket-Key for an upgrade request, else null. */
    static String readHandshake(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            head.write(b);
            if (head.size() > 8192) throw new IOException("request head too large");
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : (b == '\r' ? 1 : 0);
        }
        String key = null;
        boolean upgrade = false;
        for (String line : head.toString("UTF-8").split("\r\n")) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String name = line.substring(0, i).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(i + 1).trim();
            if (name.equals("sec-websocket-key")) key = value;
            else if (name.equals("upgrade") && value.equalsIgnoreCase("websocket")) upgrade = true;
        }
        return upgrade ? key : null;
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return base64(sha1.digest((key + GUID).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard Base64 (java.util.Base64 needs Android 8; the app supports Android 7). */
    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xFF) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xFF : 0);
            sb.append(B64[(b >> 18) & 63]).append(B64[(b >> 12) & 63])
              .append(i + 1 < d.length ? B64[(b >> 6) & 63] : '=')
              .append(i + 2 < d.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    // ------------------------------------------------------------------ one connected player

    private static final class Client {
        final Socket socket;
        final OutputStream out;
        int id;
        volatile boolean closed;

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void sendText(String text) {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void sendFrame(int op, byte[] payload) {
            if (closed) return;
            try {
                int len = payload.length;
                if (len < 126) {
                    out.write(new byte[] {(byte) (0x80 | op), (byte) len});
                } else if (len < 65536) {
                    out.write(new byte[] {(byte) (0x80 | op), 126, (byte) (len >> 8), (byte) len});
                } else {
                    byte[] h = new byte[10];
                    h[0] = (byte) (0x80 | op);
                    h[1] = 127;
                    for (int i = 0; i < 8; i++) h[9 - i] = (byte) (((long) len) >> (8 * i));
                    out.write(h);
                }
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
