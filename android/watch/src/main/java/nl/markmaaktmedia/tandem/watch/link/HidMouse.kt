package nl.markmaaktmedia.tandem.watch.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch as a Bluetooth mouse and keyboard of one computer, without the phone in between: every movement is one hop less, and it
 * works on a computer without Tandem too. The first time a computer is used it is paired, which its system asks once; after that it
 * connects as soon as the mouse screen opens, and lets go when it closes, so no phantom mouse stays behind.
 *
 * Movement is gathered and sent at a fixed pace, like the phone's pacer does: a computer accelerates each report by its size, so
 * reports that come in bursts would make an even turn of the arm into an uneven speed.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is asked for with the others before the mouse can open.
class HidMouse(private val context: Context, private val address: String) {
    enum class Status { STARTING, PAIRING, CONNECTING, CONNECTED, FAILED }

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status.asStateFlow()
    /** Called on the main thread with each new state, for the screen. */
    var onStatus: (Status) -> Unit = {}

    private fun set(status: Status) {
        _status.value = status
        main.post { onStatus(status) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var stopped = false
    private var buttons = 0
    private var pendingX = 0
    private var pendingY = 0
    private var pendingWheel = 0

    private val tick = object : Runnable {
        override fun run() {
            flush()
            if (!stopped) main.postDelayed(this, TICK_MS)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (device.address != address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> connect()
                BluetoothDevice.BOND_NONE -> if (_status.value == Status.PAIRING) fail("pairing was refused or timed out")
            }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "HID app registered: $registered")
            if (registered) main.post { pairOrConnect() } else if (!stopped) main.post { fail("the watch would not register as a mouse") }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (device.address != address) return
            Log.i(TAG, "HID connection: $state")
            main.post {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        set(Status.CONNECTED)
                        main.removeCallbacks(tick)
                        main.post(tick)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (!stopped && _status.value == Status.CONNECTED) set(Status.CONNECTING)
                }
            }
        }
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (stopped) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                return
            }
            val device = proxy as BluetoothHidDevice
            hid = device
            val sdp = BluetoothHidDeviceAppSdpSettings(NAME, DESCRIPTION, PROVIDER, BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR)
            if (!device.registerApp(sdp, null, null, { it.run() }, callback)) fail("the watch would not register as a mouse")
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
        }
    }

    fun start() {
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) return fail("Bluetooth is off")
        if (!BluetoothAdapterAddress.valid(address)) return fail("no Bluetooth address for this computer")
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        if (!bluetooth.getProfileProxy(context, proxyListener, BluetoothProfile.HID_DEVICE)) fail("this watch has no Bluetooth mouse")
    }

    private fun pairOrConnect() {
        val device = adapter?.getRemoteDevice(address) ?: return fail("no Bluetooth address for this computer")
        host = device
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connect()
        } else {
            // The computer asks once whether this watch may pair; after that it is known.
            set(Status.PAIRING)
            if (!device.createBond()) fail("pairing could not start")
        }
    }

    private fun connect() {
        val device = host ?: return
        set(Status.CONNECTING)
        if (hid?.connect(device) != true) fail("could not connect to the computer")
    }

    fun move(dx: Int, dy: Int) {
        pendingX += dx
        pendingY += dy
    }

    fun scroll(steps: Int) {
        pendingWheel += steps
    }

    fun click(button: Int, count: Int = 1) {
        repeat(count) {
            hold(button, true)
            hold(button, false)
        }
    }

    fun hold(button: Int, down: Boolean) {
        val bit = 1 shl button.coerceIn(0, 2)
        flush()
        buttons = if (down) buttons or bit else buttons and bit.inv()
        sendMouse(0, 0, 0)
    }

    /** [macKey] as the relay sends it: 123 is left, 124 right. Only the arrows for a presentation. */
    fun key(macKey: Int) {
        val usage = when (macKey) {
            KEY_LEFT -> 0x50
            KEY_RIGHT -> 0x4F
            else -> return
        }
        send(REPORT_KEYBOARD, byteArrayOf(0, 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(REPORT_KEYBOARD, ByteArray(8))
    }

    private fun flush() {
        if (pendingX == 0 && pendingY == 0 && pendingWheel == 0) return
        val x = pendingX.coerceIn(-MAX_STEP, MAX_STEP)
        val y = pendingY.coerceIn(-MAX_STEP, MAX_STEP)
        val wheel = pendingWheel.coerceIn(-127, 127)
        pendingX -= x
        pendingY -= y
        pendingWheel -= wheel
        sendMouse(x, y, wheel)
    }

    private fun sendMouse(x: Int, y: Int, wheel: Int) {
        send(REPORT_MOUSE, byteArrayOf(buttons.toByte(), x.toByte(), (x shr 8).toByte(), y.toByte(), (y shr 8).toByte(), wheel.toByte()))
    }

    private fun send(id: Int, data: ByteArray) {
        val device = host ?: return
        if (_status.value != Status.CONNECTED) return
        hid?.sendReport(device, id, data)
    }

    private fun fail(reason: String) {
        Log.w(TAG, "HID mouse: $reason")
        set(Status.FAILED)
        lastFailure = reason
    }

    /** Lets go of the computer: no button stays down and no mouse stays listed there. */
    fun stop() {
        if (stopped) return
        if (buttons != 0) {
            buttons = 0
            sendMouse(0, 0, 0)
        }
        stopped = true
        main.removeCallbacks(tick)
        runCatching { context.unregisterReceiver(bondReceiver) }
        hid?.let { device ->
            host?.let { device.disconnect(it) }
            device.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hid = null
    }

    var lastFailure: String = ""
        private set

    private companion object {
        const val TAG = "TandemWatchHid"
        const val NAME = "Tandem"
        const val DESCRIPTION = "Watch as mouse"
        const val PROVIDER = "Tandem"
        const val TICK_MS = 8L
        const val MAX_STEP = 2000
        const val REPORT_MOUSE = 1
        const val REPORT_KEYBOARD = 2
        const val KEY_LEFT = 123
        const val KEY_RIGHT = 124

        /** A mouse with three buttons, 16-bit movement and a wheel (report 1), and a keyboard for the arrows of a presentation (report 2). */
        val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), REPORT_MOUSE.toByte(),
            0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80.toByte(), 0x26, 0xFF.toByte(), 0x7F, 0x75, 0x10, 0x95.toByte(), 0x02,
            0x81.toByte(), 0x06,
            0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x01, 0x81.toByte(), 0x06,
            0xC0.toByte(), 0xC0.toByte(),
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), REPORT_KEYBOARD.toByte(),
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
        )
    }
}

/** A Bluetooth address as Android takes it: six pairs of hex digits in capitals, separated by colons. */
object BluetoothAdapterAddress {
    fun valid(address: String): Boolean = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(address)
}
