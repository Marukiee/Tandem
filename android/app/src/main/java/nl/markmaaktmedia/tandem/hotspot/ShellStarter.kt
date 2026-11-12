package nl.markmaaktmedia.tandem.hotspot

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import kotlin.system.exitProcess

/**
 * Runs [ShellUserService] with the shell's identity in a process of its own, and hands its binder to Tandem through
 * [ShellBinderProvider]. Shizuku starts this as a plain command ([ShizukuBridge] asks for it with `newProcess`), which goes straight to
 * Shizuku's server. Shizuku's own way of starting a service goes through the Shizuku app as well, and that breaks where the Shizuku
 * app cannot start properly: ColorOS 17 with Shizuku 13.6 (seen on an Oppo Find X9 Ultra, 2026-11-12).
 *
 * `app_process -Djava.class.path=<apk> /system/bin <this class> <package> <user>`
 */
object ShellStarter {
    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0) ?: exitProcess(2)
        val user = args.getOrNull(1)?.toIntOrNull() ?: 0
        Looper.prepareMainLooper()
        // Made first: as root it becomes the shell before anything else (see ShellUserService).
        val service = ShellUserService()
        val client = runCatching { deliver(pkg, user, service) }.getOrNull()
        if (client == null) {
            System.err.println("Tandem could not be reached")
            exitProcess(1)
        }
        // Tandem gone: so is this. Shizuku also ends it with the app, this is in case it does not.
        runCatching { client.linkToDeath({ exitProcess(0) }, 0) }.onFailure { exitProcess(0) }
        Looper.loop()
    }

    /** Calls Tandem's [ShellBinderProvider] with the binder of [service], as Shizuku hands its own binder to an app. */
    private fun deliver(pkg: String, user: Int, service: IBinder): IBinder? {
        val authority = "$pkg.${ShellBinderProvider.AUTHORITY_SUFFIX}"
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val acquire = activityManager.javaClass.methods.first { it.name == "getContentProviderExternal" }
        val holder = (if (acquire.parameterTypes.size == 4) acquire.invoke(activityManager, authority, user, token, null) else acquire.invoke(activityManager, authority, user, token))
            ?: return null
        val provider = holder.javaClass.getField("provider").get(holder) ?: return null
        try {
            val extras = Bundle().apply { putBinder(ShellBinderProvider.EXTRA_SERVICE, service) }
            val call = provider.javaClass.methods.filter { it.name == "call" }.maxBy { it.parameterTypes.size }
            val types = call.parameterTypes
            val method = ShellBinderProvider.METHOD
            val arguments: Array<Any?> = when {
                types.firstOrNull() == AttributionSource::class.java ->
                    arrayOf(AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build(), authority, method, null, extras)
                types.size == 6 -> arrayOf(SHELL_PACKAGE, null, authority, method, null, extras)
                else -> arrayOf(SHELL_PACKAGE, authority, method, null, extras)
            }
            val reply = call.invoke(provider, *arguments) as? Bundle
            return reply?.getBinder(ShellBinderProvider.EXTRA_CLIENT)
        } finally {
            runCatching {
                val release = activityManager.javaClass.methods.firstOrNull { it.name == "removeContentProviderExternalAsUser" }
                if (release != null) release.invoke(activityManager, authority, token, user)
                else activityManager.javaClass.methods.first { it.name == "removeContentProviderExternal" }.invoke(activityManager, authority, token)
            }
        }
    }

    private const val SHELL_PACKAGE = "com.android.shell"
}
