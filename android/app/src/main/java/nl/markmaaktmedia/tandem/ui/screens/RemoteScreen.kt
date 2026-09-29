package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PresenceDot
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.SheetSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemInput
import uniffi.tandem_core.TandemMediaKey
import kotlin.math.abs
import kotlin.math.roundToInt

// macOS virtual key codes, which is what the Mac side expects in Key events.
private const val KEY_RETURN = 36.toShort()
private const val KEY_TAB = 48.toShort()
private const val KEY_DELETE = 51.toShort()
private const val KEY_ESCAPE = 53.toShort()
private const val KEY_LEFT = 123.toShort()
private const val KEY_RIGHT = 124.toShort()
private const val KEY_DOWN = 125.toShort()
private const val KEY_UP = 126.toShort()

/** Turns the phone into a trackpad, keyboard and media remote for another device. */
@Composable
fun RemoteScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val host = context.graph.host
    val devices by host.devices.collectAsState()
    val device = devices.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var keyboard by remember { mutableStateOf(false) }

    fun input(value: TandemInput) {
        scope.launch { runCatching { host.engine?.sendInput(id, value) } }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
            Column(Modifier.weight(1f)) {
                Text(device?.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresenceDot(device?.online == true)
                    Text(
                        stringResource(if (device?.online == true) R.string.remote_ready else R.string.status_offline),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TandemIconButton(
                TandemIcons.Keyboard, stringResource(R.string.remote_keyboard),
                { keyboard = !keyboard },
                background = if (keyboard) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                tint = if (keyboard) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The trackpad
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(SheetSquircle)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .touchpad(
                    onMove = { dx, dy -> runCatching { host.engine?.sendPointer(id, dx.toShort(), dy.toShort()) } },
                    onScroll = { dx, dy -> runCatching { host.engine?.sendScroll(id, dx.toShort(), dy.toShort()) } },
                    onClick = { button, count -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); input(TandemInput.Click(button.toUByte(), count.toUByte())) },
                    onButton = { down -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); input(TandemInput.Button(0u, down)) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(TandemIcons.Touch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.remote_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }

        AnimatedVisibility(keyboard, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            KeyboardPanel(onText = { input(TandemInput.Text(it)) }, onKey = { input(TandemInput.Key(it.toUShort(), true, 0u)); input(TandemInput.Key(it.toUShort(), false, 0u)) })
        }

        // Media
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MediaButton(TandemIcons.VolumeOff) { input(TandemInput.Media(TandemMediaKey.MUTE)) }
            MediaButton(TandemIcons.VolumeDown) { input(TandemInput.Media(TandemMediaKey.VOLUME_DOWN)) }
            MediaButton(TandemIcons.Previous) { input(TandemInput.Media(TandemMediaKey.PREVIOUS)) }
            MediaButton(TandemIcons.Play, primary = true) { input(TandemInput.Media(TandemMediaKey.PLAY_PAUSE)) }
            MediaButton(TandemIcons.Next) { input(TandemInput.Media(TandemMediaKey.NEXT)) }
            MediaButton(TandemIcons.VolumeUp) { input(TandemInput.Media(TandemMediaKey.VOLUME_UP)) }
        }
    }
}

@Composable
private fun MediaButton(icon: Painter, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (primary) 56.dp else 44.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (primary) 26.dp else 22.dp))
    }
}

@Composable
private fun KeyboardPanel(onText: (String) -> Unit, onKey: (Short) -> Unit) {
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // What is typed goes straight across, so the field only ever holds the last few letters.
        BasicTextField(
            value = value,
            onValueChange = { new ->
                val old = value.text
                when {
                    new.text.length > old.length -> onText(new.text.substring(old.length))
                    new.text.length < old.length -> repeat(old.length - new.text.length) { onKey(KEY_DELETE) }
                }
                value = if (new.text.length > 40) TextFieldValue("", TextRange(0)) else new
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(SheetSquircle).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyChip("Esc", Modifier.weight(1f)) { onKey(KEY_ESCAPE) }
            KeyChip("Tab", Modifier.weight(1f)) { onKey(KEY_TAB) }
            KeyChip("←", Modifier.weight(1f)) { onKey(KEY_LEFT) }
            KeyChip("↓", Modifier.weight(1f)) { onKey(KEY_DOWN) }
            KeyChip("↑", Modifier.weight(1f)) { onKey(KEY_UP) }
            KeyChip("→", Modifier.weight(1f)) { onKey(KEY_RIGHT) }
            KeyChip("⏎", Modifier.weight(1f)) { onKey(KEY_RETURN) }
        }
    }
}

@Composable
private fun KeyChip(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer).bouncyClickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * The trackpad gestures.
 *
 * One finger moves the pointer, a tap clicks, two taps in a row double-click, a tap
 * with two fingers is a right click, two fingers moving scroll, and a press held
 * for a moment picks the pointer up so the next move drags.
 */
private fun Modifier.touchpad(
    onMove: (Int, Int) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onClick: (button: Int, count: Int) -> Unit,
    onButton: (down: Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    var lastTapEnd = 0L
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val startTime = System.currentTimeMillis()
        var moved = 0f
        var fingers = 1
        var maxFingers = 1
        var dragging = false
        var pendingX = 0f
        var pendingY = 0f
        var lastFlush = startTime

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            fingers = pressed.size
            if (fingers > maxFingers) maxFingers = fingers
            if (pressed.isEmpty()) break

            // Average movement of the fingers on the pad.
            val dx = pressed.sumOf { (it.position.x - it.previousPosition.x).toDouble() }.toFloat() / fingers
            val dy = pressed.sumOf { (it.position.y - it.previousPosition.y).toDouble() }.toFloat() / fingers
            moved += abs(dx) + abs(dy)
            pendingX += dx
            pendingY += dy
            event.changes.forEach { it.consume() }

            val now = System.currentTimeMillis()
            if (!dragging && fingers == 1 && moved < 12f && now - startTime > 380) {
                dragging = true
                onButton(true)
            }
            // Batched to about 120 updates a second, so a fast swipe is not a flood.
            if (now - lastFlush >= 8) {
                val x = pendingX.roundToInt()
                val y = pendingY.roundToInt()
                if (x != 0 || y != 0) {
                    if (fingers >= 2) onScroll(x, y) else onMove(x, y)
                    pendingX -= x
                    pendingY -= y
                }
                lastFlush = now
            }
        }
        val end = System.currentTimeMillis()
        val x = pendingX.roundToInt()
        val y = pendingY.roundToInt()
        if (x != 0 || y != 0) if (maxFingers >= 2) onScroll(x, y) else onMove(x, y)

        if (dragging) {
            onButton(false)
        } else if (moved < 14f && end - startTime < 260) {
            if (maxFingers >= 2) {
                onClick(1, 1)
            } else {
                val double = end - lastTapEnd < 320
                onClick(0, if (double) 2 else 1)
                lastTapEnd = if (double) 0 else end
            }
        }
        first.consume()
    }
}
