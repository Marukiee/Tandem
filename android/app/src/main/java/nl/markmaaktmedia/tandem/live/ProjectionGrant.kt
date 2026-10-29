package nl.markmaaktmedia.tandem.live

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.markmaaktmedia.tandem.hotspot.HotspotModule
import nl.markmaaktmedia.tandem.hotspot.ShizukuState

/**
 * The app-op PROJECT_MEDIA for Tandem, set through Shizuku. With it allowed, SystemUI's MediaProjectionPermissionActivity
 * answers Tandem's capture intent at once with a projection of the entire screen, before it would show the question or the
 * choice between one app and the entire screen (read in AOSP for Android 14, 15 and 16). The intent is still launched, it only
 * closes right away, so this needs Tandem in front just like the question does.
 *
 * The mode is read from the system every time, never remembered: it survives an update of the app, and is gone after a
 * reinstall, a clear of the data, or a reset by the person.
 */
object ProjectionGrant {
    private const val TAG = "ProjectionGrant"
    private const val OP = "android:project_media"
    private val lock = Mutex()

    private val _allowed = MutableStateFlow(false)

    /** What [allowed] last read, for the settings page. */
    val state: StateFlow<Boolean> = _allowed.asStateFlow()

    // Deprecated in the newest SDK, and still the one call that reads the app's own op on every version Tandem runs on.
    @Suppress("DEPRECATION")
    fun allowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = runCatching { ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) }.getOrDefault(AppOpsManager.MODE_ERRORED)
        // Drawing over other apps is set along with it (ShellUserService.allowProjection), so the grant counts as done only with both.
        val overlay = android.provider.Settings.canDrawOverlays(context)
        return (mode == AppOpsManager.MODE_ALLOWED && overlay).also { _allowed.value = it }
    }

    /** Allowed now, or made so when Shizuku is ready. False when it is neither, and nothing is shown to the person. */
    suspend fun ensure(context: Context): Boolean = lock.withLock {
        if (allowed(context)) return true
        val shizuku = HotspotModule.get(context).shizuku
        if (shizuku.refresh() != ShizukuState.Ready) return false
        val result = shizuku.allowProjection(context.packageName)
        if (!result.ok) Log.w(TAG, "could not set the app-op: ${result.message}")
        allowed(context)
    }
}
