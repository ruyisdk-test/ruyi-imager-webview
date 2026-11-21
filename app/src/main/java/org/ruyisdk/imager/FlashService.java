package org.ruyisdk.imager;

import android.app.*;
import android.content.*;
import android.os.*;

public final class FlashService extends Service {
    private PowerManager.WakeLock wake;
    @Override public void onCreate() {
        super.onCreate();
        String channel = "provision";
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(channel, getString(R.string.flash_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ImagerActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Ruyi Imager").setContentText(getString(R.string.flashing)).setOngoing(true).setContentIntent(open).build());
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RuyiImager:USB");
        wake.acquire();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (wake != null && wake.isHeld()) wake.release(); super.onDestroy(); }
}
