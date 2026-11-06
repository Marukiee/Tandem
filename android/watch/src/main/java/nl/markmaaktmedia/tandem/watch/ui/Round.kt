package nl.markmaaktmedia.tandem.watch.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A row of a round list that is a surface. Pass it with `SurfaceTransformation`: it shrinks and rounds off towards the rim, as in Google's own watch apps. */
fun TransformingLazyColumnItemScope.rimRow(spec: TransformationSpec): Modifier =
    Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem()

/** The same for what has no surface of its own, such as text. */
fun TransformingLazyColumnItemScope.rimContent(spec: TransformationSpec): Modifier =
    rimRow(spec).graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } }

/**
 * The padding of the scaffold with more room at the top. It counts on a short header there, and a wide button against the top of a circle
 * loses its corners and can never be scrolled to where all of it shows.
 */
fun PaddingValues.withRoomAbove(): PaddingValues = PaddingValues(
    start = calculateStartPadding(LayoutDirection.Ltr),
    end = calculateEndPadding(LayoutDirection.Ltr),
    top = calculateTopPadding() + 18.dp,
    bottom = calculateBottomPadding(),
)

/** Long text that moves slides out of sight softly, instead of being cut off by a hard line. */
fun Modifier.fadedSides(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val edge = 14.dp.toPx() / size.width
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
        blendMode = BlendMode.DstIn,
    )
}

/** How deep the scallops of a [CookieShape] are, as a part of its radius. */
const val COOKIE_DEPTH = 0.07f

/**
 * A circle with soft scallops, the cookie of Material You. Written out here because `graphics-shapes` would be a new dependency for one
 * shape. A [depth] of 0 is the plain circle, so animating that one number morphs between the two.
 */
class CookieShape(private val lobes: Int = 9, private val depth: Float = COOKIE_DEPTH) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = min(size.width, size.height) / 2f
        val path = Path()
        val steps = lobes * STEPS_PER_LOBE
        for (step in 0 until steps) {
            val angle = 2f * PI.toFloat() * step / steps
            val reach = radius * (1f - depth + depth * cos(lobes * angle))
            val x = size.width / 2f + reach * cos(angle)
            val y = size.height / 2f + reach * sin(angle)
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private companion object {
        const val STEPS_PER_LOBE = 16
    }
}

/** An icon on a cookie. The colours fade, because this usually says how something is doing. */
@Composable
fun CookieIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val shape = remember { CookieShape() }
    val fill by animateColorAsState(container, TandemMotion.fadeSpec(), label = "cookieFill")
    val tint by animateColorAsState(content, TandemMotion.fadeSpec(), label = "cookieTint")
    Box(modifier.size(size).background(fill, shape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(size * 0.58f))
    }
}

/** A cookie that slowly turns under an icon that stays upright, for waiting on something. */
@Composable
fun TurningCookieIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val shape = remember { CookieShape() }
    val fill = MaterialTheme.colorScheme.primaryContainer
    val turn by rememberInfiniteTransition(label = "cookieTurn").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 16_000, easing = LinearEasing)),
        label = "cookieTurnAngle",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }.background(fill, shape))
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.5f))
    }
}
