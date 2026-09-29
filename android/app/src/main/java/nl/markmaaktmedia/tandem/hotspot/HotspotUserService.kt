package nl.markmaaktmedia.tandem.hotspot

import android.os.Binder
import android.os.Parcel
import kotlin.system.exitProcess

/**
 * The part of Tandem that Shizuku runs with the shell's identity, in its own process.
 *
 * It is a bare Binder instead of an AIDL service: three calls do not justify the AIDL
 * toolchain, and Shizuku only asks for an IBinder. Shizuku creates it through the
 * no-argument constructor, so R8 must keep that (see proguard-rules.pro).
 */
class HotspotUserService : Binder() {
    private val tethering = ShellTethering()

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

    companion object {
        const val TX_START = 1
        const val TX_STOP = 2
        const val TX_STATUS = 3

        /** The code Shizuku uses to ask a user service to exit. */
        const val TX_DESTROY = 16777115
    }
}
