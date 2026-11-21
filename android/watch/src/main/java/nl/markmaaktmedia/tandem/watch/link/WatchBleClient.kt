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
import nl.markmaaktmedia.tandem.watch.power.PowerLog
import nl.markmaaktmedia.tandem.watch.protocol.GattContract
import nl.markmaaktmedia.tandem.watch.protocol.LinkPriorityRules
import nl.markmaaktmedia.tandem.watch.protocol.ScanPolicy
import nl.markmaaktmedia.tandem.watch.protocol.Span
import nl.markmaaktmedia.tandem.watch.protocol.Tick
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
    private val priority = LinkPriorityRules()

    /** Waiting out the rest between two looks for a phone that has been gone for a long time, see [ScanPolicy.restMs]. */
    private var resting = false
    private var lastScanStartMs = 0L
    private var refreshing = false
    private var refreshAttempts = 0
    private var linkUp = false
    private var upSince = 0L
    private var lostAt = 0L

    /** The [lostAt] of the last loss that got the fast minute of scanning, see [ScanPolicy]. */
    private var fastFor: Long? = null
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
    private val reevaluate = Runnable { evaluatePriority() }
    private val scanCycle = Runnable {
        // A scan that runs for hours is not guaranteed to keep delivering results, so start a fresh one. For a phone that has been gone
        // a long time the next look waits.
        stopScan()
        val rest = ScanPolicy.restMs(lostAt, SystemClock.elapsedRealtime())
        if (rest == null) {
            startScan()
        } else {
            if (!resting) events.onBleLog("The phone has been gone for a long time: looking for ${ScanPolicy.LOOK_MS / 1000} s every ${(ScanPolicy.LOOK_MS + rest) / 1000} s")
            resting = true
            schedule(rest)
        }
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
        resting = false
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
        priority.fast(on)
        evaluatePriority()
    }

    /**
     * The screen went on or off. A link that has nothing to do rests on low power with the screen off, and is balanced while someone looks
     * at the watch. A watch that stopped looking for a phone that is long gone looks again at once: this may be the person who has it.
     */
    fun setScreen(on: Boolean) {
        val now = SystemClock.elapsedRealtime()
        priority.screen(on, now)
        evaluatePriority()
        if (on && wanted && resting && !scanning && gatt == null && now - lastScanStartMs >= ScanPolicy.NUDGE_EVERY_MS) schedule(0)
    }

    /** Asks the link for what the rules say it should have now, and sets itself a time to look again when only time can change that. */
    private fun evaluatePriority() {
        handler.removeCallbacks(reevaluate)
        val client = gatt ?: return
        if (!linkUp) return
        val now = SystemClock.elapsedRealtime()
        priority.change(now)?.let { level ->
            val taken = client.requestConnectionPriority(
                when (level) {
                    LinkPriorityRules.Level.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    LinkPriorityRules.Level.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                    LinkPriorityRules.Level.LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                },
            )
            // A stack that is busy says no, and the next event asks again.
            if (taken) {
                priority.applied(level)
                PowerLog.count(Tick.PRIORITY)
                PowerLog.set(Span.LOW_PRIORITY, level == LinkPriorityRules.Level.LOW_POWER)
                events.onBleLog("Link priority: $level")
            }
        }
        priority.checkInMs(now)?.takeIf { it > 0 }?.let { handler.postDelayed(reevaluate, it) }
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
        val now = SystemClock.elapsedRealtime()
        val speed = ScanPolicy.speed(lostAt, now, fastFor)
        if (speed == ScanPolicy.Speed.LOW_LATENCY) fastFor = lostAt
        resting = false
        lastScanStartMs = now
        val settings = ScanSettings.Builder()
            // Fastest in the first minute after losing the phone: that is mostly Tandem on the phone restarting for an update, and then
            // it advertises again within seconds, while the slower scans found it only well after. Then quick while it is most likely
            // still close, then easy on the battery. A link that keeps dropping does not get that first minute every time.
            .setScanMode(
                when (speed) {
                    ScanPolicy.Speed.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                    ScanPolicy.Speed.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                    ScanPolicy.Speed.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                },
            )
            .build()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattContract.SERVICE)).build()
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        PowerLog.count(Tick.SCAN)
        PowerLog.open(
            when (speed) {
                ScanPolicy.Speed.LOW_LATENCY -> Span.SCAN_FAST
                ScanPolicy.Speed.BALANCED -> Span.SCAN_BALANCED
                ScanPolicy.Speed.LOW_POWER -> Span.SCAN_LOW
            },
        )
        handler.removeCallbacks(scanCycle)
        // A fresh scan where the mode changes, once in a while, and where the long absence of the phone begins.
        handler.postDelayed(scanCycle, ScanPolicy.stintMs(lostAt, now, fastFor))
    }

    private fun stopScan() {
        handler.removeCallbacks(scanCycle)
        if (!scanning) return
        scanning = false
        closeScanSpans()
        // Throws or returns null while Bluetooth is turning off; the scan is gone then anyway.
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /** A chunk went over the link. A link that rests on low power is balanced when they come in a burst, and stays so while it lasts. */
    private fun noteTraffic() {
        priority.traffic(SystemClock.elapsedRealtime())
        if (priority.applied == LinkPriorityRules.Level.LOW_POWER) evaluatePriority()
    }

    private fun closeScanSpans() {
        PowerLog.close(Span.SCAN_FAST)
        PowerLog.close(Span.SCAN_BALANCED)
        PowerLog.close(Span.SCAN_LOW)
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
        handler.removeCallbacks(reevaluate)
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
            PowerLog.close(Span.LINK)
            PowerLog.close(Span.LOW_PRIORITY)
            events.onLinkDown()
        }
    }

    private fun teardownAndRetry() {
        val stable = linkUp && SystemClock.elapsedRealtime() - upSince > ScanPolicy.STABLE_MS
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
                PowerLog.count(Tick.BLE_OUT)
                PowerLog.count(Tick.BYTES_OUT, chunk.size.toLong())
                noteTraffic()
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
                closeScanSpans()
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
                    if (linkUp) {
                        PowerLog.count(Tick.DROP)
                        priority.linkDown(SystemClock.elapsedRealtime())
                    }
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
                PowerLog.open(Span.LINK)
                PowerLog.count(Tick.CONNECT)
                priority.linkUp(upSince)
                evaluatePriority()
                events.onLinkUp(GattContract.chunkSize(mtu))
            }
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            PowerLog.count(Tick.BLE_IN)
            PowerLog.count(Tick.BYTES_IN, copy.size.toLong())
            handler.post {
                if (client === gatt && linkUp) {
                    noteTraffic()
                    events.onChunk(copy)
                }
            }
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
        val BACKOFF_MS = longArrayOf(500, 1_500, 3_000, 6_000, 12_000, 30_000)
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DISCOVER_DELAY_MS = 300L
        const val BUSY_RETRY_MS = 30L
        const val REFRESH_RETRY_MS = 200L
        const val MAX_REFRESH_ATTEMPTS = 25
    }
}
