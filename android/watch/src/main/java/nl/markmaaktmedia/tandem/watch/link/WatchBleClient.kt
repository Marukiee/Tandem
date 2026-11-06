package nl.markmaaktmedia.tandem.watch.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import nl.markmaaktmedia.tandem.watch.protocol.GattContract
import java.util.ArrayDeque

/**
 * GATT client that keeps trying until it has the phone. The phone's address changes without bonding, so it scans
 * for the service every time and leaves recognising the phone to the link's own keys. Runs on [handler].
 */
@SuppressLint("MissingPermission") // LinkService only starts after the Bluetooth permissions are granted.
class WatchBleClient(
    private val context: Context,
    private val handler: Handler,
    private val events: Events,
) {
    interface Events {
        fun onLinkUp(maxChunk: Int)
        fun onLinkDown()
        fun onChunk(chunk: ByteArray)

        /** Everything written so far has arrived. A pointer sends its next movement now, so its rate follows the link. */
        fun onSendIdle()

        /** Something else changed on the phone and our service is still there. Only a reply shows the session is too. */
        fun onServicesRefreshed()
        fun onBleLog(line: String)
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)

    private var wanted = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var writable: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var discoveryStarted = false
    private var fast = false
    private var refreshing = false
    private var refreshAttempts = 0
    private var linkUp = false
    private var upSince = 0L
    private var lostAt = 0L
    private var failures = 0

    private val queue = ArrayDeque<ByteArray>()
    private var inFlight = false

    private val attempt = Runnable { startScan() }
    private val retryPump = Runnable { pump() }
    private val refresh = Runnable { rediscover() }
    private val connectTimeout = Runnable {
        events.onBleLog("Connecting took too long")
        teardownAndRetry()
    }
    private val scanCycle = Runnable {
        // A scan that runs for hours is not guaranteed to keep delivering results, so start a fresh one.
        stopScan()
        startScan()
    }

    fun start() {
        if (wanted) return
        wanted = true
        failures = 0
        lostAt = SystemClock.elapsedRealtime()
        schedule(0)
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(attempt)
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(scanCycle)
        stopScan()
        closeGatt()
    }

    /** Drops the connection on purpose; the normal retry brings it back. */
    fun reconnect() {
        if (wanted) teardownAndRetry()
    }

    val isIdle: Boolean get() = linkUp && queue.isEmpty() && !inFlight && !refreshing

    /** A pointer needs short radio intervals, and they cost battery, so only while one is in use. */
    fun setFast(on: Boolean) {
        fast = on
        if (linkUp) applyPriority()
    }

    private fun applyPriority() {
        gatt?.requestConnectionPriority(
            if (fast) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
        )
    }

    fun send(chunk: ByteArray) {
        if (!linkUp) return
        queue.addLast(chunk)
        pump()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(attempt)
        handler.postDelayed(attempt, delayMs)
    }

    private fun startScan() {
        if (!wanted || scanning || gatt != null) return
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            events.onBleLog("Bluetooth is off")
            retryLater()
            return
        }
        val recent = SystemClock.elapsedRealtime() - lostAt < RECENT_MS
        val settings = ScanSettings.Builder()
            // Quick right after losing the phone, when it is most likely still close. Then easy on the battery.
            .setScanMode(if (recent) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattContract.SERVICE)).build()
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        handler.removeCallbacks(scanCycle)
        handler.postDelayed(scanCycle, if (recent) RECENT_MS else SCAN_CYCLE_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanCycle)
        if (!scanning) return
        scanning = false
        // Throws or returns null while Bluetooth is turning off; the scan is gone then anyway.
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        events.onBleLog("Phone found, connecting")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun closeGatt() {
        handler.removeCallbacks(connectTimeout)
        val wasUp = linkUp
        gatt?.let {
            // Closing without disconnecting first leaves the phone waiting for a timeout before it advertises again.
            it.disconnect()
            it.close()
        }
        gatt = null
        writable = null
        mtu = 23
        discoveryStarted = false
        linkUp = false
        handler.removeCallbacks(retryPump)
        handler.removeCallbacks(refresh)
        refreshing = false
        queue.clear()
        inFlight = false
        if (wasUp) {
            lostAt = SystemClock.elapsedRealtime()
            events.onLinkDown()
        }
    }

    private fun teardownAndRetry() {
        val stable = linkUp && SystemClock.elapsedRealtime() - upSince > STABLE_MS
        closeGatt()
        if (!wanted) return
        if (stable) failures = 0
        retryLater()
    }

    private fun retryLater() {
        val delay = BACKOFF_MS[failures.coerceAtMost(BACKOFF_MS.lastIndex)]
        failures++
        events.onBleLog("Next attempt in ${delay / 1000} s")
        schedule(delay)
    }

    private fun pump() {
        if (inFlight || refreshing) return
        val chunk = queue.peekFirst() ?: return
        val client = gatt ?: return
        val characteristic = writable ?: return
        when (val status = client.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
            BluetoothStatusCodes.SUCCESS -> {
                queue.pollFirst()
                inFlight = true
            }
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                handler.removeCallbacks(retryPump)
                handler.postDelayed(retryPump, BUSY_RETRY_MS)
            }
            // Anything else will not get better by trying the same connection again.
            else -> {
                events.onBleLog("Write refused ($status)")
                teardownAndRetry()
            }
        }
    }

    /** A write that is still under way makes the stack refuse this for a moment. */
    private fun rediscover() {
        val client = gatt ?: return
        if (!inFlight && client.discoverServices()) {
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            return
        }
        if (++refreshAttempts > MAX_REFRESH_ATTEMPTS) {
            events.onBleLog("Could not look at the phone's services again")
            teardownAndRetry()
            return
        }
        handler.postDelayed(refresh, REFRESH_RETRY_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { if (scanning) connect(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                handler.removeCallbacks(scanCycle)
                events.onBleLog("Scan failed ($errorCode)")
                if (wanted) retryLater()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (client !== gatt) {
                    client.close()
                    return@post
                }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    client.requestMtu(GattContract.REQUEST_MTU)
                } else {
                    events.onBleLog("Connection ended (status $status)")
                    teardownAndRetry()
                }
            }
        }

        override fun onMtuChanged(client: BluetoothGatt, newMtu: Int, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                // The other side may start an MTU exchange of its own later; that must not set everything up again.
                if (discoveryStarted) return@post
                discoveryStarted = true
                // Some stacks are not ready right after the MTU exchange.
                handler.postDelayed({ if (client === gatt) client.discoverServices() }, DISCOVER_DELAY_MS)
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                val service = client.getService(GattContract.SERVICE)
                val incoming = service?.getCharacteristic(GattContract.PHONE_TO_WATCH)
                val outgoing = service?.getCharacteristic(GattContract.WATCH_TO_PHONE)
                val config = incoming?.getDescriptor(GattContract.CLIENT_CONFIG)
                if (status != BluetoothGatt.GATT_SUCCESS || outgoing == null || config == null) {
                    events.onBleLog("Service not found (status $status)")
                    teardownAndRetry()
                    return@post
                }
                writable = outgoing
                client.setCharacteristicNotification(incoming, true)
                val status = client.writeDescriptor(config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (status != BluetoothStatusCodes.SUCCESS) {
                    events.onBleLog("Subscribe refused ($status)")
                    teardownAndRetry()
                }
            }
        }

        /**
         * The services of the whole phone changed. Tandem's app stopping or restarting is one cause, and the Bluetooth
         * connection survives that, so this is the only sign of it. But any app adding a service causes this too, and
         * that must not drop the link: so look whether ours is still there, and let the caller test the session.
         */
        override fun onServiceChanged(client: BluetoothGatt) {
            handler.post {
                if (client !== gatt) return@post
                if (!linkUp) {
                    events.onBleLog("Services changed while connecting, starting over")
                    teardownAndRetry()
                    return@post
                }
                events.onBleLog("Services on the phone changed, looking again")
                refreshing = true
                refreshAttempts = 0
                handler.removeCallbacks(refresh)
                rediscover()
            }
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    events.onBleLog("Subscribe failed (status $status)")
                    teardownAndRetry()
                    return@post
                }
                handler.removeCallbacks(connectTimeout)
                if (refreshing) {
                    refreshing = false
                    events.onServicesRefreshed()
                    pump()
                    return@post
                }
                linkUp = true
                upSince = SystemClock.elapsedRealtime()
                if (fast) applyPriority()
                events.onLinkUp(GattContract.chunkSize(mtu))
            }
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            handler.post { if (client === gatt && linkUp) events.onChunk(copy) }
        }

        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                inFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    events.onBleLog("Write failed (status $status)")
                    teardownAndRetry()
                    return@post
                }
                pump()
                if (isIdle) events.onSendIdle()
            }
        }
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_500, 2_000, 4_000, 8_000, 16_000, 30_000)
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DISCOVER_DELAY_MS = 300L
        const val BUSY_RETRY_MS = 30L
        const val REFRESH_RETRY_MS = 200L
        const val MAX_REFRESH_ATTEMPTS = 25
        const val STABLE_MS = 30_000L
        const val RECENT_MS = 2 * 60_000L
        const val SCAN_CYCLE_MS = 10 * 60_000L
    }
}
