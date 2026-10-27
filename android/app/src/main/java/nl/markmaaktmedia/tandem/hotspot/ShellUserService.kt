package nl.markmaaktmedia.tandem.hotspot

import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.system.Os
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The part of Tandem that Shizuku runs with the shell's identity (root's becomes the shell's), in its own process: the hotspot, the app-op
 * that lets Android skip its screen recording question for Tandem, hearing every copy (see [ShellClipboard]) and the saved Wi-Fi (see [ShellWifi]).
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
    private val wifi = ShellWifi()

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
            TX_CLIP_MESSAGE -> {
                val (result, message) = clipboardMessage(data.readInt())
                reply?.writeNoException()
                reply?.writeInt(result)
                reply?.writeString(message)
            }
            TX_WIFI_SAVED -> {
                val (result, message) = runCatching { 0 to wifi.saved().joinToString("\n") { it.ssid } }.getOrElse { 5 to (it.message ?: it.javaClass.simpleName) }
                reply?.writeNoException()
                reply?.writeInt(result)
                reply?.writeString(message)
            }
            TX_WIFI_PASSWORD -> {
                val ssid = data.readString().orEmpty()
                val (result, message) = runCatching {
                    val saved = wifi.saved().firstOrNull { it.ssid == ssid }
                    when {
                        saved == null -> 6 to "not saved"
                        // An open network has no password, and that is an answer too.
                        else -> 0 to saved.password.orEmpty()
                    }
                }.getOrElse { 5 to (it.message ?: it.javaClass.simpleName) }
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
     * MANAGE_APP_OPS_MODES, an app does not. The package goes as one argument (see [command]), so it cannot become a command.
     */
    private fun allowProjection(pkg: String): Pair<Int, String> {
        if (!PACKAGE.matches(pkg)) return 1 to "not a package name"
        return command("/system/bin/cmd", "appops", "set", pkg, "PROJECT_MEDIA", "allow")
    }

    /**
     * Android's own switch Show clipboard access (under Privacy), which the shell may write: [show] 1 or 0 sets it, anything else only
     * reads it. The message says "Shell pasted from your clipboard" after every copy Tandem hears, and Android has no way to leave out one
     * app, so turning it off is for every app, as the switch of Android is. The answer is what it is now, "1" or "0".
     */
    private fun clipboardMessage(show: Int): Pair<Int, String> {
        if (show == 0 || show == 1) {
            val set = command("/system/bin/cmd", "settings", "put", "secure", CLIPBOARD_MESSAGE, show.toString())
            if (set.first != 0) return set
        }
        val (result, out) = command("/system/bin/cmd", "settings", "get", "secure", CLIPBOARD_MESSAGE)
        // Never set is the default of Android, which shows it.
        return if (result == 0) 0 to (if (out == "0") "0" else "1") else result to out
    }

    /** Runs a command of the system: an argument list, no shell string, and full paths, since nothing promises a PATH in a process that Shizuku starts. */
    private fun command(vararg args: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            2 to "${args[1]} did not answer"
        } else {
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() == 0) 0 to out.ifEmpty { "ok" } else 3 to out.ifEmpty { "${args[1]} exited with ${process.exitValue()}" }
        }
    } catch (t: Throwable) {
        4 to (t.message ?: t.javaClass.simpleName)
    }

    companion object {
        private const val SHELL_UID = 2000
        const val TX_START = 1
        const val TX_STOP = 2
        const val TX_STATUS = 3
        const val TX_ALLOW_PROJECTION = 4
        const val TX_CLIP_WATCH = 5
        const val TX_CLIP_UNWATCH = 6
        const val TX_CLIP_MESSAGE = 7
        const val TX_WIFI_SAVED = 8
        const val TX_WIFI_PASSWORD = 9
        private const val CLIPBOARD_MESSAGE = "clipboard_show_access_notifications"

        /** The code Shizuku uses to ask a user service to exit. */
        const val TX_DESTROY = 16777115

        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
