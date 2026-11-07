package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a [PullSheet] rests: gone below the screen, as high as what it holds (up to most of the screen), or pulled up to the top. */
enum class SheetSpot { Hidden, Half, Full }

/** The place of a [PullSheet], and the way to close it with its own animation. */
class PullSheetState internal constructor(internal val drag: AnchoredDraggableState<SheetSpot>) {
    internal var height by mutableFloatStateOf(0f)
    internal var halfAt by mutableFloatStateOf(0f)
    internal var shown by mutableStateOf(false)

    /** True once it is on its way out: what is in it should not start anything new. */
    var closing by mutableStateOf(false)
        private set

    /** How far the sheet is below its usual height, in pixels, for something that floats over it and should leave with it. */
    val below: Float get() = ((drag.offset.takeUnless { it.isNaN() } ?: height) - halfAt).coerceAtLeast(0f)

    /** Slides the sheet away; the dim behind it goes with it. Returns when it is gone. */
    suspend fun close() {
        if (closing) return
        closing = true
        drag.animateTo(SheetSpot.Hidden, TandemMotion.spatial())
    }
}

@Composable
fun rememberPullSheetState(): PullSheetState = remember { PullSheetState(AnchoredDraggableState(SheetSpot.Hidden)) }

/**
 * A sheet that comes up from the bottom of a see-through activity: as high as what it holds, pulled up to the full screen, pulled or
 * flung down to close. Its content scrolls, and a pull up first raises the sheet, a pull down at the top of the content lowers it. The
 * dim behind it follows the sheet itself, so opening, closing and dragging never leave it fading on its own. [onGone] is called once
 * the sheet has left, however it was closed; the activity finishes there. A tap on the dim closes it when [dismissable] says so.
 */
@Composable
fun PullSheet(
    state: PullSheetState,
    onGone: () -> Unit,
    dismissable: () -> Boolean = { true },
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sheet = state.drag
    LaunchedEffect(sheet.settledValue) {
        if (sheet.settledValue != SheetSpot.Hidden) state.shown = true else if (state.shown) onGone()
    }
    val close: () -> Unit = { scope.launch { state.close() } }
    androidx.activity.compose.BackHandler { if (dismissable()) close() }
    val flingFast = with(density) { 900.dp.toPx() }
    val settle: suspend (Float) -> Unit = settle@{ velocity ->
        val at = sheet.offset
        if (at.isNaN()) return@settle
        // Quick: on to the next spot that way. Slow: the nearest one.
        val target = (if (abs(velocity) > flingFast) sheet.anchors.closestAnchor(at, velocity > 0) else null) ?: sheet.anchors.closestAnchor(at) ?: return@settle
        sheet.animateTo(target, TandemMotion.spatial())
    }
    val nested = remember(sheet) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0 && source == NestedScrollSource.UserInput) Offset(0f, sheet.dispatchRawDelta(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput) Offset(0f, sheet.dispatchRawDelta(available.y)) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                val at = sheet.offset
                if (available.y < 0 && !at.isNaN() && at > sheet.anchors.minPosition()) {
                    settle(available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Only when the sheet itself was moved: content flung back to its top leaves the sheet where it is.
                val at = sheet.offset
                if (at.isNaN() || at == sheet.anchors.positionOf(sheet.settledValue)) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        val top = WindowInsets.statusBars.getTop(density) + with(density) { 12.dp.toPx() }
        var contentHeight by remember { mutableIntStateOf(0) }
        val most = minOf(with(density) { 640.dp.toPx() }, height * 0.72f)
        val halfAt = (height - minOf(contentHeight.toFloat(), most)).coerceIn(top, height - 1f)
        state.height = height
        state.halfAt = halfAt
        // As what it holds grows or shrinks (a page that animates its size), the usual height follows it frame by frame.
        LaunchedEffect(height, top, halfAt, contentHeight > 0) {
            if (contentHeight == 0) return@LaunchedEffect
            val anchors = DraggableAnchors {
                SheetSpot.Hidden at height
                SheetSpot.Half at halfAt
                if (top < halfAt - 1f) SheetSpot.Full at top
            }
            val keep = if (sheet.targetValue == SheetSpot.Full && !anchors.hasPositionFor(SheetSpot.Full)) SheetSpot.Half else sheet.targetValue
            sheet.updateAnchors(anchors, keep)
            if (!state.shown && !state.closing && !sheet.isAnimationRunning) sheet.animateTo(SheetSpot.Half, TandemMotion.spatial())
        }
        val at = sheet.offset.takeUnless { it.isNaN() } ?: height
        val dim = 0.5f * ((height - at) / (height - halfAt).coerceAtLeast(1f)).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (dismissable()) close() },
        )
        Box(
            Modifier.offset { IntOffset(0, at.roundToInt()) }
                .fillMaxWidth().height(with(density) { (height - top).toDp() })
                .nestedScroll(nested)
                .anchoredDraggable(
                    sheet, Orientation.Vertical,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(sheet, positionalThreshold = { it * 0.5f }, animationSpec = TandemMotion.spatial()),
                )
                .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).onSizeChanged { contentHeight = it.height },
                content = content,
            )
        }
        overlay()
    }
}
