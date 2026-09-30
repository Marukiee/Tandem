package nl.markmaaktmedia.tandem.hotspot

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.Looper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * Starts and stops Wi-Fi tethering through the system's TetheringManager.
 *
 * This runs in a process with the shell's identity, which Shizuku provides (see
 * [HotspotUserService]). The shell holds TETHER_PRIVILEGED, an app never does, and that
 * is the only reason this can work without root. TetheringManager is a system API, so
 * everything is reached by reflection, and the two names its request class has had
 * (nested up to Android 15, top level after) are both tried.
 *
 * Nothing here may touch [android.app.Application] state: the process is not an app.
 */
internal class ShellTethering {

    class Outcome(val code: Int, val message: String) {
        val ok: Boolean get() = code == 0
    }

    class Status(val clients: Int, val tethered: Int)

    @Volatile private var clients = -1
    @Volatile private var tethered = -1
    private var manager: Any? = null
    private var listening = false
    private val executor = Executor { it.run() }

    @Synchronized
    fun start(): Outcome {
        val manager = try {
            manager()
        } catch (t: Throwable) {
            return Outcome(ERR_NO_MANAGER, "no tethering service: ${describe(t)}")
        }
        runCatching { listen(manager) }

        // Carrier provisioning is skipped where the shell may skip it, and asked for
        // again with a plain request where it may not.
        val first = tryStart(manager, exempt = true)
        return if (first.code == TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION) tryStart(manager, exempt = false) else first
    }

