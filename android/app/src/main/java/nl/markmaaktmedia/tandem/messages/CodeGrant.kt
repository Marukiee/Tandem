package nl.markmaaktmedia.tandem.messages

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.hotspot.HotspotModule
import nl.markmaaktmedia.tandem.hotspot.ShizukuState
import nl.markmaaktmedia.tandem.mirror.MirrorListener

/**
 * Since Android 15 an app that reads notifications gets the text of one with a code replaced ("sensitive notification content hidden"). The
 * app-op RECEIVE_SENSITIVE_NOTIFICATIONS lets Tandem see it after all, which only the shell can set, so through Shizuku. It covers what reading
 * the message store cannot: texts that Android holds back from other apps for three hours (those with a hash or a web address for the code),
 * RCS, and codes in the notifications of other apps.
 *
 * It follows the switch Copy codes: on sets it, off takes it back, so nothing is seen that is not used. The mode is read from the system every
 * time, as with [nl.markmaaktmedia.tandem.live.ProjectionGrant].
 */
object CodeGrant {
    private const val TAG = "CodeGrant"
    private const val OP = "android:receive_sensitive_notifications"
    private val lock = Mutex()

    private val _allowed = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _allowed.asStateFlow()

    /** Android hides codes from notifications at all. */
    val needed: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    @Suppress("DEPRECATION")
    fun allowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = runCatching { ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return (mode == AppOpsManager.MODE_ALLOWED).also { _allowed.value = it }
    }

    /** Whether a code in a text can get off this phone at all. */
    fun canSeeCodes(context: Context): Boolean = !needed || allowed(context) || SmsAccess.canRead(context)

    /** Makes the app-op follow the switch, when Shizuku is there to do it. */
    suspend fun sync(context: Context) = lock.withLock {
        if (!needed) return@withLock
        val want = context.graph.prefs.copyCodes.first()
        if (allowed(context) == want) return@withLock
        val shizuku = HotspotModule.get(context).shizuku
        if (shizuku.refresh() != ShizukuState.Ready) return@withLock
        val result = shizuku.allowSensitiveNotifications(context.packageName, want)
        if (!result.ok) Log.w(TAG, "could not set the app-op: ${result.message}")
        if (allowed(context) == want) MirrorListener.rebind(context)
    }
}
