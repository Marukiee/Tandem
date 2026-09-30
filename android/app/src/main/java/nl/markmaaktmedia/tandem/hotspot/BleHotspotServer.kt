package nl.markmaaktmedia.tandem.hotspot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import uniffi.tandem_core.tandemBleHint

/**
 * Makes this phone findable and answerable over Bluetooth Low Energy while it is set to
 * share its hotspot with the Mac. The Mac has no network to reach us on, so this is the
 * only door. It only ever runs with the hotspot preference on and both Bluetooth
 * permissions granted, and [HotspotModule] decides that.
 *
 * The phone is the GATT server and the advertiser, the Mac the central. The exchange
 * is in docs/HOTSPOT.md.
 */
@SuppressLint("MissingPermission")
class BleHotspotServer(
    private val context: Context,
    private val auth: HotspotAuth,
    private val challenges: ChallengeStore,
    private val myId: () -> String,
    private val currentState: () -> Pair<BleState, Int?>,
    /** Called for a request that passed the signature check. */
    private val onRequest: (action: Int, deviceId: String) -> Unit,
) {
    private class Peer(val device: BluetoothDevice) {
        var subscribed = false
        var pendingWrite: ByteArray? = null
        val outbox = ArrayDeque<ByteArray>()
        var sending = false
    }

    private val lock = Any()
    private val peers = HashMap<String, Peer>()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var advertisedHour = -1L

    @Volatile
    var running = false
        private set

    /** True when Bluetooth is on and this phone can advertise at all. */
    fun start(): Boolean {
        synchronized(lock) {
            if (running) return true
            val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
            val adapter = manager.adapter ?: return false
            if (!adapter.isEnabled || adapter.bluetoothLeAdvertiser == null) return false
            val opened = try {
                manager.openGattServer(context, callback)
            } catch (t: SecurityException) {
                Log.w(TAG, "no Bluetooth permission", t)
                null
            } ?: return false

            val service = BluetoothGattService(HotspotProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(HotspotProtocol.CHALLENGE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(HotspotProtocol.REQUEST, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE),
            )
            val state = BluetoothGattCharacteristic(
                HotspotProtocol.STATE,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            state.addDescriptor(
                BluetoothGattDescriptor(HotspotProtocol.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
            )
            service.addCharacteristic(state)

            server = opened
            stateCharacteristic = state
            advertiser = adapter.bluetoothLeAdvertiser
            running = true
            // Advertising starts in onServiceAdded, once the service is really there.
            if (!opened.addService(service)) {
                Log.w(TAG, "could not add the GATT service")
                stopLocked()
                return false
            }
            return true
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        if (advertising) runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        runCatching { server?.close() }
        server = null
        advertiser = null
        stateCharacteristic = null
        peers.clear()
        running = false
        advertisedHour = -1
    }

    /**
     * The advertisement carries a tag that changes every hour, so it has to be rebuilt
     * when the hour turns. Cheap to call often.
     */
    fun refreshAdvertisement() {
        synchronized(lock) {
            if (!running || !advertising) return
            if (currentHour() == advertisedHour) return
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            advertising = false
            startAdvertisingLocked()
        }
    }

    /** Pushes the state to every Mac that is listening. */
    fun publish(state: BleState, clients: Int?) {
        val bytes = StateCodec.encode(state, clients)
        synchronized(lock) {
            if (!running) return
            for (peer in peers.values) if (peer.subscribed) enqueueLocked(peer, bytes)
        }
    }

    // ---- Advertising -----------------------------------------------------------

    private fun currentHour(): Long = System.currentTimeMillis() / 3_600_000L

    private fun startAdvertisingLocked() {
        val advertiser = advertiser ?: return
        val id = myId()
        if (id.isEmpty()) return
        val hour = currentHour()
        val hint = tandemBleHint(id, hour.toULong())

        val settings = AdvertiseSettings.Builder()
            // A request is rare and the person is waiting anyway. A slow beacon costs
            // almost nothing all day; a fast one is a battery complaint.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // 3 bytes of flags and 18 for the UUID leave no room for the tag, so the tag
        // rides in the scan response.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(HotspotProtocol.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(HotspotProtocol.SERVICE), hint)
            .build()
        advertisedHour = hour
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            synchronized(lock) { advertising = true }
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
            // Already started counts as started.
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) synchronized(lock) { advertising = true }
        }
    }

    // ---- GATT ------------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            synchronized(lock) {
                if (!running) return
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertisingLocked() else Log.w(TAG, "service not added: $status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(lock) {
                if (!running) return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    peers[device.address] = Peer(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    peers.remove(device.address)
                    challenges.forget(device.address)
                    // Some phones stop advertising when a central connects.
                    if (!advertising) startAdvertisingLocked()
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value: ByteArray? = when (characteristic.uuid) {
                // A fresh challenge per read. A continued read (offset above zero) never
                // happens for 16 bytes and must not mint another one.
                HotspotProtocol.CHALLENGE -> if (offset == 0) challenges.issue(device.address) else null
                HotspotProtocol.STATE -> currentState().let { StateCodec.encode(it.first, it.second) }
                else -> null
            }
            if (value == null || offset > value.size) {
                reply(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                reply(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid != HotspotProtocol.REQUEST || value == null) {
                if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            if (preparedWrite) {
                // A long write: the Mac sent it in pieces because Bluetooth had not
                // agreed on a bigger packet yet. Collect the pieces until the execute.
                val ok = synchronized(lock) {
                    val peer = peers[device.address] ?: return@synchronized false
                    val old = peer.pendingWrite ?: ByteArray(0)
                    if (offset > old.size || offset + value.size > MAX_REQUEST_BYTES) return@synchronized false
                    val merged = old.copyOf(maxOf(old.size, offset + value.size))
                    value.copyInto(merged, offset)
                    peer.pendingWrite = merged
                    true
                }
                if (responseNeeded) {
                    reply(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset, value)
                }
                return
            }
            handleRequest(device, requestId, value, responseNeeded)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val bytes = synchronized(lock) {
                peers[device.address]?.let { peer -> peer.pendingWrite.also { peer.pendingWrite = null } }
            }
            if (!execute || bytes == null) {
                reply(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }
            handleRequest(device, requestId, bytes, true)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid != HotspotProtocol.CCCD || value == null) {
                if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            synchronized(lock) {
                peers[device.address]?.subscribed = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val on = synchronized(lock) { peers[device.address]?.subscribed == true }
            val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            reply(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                val peer = peers[device.address] ?: return
                peer.sending = false
                sendNextLocked(peer)
            }
        }
    }

    private fun handleRequest(device: BluetoothDevice, requestId: Int, bytes: ByteArray, responseNeeded: Boolean) {
        when (val result = auth.check(device.address, bytes)) {
            is AuthResult.Accepted -> {
                if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                onRequest(result.request.action, result.request.deviceId)
                // Also tell this Mac where things stand right now: if nothing changes
                // (the hotspot was already on) no other state would ever be sent.
                val (state, clients) = currentState()
                synchronized(lock) { peers[device.address]?.let { enqueueLocked(it, StateCodec.encode(state, clients)) } }
            }
            AuthResult.Blocked -> {
                if (responseNeeded) reply(device, requestId, HotspotProtocol.ATT_REFUSED, 0, null)
                runCatching { server?.cancelConnection(device) }
            }
            AuthResult.Malformed, AuthResult.NoChallenge, AuthResult.BadSignature -> {
                if (responseNeeded) reply(device, requestId, HotspotProtocol.ATT_REFUSED, 0, null)
                Log.i(TAG, "request refused: $result")
                synchronized(lock) { peers[device.address]?.let { enqueueLocked(it, StateCodec.encode(BleState.RefusedAuth, null)) } }
            }
        }
    }

    private fun reply(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        runCatching { server?.sendResponse(device, requestId, status, offset, value) }
            .onFailure { Log.w(TAG, "could not answer $device", it) }
    }

    // ---- Notifications, one at a time per central ------------------------------

    private fun enqueueLocked(peer: Peer, bytes: ByteArray) {
        // Only the latest state matters to a person watching a progress line.
        while (peer.outbox.size >= 4) peer.outbox.removeFirst()
        peer.outbox.addLast(bytes)
        if (!peer.sending) sendNextLocked(peer)
    }

    private fun sendNextLocked(peer: Peer) {
        val server = server ?: return
        val characteristic = stateCharacteristic ?: return
        val next = peer.outbox.removeFirstOrNull() ?: return
        peer.sending = true
        val started = try {
            if (Build.VERSION.SDK_INT >= 33) {
                server.notifyCharacteristicChanged(peer.device, characteristic, false, next) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = next
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(peer.device, characteristic, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
            false
        }
        // No callback will come for a notification that never left.
        if (!started) peer.sending = false
    }

    companion object {
        private const val TAG = "BleHotspotServer"
        private const val MAX_REQUEST_BYTES = 256
    }
}
