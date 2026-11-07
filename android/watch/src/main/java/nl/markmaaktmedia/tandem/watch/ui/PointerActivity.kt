package nl.markmaaktmedia.tandem.watch.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.HidMouse
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.pointer.CrownSteps
import nl.markmaaktmedia.tandem.watch.pointer.ArmPointer
import nl.markmaaktmedia.tandem.watch.pointer.Pinch
import nl.markmaaktmedia.tandem.watch.pointer.PinchDetector
import nl.markmaaktmedia.tandem.watch.pointer.ScreenGesture
import nl.markmaaktmedia.tandem.watch.pointer.ScreenGestures
import nl.markmaaktmedia.tandem.watch.pointer.Touch
import nl.markmaaktmedia.tandem.watch.sleep.ACCEL_UNITS_PER_G
import nl.markmaaktmedia.tandem.watch.sleep.RawLogWriter
import nl.markmaaktmedia.tandem.watch.sleep.RawRecord
import nl.markmaaktmedia.tandem.watch.track.TrackingService
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The watch as a mouse or a presentation remote. Runs only while on screen: a locked or sleeping watch pauses this
 * activity, and that ends the session on the phone too.
 */
class PointerActivity : ComponentActivity(), SensorEventListener {
    private val sensorThread = HandlerThread("tandem-watch-pointer").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    private lateinit var sensors: SensorManager
    /** Follows where the forearm points, whatever way the wrist is turned (see [ArmPointer]). */
    private val gyro = ArmPointer()
    /**
     * The hand on a table as on a mouse: a tap of a finger on the table is softer than a pinch in the air (1.2 to 3.4 m/s2 in a recording,
     * against 2.4 to 5.8), and the wrist stays stiller while it slides (under 1.3 rad/s).
     */
    private val tableMode by lazy { intent.getBooleanExtra(EXTRA_TABLE_MODE, false) }
    private val pinch by lazy { if (tableMode) PinchDetector(minKnock = 1.25f, maxTurn = 1.3f) else PinchDetector() }

    /** Only the sensor thread reads these; they change rarely and a sample late does no harm. */
    @Volatile private var moving = true

    /**
     * A finger of the other hand on the screen pushes the watch a little. Until it is known whether that finger taps or holds, the
     * pointer stays where it is, so a click lands where it was aimed.
     */
    @Volatile private var touching = false

    /** Off unless chosen on the watch, or while testing it. */
    @Volatile private var listenToPinch = false

    /** The left button is down for a drag, from a finger that holds the screen until it is lifted. */
    private var holding by mutableStateOf(false)

    private val presentation by lazy { intent.getBooleanExtra(EXTRA_PRESENTATION, false) }
    private val targetId by lazy { intent.getStringExtra(EXTRA_TARGET).orEmpty() }
    /** The Bluetooth address of a computer to be the mouse of directly, without the phone. */
    private val hidAddress by lazy { intent.getStringExtra(EXTRA_HID) }
    private var hid: HidMouse? = null
    private var hidState by mutableStateOf(HidMouse.Status.STARTING)
    private var hidWhy by mutableStateOf("")

    /** Without a computer this only counts pinches and records them, to tune the detector on a real wrist. */
    private val testing by lazy { targetId.isEmpty() && hidAddress == null }
    private var pinches by mutableIntStateOf(0)
    private var pulse by mutableIntStateOf(0)
    /** Counts the pinches heard, so the screen can light up for each: a buzz alone did not say whether it was the pinch or a tap. */
    private var flashes by mutableIntStateOf(0)
    private var flashDouble by mutableStateOf(false)
    private var movingUi by mutableStateOf(true)

    // Raw motion for tuning the pinch detector on real wrists. Not in a release.
    private var tuningOut: BufferedOutputStream? = null
    private var tuning: RawLogWriter? = null

    /**
     * The test goes through steps and says each one, with a buzz when the next begins. What the wrist was asked to do is written next
     * to the motion, so a real pinch can be told from what only looks like one: moving the arm, a fist, a tap on the table.
     */
    private data class TuningStep(val name: String, val seconds: Int, val text: Int, val cued: Boolean = false)

    /** Pinches in the air: when asked, while moving, and what is no pinch. */
    private val pinchSteps = listOf(
        TuningStep("still", 5, R.string.tune_still),
        TuningStep("pinch", 30, R.string.tune_pinch, cued = true),
        TuningStep("move", 15, R.string.tune_move),
        TuningStep("pinch_moving", 30, R.string.tune_pinch_moving, cued = true),
        TuningStep("other", 20, R.string.tune_other),
        TuningStep("done", 0, R.string.tune_done),
    )

