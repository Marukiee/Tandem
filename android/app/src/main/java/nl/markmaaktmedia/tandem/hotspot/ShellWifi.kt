package nl.markmaaktmedia.tandem.hotspot

import android.content.AttributionSource
import android.os.Bundle
import android.os.IBinder

/**
 * The networks this phone has saved, with their passwords, read as the shell (see [ShellUserService]). The shell holds
 * READ_WIFI_CREDENTIAL, which no app gets: it is what lets a phone hand its Wi-Fi to a computer. IWifiManager is hidden, so it is
 * reached by reflection, and since Android 13 the call wants to know who asks, in a Bundle.
 *
 * Nothing here may touch [android.app.Application] state: the process is not an app.
 */
internal class ShellWifi {
    class Saved(val ssid: String, val password: String?)

    fun saved(): List<Saved> {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "wifi") as IBinder
        val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val method = wifi.javaClass.methods.first { it.name == "getPrivilegedConfiguredNetworks" }
        val asker = Bundle().apply {
            putParcelable(ATTRIBUTION, AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build())
        }
        val args = method.parameterTypes.mapIndexed { index, type ->
            when {
                type == Bundle::class.java -> asker
                type == String::class.java && index == 0 -> SHELL_PACKAGE
                else -> null
            }
        }
        val slice = method.invoke(wifi, *args.toTypedArray()) ?: error("the system gave no networks")
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
        // Android keeps a network once for each kind of security it takes (WPA2 and WPA3 of the same name), with the same password.
        return list.filterNotNull().mapNotNull { config ->
            val ssid = unquote(config.javaClass.getField("SSID").get(config) as String?) ?: return@mapNotNull null
            Saved(ssid, unquote(config.javaClass.getField("preSharedKey").get(config) as String?))
        }.distinctBy { it.ssid }
    }

    companion object {
        private const val ATTRIBUTION = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"
        private const val SHELL_UID = 2000
        private const val SHELL_PACKAGE = "com.android.shell"

        /** A name or a passphrase comes in quotes; a key of 64 hex digits and a name in hex do not. */
        fun unquote(raw: String?): String? = raw?.takeIf { it.isNotEmpty() && it != "*" }?.removeSurrounding("\"")
    }
}
