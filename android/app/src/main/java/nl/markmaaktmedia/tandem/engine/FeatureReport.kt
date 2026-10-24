package nl.markmaaktmedia.tandem.engine

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.live.TandemAccessibilityService
import nl.markmaaktmedia.tandem.mirror.MirrorListener
import uniffi.tandem_core.TandemFeature

/**
 * What this phone does for the other devices and whether it is ready for it (see `Feature` in the core). The others grey out what will
 * not work, say why, and can ask this phone to open the page of its settings where it is turned on ([open]).
 */
object FeatureReport {
    /** Looks again every few seconds while Tandem runs: permissions are changed in the settings, outside the app. The core sends the list on only when it changed. */
    fun start(context: Context, scope: CoroutineScope) {
        scope.launch {
            while (true) {
                runCatching { context.graph.host.engine?.setFeatures(current(context)) }
                delay(5_000)
            }
        }
    }

    suspend fun current(context: Context): List<TandemFeature> {
        val list = mutableListOf<TandemFeature>()
        fun add(id: String, ready: Boolean, name: Int, why: Int, page: String) {
            list += TandemFeature(id, ready, context.getString(name), if (ready) "" else context.getString(why), if (ready) null else page)
        }
        val listener = Permissions.notificationAccess(context)
        add("control", accessibility(context), R.string.feature_control, R.string.feature_control_why, "accessibility")
        // The pointer of a computer pushed over to this phone: the switch in Tandem, and the accessibility service that plays it.
        val pointerOn = context.graph.prefs.pointerIn.first()
        if (!pointerOn) add("pointer", false, R.string.feature_pointer, R.string.feature_pointer_off, "sharing")
        else add("pointer", accessibility(context), R.string.feature_pointer, R.string.feature_control_why, "accessibility")
        add("notifications", listener, R.string.feature_notifications, R.string.feature_listener_why, "notification-access")
        add("media", listener, R.string.feature_media, R.string.feature_listener_why, "notification-access")
        add("calls", Permissions.phone(context), R.string.feature_calls, R.string.feature_calls_why, "access")
        add("files", Environment.isExternalStorageManager(), R.string.feature_files, R.string.feature_files_why, "all-files")
        add("camera", Permissions.camera(context), R.string.feature_camera, R.string.feature_camera_why, "access")
        add("microphone", Permissions.isGranted(context, Manifest.permission.RECORD_AUDIO), R.string.feature_microphone, R.string.feature_microphone_why, "access")
        add("background", Permissions.batteryUnrestricted(context), R.string.feature_background, R.string.feature_background_why, "battery")
        add("bluetooth", Permissions.bluetooth(context), R.string.feature_bluetooth, R.string.feature_bluetooth_why, "access")
        add("alerts", Permissions.notifications(context), R.string.feature_alerts, R.string.feature_alerts_why, "app-notifications")
        // Focus: the switch in Tandem, and access to Do Not Disturb for the mode of its own that follows the other devices.
        if (!context.graph.prefs.focusShare.first()) {
            list += TandemFeature("focus", false, context.getString(R.string.feature_focus), context.getString(R.string.feature_focus_off), null)
        } else {
            add("focus", FocusMode.canUse(context), R.string.feature_focus, R.string.feature_focus_why, "dnd-access")
        }
        return list
    }

    /** Tandem's accessibility service is switched on, whether or not Android has bound it yet. */
    fun accessibility(context: Context): Boolean {
        if (TandemAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val own = ComponentName(context, TandemAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
    }

    /** The screen that turns `page` on. Permissions Android asks for itself go to Tandem's own page, where a press asks. */
    fun intent(context: Context, page: String): Intent {
        val pkg = Uri.parse("package:${context.packageName}")
        val intent = when (page) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification-access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, MirrorListener::class.java).flattenToString())
            "all-files" -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "app-notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "dnd-access" -> FocusMode.accessIntent()
            // Single top: with Tandem already open, Android otherwise only brings it to the front and never hands it the intent, so the
            // page of the setting was not opened.
            "sharing" -> Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SHARING).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            else -> Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_ACCESS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // A page that this phone does not have falls back to the app's own settings.
        return if (intent.resolveActivity(context.packageManager) != null) intent else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }

    /** Opens `page` here: at once with Tandem in front, otherwise as a heads-up to tap, since an app may not open a screen from behind. */
    fun open(context: Context, page: String, from: String?) {
        val target = intent(context, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.graph.appVisible) {
            runCatching { context.startActivity(target) }
            return
        }
        val name = from?.let { context.graph.host.device(it)?.name } ?: context.getString(R.string.feature_device_fallback)
        val tap = PendingIntent.getActivity(context, 0x5354, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.feature_open_title, name))
            .setContentText(context.getString(R.string.feature_open_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(300_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(0x5354, notification)
    }
}
