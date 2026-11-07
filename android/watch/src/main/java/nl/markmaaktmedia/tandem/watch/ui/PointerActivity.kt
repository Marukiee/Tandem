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
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.pointer.CrownSteps
import nl.markmaaktmedia.tandem.watch.pointer.GyroPointer
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
    private val gyro = GyroPointer()
    private val pinch = PinchDetector()

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

    /** Without a computer this only counts pinches and records them, to tune the detector on a real wrist. */
    private val testing by lazy { targetId.isEmpty() }
    private var pinches by mutableIntStateOf(0)
    private var pulse by mutableIntStateOf(0)
    private var movingUi by mutableStateOf(true)

    // Raw motion for tuning the pinch detector on real wrists. Not in a release.
    private var tuningOut: BufferedOutputStream? = null
    private var tuning: RawLogWriter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        PointerPrefs.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        if (!testing) WatchLink.startPointer(targetId)
        gyro.sensitivity = PointerPrefs.speed.value.sensitivity
        listenToPinch = testing || PointerPrefs.pinch.value
        gyro.reset()
        pinch.reset()
        startTuningLog()
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SAMPLE_US, sensorHandler) }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SAMPLE_US, sensorHandler) }
    }

    override fun onPause() {
        // Never leave a button down on the computer: a watch that goes dark mid-drag lets go here.
        if (holding) {
            holding = false
            if (!testing) WatchLink.holdPointer(BUTTON_LEFT, false)
        }
        touching = false
        sensors.unregisterListener(this)
        if (!testing) WatchLink.stopPointer()
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
            Sensor.TYPE_GYROSCOPE -> if (moving && !touching) {
                gyro.update(event.timestamp, event.values[1], event.values[2])?.let { WatchLink.movePointer(it.dx, it.dy) }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                tuning?.write(RawRecord.Accel(event.timestamp / 1_000_000, unit(x), unit(y), unit(z)))
                if (listenToPinch && pinch.update(event.timestamp, x, y, z) && !holding) {
                    tuning?.write(RawRecord.Mark(event.timestamp / 1_000_000, "pinch"))
                    runOnUiThread {
                        pinches++
                        primary()
                    }
                }
            }
        }
    }

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

    /** A tap on the screen, or a double pinch for when the other hand is not free. */
    private fun primary() {
        feedback()
        if (testing) return
        if (presentation) WatchLink.sendKey(KEY_RIGHT) else WatchLink.clickPointer(BUTTON_LEFT)
    }

    private fun onGesture(gesture: ScreenGesture) {
        val canHold = WatchLink.ui.value.pointerCanHold
        when (gesture) {
            ScreenGesture.TAP -> primary()
            ScreenGesture.TWO_FINGER_TAP -> {
                feedback()
                if (testing) return
                if (presentation) WatchLink.sendKey(KEY_LEFT) else WatchLink.clickPointer(BUTTON_RIGHT)
            }
            ScreenGesture.HOLD -> when {
                presentation -> {
                    setMoving(true)
                    feedback()
                }
                testing -> feedback()
                // A phone that cannot hold a button still gets the right click a long press always was.
                !canHold -> {
                    feedback()
                    WatchLink.clickPointer(BUTTON_RIGHT)
                }
                else -> {
                    feedback()
                    gyro.reset()
                    holding = true
                    WatchLink.holdPointer(BUTTON_LEFT, true)
                }
            }
            ScreenGesture.HOLD_END -> if (holding) {
                holding = false
                WatchLink.holdPointer(BUTTON_LEFT, false)
                feedback()
            }
            ScreenGesture.SLIDE -> if (!presentation) {
                feedback()
                setMoving(!moving)
            }
        }
    }

    private fun onFingersGone() {
        // In a presentation the pointer only follows while a finger holds it.
        if (presentation && moving) setMoving(false)
    }

    private fun feedback() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        pulse++
    }

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
                            repeat(kotlin.math.abs(steps)) { WatchLink.sendKey(if (steps > 0) KEY_RIGHT else KEY_LEFT) }
                        } else {
                            WatchLink.scrollPointer(0, event.verticalScrollPixels.toInt())
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
                        stringResource(R.string.pinch_count, pinches)
                    } else stringResource(
                        when {
                            !ready -> R.string.phone_not_connected
                            presentation -> R.string.presentation_hint
                            holding -> R.string.mouse_holding
                            movingUi -> R.string.mouse_moving
                            else -> R.string.mouse_paused
                        },
                    ),
                    hint = when {
                        testing -> stringResource(R.string.pinch_hint)
                        ready && !presentation && !holding -> stringResource(if (canHold) R.string.mouse_hint else R.string.mouse_hint_tap)
                        else -> null
                    },
                    live = live,
                    scale = { scale.value },
                    turn = { turn.value },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_PRESENTATION = "presentation"

        /** 200 times a second, the most Android gives without a special permission, and what a knock needs. */
        private const val SAMPLE_US = 5_000
        private const val BUTTON_LEFT = 0
        private const val BUTTON_RIGHT = 1

        /** Tandem's input uses Mac key codes everywhere; each computer translates them. */
        private const val KEY_LEFT = 123
        private const val KEY_RIGHT = 124

        fun open(context: Context, targetId: String, presentation: Boolean) {
            context.startActivity(
                Intent(context, PointerActivity::class.java)
                    .putExtra(EXTRA_TARGET, targetId)
                    .putExtra(EXTRA_PRESENTATION, presentation),
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
