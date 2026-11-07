package nl.markmaaktmedia.tandem.quickshare

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import java.util.UUID

/**
 * The Bluetooth call that wakes the Quick Share of phones nearby. A phone that is visible to everyone does not always say so on the
 * network: it starts to (over mDNS, where Tandem looks) when it hears this advertisement, which is what a phone sends while it searches
 * for someone to share with. Without it such a phone is only found when it happens to be searching itself, which is why one that was
 * set to always visible was sometimes not there. Sent only while the Quick Share sheet is open, and never longer than a few minutes.
 *
 * The advertisement is the one Android looks for: service UUID FE2C with fourteen fixed bytes and ten random ones (see docs/QUICKSHARE.md).
 */
class QuickShareWake(private val context: Context) {
    private var advertising = false

    private val callback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "the wake advertisement did not start: $errorCode")
            synchronized(this@QuickShareWake) { advertising = false }
        }
    }

    /** Whether this phone may advertise over Bluetooth. Before Android 12 it needs no permission of its own. */
    fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        if (!allowed()) return
        // One that ran out of time (Android ends it without a word) or still runs is started over: the sheet was opened again.
        stop()
        val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser ?: return
        val data = PREFIX + ByteArray(10).also { SecureRandom().nextBytes(it) }
        val settings = AdvertiseSettings.Builder()
            // Quick and short: the person is looking at the sheet and waiting for the phones to show up.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(MAX_MILLIS)
            .build()
        // Nothing else fits: three bytes of flags and 28 of service data fill the 31 of an advertisement.
        val content = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE, data)
            .build()
        try {
            advertiser.startAdvertising(settings, content, callback)
            advertising = true
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to advertise", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Bluetooth went away", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!advertising) return
        advertising = false
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to stop advertising", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Bluetooth went away", e)
        }
    }

    private companion object {
        const val TAG = "QuickShareWake"
        const val MAX_MILLIS = 180_000
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb"))
        val PREFIX = byteArrayOf(0xFC.toByte(), 0x12, 0x8E.toByte(), 0x01, 0x42, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
