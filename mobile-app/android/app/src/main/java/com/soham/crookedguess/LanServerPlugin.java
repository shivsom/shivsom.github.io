package com.soham.crookedguess;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.view.WindowManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.soham.crookedguess.lan.LanRelay;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LAN Versus for the Android app (used by the web code as Capacitor.registerPlugin('LanServer')).
 *
 *  start({port, name, level}) -> {ip, port}
 *      Hosts a match on this phone: runs the WebSocket relay (LanRelay) and announces the
 *      game on the local network with a small UDP broadcast once a second, so the other
 *      phone can list it. Keeps the screen on while hosting.
 *  update({name, level})       changes what the announcement says.
 *  stop()                      stops hosting.
 *  startDiscovery() / stopDiscovery()
 *      Listens for those announcements and passes each one to the web code as a
 *      "hostFound" event: {ip, port, name, level, open}.
 *  getInfo() -> {ip, hosting}  this phone's Wi-Fi / hotspot address.
 */
@CapacitorPlugin(name = "LanServer")
public class LanServerPlugin extends Plugin {

    private static final int BEACON_PORT = 8766;
    private static final String BEACON_TAG = "CGUESS1 ";

    private volatile LanRelay relay;
    private volatile String hostName = "Player";
    private volatile String hostLevel = "easy";
    private volatile boolean beaconing;
    private volatile boolean listening;
    private volatile DatagramSocket listenSocket;
    private WifiManager.MulticastLock multicastLock;

    // ------------------------------------------------------------------ hosting

    @PluginMethod
    public void start(PluginCall call) {
        int port = call.getInt("port", 8765);
        hostName = clean(call.getString("name", "Player"));
        hostLevel = clean(call.getString("level", "easy"));
        stopHosting();
        LanRelay r = new LanRelay(port, count -> { });
        try {
            r.start();
        } catch (Exception e) {
            call.reject("Couldn't open a game on this phone (" + e.getMessage() + ").");
            return;
        }
        relay = r;
        startBeacon();
        keepScreenOn(true);
        JSObject ret = new JSObject();
        ret.put("ip", bestIp());
        ret.put("port", r.getPort());
        call.resolve(ret);
    }

    @PluginMethod
    public void update(PluginCall call) {
        hostName = clean(call.getString("name", hostName));
        hostLevel = clean(call.getString("level", hostLevel));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopHosting();
        call.resolve();
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("ip", bestIp());
        LanRelay r = relay;
        ret.put("hosting", r != null && r.isRunning());
        call.resolve(ret);
    }

    private void stopHosting() {
        beaconing = false;
        LanRelay r = relay;
        relay = null;
        if (r != null) r.stop();
        keepScreenOn(false);
    }

    /** Announce the game on every local network this phone is on (Wi-Fi and/or its own hotspot). */
    private void startBeacon() {
        beaconing = true;
        Thread t = new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                while (beaconing) {
                    LanRelay r = relay;
                    if (r != null && r.isRunning()) {
                        JSONObject j = new JSONObject();
                        j.put("name", hostName);
                        j.put("level", hostLevel);
                        j.put("port", r.getPort());
                        j.put("open", r.playerCount() < 2);
                        byte[] data = (BEACON_TAG + j).getBytes(StandardCharsets.UTF_8);
                        for (InetAddress to : broadcastAddresses()) {
                            try {
                                sock.send(new DatagramPacket(data, data.length, to, BEACON_PORT));
                            } catch (Exception ignored) {
                                // that network may be gone; try the others
                            }
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {
            } finally {
                if (sock != null) sock.close();
            }
        }, "lan-beacon");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ finding games

    @PluginMethod
    public void startDiscovery(PluginCall call) {
        stopListening();
        listening = true;
        acquireMulticastLock();
        Thread t = new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.setBroadcast(true);
                sock.bind(new InetSocketAddress(BEACON_PORT));
                sock.setSoTimeout(1500);
                listenSocket = sock;
                byte[] buf = new byte[1024];
                while (listening) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                    } catch (SocketTimeoutException timeout) {
                        continue;
                    }
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!s.startsWith(BEACON_TAG)) continue;
                    String ip = p.getAddress().getHostAddress();
                    if (relay != null && localIps().contains(ip)) continue;   // our own game
                    try {
                        JSONObject j = new JSONObject(s.substring(BEACON_TAG.length()));
                        JSObject ev = new JSObject();
                        ev.put("ip", ip);
                        ev.put("port", j.optInt("port", 8765));
                        ev.put("name", clean(j.optString("name", "Player")));
                        ev.put("level", clean(j.optString("level", "easy")));
                        ev.put("open", j.optBoolean("open", true));
                        notifyListeners("hostFound", ev);
                    } catch (Exception ignored) {
                        // not one of ours
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (sock != null) sock.close();
                listenSocket = null;
            }
        }, "lan-discovery");
        t.setDaemon(true);
        t.start();
        call.resolve();
    }

    @PluginMethod
    public void stopDiscovery(PluginCall call) {
        stopListening();
        call.resolve();
    }

    private void stopListening() {
        listening = false;
        DatagramSocket s = listenSocket;
        if (s != null) s.close();
        listenSocket = null;
        releaseMulticastLock();
    }

    /** Some phones drop broadcast packets to save battery unless an app holds this lock. */
    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("crooked-guess-lan");
                multicastLock.setReferenceCounted(false);
            }
            multicastLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void handleOnDestroy() {
        stopHosting();
        stopListening();
    }

    // ------------------------------------------------------------------ helpers

    private void keepScreenOn(final boolean on) {
        final Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    /** IPv4 interfaces that are up, ranked: Wi-Fi / hotspot first, never mobile data or VPN. */
    private static List<NetworkInterface> lanInterfaces() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") || n.startsWith("dummy") || n.startsWith("v4-")) continue;
                out.add(ni);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> rank(a) - rank(b));
        return out;
    }

    private static int rank(NetworkInterface ni) {
        String n = ni.getName().toLowerCase(Locale.ROOT);
        if (n.startsWith("wlan")) return 0;
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")) return 1;
        if (n.startsWith("eth")) return 2;
        return 3;
    }

    static String bestIp() {
        for (NetworkInterface ni : lanInterfaces()) {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        }
        for (NetworkInterface ni : lanInterfaces()) {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        }
        return "";
    }

    private static Set<String> localIps() {
        Set<String> ips = new HashSet<>();
        for (NetworkInterface ni : lanInterfaces()) {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) ips.add(a.getHostAddress());
        }
        return ips;
    }

    private static Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> out = new LinkedHashSet<>();
        for (NetworkInterface ni : lanInterfaces()) {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) out.add(ia.getBroadcast());
            }
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        return out;
    }
}
