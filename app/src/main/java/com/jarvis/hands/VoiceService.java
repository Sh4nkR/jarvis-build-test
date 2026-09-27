package com.jarvis.hands;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps Jarvis alive in the background: a permanent notification (with STOP), and Wi-Fi + CPU
 *  locks so the mic, speaker and link keep working when Dr Wolf switches to another app or the
 *  screen turns off. While this runs, Link.on is true. */
public class VoiceService extends Service {
    static final String STOP = "com.jarvis.hands.STOP";
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && STOP.equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForeground(1, note());
        Link.on = true;
        Link.status = "on";
        keepAwake();
        return START_STICKY;
    }

    private Notification note() {
        String ch = "jarvis";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "Jarvis", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
        Intent stop = new Intent(this, VoiceService.class).setAction(STOP);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, flag);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        return b.setContentTitle("Jarvis is on")
                .setContentText("Listening and ready. He stays out of banking apps and never types passwords.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", pi)
                .build();
    }

    private void keepAwake() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:voice");
            wake.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jarvis:wifi");
            wifi.acquire();
        }
    }

    @Override public void onDestroy() {
        Link.on = false;
        Link.status = "off";
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