    /** The hand on a table as on a mouse: taps when asked, sliding, and taps while sliding. */
    private val tableSteps = listOf(
        TuningStep("table_still", 5, R.string.table_still),
        TuningStep("table_tap", 30, R.string.table_tap, cued = true),
        TuningStep("table_slide", 20, R.string.table_slide),
        TuningStep("table_slide_tap", 30, R.string.table_slide_tap, cued = true),
        TuningStep("done", 0, R.string.tune_done),
    )
    private val tableTest by lazy { intent.getBooleanExtra(EXTRA_TABLE, false) }
    private val steps by lazy { if (tableTest) tableSteps else pinchSteps }
    private var stepAt by mutableIntStateOf(0)
    private val step: TuningStep get() = steps[stepAt]
    private var stepLeft by mutableIntStateOf(0)
    /** The screen says "pinch now": each pinch then has a moment it belongs to, which a recording needs to learn from. */
    private var cue by mutableStateOf(false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val stepTick = object : Runnable {
        override fun run() {
            stepLeft--
            if (stepLeft > 0) {
                main.postDelayed(this, 1_000)
                return
            }
            beginStep(stepAt + 1)
        }
    }
    private val cueTick = object : Runnable {
        override fun run() {
            if (!step.cued) return
            cue = true
            mark("cue")
            main.postDelayed({ cue = false }, CUE_SHOWN_MS)
            main.postDelayed(this, CUE_EVERY_MS)
        }
    }

    /** In the time of the sensor, so the marks line up with the motion. */
    private fun mark(label: String) {
        val now = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000
        sensorHandler.post { tuning?.write(RawRecord.Mark(now, label)) }
    }

    private fun beginStep(index: Int) {
        stepAt = index
        val next = steps[index]
        stepLeft = next.seconds
        cue = false
        main.removeCallbacks(cueTick)
        mark("step:${next.name}")
        if (next.name == "done") {
            listenToPinch = false
            sensorHandler.post { stopTuningLog() }
            // Only now: a buzz while it records is a knock in the very sensor it records.
            getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
            return
        }
        if (next.cued) main.postDelayed(cueTick, CUE_FIRST_MS)
        main.postDelayed(stepTick, 1_000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        PointerPrefs.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(android.app.NotificationManager::class.java)?.cancel(MOUSE_NOTIFICATION)
        // In a presentation the pointer stays still until a finger asks for it.
        setMoving(!presentation)
        setContent {
            TandemWatchTheme {
                AppScaffold(timeText = {}) {
                    val ui by WatchLink.ui.collectAsState()
                    val name = ui.pointerTargets.firstOrNull { it.id == targetId }?.name.orEmpty()
                    PointerScreen(name, ui.ready, ui.pointerCanHold)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hidAddress != null) {
            val mouse = HidMouse(applicationContext, hidAddress!!)
            hid = mouse
            mouse.onStatus = { status ->
                hidState = status
                hidWhy = mouse.lastFailure
            }
            mouse.start()
        } else if (!testing) {
            WatchLink.startPointer(targetId)
        }
        // On a table the watch moves much less than the hand that slides it, so the same slide must go further.
        gyro.sensitivity = PointerPrefs.speed.value.sensitivity * (if (tableMode) TABLE_GAIN else 1f)
        // On a table the taps are how it clicks, whatever is set for pinching in the air.
        listenToPinch = testing || tableMode || PointerPrefs.pinch.value
        gyro.reset()
        pinch.reset()
        startTuningLog()
        if (testing) {
            main.removeCallbacks(stepTick)
            beginStep(0)
        }
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SAMPLE_US, sensorHandler) }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SAMPLE_US, sensorHandler) }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SAMPLE_US, sensorHandler) }
    }

    override fun onPause() {
        // Never leave a button down on the computer: a watch that goes dark mid-drag lets go here.
        if (holding) {
            holding = false
            if (!testing) outHold(BUTTON_LEFT, false)
        }
        touching = false
        sensors.unregisterListener(this)
        main.removeCallbacks(stepTick)
        main.removeCallbacks(cueTick)
        hid?.stop()
        hid = null
        if (!testing && hidAddress == null) WatchLink.stopPointer()
        sensorHandler.post { stopTuningLog() }
        super.onPause()
    }

    override fun onDestroy() {
        sensorThread.quitSafely()
        super.onDestroy()
    }

    private fun setMoving(on: Boolean) {
        moving = on
        movingUi = on
        if (on) gyro.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> if (moving && !touching) {
                gyro.update(event.values[0], event.values[1], event.values[2], event.values[3])?.let { outMove(it.dx, it.dy) }
            }
            // The gyroscope no longer steers: it tells the pinches apart from the arm, and goes into a recording for tuning.
            Sensor.TYPE_GYROSCOPE -> {
                tuning?.write(RawRecord.Gyro(event.timestamp / 1_000_000, milli(event.values[0]), milli(event.values[1]), milli(event.values[2])))
                pinch.gyro(event.timestamp, event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                tuning?.write(RawRecord.Accel(event.timestamp / 1_000_000, unit(x), unit(y), unit(z)))
                val heard = if (listenToPinch) pinch.update(event.timestamp, x, y, z) else null
                if (heard != null && !holding) {
                    tuning?.write(RawRecord.Mark(event.timestamp / 1_000_000, if (heard == Pinch.DOUBLE) "pinch2" else "pinch"))
                    runOnUiThread {
                        pinches++
                        flashDouble = heard == Pinch.DOUBLE
                        flashes++
                        // One pinch is the left button, two in a row the right one, as a tap and a tap with two fingers are.
                        if (heard == Pinch.DOUBLE) secondary() else primary()
                    }
                }
            }
        }
    }

    private fun milli(value: Float): Short = (value * 1000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun unit(value: Float): Short =
        (value / SensorManager.GRAVITY_EARTH * ACCEL_UNITS_PER_G)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun startTuningLog() {
        if (!resources.getBoolean(R.bool.test_tools)) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = BufferedOutputStream(FileOutputStream(File(TrackingService.rawDir(this), "pinch-$stamp.tws")), 64 * 1024)
        tuningOut = out
        tuning = RawLogWriter(out, writeHeader = true)
    }

    private fun stopTuningLog() {
        tuning = null
        runCatching { tuningOut?.close() }
        tuningOut = null
    }

    /** A tap on the screen, or a pinch for when the other hand is not free. */
    private fun primary() {
        if (testing) return
        feedback()
        if (presentation) outKey(KEY_RIGHT) else outClick(BUTTON_LEFT)
    }

    /** A tap with two fingers, or two pinches in a row: the right button, or back a slide. */
    private fun secondary() {
        if (testing) return
        feedback()
        if (presentation) outKey(KEY_LEFT) else outClick(BUTTON_RIGHT)
    }

    private fun onGesture(gesture: ScreenGesture) {
        val canHold = WatchLink.ui.value.pointerCanHold
        when (gesture) {
            ScreenGesture.TAP -> if (testing) feedback() else primary()
            ScreenGesture.TWO_FINGER_TAP -> if (testing) feedback() else secondary()
            ScreenGesture.HOLD -> when {
                presentation -> {
                    setMoving(true)
                    feedback()
                }
                testing -> feedback()
                // A phone that cannot hold a button still gets the right click a long press always was.
                !canHold && hid == null -> {
                    feedback()
                    outClick(BUTTON_RIGHT)
                }
                else -> {
                    feedback()
                    gyro.reset()
                    holding = true
                    outHold(BUTTON_LEFT, true)
                }
            }
            ScreenGesture.HOLD_END -> if (holding) {
                holding = false
                outHold(BUTTON_LEFT, false)
                feedback()
            }
            ScreenGesture.SLIDE -> if (!presentation) {
                feedback()
                setMoving(!moving)
            }
            // As everywhere on a watch: the screen takes every touch, so the system can not do this for it.
            ScreenGesture.SWIPE_RIGHT -> finish()
        }
    }

    private fun onFingersGone() {
        // In a presentation the pointer only follows while a finger holds it.
        if (presentation && moving) setMoving(false)
    }

    private fun feedback() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        // The buzz is a knock in the sensor the pinches come from: without this it was heard as the next pinch, and that one buzzed again.
        pinch.quietUntilNs = android.os.SystemClock.elapsedRealtimeNanos() + BUZZ_QUIET_NS
        pulse++
    }

    // Where the pointer goes: straight to the computer over Bluetooth, or by way of the phone.
    private fun outMove(dx: Int, dy: Int) = hid?.move(dx, dy) ?: WatchLink.movePointer(dx, dy)
    private fun outClick(button: Int) = hid?.click(button) ?: WatchLink.clickPointer(button)
    private fun outHold(button: Int, down: Boolean) = hid?.hold(button, down) ?: WatchLink.holdPointer(button, down)
    private fun outKey(code: Int) = hid?.key(code) ?: WatchLink.sendKey(code)
    private fun outScroll(steps: Int) = hid?.scroll(steps) ?: WatchLink.scrollPointer(0, steps)

    @Composable
    private fun PointerScreen(name: String, ready: Boolean, canHold: Boolean) {
        val focus = remember { FocusRequester() }
        val crown = remember { CrownSteps() }
        val scale = remember { Animatable(1f) }
        val turn = remember { Animatable(0f) }
        val live = testing || (ready && (movingUi || presentation || holding))
        val classifier = remember { ScreenGestures() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(pulse) {
            if (pulse == 0) return@LaunchedEffect
            // One step of the cookie per click. It only moves when something happened, so the screen is not redrawn for nothing.
            launch { turn.animateTo(turn.targetValue + 40f, TandemMotion.shapeSpec()) }
            scale.snapTo(1.2f)
            scale.animateTo(1f, TandemMotion.shapeSpec())
        }
        ScreenScaffold {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .focusable()
                    .onRotaryScrollEvent { event ->
                        if (presentation) {
                            val steps = crown.update(event.verticalScrollPixels)
                            if (steps != 0) feedback()
                            repeat(kotlin.math.abs(steps)) { outKey(if (steps > 0) KEY_RIGHT else KEY_LEFT) }
                        } else {
                            if (hid != null) outScroll(-(event.verticalScrollPixels / CROWN_PER_WHEEL).toInt()) else WatchLink.scrollPointer(0, event.verticalScrollPixels.toInt())
                        }
                        true
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val wait = classifier.deadline?.let { (it - System.currentTimeMillis()).coerceAtLeast(1) }
                                val event = if (wait != null) withTimeoutOrNull(wait) { awaitPointerEvent() } else awaitPointerEvent()
                                val now = System.currentTimeMillis()
                                if (event == null) {
                                    classifier.onTimer(now)?.let(::onGesture)
                                    touching = classifier.undecided
                                    continue
                                }
                                event.changes.forEach { it.consume() }
                                val touches = event.changes.filter { it.pressed }.map { Touch(it.id.value, it.position.x, it.position.y) }
                                classifier.onFrame(now, touches)?.let(::onGesture)
                                touching = classifier.undecided
                                if (touches.isEmpty()) onFingersGone()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PointerContent(
                    name = name,
                    status = if (testing) {
                        stringResource(if (cue) (if (tableTest) R.string.table_now else R.string.tune_now) else step.text)
                    } else if (hidAddress != null && hidState != HidMouse.Status.CONNECTED) {
                        when (hidState) {
                            HidMouse.Status.PAIRING -> stringResource(R.string.hid_pairing)
                            HidMouse.Status.FAILED -> hidWhy
                            else -> stringResource(R.string.hid_connecting)
                        }
                    } else stringResource(
                        when {
                            !ready && hidAddress == null -> R.string.phone_not_connected
                            presentation -> R.string.presentation_hint
                            holding -> R.string.mouse_holding
                            movingUi -> R.string.mouse_moving
                            else -> R.string.mouse_paused
                        },
                    ),
                    hint = when {
                        testing && step.name != "done" -> stringResource(R.string.tune_left, stepLeft, pinches)
                        testing -> stringResource(R.string.pinch_count, pinches)
                        ready && !presentation && !holding -> stringResource(if (canHold) R.string.mouse_hint else R.string.mouse_hint_tap)
                        else -> null
                    },
                    live = live,
                    scale = { scale.value },
                    turn = { turn.value },
                )
                PinchFlash(flashes, flashDouble)
            }
        }
    }

    /** The whole screen lights up for a moment when a pinch is heard: the primary colour for one, the tertiary for two in a row. */
    @Composable
    private fun PinchFlash(count: Int, double: Boolean) {
        val glow = remember { Animatable(0f) }
        LaunchedEffect(count) {
            if (count == 0) return@LaunchedEffect
            glow.snapTo(1f)
            glow.animateTo(0f, androidx.compose.animation.core.tween(FLASH_MS))
        }
        val colour = if (double) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxSize().background(colour.copy(alpha = FLASH_ALPHA * glow.value)))
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_PRESENTATION = "presentation"

        /** 200 times a second, the most Android gives without a special permission, and what a knock needs. */
        private const val SAMPLE_US = 5_000
        private const val EXTRA_HID = "hid"
        private const val EXTRA_TABLE = "table"
        private const val EXTRA_TABLE_MODE = "table_mode"
        private const val TABLE_GAIN = 3f
        private const val MOUSE_CHANNEL = "mouse"
        /** Its own: see the other numbers in the watch app. */
        private const val MOUSE_NOTIFICATION = 360
        private const val FLASH_MS = 450
        private const val FLASH_ALPHA = 0.6f
        private const val BUZZ_QUIET_NS = 450_000_000L
        private const val CUE_FIRST_MS = 1_500L
        private const val CUE_EVERY_MS = 2_500L
        private const val CUE_SHOWN_MS = 900L
        private const val CROWN_PER_WHEEL = 40f
        private const val BUTTON_LEFT = 0
        private const val BUTTON_RIGHT = 1

        /** Tandem's input uses Mac key codes everywhere; each computer translates them. */
        private const val KEY_LEFT = 123
        private const val KEY_RIGHT = 124

        /** Straight to a computer over Bluetooth, as its mouse: the computer said its address. The first time its system asks to pair. */
        fun openBluetooth(context: Context, address: String, presentation: Boolean = false, table: Boolean = false) {
            context.startActivity(
                Intent(context, PointerActivity::class.java).putExtra(EXTRA_HID, address).putExtra(EXTRA_PRESENTATION, presentation).putExtra(EXTRA_TABLE_MODE, table),
            )
        }

        /**
         * A computer asked for the mouse, so the watch was not touched: a notification that opens the mouse screen at once, also with
         * the screen off, the way a call does. Over Bluetooth when the computer said its address.
         */
        fun openFromAfar(context: Context, targetId: String) {
            val target = WatchLink.ui.value.pointerTargets.firstOrNull { it.id == targetId }
            val intent = Intent(context, PointerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (target?.btAddress != null) putExtra(EXTRA_HID, target.btAddress) else putExtra(EXTRA_TARGET, targetId)
            }
            val screen = android.app.PendingIntent.getActivity(context, 7, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(MOUSE_CHANNEL, context.getString(R.string.channel_mouse), android.app.NotificationManager.IMPORTANCE_HIGH),
            )
            manager.notify(
                MOUSE_NOTIFICATION,
                android.app.Notification.Builder(context, MOUSE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_tandem)
                    .setContentTitle(context.getString(R.string.mouse_asked, target?.name.orEmpty()))
                    .setContentIntent(screen)
                    .setFullScreenIntent(screen, true)
                    .setCategory(android.app.Notification.CATEGORY_CALL)
                    .setAutoCancel(true)
                    .setTimeoutAfter(30_000)
                    .setLocalOnly(true)
                    .build(),
            )
        }

        /** The guided recording of a hand on a table, for the table mode. */
        fun openTableTest(context: Context) {
            context.startActivity(Intent(context, PointerActivity::class.java).putExtra(EXTRA_TARGET, "").putExtra(EXTRA_TABLE, true))
        }

        fun open(context: Context, targetId: String, presentation: Boolean, table: Boolean = false) {
            context.startActivity(
                Intent(context, PointerActivity::class.java)
                    .putExtra(EXTRA_TARGET, targetId)
                    .putExtra(EXTRA_PRESENTATION, presentation)
                    .putExtra(EXTRA_TABLE_MODE, table),
            )
        }
    }
}

@Composable
private fun PointerContent(name: String, status: String, hint: String?, live: Boolean, scale: () -> Float, turn: () -> Float) {
    val depth by animateFloatAsState(if (live) COOKIE_DEPTH else 0f, TandemMotion.shapeSpec(), label = "pointerShape")
    val fill by animateColorAsState(
        if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        TandemMotion.fadeSpec(),
        label = "pointerFill",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 22.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().fadedSides().basicMarquee(iterations = 2),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = scale()
                    scaleY = scale()
                    rotationZ = turn()
                }
                .background(fill, CookieShape(depth = depth)),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The screen as it looks, for the gallery in debug builds. */
@Composable
internal fun PointerFace(name: String, state: String, ready: Boolean) {
    ScreenScaffold {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PointerContent(
                name = name,
                status = stringResource(
                    when {
                        !ready -> R.string.phone_not_connected
                        state == "presentation" -> R.string.presentation_hint
                        state == "moving" -> R.string.mouse_moving
                        else -> R.string.mouse_paused
                    },
                ),
                hint = if (ready && state == "moving") stringResource(R.string.mouse_hint) else null,
                live = ready && state != "paused",
                scale = { 1f },
                turn = { 0f },
            )
        }
    }
}
