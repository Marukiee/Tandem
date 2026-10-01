package nl.markmaaktmedia.tandem.engine

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import nl.markmaaktmedia.tandem.mirror.MirrorListener

/** What Tandem may do on this phone, checked in one place. Every one of these is optional. */
object Permissions {
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isGranted(context: Context, permission: String) = granted(context, permission)

    fun notifications(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun batteryUnrestricted(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun camera(context: Context) = granted(context, Manifest.permission.CAMERA)
    fun photos(context: Context) = granted(context, Manifest.permission.READ_MEDIA_IMAGES)

    fun notificationAccess(context: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** What showing a call on the other device cannot do without. */
    val phoneEssential = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_CONTACTS,
    )

    /**
     * Extras that only add to calls: the caller's number, declining with a message and
     * dialling from the Mac.
     *
     * READ_CALL_LOG and SEND_SMS are hard restricted on Android 13 and up for an app that
     * was not installed from a store. The system dialog never grants them until the person
     * has chosen "Allow restricted settings" in app info, so a missing one must not keep
     * the whole phone card on Allow.
     */
    val phoneOptional = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
    )

    val phoneRestricted = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS,
    )

    val phonePermissions = phoneEssential + phoneOptional

    /** True once calls can be mirrored. The extras are reported by [phoneOptionalMissing]. */
    fun phone(context: Context) = phoneEssential.all { granted(context, it) }

    fun phoneOptionalMissing(context: Context) = phoneOptional.filterNot { granted(context, it) }

    val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    fun bluetooth(context: Context) = bluetoothPermissions.all { granted(context, it) }

    fun installApps(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** The app info screen. Its three-dot menu is where "Allow restricted settings" lives. */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openInstallSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openDndSettings(context: Context) {
        context.getSystemService(NotificationManager::class.java)
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Nudges the system to rebind our listener after the person turned access on. */
    fun rebindListener(context: Context) {
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(context, MirrorListener::class.java),
            )
        }
    }

    /**
     * The harder nudge, for a listener that is allowed but still not running after [rebindListener]: switching the
     * service off and on makes Android look at it again and bind it. Access stays allowed meanwhile.
     */
    fun restartListener(context: Context) {
        val component = android.content.ComponentName(context, MirrorListener::class.java)
        runCatching {
            val packages = context.packageManager
            packages.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            packages.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        rebindListener(context)
    }
}
