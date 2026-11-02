package com.phonehands.hid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * CH9329 UART->USB-HID bridge, reached through a USB-serial adapter (CH340/CP210x/FTDI/PL2303)
 * plugged into the phone (USB host / OTG). UNTESTED on real hardware.
 */
public class Ch9329Backend implements HidBackend {
    static final String ACTION_PERM = "com.phonehands.hid.USB_PERMISSION";
    final Context ctx;
    final UsbManager usb;
    UsbSerialPort port;
    UsbDeviceConnection conn;
    UsbDevice device;
    int baud = 9600, addr = 0x00;
    boolean waitAck = true;
    long framesSent, acksOk, acksErr, acksMissing;
    String lastError = "", lastInfo = "";
    final byte[] rx = new byte[256];

    Ch9329Backend(Context c, int baud) {
        ctx = c; this.baud = baud;
        usb = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        IntentFilter f = new IntentFilter(ACTION_PERM);
        c.registerReceiver(permRx, f, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter d = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        d.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        c.registerReceiver(detachRx, d, Context.RECEIVER_EXPORTED);
    }

    final BroadcastReceiver permRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            HidService.log("USB permission " + (ok ? "granted" : "denied"));
            if (ok) open();
        }
    };

    final BroadcastReceiver detachRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                if (device != null && d != null && d.getDeviceId() == device.getDeviceId()) {
                    HidService.log("CH9329 USB adapter detached");
                    close();
                }
            } else open();
        }
    };

    void unregister() {
        try { ctx.unregisterReceiver(permRx); } catch (Exception ignored) {}
        try { ctx.unregisterReceiver(detachRx); } catch (Exception ignored) {}
    }

    @Override public String name() { return "ch9329"; }
    @Override public synchronized boolean ready() { return port != null && port.isOpen(); }
    @Override public boolean supportsAbsolute() { return true; }

    /** Find a supported USB-serial adapter, request permission if needed, and open it. */
    public synchronized String open() {
        if (ready()) return "already open";
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
        if (drivers.isEmpty()) { lastError = "no USB-serial adapter found (need OTG + CH340/CP210x/FTDI/PL2303)"; return lastError; }
        UsbSerialDriver drv = drivers.get(0);
        UsbDevice dev = drv.getDevice();
        if (!usb.hasPermission(dev)) {
            Intent pi = new Intent(ACTION_PERM).setPackage(ctx.getPackageName());
            usb.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, pi, PendingIntent.FLAG_MUTABLE));
            lastError = "USB permission requested; tap OK on the phone";
            HidService.log(lastError);
            return lastError;
        }
        try {
            conn = usb.openDevice(dev);
            if (conn == null) throw new IllegalStateException("openDevice returned null");
            port = drv.getPorts().get(0);
            port.open(conn);
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            device = dev;
            lastError = "";
            HidService.log("CH9329 adapter opened: " + drv.getClass().getSimpleName() + " " + dev.getDeviceName() + " @" + baud);
            try { queryInfo(); } catch (Exception e) { HidService.log("CH9329 get-info: " + e); }
            return "opened";
        } catch (Exception e) {
            lastError = "open: " + e;
            HidService.log(lastError);
            close();
            return lastError;
        }
    }

    public synchronized void close() {
        try { if (port != null) port.close(); } catch (Exception ignored) {}
        port = null; conn = null; device = null;
    }

    /** Send a frame and (optionally) wait briefly for the chip's reply. Returns the reply frame or null. */
    synchronized byte[] xfer(byte[] frame, int timeoutMs) {
        if (!ready()) throw new IllegalStateException("CH9329 adapter not open");
        try {
            port.write(frame, 200);
            framesSent++;
            if (!waitAck && timeoutMs <= 0) return null;
            int len = 0; long end = System.currentTimeMillis() + Math.max(timeoutMs, 60);
            while (System.currentTimeMillis() < end && len < rx.length) {
                byte[] tmp = new byte[64];
                int n = port.read(tmp, 30);
                if (n > 0) {
                    System.arraycopy(tmp, 0, rx, len, Math.min(n, rx.length - len)); len += n;
                    int[] r = Ch9329.findReply(rx, len);
                    if (r != null) {
                        byte[] out = new byte[r[1]]; System.arraycopy(rx, r[0], out, 0, r[1]);
                        int cmd = out[3] & 0xFF;
                        if ((cmd & 0xC0) == 0xC0) { acksErr++; lastError = "chip error reply " + Ch9329.hex(out); }
                        else acksOk++;
                        return out;
                    }
                }
            }
            acksMissing++;
            return null;
        } catch (java.io.IOException e) {
            lastError = "io: " + e; HidService.log(lastError); close();
            throw new IllegalStateException(lastError);
        }
    }

    JSONObject queryInfo() throws Exception {
        byte[] r = xfer(Ch9329.getInfo(addr), 150);
        JSONObject o = new JSONObject();
        if (r == null) { o.put("reply", JSONObject.NULL); lastInfo = "no reply"; return o; }
        o.put("reply", Ch9329.hex(r));
        if ((r[3] & 0xFF) == 0x81 && (r[4] & 0xFF) >= 3) {
            o.put("chipVersion", String.format("0x%02x", r[5] & 0xFF));
            o.put("usbEnumerated", (r[6] & 0xFF) == 1);
            int led = r[7] & 0xFF;
            o.put("numLock", (led & 1) != 0).put("capsLock", (led & 2) != 0).put("scrollLock", (led & 4) != 0);
        }
        lastInfo = o.toString();
        return o;
    }

    @Override public void keyboard(byte[] report) { xfer(Ch9329.keyboard(addr, report), waitAck ? 60 : 0); }
    @Override public void mouseRel(int b, int dx, int dy, int w) { xfer(Ch9329.mouseRel(addr, b, dx, dy, w), waitAck ? 60 : 0); }
    @Override public void mouseAbs(int b, int x, int y, int w) { xfer(Ch9329.mouseAbs(addr, b, x, y, w), waitAck ? 60 : 0); }

    @Override public JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ready", ready()).put("baud", baud).put("addr", addr).put("waitAck", waitAck);
        o.put("framesSent", framesSent).put("acksOk", acksOk).put("acksErr", acksErr).put("acksMissing", acksMissing);
        JSONArray adapters = new JSONArray();
        for (UsbDevice d : usb.getDeviceList().values())
            adapters.put(new JSONObject().put("name", d.getDeviceName()).put("vid", String.format("%04x", d.getVendorId()))
                .put("pid", String.format("%04x", d.getProductId())).put("permission", usb.hasPermission(d)));
        o.put("usbDevices", adapters);
        UsbDevice d = device;
        if (d != null) o.put("device", d.getDeviceName());
        if (!lastInfo.isEmpty()) o.put("lastInfo", lastInfo);
        if (!lastError.isEmpty()) o.put("lastError", lastError);
        return o;
    }
}
