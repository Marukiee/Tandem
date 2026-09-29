package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * The loading mark: the launcher icon's tilted capsule, turning.
 *
 * It does not spin at a constant rate. Each cycle is two eased turns with a held beat
 * between them, so it winds up, lands back on its own tilt, pauses just long enough to
 * be read as the app's mark rather than a generic spinner, and goes again. A steady
 * rotation reads as a progress spinner from any app; this reads as this app waiting.
 *
 * The rest points at -45 degrees, matching the icon exactly, and every keyframe is a
 * whole number of turns from there, so it never comes to rest at an angle the icon
 * never sits at.
 */
@Composable
fun PillSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    active: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "pillSpinner")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 720f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = TandemMotion.SpinnerCycleMillis
                // First turn, eased in and out.
                0f at 0 using TandemMotion.Emphasised
                360f at (TandemMotion.SpinnerCycleMillis * 40 / 100)
                // The beat. Same value twice is what holds it still.
                360f at (TandemMotion.SpinnerCycleMillis * 55 / 100) using TandemMotion.Emphasised
                720f at (TandemMotion.SpinnerCycleMillis * 95 / 100)
                720f at TandemMotion.SpinnerCycleMillis
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    // A slow breath underneath, so the held beat is never completely dead.
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Canvas(modifier = modifier.size(size)) {
        val side = minOf(this.size.width, this.size.height)
        val capsuleWidth = side * CAPSULE_WIDTH_RATIO
        val capsuleHeight = side * CAPSULE_HEIGHT_RATIO
        val topLeft = Offset(
            x = (this.size.width - capsuleWidth) / 2f,
            y = (this.size.height - capsuleHeight) / 2f,
        )

        scale(if (active) breath else 1f) {
            rotate(degrees = REST_ANGLE + if (active) rotation else 0f) {
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(capsuleWidth, capsuleHeight),
                    cornerRadius = CornerRadius(capsuleHeight / 2f),
                )
            }
        }
    }
}

/** The spinner with a line under it, for a wait long enough to need explaining. */
@Composable
fun PillLoader(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PillSpinner(size = size, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The mark standing still, for empty states and headers. */
@Composable
fun PillMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(modifier = modifier) {
        PillSpinner(size = size, color = color, active = false)
    }
}

/** Same proportions as the launcher icon: a 22 by 16 capsule in a 108 square. */
private const val CAPSULE_WIDTH_RATIO = 0.52f
private const val CAPSULE_HEIGHT_RATIO = 0.22f
private const val REST_ANGLE = -45f
