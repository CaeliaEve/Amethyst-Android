package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.NotificationUtils;

import java.lang.ref.WeakReference;

public class GameService extends Service {
    private static final WeakReference<Service> sGameService = new WeakReference<>(null);
    private final LocalBinder mLocalBinder = new LocalBinder();
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        acquireLocks();
    }

    private void acquireLocks() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && mWakeLock == null) {
                mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amethyst:game_runtime_wakelock");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire();
                Log.i("GameService", "Acquired PARTIAL_WAKE_LOCK for game runtime");
            }
        } catch (Exception e) {
            Log.e("GameService", "Failed to acquire WakeLock", e);
        }

        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && mWifiLock == null) {
                int wifiMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                mWifiLock = wifiManager.createWifiLock(wifiMode, "amethyst:game_runtime_wifilock");
                mWifiLock.setReferenceCounted(false);
                mWifiLock.acquire();
                Log.i("GameService", "Acquired WifiLock for game runtime");
            }
        } catch (Exception e) {
            Log.e("GameService", "Failed to acquire WifiLock", e);
        }
    }

    private void releaseLocks() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
                Log.i("GameService", "Released WakeLock");
            }
            mWakeLock = null;
        } catch (Exception e) {
            Log.e("GameService", "Failed to release WakeLock", e);
        }

        try {
            if (mWifiLock != null && mWifiLock.isHeld()) {
                mWifiLock.release();
                Log.i("GameService", "Released WifiLock");
            }
            mWifiLock = null;
        } catch (Exception e) {
            Log.e("GameService", "Failed to release WifiLock", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.getBooleanExtra("kill", false)) {
            releaseLocks();
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        acquireLocks();
        Intent killIntent = new Intent(getApplicationContext(), GameService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_GAME_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                 PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle(getString(R.string.lazy_service_default_title))
                .setContentText(getString(R.string.notification_game_runs))
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,  getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setNotificationSilent();

        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification);
        }
        return START_NOT_STICKY; // non-sticky so android wont try restarting the game after the user uses the "Quit" button
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        //At this point in time  only the game runs and the user poofed the window, time to die
        releaseLocks();
        stopSelf();
        Process.killProcess(Process.myPid());
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mLocalBinder;
    }

    public static class LocalBinder extends Binder {
        public boolean isActive;
    }
}
