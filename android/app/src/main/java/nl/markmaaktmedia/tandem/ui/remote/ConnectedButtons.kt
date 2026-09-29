package nl.markmaaktmedia.tandem.ui.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * The buttons of a connected group, the Material 3 Expressive way: they sit against
 * each other, the ends of the group are fully round and every corner that faces a
 * neighbour is only slightly rounded, so a row of buttons reads as one cut slab.
 * Pressing one rounds all of its corners, which is what makes the press feel like the
 * button coming loose from the group.
 */

/** The radius of a corner that faces a neighbour. */
val JoinRadius = 6.dp

/** The gap between buttons of a group. Same 2dp as the grouped slabs elsewhere. */
val JoinGap = 2.dp

/** Which corners of a button face a neighbour and so stay small. */
@Stable
class Joins(val topStart: Boolean, val topEnd: Boolean, val bottomEnd: Boolean, val bottomStart: Boolean) {
    companion object {
        val None = Joins(false, false, false, false)

        /** The [index]th of [count] buttons side by side. */
        fun row(index: Int, count: Int) = Joins(
            topStart = index > 0, topEnd = index < count - 1,
            bottomEnd = index < count - 1, bottomStart = index > 0,
        )

        /** The [index]th of [count] buttons stacked. */
        fun column(index: Int, count: Int) = Joins(
            topStart = index > 0, topEnd = index > 0,
            bottomEnd = index < count - 1, bottomStart = index < count - 1,
        )
    }
}

/** Whether a finger is on a button. Held outside so a group can react to it. */
@Stable
class PressState {
    var down by mutableStateOf(false)
        internal set
}

@Composable
fun rememberPressState() = remember { PressState() }

@Stable
class GroupColors(val container: Color, val content: Color, val pressedContainer: Color, val pressedContent: Color)

object GroupTone {
    @Composable
    fun neutral() = MaterialTheme.colorScheme.let {
        GroupColors(it.surfaceContainerHigh, it.onSurfaceVariant, it.primaryContainer, it.onPrimaryContainer)
    }

    /** The one button of a group that is the point of it, like play. */
    @Composable
    fun accent() = MaterialTheme.colorScheme.let {
        GroupColors(it.primaryContainer, it.onPrimaryContainer, it.primary, it.onPrimary)
    }

    /** A toggle that is on. */
    @Composable
    fun selected() = MaterialTheme.colorScheme.let {
        GroupColors(it.primary, it.onPrimary, it.primary, it.onPrimary)
    }

    /** A button that is held: the colour says it is down for as long as it is. */
    @Composable
    fun held() = MaterialTheme.colorScheme.let {
        GroupColors(it.surfaceContainerHigh, it.onSurface, it.primary, it.onPrimary)
    }
}

/**
 * One button of a connected group.
 *
 * It answers a finger three ways at once, all on springs: the corners round out, the
 * button dips to the shared 0.96 and the colour moves to its pressed tone. Colour is
 * a tween, shape and size are springs, never the other way round.
 *
 * [onDown] fires the moment a finger lands, [onUp] when it leaves with `inside` saying
 * whether it left over the button. A click uses `onUp(inside)`, a key or a held mouse
 * button uses both. [onRepeat] keeps firing while the finger stays, like a key held
 * down on a keyboard.
 */
@Composable
fun GroupButton(
    press: PressState,
    height: Dp,
    joins: Joins,
    colors: GroupColors,
    modifier: Modifier = Modifier,
    description: String? = null,
    round: Boolean = false,
    joinRadius: Dp = JoinRadius,
    haptics: Boolean = true,
    onDown: () -> Unit = {},
    onRepeat: (() -> Unit)? = null,
    onUp: (inside: Boolean) -> Unit = {},
    content: @Composable BoxScope.(tint: Color) -> Unit,
) {
    val full = height / 2
    val open = press.down || round
    val cornerSpec = TandemMotion.springy<Dp>()
    val topStart by animateDpAsState(if (open || !joins.topStart) full else joinRadius, cornerSpec, label = "cornerTS")
    val topEnd by animateDpAsState(if (open || !joins.topEnd) full else joinRadius, cornerSpec, label = "cornerTE")
    val bottomEnd by animateDpAsState(if (open || !joins.bottomEnd) full else joinRadius, cornerSpec, label = "cornerBE")
    val bottomStart by animateDpAsState(if (open || !joins.bottomStart) full else joinRadius, cornerSpec, label = "cornerBS")

    val scale by animateFloatAsState(if (press.down) TandemMotion.PressedScale else 1f, TandemMotion.bouncy(), label = "dip")
    val colourSpec = tween<Color>(TandemMotion.DurationFast, easing = TandemMotion.Standard)
    val container by animateColorAsState(if (press.down) colors.pressedContainer else colors.container, colourSpec, label = "container")
    val tint by animateColorAsState(if (press.down) colors.pressedContent else colors.content, colourSpec, label = "tint")

    val haptic = LocalHapticFeedback.current
    val downNow by rememberUpdatedState(onDown)
    val upNow by rememberUpdatedState(onUp)
    val repeatNow by rememberUpdatedState(onRepeat)
    val hapticsNow by rememberUpdatedState(haptics)

    Box(
        modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CornerShape(topStart, topEnd, bottomEnd, bottomStart))
            .background(container)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (description != null) contentDescription = description
                onClick { downNow(); upNow(true); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    press.down = true
                    if (hapticsNow) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    downNow()
                    var inside = false
                    try {
                        var nextRepeat = down.uptimeMillis + RepeatDelayMs
                        while (true) {
                            val repeating = repeatNow != null
                            val event = if (repeating) {
                                val wait = nextRepeat - android.os.SystemClock.uptimeMillis()
                                withTimeoutOrNull(wait.coerceAtLeast(1)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                repeatNow?.invoke()
                                nextRepeat = android.os.SystemClock.uptimeMillis() + RepeatIntervalMs
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                inside = change.position.x in 0f..size.width.toFloat() &&
                                    change.position.y in 0f..size.height.toFloat()
                                break
                            }
                        }
                    } finally {
                        press.down = false
                        // Also on cancellation: a held mouse button must never be left down.
                        upNow(inside)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

private const val RepeatDelayMs = 400L
private const val RepeatIntervalMs = 45L

/**
 * A rounded rectangle whose four corners are set one by one, each clamped to half the
 * shorter side. Compose's own version scales every corner down together when two
 * neighbours do not fit, which would pull the small joined corners out of shape
 * exactly while the others are opening up.
 */
private class CornerShape(val topStart: Dp, val topEnd: Dp, val bottomEnd: Dp, val bottomStart: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val limit = minOf(size.width, size.height) / 2f
        fun radius(dp: Dp) = CornerRadius(with(density) { dp.toPx() }.coerceIn(0f, limit))
        val start = layoutDirection == LayoutDirection.Ltr
        return Outline.Rounded(
            RoundRect(
                left = 0f, top = 0f, right = size.width, bottom = size.height,
                topLeftCornerRadius = radius(if (start) topStart else topEnd),
                topRightCornerRadius = radius(if (start) topEnd else topStart),
                bottomRightCornerRadius = radius(if (start) bottomEnd else bottomStart),
                bottomLeftCornerRadius = radius(if (start) bottomStart else bottomEnd),
            ),
        )
    }
}
