package nl.markmaaktmedia.tandem.hotspot

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.BuildConfig
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

enum class ShizukuState {
    /** The Shizuku app is not on this phone. */
    NotInstalled,

    /** Installed, but its service is not running (it dies with every restart of the phone). */
    NotRunning,

    /** Running, and Tandem has not been allowed yet. */
    NeedsPermission,

    /** The person pressed "Deny and do not ask again" in Shizuku. */
    Denied,

    /** Shizuku is older than version 11 and cannot hand out permissions the way we need. */
    TooOld,
    Ready,
}

class ShellResult(val ok: Boolean, val message: String, val clients: Int? = null, val tethered: Int? = null)

/**
 * Talks to Shizuku, if it is there. Everything is optional at run time: without the
 * Shizuku app every call answers "not available" and the manual route takes over.
 * Nothing here throws to the caller.
 */
class ShizukuBridge(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NotInstalled)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var registered = false
    private var binder: IBinder? = null

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, HotspotUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("hotspot")
            .debuggable(BuildConfig.DEBUG)
            // A new version restarts the service, so it never runs old code after an update.
            .version(BuildConfig.VERSION_CODE)
    }

    private val onBinder = Shizuku.OnBinderReceivedListener { refresh() }
    private val onDead = Shizuku.OnBinderDeadListener {
        binder = null
        refresh()
    }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    /** Starts listening for Shizuku coming and going. Safe to call more than once. */
    fun register() {
        if (registered) return
        registered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(onBinder)
            Shizuku.addBinderDeadListener(onDead)
            Shizuku.addRequestPermissionResultListener(onPermission)
        }.onFailure { Log.w(TAG, "could not listen to Shizuku", it) }
        refresh()
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching {
            Shizuku.removeBinderReceivedListener(onBinder)
            Shizuku.removeBinderDeadListener(onDead)
            Shizuku.removeRequestPermissionResultListener(onPermission)
        }
    }

    /** Looks again. The settings screen calls this while it is open. */
    fun refresh(): ShizukuState {
        val now = read()
        _state.value = now
        return now
    }

    private fun read(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> if (installed()) ShizukuState.NotRunning else ShizukuState.NotInstalled
            Shizuku.isPreV11() -> ShizukuState.TooOld
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.Ready
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.Denied
            else -> ShizukuState.NeedsPermission
        }
    } catch (t: Throwable) {
        if (installed()) ShizukuState.NotRunning else ShizukuState.NotInstalled
    }

    private fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** Opens Shizuku's own permission dialog. The answer arrives through [state]. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun launchShizuku(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        return runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    // ---- Calls -----------------------------------------------------------------

    suspend fun start(): ShellResult = call(HotspotUserService.TX_START)
    suspend fun stop(): ShellResult = call(HotspotUserService.TX_STOP)

    suspend fun status(): ShellResult = call(HotspotUserService.TX_STATUS)

    private suspend fun call(code: Int): ShellResult = withContext(Dispatchers.IO) {
        if (refresh() != ShizukuState.Ready) return@withContext ShellResult(false, "Shizuku is not ready")
        val service = connect() ?: return@withContext ShellResult(false, "could not reach the Shizuku service")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            if (!service.transact(code, data, reply, 0)) return@withContext ShellResult(false, "the service refused the call")
            reply.readException()
            if (code == HotspotUserService.TX_STATUS) {
                val clients = reply.readInt().takeIf { it >= 0 }
                val tethered = reply.readInt().takeIf { it >= 0 }
                ShellResult(true, "ok", clients, tethered)
            } else {
                val result = reply.readInt()
                val message = reply.readString().orEmpty()
                ShellResult(result == 0, message)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "shell call $code failed", t)
            // A dead service is dropped so the next call binds a fresh one.
            binder = null
            ShellResult(false, t.message ?: t.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private suspend fun connect(): IBinder? {
        binder?.takeIf { it.pingBinder() }?.let { return it }
        return withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine<IBinder?> { continuation ->
                val waiting = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        binder = service
                        if (continuation.isActive) continuation.resume(service)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        binder = null
                    }
                }
                try {
                    Shizuku.bindUserService(args, waiting)
                } catch (t: Throwable) {
                    Log.w(TAG, "could not bind the Shizuku service", t)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4711
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val TAG = "ShizukuBridge"
    }
}
