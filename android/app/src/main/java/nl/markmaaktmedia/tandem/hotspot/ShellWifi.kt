package nl.markmaaktmedia.tandem.hotspot

import android.content.AttributionSource
import android.os.Bundle
import android.os.IBinder

/**
 * The networks this phone has saved, and its own hotspot, with their passwords, read as the shell (see [ShellUserService]). The shell holds
 * READ_WIFI_CREDENTIAL, which no app gets: it is what lets a phone hand its Wi-Fi to a computer. IWifiManager is hidden, so it is
 * reached by reflection, and since Android 13 the call wants to know who asks, in a Bundle.
 *
 * Nothing here may touch [android.app.Application] state: the process is not an app.
 */
internal class ShellWifi {
    class Saved(val ssid: String, val password: String?)

    /** [security] is a `SoftApConfiguration.SECURITY_TYPE_*`. */
    class Hotspot(val ssid: String, val security: Int, val hidden: Boolean, val password: String?)

    fun saved(): List<Saved> {
        val wifi = wifiService()
        val method = wifi.javaClass.methods.first { it.name == "getPrivilegedConfiguredNetworks" }
        val slice = method.invoke(wifi, *arguments(method)) ?: error("the system gave no networks")
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
        // Android keeps a network once for each kind of security it takes (WPA2 and WPA3 of the same name), with the same password.
        return list.filterNotNull().mapNotNull { config ->
            val ssid = unquote(config.javaClass.getField("SSID").get(config) as String?) ?: return@mapNotNull null
            Saved(ssid, unquote(config.javaClass.getField("preSharedKey").get(config) as String?))
        }.distinctBy { it.ssid }
    }

    /**
     * The hotspot of this phone itself: its name, the kind of security and the password. No app may read it (`getSoftApConfiguration`
     * is a system API), the shell may: it holds NETWORK_SETTINGS. Reached the same way as the saved networks.
     */
    fun hotspot(): Hotspot {
        val wifi = wifiService()
        val method = wifi.javaClass.methods.first { it.name == "getSoftApConfiguration" }
        val config = method.invoke(wifi, *arguments(method)) ?: error("the system gave no hotspot")
        val type = config.javaClass
        // getSsid is deprecated since Android 13 but still there; getWifiSsid is its successor and prints the name in quotes.
        val ssid = unquote(runCatching { type.getMethod("getSsid").invoke(config) as String? }.getOrNull())
            ?: unquote(runCatching { type.getMethod("getWifiSsid").invoke(config)?.toString() }.getOrNull())
            ?: error("the hotspot has no name")
        val security = type.getMethod("getSecurityType").invoke(config) as Int
        val hidden = runCatching { type.getMethod("isHiddenSsid").invoke(config) as Boolean }.getOrDefault(false)
        val passphrase = runCatching { type.getMethod("getPassphrase").invoke(config) as String? }.getOrNull()
        return Hotspot(ssid, security, hidden, passphrase)
    }

    private fun wifiService(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "wifi") as IBinder
        return Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /** Since Android 13 these calls want to know who asks, in a Bundle; older ones a package name. */
    private fun arguments(method: java.lang.reflect.Method): Array<Any?> {
        val asker = Bundle().apply {
            putParcelable(ATTRIBUTION, AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build())
        }
        return method.parameterTypes.mapIndexed { index, type ->
            when {
                type == Bundle::class.java -> asker
                type == String::class.java && index == 0 -> SHELL_PACKAGE
                else -> null
            }
        }.toTypedArray()
    }

    companion object {
        private const val ATTRIBUTION = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"
        private const val SHELL_UID = 2000
        private const val SHELL_PACKAGE = "com.android.shell"

        /** A name or a passphrase comes in quotes; a key of 64 hex digits and a name in hex do not. */
        fun unquote(raw: String?): String? = raw?.takeIf { it.isNotEmpty() && it != "*" }?.removeSurrounding("\"")
    }
}
