package nl.markmaaktmedia.tandem.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * Opening a page from a row or card: the page grows out of what was tapped, and shrinks
 * back into it on the way out. The row and the page share a key, and the shared bounds
 * take care of the rest.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The route transition the shared bounds belong to. Not the one of the tab pager inside a page. */
val LocalRouteVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

fun routeKey(route: Route): String = when (route) {
    Route.Home -> "home"
    is Route.Device -> "device:${route.id}"
    Route.Pair -> "pair"
    is Route.Remote -> "remote:${route.id}"
    Route.Access -> "access"
    Route.MirrorApps -> "mirror"
    Route.Appearance -> "appearance"
    Route.Developer -> "developer"
    Route.OnboardingPreview -> "onboarding-preview"
}

/** Marks a tapped item, or the page it opens, as the two ends of one transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.routeBounds(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val visibility = LocalRouteVisibility.current ?: return this
    return with(shared) {
        this@routeBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visibility,
            enter = fadeIn(tween(TandemMotion.DurationMedium)),
            exit = fadeOut(tween(TandemMotion.DurationFast)),
            boundsTransform = BoundsTransform { _, _ -> TandemMotion.spatial() },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.Center),
            clipInOverlayDuringTransition = OverlayClip(CardSquircle),
        )
    }
}
