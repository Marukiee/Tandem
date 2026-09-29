package nl.markmaaktmedia.tandem.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize

/**
 * One place for how things move, so the whole app moves the same way.
 *
 * The split is deliberate. Anything that changes position or size uses a spring,
 * because a spring carries momentum and reads as a physical object being moved.
 * Anything that only changes colour or opacity uses a tween, because a bouncing
 * colour looks like a bug. Getting these two mixed up is what makes an app feel
 * almost right and slightly cheap.
 */
object TandemMotion {

    /**
     * The house easing. Fast out of the gate and a long settle, so a transition is
     * over before it is noticed but never lands with a snap.
     */
    val Standard = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** For something arriving that should feel deliberate rather than quick. */
    val Emphasised = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val DurationFast = 140
    const val DurationMedium = 260
    const val DurationSlow = 420

    /** Theme flips and other pure colour changes. */
    fun <T> colourSpec(): FiniteAnimationSpec<T> = tween(durationMillis = 320, easing = Standard)

    fun <T> fadeSpec(): FiniteAnimationSpec<T> = tween(durationMillis = DurationMedium, easing = Standard)

    /** Movement with no overshoot: sliding panels, scroll driven offsets. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Movement that is allowed a little overshoot: press feedback, tab indicators. */
    fun <T> springy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** The loosest one, kept for the press scale and the nav pill only. */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Size changes need their own visibility threshold to avoid a final jitter. */
    fun sizeSpring(): FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize(1, 1),
    )

    /** How far a pressed element shrinks. Small enough to feel, not to distract. */
    const val PressedScale = 0.96f

    /** The capsule spinner: two eased turns, with a breath between them. */
    const val SpinnerCycleMillis = 2600
}
