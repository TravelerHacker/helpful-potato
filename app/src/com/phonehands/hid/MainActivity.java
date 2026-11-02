package com.phonehands.hid;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String[] PERMS = {
        "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BLUETOOTH_SCAN", "android.permission.POST_NOTIFICATIONS"};
    TextView status, camLabel;
    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable tick = new Runnable() { public void run() { refresh(); h.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets bi = ins.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(40 + bi.left, 40 + bi.top, 40 + bi.right, 40 + bi.bottom);
            return ins;
        });
        TextView title = new TextView(this);
        title.setText("Phone Hands HID");
        title.setTextSize(26);
        root.addView(title);
        camLabel = new TextView(this);
        camLabel.setText("\uD83D\uDCF7 CAMERA ON - remote viewing");
        camLabel.setTextSize(22); camLabel.setTextColor(0xFFFFFFFF); camLabel.setBackgroundColor(0xFFE00000);
        camLabel.setPadding(20, 20, 20, 20); camLabel.setVisibility(android.view.View.GONE);
        root.addView(camLabel);
        Button disc = new Button(this);
        disc.setText("Make discoverable (5 min)");
        disc.setOnClickListener(v -> requestDiscoverable(300));
        root.addView(disc);
        Button reg = new Button(this);
        reg.setText("Re-register HID app");
        reg.setOnClickListener(v -> { HidService s = HidService.instance; if (s != null) s.reregister(); });
        root.addView(reg);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        root.addView(sv);
        setContentView(root);
        ensurePermsAndStart();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleIntent(i); }

    void handleIntent(Intent i) {
        if (i != null && "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(i.getAction())) {
            HidService s = HidService.instance;
            HidService.log("USB device attached (activity)");
            if (s != null && s.ch != null) new Thread(() -> s.ch.open()).start();
        }
        if (i != null && i.getBooleanExtra("discoverable", false)) {
            requestDiscoverable(i.getIntExtra("duration", 300));
        }
    }

    void requestDiscoverable(int secs) {
        Intent d = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        d.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, secs);
        try { startActivityForResult(d, 2); } catch (Exception e) { HidService.log("discoverable intent failed: " + e); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 2) HidService.log("discoverable result=" + res + " (seconds if >0, 0=denied)");
    }

    void ensurePermsAndStart() {
        List<String> need = new ArrayList<>();
        for (String p : PERMS) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
        else startSvc();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { startSvc(); }

    void startSvc() {
        try { startForegroundService(new Intent(this, HidService.class)); }
        catch (Exception e) { HidService.log("start service failed: " + e); }
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    void refresh() {
        HidService s = HidService.instance;
        status.setText(s == null ? "Service not running" : s.statusText());
        boolean on = s != null && s.cam != null && s.cam.active();
        camLabel.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
    }
}