    @Synchronized
    fun stop(): Outcome {
        val manager = try {
            manager()
        } catch (t: Throwable) {
            return Outcome(ERR_NO_MANAGER, "no tethering service: ${describe(t)}")
        }
        return try {
            manager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, TETHERING_WIFI)
            Outcome(0, "stopped")
        } catch (t: Throwable) {
            Outcome(ERR_REFLECTION, "could not stop: ${describe(t)}")
        }
    }

    @Synchronized
    fun status(): Status {
        runCatching { listen(manager()) }
        return Status(clients, tethered)
    }

    // ---- Starting --------------------------------------------------------------

    private fun tryStart(manager: Any, exempt: Boolean): Outcome {
        val callbackClass = try {
            Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        } catch (t: Throwable) {
            return Outcome(ERR_REFLECTION, "no callback type: ${describe(t)}")
        }

        val latch = CountDownLatch(1)
        val result = AtomicInteger(PENDING)
        val callback = proxy(callbackClass) { name, args ->
            when (name) {
                "onTetheringStarted" -> {
                    result.set(0)
                    latch.countDown()
                }
                "onTetheringFailed" -> {
                    result.set((args?.firstOrNull() as? Int) ?: ERR_REFLECTION)
                    latch.countDown()
                }
            }
        }

        try {
            val request = buildRequest(exempt)
            val method = manager.javaClass.methods.firstOrNull { m ->
                m.name == "startTethering" && m.parameterTypes.size == 3 &&
                    request != null && m.parameterTypes[0].isInstance(request)
            }
            if (method != null) {
                method.invoke(manager, request, executor, callback)
            } else {
                // The older, shorter form. It asks for carrier provisioning itself.
                manager.javaClass
                    .getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, callbackClass)
                    .invoke(manager, TETHERING_WIFI, executor, callback)
            }
        } catch (t: Throwable) {
            return Outcome(ERR_REFLECTION, "could not start: ${describe(t)}")
        }

        if (!latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return Outcome(ERR_TIMEOUT, "the tethering service did not answer")
        }
        val code = result.get()
        return Outcome(code, if (code == 0) "started" else messageFor(code))
    }

    private fun buildRequest(exempt: Boolean): Any? {
        for (name in listOf("android.net.TetheringManager\$TetheringRequest\$Builder", "android.net.TetheringRequest\$Builder")) {
            try {
                val builderClass = Class.forName(name)
                val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
                runCatching { builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(builder, false) }
                if (exempt) {
                    runCatching { builderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType).invoke(builder, true) }
                }
                return builderClass.getMethod("build").invoke(builder)
            } catch (_: Throwable) {
                // The other spelling, or the short form below.
            }
        }
        return null
    }

    // ---- Watching --------------------------------------------------------------

    /** Keeps a running count of connected devices, which nothing else can tell us. */
    private fun listen(manager: Any) {
        if (listening) return
        val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        val callback = proxy(callbackClass) { name, args ->
            when (name) {
                "onClientsChanged" -> clients = (args?.firstOrNull() as? Collection<*>)?.size ?: -1
                "onTetheredInterfacesChanged" -> tethered = (args?.firstOrNull() as? Collection<*>)?.size ?: -1
            }
        }
        manager.javaClass
            .getMethod("registerTetheringEventCallback", Executor::class.java, callbackClass)
            .invoke(manager, executor, callback)
        listening = true
    }

    // ---- Plumbing --------------------------------------------------------------

    private fun manager(): Any {
        manager?.let { return it }
        val base = systemContext()
        // The tethering service checks that the calling package belongs to the calling uid.
        // The system context calls itself "android", which is not the shell's package, so the
        // manager is built by hand with the name the shell really has.
        val created = shellManager(base) ?: base.getSystemService("tethering") ?: error("this device has no tethering service")
        manager = created
        return created
    }

    private fun shellManager(base: Context): Any? = runCatching {
        val wrapper = object : ContextWrapper(base) {
            override fun getOpPackageName(): String = SHELL_PACKAGE
            override fun getPackageName(): String = SHELL_PACKAGE
            override fun getAttributionTag(): String? = null
        }
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "tethering") as? IBinder
            ?: return@runCatching null
        Class.forName("android.net.TetheringManager")
            .getConstructor(Context::class.java, Supplier::class.java)
            .newInstance(wrapper, Supplier { binder })
    }.getOrNull()

    private fun systemContext(): Context {
        // ActivityThread wants a main looper to exist. Shizuku's process already has one;
        // a plain app_process may not, and preparing it twice throws.
        if (Looper.getMainLooper() == null) runCatching { Looper.prepareMainLooper() }
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            ?: threadClass.getMethod("systemMain").invoke(null)
        return threadClass.getMethod("getSystemContext").invoke(thread) as Context
    }

    private fun proxy(type: Class<*>, on: (name: String, args: Array<Any?>?) -> Unit): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ShellTethering.callback"
                else -> {
                    on(method.name, args)
                    null
                }
            }
        }
        val loader = type.classLoader ?: ClassLoader.getSystemClassLoader()
        return Proxy.newProxyInstance(loader, arrayOf(type), handler)
    }

    private fun describe(t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.targetException ?: t else t
        return "${cause.javaClass.simpleName}: ${cause.message}".take(200)
    }

    companion object {
        /** android.net.TetheringManager.TETHERING_WIFI. It is 0: 1 is USB, which is what this asked for at first. */
        const val TETHERING_WIFI = 0
        const val SHELL_PACKAGE = "com.android.shell"
        const val START_TIMEOUT_SECONDS = 15L

        // Ours, all below zero so they cannot clash with the system's error codes.
        const val ERR_NO_MANAGER = -1
        const val ERR_REFLECTION = -2
        const val ERR_TIMEOUT = -3
        private const val PENDING = Int.MIN_VALUE

        const val TETHER_ERROR_PROVISIONING_FAILED = 11
        const val TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION = 14
        const val TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION = 15

        fun messageFor(code: Int): String = when (code) {
            TETHER_ERROR_PROVISIONING_FAILED -> "the carrier refused tethering (code $code)"
            TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION, TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION ->
                "not allowed to change tethering (code $code)"
            else -> "tethering error $code"
        }
    }
}

/**
 * Lets the shell run the same code without Shizuku, for checking a device from a computer:
 * `CLASSPATH=<apk> app_process / nl.markmaaktmedia.tandem.hotspot.ShellTetheringCli start|stop|status`.
 */
object ShellTetheringCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val tethering = ShellTethering()
        when (args.firstOrNull()) {
            "stop" -> tethering.stop().let { println("stop: ${it.code} ${it.message}") }
            "status" -> tethering.status().let { println("clients=${it.clients} tethered=${it.tethered}") }
            else -> tethering.start().let { println("start: ${it.code} ${it.message}") }
        }
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }
}
