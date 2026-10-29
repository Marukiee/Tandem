package nl.markmaaktmedia.tandem.ui.screens

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.remote.MacKeys
import nl.markmaaktmedia.tandem.ui.remote.RemoteLink
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * The phone as the clicker of a presentation on a computer: big buttons for the next and the previous slide, the volume keys doing the
 * same while this screen is open (down is next, as on most clickers), and a pointer: hold its button and point the phone, and the
 * pointer of the computer follows, by the gyroscope. The slides go with the arrow keys, which Keynote, PowerPoint, Google Slides and
 * a PDF all take; the pointer is the computer's own, which a slideshow shows when it moves.
 */
@Composable
fun PresentationScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val devices by graph.host.devices.collectAsState()
    val device = devices.firstOrNull { it.id == id }
    val haptics = LocalHapticFeedback.current

    val link = remember(id) { RemoteLink(graph.host, id, graph.scope) }
    DisposableEffect(link) {
        link.start()
        onDispose { link.release() }
    }
    // The screen stays on while presenting: the phone in a hand is not touched for minutes.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    fun next() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        link.key(MacKeys.RIGHT, 0)
    }
    fun previous() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        link.key(MacKeys.LEFT, 0)
    }

    var pointing by remember { mutableStateOf(false) }
    GyroPointer(active = pointing) { dx, dy -> link.pointer(dx, dy) }

    // The volume keys reach the screen only while something on it has the focus.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.VolumeDown -> { if (event.type == KeyEventType.KeyDown) next(); true }
                    Key.VolumeUp -> { if (event.type == KeyEventType.KeyDown) previous(); true }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(stringResource(R.string.presentation_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    device?.name.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.presentation_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        // Next is the big one: it is what a presenter presses most.
        SlideButton(TandemIcons.ArrowRight, stringResource(R.string.presentation_next), primary = true, modifier = Modifier.weight(2f), onClick = ::next)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SlideButton(TandemIcons.ArrowLeft, stringResource(R.string.presentation_previous), primary = false, modifier = Modifier.weight(1f), onClick = ::previous)
            PointerButton(pointing, { pointing = it }, Modifier.weight(1f))
        }
        Box(Modifier.height(4.dp))
    }
}

@Composable
private fun SlideButton(icon: Painter, label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier.fillMaxSize().clip(SquircleShape(32.dp)).background(container).bouncyClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(if (primary) 56.dp else 36.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

/** Held, the phone points: let go, and the pointer stays where it is. */
@Composable
private fun PointerButton(pointing: Boolean, onPointing: (Boolean) -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (pointing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
        TandemMotion.colourSpec(), label = "pointer",
    )
    val content = if (pointing) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .fillMaxSize()
            .clip(SquircleShape(32.dp))
            .background(container)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPointing(true)
                    waitForUpOrCancellation()
                    onPointing(false)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(TandemIcons.Touch, null, tint = content, modifier = Modifier.size(36.dp))
        Text(
            stringResource(if (pointing) R.string.presentation_pointing else R.string.presentation_pointer),
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}

/**
 * While [active], turning the phone moves the pointer: left and right (around the axis through the screen) across, up and down
 * (around the axis across the phone) up and down, as a phone held like a remote points. The gyroscope gives turning speed, so a
 * still hand gives no drift; parts of a pixel are carried over so slow movement is not lost.
 */
@Composable
private fun GyroPointer(active: Boolean, move: (Int, Int) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        val sensors = context.getSystemService(SensorManager::class.java)
        val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return@DisposableEffect onDispose {}
        var last = 0L
        var restX = 0f
        var restY = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (last != 0L) {
                    val seconds = (event.timestamp - last) / 1e9f
                    restX += -event.values[2] * seconds * PIXELS_PER_RADIAN
                    restY += -event.values[0] * seconds * PIXELS_PER_RADIAN
                    val dx = restX.toInt()
                    val dy = restY.toInt()
                    if (dx != 0 || dy != 0) {
                        restX -= dx
                        restY -= dy
                        move(dx, dy)
                    }
                }
                last = event.timestamp
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, gyro, SAMPLE_US)
        onDispose { sensors.unregisterListener(listener) }
    }
}

/** 200 times a second, the most Android gives without a special permission. The game rate is 50, which a pointer shows as stutter. */
private const val SAMPLE_US = 5_000

/** A quarter turn of the wrist takes the pointer across most of a screen. */
private const val PIXELS_PER_RADIAN = 1400f
