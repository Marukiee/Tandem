package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import kotlin.math.abs

/**
 * A row of choices with one pill that travels between them.
 *
 * The same idea as the navigation bar, and here for the same reason: three chips that
 * each recolour themselves are three things changing at once, and the eye has to find
 * the new selection. One pill that moves is a single object going somewhere, and the
 * eye follows it there.
 *
 * Unlike the navigation bar the slots are not equal width, because "Unread" is not as
 * wide as "Needs attention". Each label reports where it ended up, and the pill
 * animates to that position and that width, so it grows and shrinks as it travels.
 *
 * The stretch is the same trick as the nav bar: the pill scales along its direction of
 * travel in proportion to how far it still has to go, which makes a hop to the
 * neighbour and a jump across the row look like different distances rather than the
 * same animation twice.
 */
@Composable
fun <T> SegmentedPillRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (options.isEmpty()) return

    val density = LocalDensity.current
    // Filled in on the first layout pass. Until then the pill has nowhere to be, so it
    // is simply not drawn rather than parked at zero and sliding in from the left.
    val slots = remember(options) { mutableStateMapOf<Int, Slot>() }
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val target = slots[selectedIndex]

    Box(
        modifier = modifier
            .clip(PillShape)
            .background(containerColor)
            .padding(Inset),
    ) {
        if (target != null) {
            val x = remember { Animatable(target.x) }
            val width = remember { Animatable(target.width) }

            LaunchedEffect(target) {
                x.animateTo(target.x, animationSpec = TandemMotion.spatial())
            }
            LaunchedEffect(target) {
                width.animateTo(target.width, animationSpec = TandemMotion.spatial())
            }

            val remaining = abs(target.x - x.value) / target.width.coerceAtLeast(1f)
            val stretch = 1f + (remaining.coerceIn(0f, 1.5f) * StretchFactor)

            Box(
                modifier = Modifier
                    .offset(x = with(density) { x.value.toDp() })
                    .width(with(density) { width.value.toDp() })
                    .height(SlotHeight)
                    .graphicsLayer {
                        scaleX = stretch
                        scaleY = 1f - (remaining.coerceIn(0f, 1.5f) * SquashFactor)
                    }
                    .clip(PillShape)
                    .background(indicatorColor)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val content by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = TandemMotion.colourSpec(),
                    label = "segmentContent",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(SlotHeight)
                        .onGloballyPositioned { coordinates ->
                            slots[index] = Slot(
                                x = coordinates.positionInParent().x,
                                width = coordinates.size.width.toFloat(),
                            )
                        }
                        .clip(PillShape)
                        .bouncyClickable { onSelect(option) }
                        .padding(horizontal = LabelPadding),
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                    )
                }
            }
        }
    }
}

/** Where one choice sits inside the row, in pixels. */
private data class Slot(val x: Float, val width: Float)

private val Inset = 4.dp
private val SlotHeight = 38.dp
private val LabelPadding = 16.dp

/** Matched to the navigation bar, so the two pills move like the same object. */
private const val StretchFactor = 0.16f
private const val SquashFactor = 0.05f
