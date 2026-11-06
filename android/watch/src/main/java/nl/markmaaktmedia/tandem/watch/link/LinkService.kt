package nl.markmaaktmedia.tandem.watch.link

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.track.AutoSleep
import nl.markmaaktmedia.tandem.watch.track.StepTracker
import nl.markmaaktmedia.tandem.watch.ui.MainActivity

/** Keeps the connection alive with the screen off. */
class LinkService : Service() {
    private var steps: StepTracker? = null
    private var watchingBattery = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            WatchLink.reportBattery(level * 100 / scale, plugged)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_status), NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setLocalOnly(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        WatchLink.start(this)
        if (steps == null) steps = StepTracker(this).also { it.start() }
        AutoSleep.start(this)
        if (!watchingBattery) {
            watchingBattery = true
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        steps?.stop()
        steps = null
        AutoSleep.stop()
        if (watchingBattery) unregisterReceiver(batteryReceiver)
        watchingBattery = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1

        fun canRun(context: Context) = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        fun start(context: Context) {
            if (canRun(context)) context.startForegroundService(Intent(context, LinkService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // After an update too: nobody opens a watch app just to get their calls back.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> LinkService.start(context)
        }
    }
}
