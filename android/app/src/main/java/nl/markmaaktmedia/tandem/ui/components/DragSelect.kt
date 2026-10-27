package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Choosing rows of a list the way Google Photos does: holding one down chooses it, and dragging on without letting go chooses every row
 * between it and the finger (dragging back lets them go again). Near the top or bottom edge the list scrolls along by itself, faster the
 * closer the finger is. While something is chosen a tap chooses or lets go of a row instead of opening it.
 *
 * Everything is looked at in the first pass of the touch, before the rows and the list see it: once a hold turned into choosing, the
 * rows do not take it as a tap and the list does not scroll under the finger. A tap or a scroll that starts as one is left alone.
 *
 * [keys] are the keys of the rows that can be chosen, in their order in the list. [bottomInset] is what covers the bottom of the list
 * (the navigation bar of the app), so scrolling starts above it.
 */
@Composable
fun Modifier.dragSelect(
    state: LazyListState,
    keys: List<String>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    bottomInset: Dp = 0.dp,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentKeys by rememberUpdatedState(keys)
    val currentSelected by rememberUpdatedState(selected)
    val change by rememberUpdatedState(onChange)
    // Where the finger is while choosing by dragging, null otherwise; with the row it started on and what was chosen before.
    var finger by remember { mutableStateOf<Float?>(null) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var before by remember { mutableStateOf<Set<String>>(emptySet()) }
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val fastest = with(density) { 22.dp.toPx() }
    val inset = with(density) { bottomInset.toPx() }

    fun keyAt(y: Float): String? {
        val info = state.layoutInfo
        // The offsets of the rows start after the padding at the top of the list.
        val at = y + info.viewportStartOffset
        val key = info.visibleItemsInfo.firstOrNull { at >= it.offset && at < it.offset + it.size }?.key as? String
        return key?.takeIf { it in currentKeys }
    }

    fun reach(y: Float) {
        val from = currentKeys.indexOf(anchor ?: return)
        val to = currentKeys.indexOf(keyAt(y) ?: return)
        if (from < 0 || to < 0) return
        val range = currentKeys.subList(minOf(from, to), maxOf(from, to) + 1)
        val next = before + range
        if (next != currentSelected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(next)
        }
    }

    LaunchedEffect(finger != null) {
        while (true) {
            val y = finger ?: break
            val bottom = state.layoutInfo.viewportSize.height - inset
            val speed = when {
                y < edge -> -fastest * ((edge - y) / edge).coerceIn(0f, 1f)
                y > bottom - edge -> fastest * ((y - (bottom - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f && state.scrollBy(speed) != 0f) reach(y)
            withFrameNanos { }
        }
    }

    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val choosing = currentSelected.isNotEmpty()
            // Null when the finger was held still long enough; otherwise how the touch ended: lifted (a tap) or moved (a scroll).
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val touch = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!touch.pressed) {
                        // While choosing, a tap chooses or lets go of the row, and does not open it.
                        if (choosing) {
                            keyAt(touch.position.y)?.let { key ->
                                touch.consume()
                                change(if (key in currentSelected) currentSelected - key else currentSelected + key)
                            }
                        }
                        return@withTimeoutOrNull true
                    }
                    if ((touch.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (ended != null) return@awaitEachGesture
            val key = keyAt(down.position.y) ?: return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            anchor = key
            before = currentSelected - key
            change(currentSelected + key)
            finger = down.position.y
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val touch = event.changes.firstOrNull { it.id == down.id } ?: break
                    touch.consume()
                    if (!touch.pressed) break
                    finger = touch.position.y
                    reach(touch.position.y)
                }
            } finally {
                finger = null
                anchor = null
            }
        }
    }
}
