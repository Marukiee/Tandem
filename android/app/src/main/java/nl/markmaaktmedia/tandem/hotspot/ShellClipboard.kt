package nl.markmaaktmedia.tandem.hotspot

import android.content.ClipData
import android.content.ClipDescription
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Hears every copy on this phone, in the process Shizuku runs with the shell's identity (see [ShellUserService]). Android only lets the app
 * that is in front read the clipboard; the shell may read it at any time (it holds READ_CLIPBOARD_IN_BACKGROUND, for tests), so Tandem can
 * send on what the person copies when they turned that on. Copies marked sensitive (a password manager marks its passwords so) are left
 * alone. Android then says "Shell pasted from your clipboard"; the person can turn that message off in the privacy settings.
 *
 * The clipboard service is a hidden interface, so everything is reached by reflection: its calls take the package and the user, and since
 * Android 14 an attribution tag and a device as well, which [args] fills in by type. Tried on Android 16 (emulator) with app_process as shell.
 */
internal class ShellClipboard {
    private var listener: Any? = null
    @Volatile private var target: IBinder? = null

    /** Starts telling [to] about every copy, or only changes where it goes. 0 and "ok", or a code and what went wrong. */
    @Synchronized
    fun watch(to: IBinder): Pair<Int, String> {
        target = to
        if (listener != null) return 0 to "ok"
        return try {
            val service = service()
            val raw = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    changed(service)
                    return true
                }
            }
            raw.attachInterface(null, LISTENER)
            val type = Class.forName(LISTENER)
            val made = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> if (method.name == "asBinder") raw else null }
            method(service, "addPrimaryClipChangedListener").let { it.invoke(service, *args(it, made)) }
            listener = made
            0 to "ok"
        } catch (t: Throwable) {
            // Under root the package of the shell is not ours to give; tried only as the shell so far.
            if (Process.myUid() == 0) 2 to ROOT else 1 to describe(t)
        }
    }

    @Synchronized
    fun unwatch() {
        target = null
        val made = listener ?: return
        listener = null
        runCatching {
            val service = service()
            method(service, "removePrimaryClipChangedListener").let { it.invoke(service, *args(it, made)) }
        }
    }

    private fun changed(service: Any) {
        val to = target ?: return
        val text = runCatching { read(service) }.getOrNull() ?: return
        val data = Parcel.obtain()
        try {
            data.writeString(text)
            to.transact(TX_CLIP, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
            // The app is gone: nothing to tell any more.
            unwatch()
        } finally {
            data.recycle()
        }
    }

    private fun read(service: Any): String? {
        val m = method(service, "getPrimaryClip")
        val clip = m.invoke(service, *args(m, null)) as? ClipData ?: return null
        if (clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
    }

    private fun service(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        return Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private fun method(service: Any, name: String): Method = service.javaClass.methods.first { it.name == name }

    /** The arguments of a call, by the types it takes: [first] (a listener) if given, the package of the shell, no tag, this user, the default device. */
    private fun args(method: Method, first: Any?): Array<Any?> {
        val types = method.parameterTypes
        val out = arrayOfNulls<Any>(types.size)
        var start = 0
        if (first != null) {
            out[0] = first
            start = 1
        }
        var named = false
        var users = 0
        for (i in start until types.size) {
            when (types[i]) {
                String::class.java -> { out[i] = if (named) null else PACKAGE; named = true }
                Int::class.javaPrimitiveType -> { out[i] = if (users++ == 0) Process.myUid() / 100_000 else 0 }
                else -> out[i] = null
            }
        }
        return out
    }

    private fun describe(t: Throwable): String {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        return cause.javaClass.simpleName + (cause.message?.let { ": $it" } ?: "")
    }

    companion object {
        /** The call that brings a copy to the app: one string. */
        const val TX_CLIP = 1
        /** What [watch] says when it failed under root, for the app to put in words. */
        const val ROOT = "root"
        private const val LISTENER = "android.content.IOnPrimaryClipChangedListener"
        private const val PACKAGE = "com.android.shell"
        /** A copy longer than this is not text to paste somewhere else. */
        private const val MAX_TEXT = 200_000
    }
}
