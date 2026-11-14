package nl.markmaaktmedia.tandem.watch

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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import nl.markmaaktmedia.tandem.watch.protocol.GattContract
import java.util.ArrayDeque

/**
 * GATT server for one watch. Advertises only while no watch is connected and sends one notification at
 * a time, waiting for the stack's callback. Everything runs on [handler]; Binder callbacks are posted there.
 */
@SuppressLint("MissingPermission") // WatchBridge only starts this after the Bluetooth permissions are granted.
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PhoneBleServer(
    private val context: Context,
    private val handler: Handler,
    private val events: Events,
) {
    interface Events {
        fun onLinkUp(maxChunk: Int)
        fun onLinkDown()
        fun onChunk(chunk: ByteArray)
        /** Everything sent so far is out, so the next message can be sealed. See OutboundQueue for why it waits. */
        fun onSendIdle()
        fun onBleLog(line: String)
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var outgoing: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null
    private var mtu = 23
    private var linkUp = false
    private var advertising = false
    private var wanted = false

    private val queue = ArrayDeque<ByteArray>()
    private var inFlight = false
    private var busyRetries = 0

    /**
     * After this app restarts, the stack reports the connection the watch still holds as a new one, and not every
     * stack reports the end of it either. So forget it here too: holding on would refuse the watch when it comes back
     * with a new address.
     */
    private val subscribeTimeout = Runnable {
        events.onBleLog("Watch did not subscribe, dropping the connection")
        disconnect()
        clearConnection()
        startAdvertising()
    }

    val isIdle: Boolean get() = linkUp && queue.isEmpty() && !inFlight

    /** Also called after Bluetooth comes back on: the old server does not survive that. */
    fun start() {
        wanted = true
        if (server == null) openServer()
    }

    fun stop() {
        wanted = false
        slow = false
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        stopAdvertising()
        server?.let { s ->
            device?.let(s::cancelConnection)
            s.close()
        }
        server = null
        outgoing = null
        val wasUp = linkUp
        clearConnection()
        if (wasUp) events.onLinkDown()
    }

    fun send(chunk: ByteArray) {
        if (!linkUp) return
        queue.addLast(chunk)
        pump()
    }

    fun disconnect() {
        val current = device ?: return
        server?.cancelConnection(current)
    }

    private fun openServer() {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            events.onBleLog("Bluetooth is off")
            return
        }
        val opened = manager.openGattServer(context, callback)
        if (opened == null) {
            events.onBleLog("Could not open GATT server, retrying in 5 s")
            retryLater(::openServer)
            return
        }
        server = opened
        val send = BluetoothGattCharacteristic(
            GattContract.PHONE_TO_WATCH,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    GattContract.CLIENT_CONFIG,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val receive = BluetoothGattCharacteristic(
            GattContract.WATCH_TO_PHONE,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(GattContract.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(send)
            addCharacteristic(receive)
        }
        outgoing = send
        opened.addService(service)
    }

    /**
     * The first minute after the watch went away is when it comes back most often (out of range for a moment), so that is worth the
     * faster setting. After it the phone would keep calling at that pace all day, so it slows down until the watch connects again.
     */
    private var slow = false

    private val slowDown = Runnable {
        if (!advertising || device != null) return@Runnable
        stopAdvertising()
        slow = true
        startAdvertising()
    }

    private fun startAdvertising() {
        if (!wanted || advertising || device != null) return
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            events.onBleLog("Advertising not available, retrying in 5 s")
            retryLater(::startAdvertising)
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (slow) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(GattContract.SERVICE))
            .build()
        advertising = true
        advertiser.startAdvertising(settings, data, advertiseCallback)
        if (!slow) handler.postDelayed(slowDown, SLOW_DOWN_MS)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        handler.removeCallbacks(slowDown)
        manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private fun clearConnection() {
        handler.removeCallbacks(subscribeTimeout)
        device = null
        mtu = 23
        linkUp = false
        queue.clear()
        inFlight = false
        busyRetries = 0
    }

    private fun pump() {
        if (inFlight) return
        val chunk = queue.peekFirst() ?: return
        val target = device ?: return
        val characteristic = outgoing ?: return
        val status = server?.notifyCharacteristicChanged(target, characteristic, false, chunk)
        if (status == BluetoothStatusCodes.SUCCESS) {
            queue.pollFirst()
            inFlight = true
            busyRetries = 0
            return
        }
        // The stack is busy: try again shortly. If it stays stuck, a fresh connection is the reliable way out.
        if (++busyRetries > MAX_BUSY_RETRIES) {
            events.onBleLog("Sending keeps failing ($status), dropping the connection")
            disconnect()
            return
        }
        handler.postDelayed({ pump() }, RETRY_TOKEN, BUSY_RETRY_MS)
    }

    private fun retryLater(action: () -> Unit) {
        handler.postDelayed({ if (wanted) action() }, RETRY_TOKEN, 5_000)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            handler.post { events.onBleLog("Advertising") }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return@post
                advertising = false
                events.onBleLog("Advertising failed ($errorCode), retrying in 5 s")
                retryLater(::startAdvertising)
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    startAdvertising()
                } else {
                    events.onBleLog("Could not add service ($status)")
                }
            }
        }

        override fun onConnectionStateChange(remote: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (device != null && device != remote) {
                            server?.cancelConnection(remote)
                            return@post
                        }
                        device = remote
                        slow = false
                        stopAdvertising()
                        events.onBleLog("Watch connected")
                        if (!linkUp) {
                            handler.removeCallbacks(subscribeTimeout)
                            handler.postDelayed(subscribeTimeout, SUBSCRIBE_TIMEOUT_MS)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (remote != device) return@post
                        val wasUp = linkUp
                        clearConnection()
                        events.onBleLog("Watch gone (status $status)")
                        if (wasUp) events.onLinkDown()
                        startAdvertising()
                    }
                }
            }
        }

        override fun onMtuChanged(remote: BluetoothDevice, newMtu: Int) {
            handler.post { if (remote == device) mtu = newMtu }
        }

        override fun onDescriptorWriteRequest(
            remote: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val isConfig = descriptor.uuid == GattContract.CLIENT_CONFIG
            if (responseNeeded) {
                server?.sendResponse(
                    remote,
                    requestId,
                    if (isConfig) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset,
                    null,
                )
            }
            if (!isConfig) return
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            handler.post {
                if (remote != device) return@post
                if (enabled && !linkUp) {
                    handler.removeCallbacks(subscribeTimeout)
                    linkUp = true
                    events.onLinkUp(GattContract.chunkSize(mtu))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            remote: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val accepted = characteristic.uuid == GattContract.WATCH_TO_PHONE && !preparedWrite && offset == 0
            if (responseNeeded) {
                server?.sendResponse(
                    remote,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
            if (!accepted) return
            val copy = value.copyOf()
            handler.post { if (remote == device && linkUp) events.onChunk(copy) }
        }

        override fun onNotificationSent(remote: BluetoothDevice, status: Int) {
            handler.post {
                inFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    events.onBleLog("Notify failed ($status), dropping the connection")
                    disconnect()
                    return@post
                }
                pump()
                if (isIdle) events.onSendIdle()
            }
        }
    }

    private companion object {
        val RETRY_TOKEN = Any()
        const val BUSY_RETRY_MS = 30L
        const val MAX_BUSY_RETRIES = 100
        const val SUBSCRIBE_TIMEOUT_MS = 15_000L
        const val SLOW_DOWN_MS = 60_000L
    }
}
