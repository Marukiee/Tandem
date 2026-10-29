package nl.markmaaktmedia.tandem.ui.remote

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Feeds the fingers on this element to a [TouchpadClassifier].
 *
 * The classifier is told about every event as it arrives. It used to be batched to one
 * update per 8 ms, which only added a frame of lag to a pointer that is judged on how
 * it feels under the finger. The one thing the clock is needed for is the press that
 * turns into a drag, and that wakes the loop by itself so the finger does not have to
 * wiggle first.
 *
 * Events are only consumed once the classifier has seen them, and after the children,
 * so this never hides touches from anything that wants them.
 */
fun Modifier.touchpad(classifier: TouchpadClassifier): Modifier = pointerInput(classifier) {
    awaitEachGesture {
        try {
            val first = awaitFirstDown(requireUnconsumed = false)
            first.consume()
            classifier.onFrame(first.uptimeMillis, listOf(Touch(first.id.value, first.position.x, first.position.y)))

            while (true) {
                val wait = classifier.deadline()?.let { (it - SystemClock.uptimeMillis()).coerceAtLeast(1L) }
                val event = if (wait == null) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
                if (event == null) {
                    classifier.onTimer(SystemClock.uptimeMillis())
                    continue
                }
                val down = event.changes.filter { it.pressed }
                // The screen samples a finger faster than it draws (240 times a second on some phones), and the samples
                // between two frames come along as history. One finger is fed every one of them, so the pointer moves as
                // often as the finger is measured instead of once a frame.
                down.singleOrNull()?.takeIf { event.changes.size == 1 }?.let { finger ->
                    for (h in finger.historical) classifier.onFrame(h.uptimeMillis, listOf(Touch(finger.id.value, h.position.x, h.position.y)))
                }
                classifier.onFrame(
                    event.changes.maxOf { it.uptimeMillis },
                    down.map { Touch(it.id.value, it.position.x, it.position.y) },
                )
                event.changes.forEach { it.consume() }
                if (down.isEmpty()) break
            }
        } finally {
            // Also when the screen goes away mid-gesture: a held button must go up.
            classifier.cancel()
        }
    }
}
