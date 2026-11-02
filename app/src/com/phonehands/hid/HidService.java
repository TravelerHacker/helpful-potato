package com.phonehands.hid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class HidService extends Service {
    static final String TAG = "PhoneHands";
    static final int PORT = 8765;
    static volatile HidService instance;
    static final ArrayDeque<String> LOG = new ArrayDeque<>();

    static final byte ID_KB = 1, ID_MOUSE = 2;
    static final byte[] DESCRIPTOR = {
        // Keyboard (report id 1): mods, reserved, 6 keys; LED output report
        0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, ID_KB,
        0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
        (byte) 0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, (byte) 0x91, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x91, 0x01,
        (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, (byte) 0xFF, 0x00,
        0x05, 0x07, 0x19, 0x00, 0x29, (byte) 0xFF, (byte) 0x81, 0x00,
        (byte) 0xC0,
        // Mouse (report id 2): 3 buttons, X, Y, wheel (relative)
        0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, (byte) 0x85, ID_MOUSE,
        0x09, 0x01, (byte) 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06,
        (byte) 0xC0, (byte) 0xC0
    };

    BluetoothAdapter adapter;
    volatile BluetoothHidDevice hid;
    volatile boolean registered;
    volatile BluetoothDevice host;
    volatile int hostState = BluetoothProfile.STATE_DISCONNECTED;
    volatile int mouseButtons = 0;
    volatile String lastError = "";
    ExecutorService hidExec = Executors.newSingleThreadExecutor();
    ExecutorService cbExec = Executors.newSingleThreadExecutor();
    ServerSocket server;
    PowerManager.WakeLock wl;
    SharedPreferences prefs;
    final BtBackend bt = new BtBackend();
    Ch9329Backend ch;
    CameraIndicator cam;
    /** auto | bluetooth | ch9329 */
    volatile String backendMode = "auto";

    static void log(String s) {
        Log.i(TAG, s);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + s;
        synchronized (LOG) { LOG.addLast(line); while (LOG.size() > 40) LOG.removeFirst(); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("hid", MODE_PRIVATE);
        startFg();
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneHands:hid");
        wl.setReferenceCounted(false);
        registerReceiver(btState, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        openProxy();
        backendMode = prefs.getString("backend", "auto");
        try { ch = new Ch9329Backend(this, prefs.getInt("baud", 9600)); ch.open(); } catch (Exception e) { log("ch9329 init: " + e); }
        cam = new CameraIndicator(this); cam.start();
        startServer();
        log("service created");
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("hid", "HID service", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "hid")
            .setContentTitle("Phone Hands HID")
            .setContentText("Bluetooth keyboard/mouse ready; API on 127.0.0.1:" + PORT)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi).setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    final BroadcastReceiver btState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            log("adapter state " + st);
            if (st == BluetoothAdapter.STATE_ON) openProxy();
            if (st == BluetoothAdapter.STATE_OFF) { registered = false; hid = null; host = null; }
        }
    };

    void openProxy() {
        if (adapter == null || !adapter.isEnabled()) { log("bluetooth off; waiting"); return; }
        try {
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int p, BluetoothProfile proxy) {
                    if (p != BluetoothProfile.HID_DEVICE) return;
                    hid = (BluetoothHidDevice) proxy;
                    log("HID_DEVICE proxy connected");
                    register();
                }
                @Override public void onServiceDisconnected(int p) {
                    if (p == BluetoothProfile.HID_DEVICE) { hid = null; registered = false; log("HID_DEVICE proxy disconnected"); }
                }
            }, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) { lastError = e.toString(); log("getProfileProxy: " + e); }
    }

    void register() {
        BluetoothHidDevice h = hid;
        if (h == null) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
            "Phone Hands", "Keyboard + Mouse", "PhoneHands", BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        try {
            boolean ok = h.registerApp(sdp, null, null, cbExec, cb);
            log("registerApp() returned " + ok);
        } catch (SecurityException e) { lastError = e.toString(); log("registerApp: " + e); }
    }

    void reregister() {
        BluetoothHidDevice h = hid;
        if (h == null) { openProxy(); return; }
        try { h.unregisterApp(); } catch (SecurityException e) { log("unregister: " + e); }
        registered = false;
        new android.os.Handler(getMainLooper()).postDelayed(this::register, 500);
    }

    final BluetoothHidDevice.Callback cb = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            registered = reg;
            log("onAppStatusChanged registered=" + reg + " plugged=" + (plugged == null ? "null" : plugged.getAddress()));
            if (reg) {
                String last = prefs.getString("lastHost", null);
                if (plugged == null && last != null && hostState != BluetoothProfile.STATE_CONNECTED) {
                    try {
                        BluetoothDevice d = adapter.getRemoteDevice(last);
                        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                            log("auto-reconnect to last host " + last + " -> " + hid.connect(d));
                        }
                    } catch (Exception e) { log("auto-reconnect: " + e); }
                }
            }
        }
        @Override public void onConnectionStateChanged(BluetoothDevice d, int state) {
            log("onConnectionStateChanged " + safeName(d) + " " + d.getAddress() + " state=" + state);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = d; hostState = state;
                prefs.edit().putString("lastHost", d.getAddress()).apply();
                wl.acquire();
            } else if (host == null || host.equals(d)) {
                hostState = state;
                if (state == BluetoothProfile.STATE_DISCONNECTED) { host = null; if (wl.isHeld()) wl.release(); }
                else host = d;
            }
        }
        @Override public void onGetReport(BluetoothDevice d, byte type, byte id, int bufSize) {
            log("onGetReport type=" + type + " id=" + id);
            try {
                if (id == ID_MOUSE) hid.replyReport(d, type, id, new byte[4]);
                else hid.replyReport(d, type, ID_KB, new byte[8]);
            } catch (SecurityException e) { log("replyReport: " + e); }
        }
        @Override public void onSetReport(BluetoothDevice d, byte type, byte id, byte[] data) {
            try { hid.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS); } catch (SecurityException ignored) {}
        }
        @Override public void onSetProtocol(BluetoothDevice d, byte protocol) { log("onSetProtocol " + protocol); }
        @Override public void onInterruptData(BluetoothDevice d, byte id, byte[] data) { }
        @Override public void onVirtualCableUnplug(BluetoothDevice d) { log("virtual cable unplug " + d.getAddress()); }
    };

    String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? "?" : n; } catch (SecurityException e) { return "?"; }
    }

    // ---------------- HID output ----------------
    class BtBackend implements HidBackend {
        public String name() { return "bluetooth"; }
        public boolean ready() { return hid != null && host != null && hostState == BluetoothProfile.STATE_CONNECTED; }
        public boolean supportsAbsolute() { return false; }
        void send(byte id, byte[] data) {
            BluetoothHidDevice h = hid; BluetoothDevice d = host;
            if (h == null || d == null || hostState != BluetoothProfile.STATE_CONNECTED) throw new IllegalStateException("bluetooth: no connected host");
            boolean ok;
            try { ok = h.sendReport(d, id, data); } catch (SecurityException e) { throw new IllegalStateException(e.toString()); }
            if (!ok) throw new IllegalStateException("bluetooth: sendReport failed");
        }
        public void keyboard(byte[] r) { send(ID_KB, r); }
        public void mouseRel(int b, int dx, int dy, int w) { send(ID_MOUSE, new byte[]{(byte) b, (byte) dx, (byte) dy, (byte) w}); }
        public void mouseAbs(int b, int x, int y, int w) { throw new UnsupportedOperationException("bluetooth backend has no absolute pointer"); }
        public JSONObject status() throws Exception {
            JSONObject o = new JSONObject().put("ready", ready()).put("registered", registered);
            o.put("connectionState", new String[]{"disconnected", "connecting", "connected", "disconnecting"}[Math.max(0, Math.min(3, hostState))]);
            return o;
        }
    }

    HidBackend active() {
        String m = backendMode;
        if ("bluetooth".equals(m)) return bt;
        if ("ch9329".equals(m)) { if (ch == null) throw new IllegalStateException("ch9329 backend unavailable"); return ch; }
        if (ch != null && ch.ready()) return ch;
        return bt;
    }

    void kb(int mods, int... keys) {
        byte[] r = new byte[8];
        r[0] = (byte) mods;
        for (int i = 0; i < keys.length && i < 6; i++) r[2 + i] = (byte) keys[i];
        active().keyboard(r);
    }

    static void sleep(long ms) { if (ms > 0) try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    void tap(int mods, int[] keys, long holdMs, long gapMs) {
        if (mods != 0 && keys.length > 0) { kb(mods); sleep(Math.max(5, gapMs / 2)); }
        kb(mods, keys);
        sleep(holdMs);
        if (mods != 0 && keys.length > 0) { kb(mods); sleep(Math.max(5, gapMs / 2)); }
        kb(0);
        sleep(gapMs);
    }

    JSONObject typeText(String text, long delay) throws Exception {
        int typed = 0; StringBuilder skipped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') continue;
            int[] k = Keys.ascii(c);
            if (k == null) { skipped.append(c); continue; }
            tap(k[1], new int[]{k[0]}, delay, delay);
            typed++;
        }
        JSONObject o = new JSONObject().put("ok", true).put("typed", typed);
        if (skipped.length() > 0) o.put("skipped", skipped.toString());
        return o;
    }

    JSONObject combo(String spec, long hold, long gap) throws Exception {
        JSONArray done = new JSONArray();
        for (String part : spec.trim().split("[\\s,]+")) {
            if (part.isEmpty()) continue;
            int[] mk = Keys.parseCombo(part);
            int mods = mk[0];
            int[] keys = new int[mk.length - 1];
            System.arraycopy(mk, 1, keys, 0, keys.length);
            if (keys.length == 0) { kb(mods); sleep(hold); kb(0); sleep(gap); }
            else tap(mods, keys, hold, gap);
            done.put(part);
        }
        return new JSONObject().put("ok", true).put("sent", done);
    }

    void mouse(int buttons, int dx, int dy, int wheel) {
        active().mouseRel(buttons, dx, dy, wheel);
    }

    JSONObject move(int dx, int dy, long stepDelay) throws Exception {
        int steps = 0;
        while (dx != 0 || dy != 0) {
            int sx = Math.max(-127, Math.min(127, dx));
            int sy = Math.max(-127, Math.min(127, dy));
            mouse(mouseButtons, sx, sy, 0);
            dx -= sx; dy -= sy; steps++;
            sleep(stepDelay);
        }
        return new JSONObject().put("ok", true).put("reports", steps);
    }

    static int btn(String b) {
        if (b == null) return 1;
        switch (b.toLowerCase(Locale.US)) {
            case "right": case "r": case "2": return 2;
            case "middle": case "m": case "3": return 4;
            default: return 1;
        }
    }

    JSONObject click(int b, int count, long hold) throws Exception {
        for (int i = 0; i < count; i++) {
            mouse(mouseButtons | b, 0, 0, 0); sleep(hold);
            mouse(mouseButtons & ~b, 0, 0, 0); sleep(hold + 30);
        }
        return new JSONObject().put("ok", true).put("clicks", count);
    }

    JSONObject scroll(int amount, long stepDelay) throws Exception {
        int n = 0;
        while (amount != 0) {
            int s = Math.max(-127, Math.min(127, amount));
            mouse(mouseButtons, 0, 0, s); amount -= s; n++; sleep(stepDelay);
        }
        return new JSONObject().put("ok", true).put("reports", n);
    }

    // ---------------- status ----------------
    JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        try { o.put("activeBackend", active().name()); } catch (Exception e) { o.put("activeBackend", JSONObject.NULL); }
        o.put("backendMode", backendMode);
        o.put("ready", (ch != null && ch.ready() && !"bluetooth".equals(backendMode)) || (bt.ready() && !"ch9329".equals(backendMode)));
        JSONObject be = new JSONObject().put("bluetooth", bt.status());
        if (ch != null) be.put("ch9329", ch.status());
        o.put("backends", be);
        o.put("bluetoothEnabled", adapter != null && adapter.isEnabled());
        o.put("proxy", hid != null);
        o.put("registered", registered);
        try {
            o.put("adapterName", adapter.getName());
            int sm = adapter.getScanMode();
            o.put("discoverable", sm == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        } catch (SecurityException e) { o.put("adapterError", e.toString()); }
        o.put("connectionState", new String[]{"disconnected", "connecting", "connected", "disconnecting"}[Math.max(0, Math.min(3, hostState))]);
        BluetoothDevice d = host;
        if (d != null) o.put("host", new JSONObject().put("name", safeName(d)).put("address", d.getAddress()));
        JSONArray bonded = new JSONArray();
        try {
            for (BluetoothDevice b : adapter.getBondedDevices()) {
                JSONObject bo = new JSONObject().put("name", safeName(b)).put("address", b.getAddress());
                try { bo.put("class", String.format("0x%06x", b.getBluetoothClass().getDeviceClass())); } catch (Exception ignored) {}
                if (hid != null) try { bo.put("hidState", hid.getConnectionState(b)); } catch (Exception ignored) {}
                bonded.put(bo);
            }
        } catch (SecurityException e) { o.put("bondedError", e.toString()); }
        o.put("bonded", bonded);
        o.put("lastHost", prefs.getString("lastHost", null));
        o.put("mouseButtonsHeld", mouseButtons);
        if (cam != null) { JSONObject c = cam.status(); o.put("cameraOn", c.getBoolean("cameraOn")); o.put("camera", c); }
        if (!lastError.isEmpty()) o.put("lastError", lastError);
        JSONArray l = new JSONArray();
        synchronized (LOG) { for (String s : LOG) l.put(s); }
        o.put("log", l);
        return o;
    }

    String statusText() {
        try {
            JSONObject s = status();
            StringBuilder b = new StringBuilder();
            if (s.optBoolean("cameraOn")) b.append("*** \uD83D\uDCF7 CAMERA ON - remote viewing ***\n\n"); else b.append("Camera: off\n");
            b.append("Active backend: ").append(s.optString("activeBackend")).append(" (mode ").append(s.optString("backendMode")).append(")  ready=").append(s.optBoolean("ready")).append('\n');
            JSONObject bes = s.optJSONObject("backends");
            JSONObject chs = bes == null ? null : bes.optJSONObject("ch9329");
            if (chs != null) b.append("CH9329 USB: ").append(chs.optBoolean("ready") ? "open @" + chs.optInt("baud") : "not open").append(chs.has("lastError") ? " - " + chs.optString("lastError") : "").append('\n');
            b.append("Bluetooth on: ").append(s.optBoolean("bluetoothEnabled")).append('\n');
            b.append("HID registered: ").append(s.optBoolean("registered")).append('\n');
            b.append("Discoverable: ").append(s.optBoolean("discoverable")).append('\n');
            b.append("Host: ").append(s.optString("connectionState"));
            JSONObject h = s.optJSONObject("host");
            if (h != null) b.append(" ").append(h.optString("name")).append(" ").append(h.optString("address"));
            b.append("\nAPI: 127.0.0.1:").append(PORT).append("\n\nBonded devices:\n");
            JSONArray bo = s.optJSONArray("bonded");
            for (int i = 0; bo != null && i < bo.length(); i++)
                b.append("  ").append(bo.getJSONObject(i).optString("name")).append("  ").append(bo.getJSONObject(i).optString("address")).append('\n');
            b.append("\nLog:\n");
            JSONArray l = s.optJSONArray("log");
            for (int i = l.length() - 1; i >= 0 && i >= l.length() - 15; i--) b.append(l.getString(i)).append('\n');
            return b.toString();
        } catch (Exception e) { return e.toString(); }
    }

    // ---------------- HTTP API ----------------
    void startServer() {
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
                server.setReuseAddress(true);
                log("API listening on 127.0.0.1:" + PORT);
                while (!server.isClosed()) {
                    Socket s = server.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (Exception e) { log("server: " + e); }
        }, "api").start();
    }

    <T> T onHid(Callable<T> c) throws Exception {
        Future<T> f = hidExec.submit(c);
        try { return f.get(); }
        catch (java.util.concurrent.ExecutionException e) { throw (e.getCause() instanceof Exception) ? (Exception) e.getCause() : e; }
    }

    void handle(Socket s) {
        int code = 200; JSONObject out;
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String reqLine = readLine(in);
            if (reqLine == null) { s.close(); return; }
            String[] parts = reqLine.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            int len = 0; String ctype = "";
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase(Locale.US), v = line.substring(c + 1).trim();
                if (k.equals("content-length")) len = Integer.parseInt(v);
                if (k.equals("content-type")) ctype = v.toLowerCase(Locale.US);
            }
            byte[] body = new byte[len];
            int r = 0; while (r < len) { int n = in.read(body, r, len - r); if (n < 0) break; r += n; }
            String path = target; Map<String, String> p = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); parseQuery(target.substring(q + 1), p); }
            String bs = new String(body, 0, r, StandardCharsets.UTF_8);
            if (r > 0) {
                if (ctype.contains("json")) {
                    JSONObject j = new JSONObject(bs);
                    for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) { String k = it.next(); p.put(k, j.get(k).toString()); }
                } else if (ctype.contains("x-www-form-urlencoded")) parseQuery(bs, p);
                else if (!p.containsKey("text")) p.put("text", bs);
            }
            out = route(path, p);
            if (!out.optBoolean("ok", true)) code = 400;
        } catch (IllegalStateException e) {
            code = 409; out = err(e.getMessage());
        } catch (Exception e) {
            code = 400; out = err(e.toString());
        }
        try {
            byte[] b = (out.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            os.write(b); os.flush(); s.close();
        } catch (Exception ignored) {}
    }

    static JSONObject err(String m) { try { return new JSONObject().put("ok", false).put("error", m); } catch (Exception e) { return new JSONObject(); } }

    static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') b.write(c); }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    static void parseQuery(String q, Map<String, String> p) throws Exception {
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
            String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
            p.put(k, v);
        }
    }

    static int ip(Map<String, String> p, String k, int d) { String v = p.get(k); return v == null || v.isEmpty() ? d : (int) Double.parseDouble(v); }

    JSONObject route(String path, Map<String, String> p) throws Exception {
        switch (path) {
            case "/": case "/status": { JSONObject o = status(); o.put("ok", true); return o; }
            case "/camera/on": case "/camera/heartbeat": cam.on(p.get("by")); return cam.status().put("ok", true);
            case "/camera/off": cam.off(); return cam.status().put("ok", true);
            case "/camera": return cam.status().put("ok", true);
            case "/register": reregister(); return new JSONObject().put("ok", true).put("note", "re-registering; check /status");
            case "/discoverable": {
                Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("discoverable", true).putExtra("duration", ip(p, "duration", 300));
                try { startActivity(i); } catch (Exception e) { return err("startActivity: " + e); }
                return new JSONObject().put("ok", true).put("note", "requested; user may need to tap Allow on the phone. (Background launch may be blocked; CLI also uses adb am start.)");
            }
            case "/connect": {
                String a = p.get("address");
                if (a == null || hid == null) return err("need address and registered HID proxy");
                BluetoothDevice d = adapter.getRemoteDevice(a.toUpperCase(Locale.US));
                boolean ok = hid.connect(d);
                log("connect(" + a + ") -> " + ok);
                return new JSONObject().put("ok", ok).put("address", d.getAddress()).put("note", "watch /status for connectionState");
            }
            case "/disconnect": {
                BluetoothDevice d = host;
                if (d == null || hid == null) return err("no host");
                return new JSONObject().put("ok", hid.disconnect(d));
            }
            case "/type": {
                String t = p.get("text"); if (t == null) return err("need text");
                long delay = ip(p, "delay", 12);
                return onHid(() -> typeText(t, delay));
            }
            case "/key": {
                String c = p.get("combo"); if (c == null) c = p.get("keys");
                if (c == null) return err("need combo");
                Keys.parseCombo(c.trim().split("[\\s,]+")[0]); // validate early
                final String cc = c;
                return onHid(() -> combo(cc, ip(p, "hold", 40), ip(p, "gap", 40)));
            }
            case "/move": return onHid(() -> move(ip(p, "dx", 0), ip(p, "dy", 0), ip(p, "delay", 8)));
            case "/click": {
                int b = btn(p.get("button")); int n = ip(p, "count", 1);
                return onHid(() -> click(b, n, ip(p, "hold", 40)));
            }
            case "/down": { int b = btn(p.get("button")); return onHid(() -> { mouseButtons |= b; mouse(mouseButtons, 0, 0, 0); return new JSONObject().put("ok", true).put("buttons", mouseButtons); }); }
            case "/up": { int b = btn(p.get("button")); return onHid(() -> { mouseButtons &= ~b; mouse(mouseButtons, 0, 0, 0); return new JSONObject().put("ok", true).put("buttons", mouseButtons); }); }
            case "/scroll": return onHid(() -> scroll(ip(p, "amount", 0), ip(p, "delay", 15)));
            case "/backend": {
                String n = p.get("name");
                if (n != null) {
                    if (!n.equals("auto") && !n.equals("bluetooth") && !n.equals("ch9329")) return err("name must be auto|bluetooth|ch9329");
                    backendMode = n; prefs.edit().putString("backend", n).apply();
                }
                String res = null;
                if (ch != null && (p.containsKey("baud") || p.containsKey("waitAck") || p.containsKey("addr") || "ch9329".equals(n) || p.containsKey("reopen"))) {
                    if (p.containsKey("baud")) { ch.baud = ip(p, "baud", 9600); prefs.edit().putInt("baud", ch.baud).apply(); ch.close(); }
                    if (p.containsKey("addr")) ch.addr = ip(p, "addr", 0);
                    if (p.containsKey("waitAck")) ch.waitAck = Boolean.parseBoolean(p.get("waitAck"));
                    if (p.containsKey("reopen")) ch.close();
                    res = ch.open();
                }
                JSONObject o = new JSONObject().put("ok", true).put("backendMode", backendMode);
                try { o.put("activeBackend", active().name()); } catch (Exception e) { o.put("activeBackend", JSONObject.NULL); }
                if (res != null) o.put("ch9329", res);
                return o;
            }
            case "/chinfo": {
                if (ch == null || !ch.ready()) return err("CH9329 adapter not open");
                JSONObject o = onHid(() -> ch.queryInfo()); o.put("ok", true); return o;
            }
            case "/moveto": {
                HidBackend a = active();
                if (!a.supportsAbsolute()) return err(a.name() + " backend has no absolute positioning; use /move");
                int x = p.containsKey("fx") ? (int) Math.round(Double.parseDouble(p.get("fx")) * Ch9329.ABS_MAX) : ip(p, "x", 0);
                int y = p.containsKey("fy") ? (int) Math.round(Double.parseDouble(p.get("fy")) * Ch9329.ABS_MAX) : ip(p, "y", 0);
                return onHid(() -> { a.mouseAbs(mouseButtons, x, y, 0); return new JSONObject().put("ok", true).put("x", x).put("y", y); });
            }
            case "/release": return onHid(() -> { mouseButtons = 0; kb(0); mouse(0, 0, 0, 0); return new JSONObject().put("ok", true); });
            default: return err("unknown endpoint " + path);
        }
    }

    @Override public void onDestroy() {
        log("service destroyed");
        try { unregisterReceiver(btState); } catch (Exception ignored) {}
        try { if (hid != null) { hid.unregisterApp(); adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } } catch (Exception ignored) {}
        if (ch != null) { ch.close(); ch.unregister(); }
        if (cam != null) cam.stop();
        try { server.close(); } catch (Exception ignored) {}
        if (wl != null && wl.isHeld()) wl.release();
        instance = null;
        super.onDestroy();
    }
}
