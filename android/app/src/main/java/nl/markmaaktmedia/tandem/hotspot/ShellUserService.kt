package nl.markmaaktmedia.tandem.hotspot

import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.system.Os
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The part of Tandem that Shizuku runs with the shell's identity (root's becomes the shell's), in its own process: the hotspot, the app-op
 * that lets Android skip its screen recording question for Tandem, and hearing every copy (see [ShellClipboard]).
 *
 * It is a bare Binder instead of an AIDL service: a few calls do not justify the AIDL toolchain, and Shizuku only asks for an
 * IBinder. Shizuku creates it through the no-argument constructor, so R8 must keep that (see proguard-rules.pro).
 */
class ShellUserService : Binder() {
    init {
        // Shizuku started with root runs this as root, but every call here names the shell's package, and Android checks that a package
        // belongs to its caller: as root the clipboard refuses each read and clears itself for it, so copies vanished (seen 2026-10-26).
        // Everything here works as the shell, so a root process becomes the shell before anything else.
        if (Process.myUid() == 0) runCatching {
            Os.setgid(SHELL_UID)
            Os.setuid(SHELL_UID)
        }
    }

    private val tethering = ShellTethering()
    private val clipboard = ShellClipboard()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TX_START -> {
                val outcome = tethering.start()
                reply?.writeNoException()
                reply?.writeInt(outcome.code)
                reply?.writeString(outcome.message)
            }
            TX_STOP -> {
                val outcome = tethering.stop()
                reply?.writeNoException()
                reply?.writeInt(outcome.code)
                reply?.writeString(outcome.message)
            }
            TX_STATUS -> {
                val status = tethering.status()
                reply?.writeNoException()
                reply?.writeInt(status.clients)
                reply?.writeInt(status.tethered)
            }
            TX_ALLOW_PROJECTION -> {
                val (result, message) = allowProjection(data.readString().orEmpty())
                reply?.writeNoException()
                reply?.writeInt(result)
                reply?.writeString(message)
            }
            TX_CLIP_WATCH -> {
                val to = data.readStrongBinder()
                val (result, message) = if (to == null) 1 to "no one to tell" else clipboard.watch(to)
                reply?.writeNoException()
                reply?.writeInt(result)
                reply?.writeString(message)
            }
            TX_CLIP_UNWATCH -> {
                clipboard.unwatch()
                reply?.writeNoException()
                reply?.writeInt(0)
                reply?.writeString("ok")
            }
            TX_DESTROY -> {
                // Shizuku does not end this process by itself.
                reply?.writeNoException()
                Thread {
                    Thread.sleep(150)
                    exitProcess(0)
                }.start()
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    /**
     * `appops set <package> PROJECT_MEDIA allow`. With it, MediaProjectionManagerService's hasProjectionPermission is true for the
     * package, and SystemUI's MediaProjectionPermissionActivity then answers RESULT_OK at once instead of asking. The shell holds
     * MANAGE_APP_OPS_MODES, an app does not. An argument list, no shell string, so the package name cannot become a command, and
     * the full path, since nothing promises a PATH in a process that Shizuku starts.
     */
    private fun allowProjection(pkg: String): Pair<Int, String> {
        if (!PACKAGE.matches(pkg)) return 1 to "not a package name"
        return try {
            val process = ProcessBuilder("/system/bin/cmd", "appops", "set", pkg, "PROJECT_MEDIA", "allow").redirectErrorStream(true).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy()
                return 2 to "appops did not answer"
            }
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() == 0) 0 to out.ifEmpty { "ok" } else 3 to out.ifEmpty { "appops exited with ${process.exitValue()}" }
        } catch (t: Throwable) {
            4 to (t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private const val SHELL_UID = 2000
        const val TX_START = 1
        const val TX_STOP = 2
        const val TX_STATUS = 3
        const val TX_ALLOW_PROJECTION = 4
        const val TX_CLIP_WATCH = 5
        const val TX_CLIP_UNWATCH = 6

        /** The code Shizuku uses to ask a user service to exit. */
        const val TX_DESTROY = 16777115

        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
