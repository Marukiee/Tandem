package nl.markmaaktmedia.tandem.hotspot

import android.os.Binder
import android.os.Looper
import android.os.Parcel

/**
 * Debug builds only. Runs [ShellClipboard] the way Shizuku runs it, as the shell, and prints every copy it hears:
 *
 *     adb shell 'CLASSPATH=$(pm path nl.markmaaktmedia.tandem.debug | cut -d: -f2) app_process /system/bin nl.markmaaktmedia.tandem.hotspot.ShellClipboardCheck'
 */
object ShellClipboardCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val printer = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                println("copied: ${data.readString()}")
                return true
            }
        }
        println("watch: ${ShellClipboard().watch(printer)}")
        Looper.loop()
    }
}
