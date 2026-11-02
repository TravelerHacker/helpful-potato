package com.phonehands.hid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            try { c.startForegroundService(new Intent(c, HidService.class)); } catch (Exception ignored) {}
        }
    }
}
