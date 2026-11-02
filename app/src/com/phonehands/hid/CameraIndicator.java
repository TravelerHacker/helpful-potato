package com.phonehands.hid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/**
 * "Camera is on" indicator: high-importance notification + red overlay banner.
 * Shown ONLY during an explicit Phone Hands remote-view session: a client (e.g. look.py) called
 * /camera/on and the most recent heartbeat is still fresh (see active(), which returns explicitOn).
 * The CameraManager availability callback and cameraInUse() are NOT a trigger; they serve only as
 * (a) corroboration within an explicit session (the seenInUseSinceOn logic and the RELEASE_MS
 * early-clear in watchdog()) and (b) a diagnostic field (camerasInUse) in status(). Everyday camera
 * use by other apps therefore never raises the banner/notification on its own.
 * Watchdog: explicit-on expires after TIMEOUT_MS without heartbeat, or when the camera, having been
 * seen in use, becomes available again for RELEASE_MS.
 */
class CameraIndicator {
    static final long TIMEOUT_MS = 15000, RELEASE_MS = 4000;
    static final int NOTIF_ID = 77;
    static final String CHANNEL = "camera_on";
    final HidService svc;
    final Handler main = new Handler(Looper.getMainLooper());
    final Set<String> inUse = new HashSet<>();
    volatile boolean explicitOn;
    volatile long lastBeat, onSince, freeSince;
    volatile boolean seenInUseSinceOn;
    volatile String who = "";
    volatile boolean shown;
    volatile String overlayError = "";
    View banner;
    CameraManager cm;

    CameraIndicator(HidService s) { svc = s; }

    void start() {
        NotificationManager nm = svc.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Camera ON indicator", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Shown whenever the phone camera is being viewed remotely");
        ch.enableLights(true); ch.setLightColor(Color.RED);
        nm.createNotificationChannel(ch);
        cm = svc.getSystemService(CameraManager.class);
        try { cm.registerAvailabilityCallback(avail, main); } catch (Exception e) { HidService.log("camera callback: " + e); }
        main.post(tick);
    }

    void stop() {
        main.removeCallbacks(tick);
        try { cm.unregisterAvailabilityCallback(avail); } catch (Exception ignored) {}
        explicitOn = false; synchronized (inUse) { inUse.clear(); }
        main.post(this::apply);
    }

    final CameraManager.AvailabilityCallback avail = new CameraManager.AvailabilityCallback() {
        @Override public void onCameraUnavailable(String id) {
            synchronized (inUse) { inUse.add(id); }
            if (explicitOn) seenInUseSinceOn = true;
            HidService.log("camera " + id + " IN USE");
            apply();
        }
        @Override public void onCameraAvailable(String id) {
            boolean was; synchronized (inUse) { was = inUse.remove(id); if (inUse.isEmpty()) freeSince = SystemClock.elapsedRealtime(); }
            if (was) HidService.log("camera " + id + " released");
            apply();
        }
    };

    final Runnable tick = new Runnable() { public void run() { watchdog(); main.postDelayed(this, 1000); } };

    void watchdog() {
        long now = SystemClock.elapsedRealtime();
        if (explicitOn) {
            boolean free; synchronized (inUse) { free = inUse.isEmpty(); }
            if (now - lastBeat > TIMEOUT_MS) { explicitOn = false; HidService.log("camera indicator: heartbeat timeout, cleared"); }
            else if (seenInUseSinceOn && free && now - freeSince > RELEASE_MS) { explicitOn = false; HidService.log("camera indicator: camera released, cleared"); }
        }
        apply();
    }

    void on(String by) {
        long now = SystemClock.elapsedRealtime();
        if (!explicitOn) { onSince = now; seenInUseSinceOn = false; }
        synchronized (inUse) { if (!inUse.isEmpty()) seenInUseSinceOn = true; }
        who = by == null ? "" : by; lastBeat = now; explicitOn = true;
        main.post(this::apply);
    }

    void off() { explicitOn = false; main.post(this::apply); }

    boolean cameraInUse() { synchronized (inUse) { return !inUse.isEmpty(); } }
    /** Banner/notification shown ONLY during an explicit Phone Hands remote-view session.
     *  cameraInUse() is corroboration/diagnostics only (see watchdog() and status()); it never raises the UI. */
    boolean active() { return explicitOn; }

    /** main thread only */
    void apply() {
        boolean want = active();
        if (want == shown && (!want || banner != null || !overlayError.isEmpty())) { if (want) updateText(); return; }
        shown = want;
        NotificationManager nm = svc.getSystemService(NotificationManager.class);
        if (want) {
            PendingIntent pi = PendingIntent.getActivity(svc, 1, new Intent(svc, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(svc, CHANNEL)
                .setContentTitle("\uD83D\uDCF7 Camera ON - remote viewing")
                .setContentText("The phone camera is being captured over adb (Phone Hands)")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setColor(Color.RED).setColorized(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build();
            nm.notify(NOTIF_ID, n);
            showBanner();
        } else {
            nm.cancel(NOTIF_ID);
            hideBanner();
        }
        HidService.log("camera indicator " + (want ? "ON" : "OFF"));
    }

    String text() {
        return "\uD83D\uDCF7  CAMERA ON - remote viewing" + (cameraInUse() ? "" : " (starting)");
    }

    void updateText() { if (banner instanceof TextView) ((TextView) banner).setText(text()); }

    void showBanner() {
        if (banner != null) { updateText(); return; }
        if (!Settings.canDrawOverlays(svc)) { overlayError = "SYSTEM_ALERT_WINDOW not granted"; return; }
        try {
            TextView t = new TextView(svc);
            t.setText(text()); t.setTextColor(Color.WHITE); t.setBackgroundColor(0xFFE00000);
            t.setTextSize(20); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER); t.setPadding(16, 24, 16, 24);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            int rid = svc.getResources().getIdentifier("status_bar_height", "dimen", "android");
            lp.y = rid > 0 ? svc.getResources().getDimensionPixelSize(rid) : 0; // just below the status bar
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            svc.getSystemService(WindowManager.class).addView(t, lp);
            banner = t; overlayError = "";
        } catch (Exception e) { overlayError = e.toString(); HidService.log("banner: " + e); }
    }

    void hideBanner() {
        if (banner == null) { overlayError = ""; return; }
        try { svc.getSystemService(WindowManager.class).removeView(banner); } catch (Exception ignored) {}
        banner = null;
    }

    JSONObject status() throws Exception {
        JSONArray ids = new JSONArray(); synchronized (inUse) { for (String s : inUse) ids.put(s); }
        long now = SystemClock.elapsedRealtime();
        JSONObject o = new JSONObject().put("cameraOn", active()).put("explicitOn", explicitOn)
            .put("camerasInUse", ids).put("bannerShown", banner != null)
            .put("overlayPermission", Settings.canDrawOverlays(svc));
        if (explicitOn) o.put("heartbeatAgeMs", now - lastBeat).put("by", who);
        if (!overlayError.isEmpty()) o.put("overlayError", overlayError);
        return o;
    }
}
