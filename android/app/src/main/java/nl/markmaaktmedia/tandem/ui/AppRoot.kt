package nl.markmaaktmedia.tandem.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Easing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.TandemService
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PillLoader
import nl.markmaaktmedia.tandem.ui.components.PillNavItem
import nl.markmaaktmedia.tandem.ui.components.PillNavigationBar
import nl.markmaaktmedia.tandem.ui.screens.AccessScreen
import nl.markmaaktmedia.tandem.ui.screens.AppearanceScreen
import nl.markmaaktmedia.tandem.ui.screens.DeviceDetailScreen
import nl.markmaaktmedia.tandem.ui.screens.DevicesScreen
import nl.markmaaktmedia.tandem.ui.screens.MirrorAppsScreen
import nl.markmaaktmedia.tandem.ui.screens.OnboardingScreen
import nl.markmaaktmedia.tandem.ui.screens.PairScreen
import nl.markmaaktmedia.tandem.ui.screens.RemoteScreen
import nl.markmaaktmedia.tandem.ui.screens.SettingsScreen
import nl.markmaaktmedia.tandem.ui.screens.TransfersScreen
import nl.markmaaktmedia.tandem.ui.update.UpdateBanner
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val graph = context.graph
    val onboarded by graph.prefs.onboarded.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    // Once set up, the background service keeps the engine alive whatever the screens do.
    LaunchedEffect(onboarded) {
        if (onboarded == true) TandemService.start(context)
    }
    // Every time the app comes to the front, so a release from an hour ago is not missed
    // just because the process has been alive for days.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        if (onboarded == true) scope.launch { graph.updater.checkIfDue() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (onboarded) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PillLoader(label = stringResource(R.string.app_name)) }
            false -> OnboardingScreen(onFinished = { scope.launch { graph.prefs.setOnboarded(true) } })
            true -> MainNavigation()
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MainNavigation() {
    val nav = androidx.compose.runtime.saveable.rememberSaveable(saver = NavSaver) { Nav() }
    val startAtPair by LocalContext.current.graph.startAtPair.collectAsState()
    val pairLink by LocalContext.current.graph.pairLink.collectAsState()
    LaunchedEffect(pairLink) { if (pairLink != null) nav.push(Route.Pair) }
    val pairFlag = LocalContext.current.graph.startAtPair
    LaunchedEffect(startAtPair) {
        if (startAtPair) {
            nav.push(Route.Pair)
            pairFlag.value = false
        }
    }
    BackHandler(enabled = nav.stack.size == 1 && nav.tab != 0) { nav.tab = 0 }

    // One transition drives every page change, including the back gesture. While the finger
    // drags, the transition is *seeked*: the page beneath is composed once, at the start, and
    // the very same transition (the fade, the shrink, and the shared bounds that pull the page
    // back into the card it came from) plays out under the finger and carries on when it lets
    // go. Nothing is composed twice and nothing is swapped at the end, which is what made the
    // earlier hand-made version stutter.
    val seekable = remember { androidx.compose.animation.core.SeekableTransitionState(nav.top) }
    LaunchedEffect(nav.top) {
        if (seekable.targetState != nav.top) seekable.animateTo(nav.top)
    }
    val transition = androidx.compose.animation.core.rememberTransition(seekable, label = "route")

    PredictiveBackHandler(enabled = nav.stack.size > 1) { events ->
        val target = nav.stack[nav.stack.size - 2]
        try {
            events.collect { event -> seekable.seekTo(event.progress, target) }
            seekable.animateTo(target)
            // Last, so the handler switching itself off cannot cut the animation short.
            nav.pop()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { seekable.snapTo(nav.top) }
            throw e
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        transition.AnimatedContent(
            transitionSpec = {
                // The Material shared axis that system apps use: the new page comes in from a
                // third of the screen away and fades up, the old one slides a third the other
                // way and shrinks a little behind it. Going back mirrors it, and the page that
                // leaves goes all the way out. The same spec is what the back gesture seeks.
                val forward = routeDepth(targetState) > routeDepth(initialState)
                val ease = tween<Float>(SharedAxisMillis, easing = Emphasized)
                val slide = tween<IntOffset>(SharedAxisMillis, easing = Emphasized)
                if (forward) {
                    (slideInHorizontally(slide) { it / 3 } + fadeIn(ease)) togetherWith
                        (slideOutHorizontally(slide) { -it / 3 } + scaleOut(ease, targetScale = 0.92f))
                } else {
                    ((slideInHorizontally(slide) { -it / 3 } + fadeIn(ease)) togetherWith
                        (slideOutHorizontally(tween(SharedAxisMillis, easing = Easing { f -> f * f * f })) { it } +
                            scaleOut(ease, targetScale = 0.85f)))
                        // The page going out stays on top of the one coming back.
                        .apply { targetContentZIndex = -1f }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { route ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { RouteContent(route, nav) }
        }
        // Over every screen: an update is worth seeing wherever you are in the app.
        UpdateBanner(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

private const val SharedAxisMillis = 350
private val Emphasized = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** How deep a page sits, so a transition knows whether it is opening or going back. */
private fun routeDepth(route: Route): Int = when (route) {
    Route.Home -> 0
    is Route.Remote -> 2
    else -> 1
}

@Composable
private fun RouteContent(route: Route, nav: Nav) {
    // Every page except the home tabs is the far end of a shared transition from its item.
    Box(if (route == Route.Home) Modifier else Modifier.routeBounds(routeKey(route))) { RouteBody(route, nav) }
}

@Composable
private fun RouteBody(route: Route, nav: Nav) {
    when (route) {
        Route.Home -> HomeTabs(nav)
        is Route.Device -> DeviceDetailScreen(route.id, onBack = { nav.pop() }, onRemote = { nav.push(Route.Remote(it)) })
        Route.Pair -> PairScreen(onBack = { nav.pop() }, onPaired = { nav.pop() })
        is Route.Remote -> RemoteScreen(route.id, onBack = { nav.pop() })
        Route.Access -> AccessScreen(onBack = { nav.pop() })
        Route.MirrorApps -> MirrorAppsScreen(onBack = { nav.pop() })
        Route.Appearance -> AppearanceScreen(onBack = { nav.pop() })
        Route.Developer -> nl.markmaaktmedia.tandem.ui.screens.DeveloperScreen(onBack = { nav.pop() }, onOpen = { nav.push(it) })
        Route.OnboardingPreview -> OnboardingScreen(onFinished = { nav.pop() }, preview = true)
    }
}

@Composable
private fun HomeTabs(nav: Nav) {
    val items = listOf(
        PillNavItem(stringResource(R.string.tab_devices), { TandemIcons.Devices }, { TandemIcons.DevicesFilled }),
        PillNavItem(stringResource(R.string.tab_transfers), { TandemIcons.Transfers }, { TandemIcons.TransfersFilled }),
        PillNavItem(stringResource(R.string.tab_settings), { TandemIcons.Settings }, { TandemIcons.SettingsFilled }),
    )
    val barSpace = 66.dp + 24.dp

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = nav.tab,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(TandemMotion.spatial()) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(TandemMotion.DurationMedium))) togetherWith
                    (slideOutHorizontally(TandemMotion.spatial()) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(TandemMotion.DurationFast)))
            },
            label = "tabs",
        ) { tab ->
            when (tab) {
                0 -> DevicesScreen(onOpenDevice = { nav.push(Route.Device(it)) }, onPair = { nav.push(Route.Pair) }, bottomPadding = barSpace)
                1 -> TransfersScreen(bottomPadding = barSpace)
                else -> SettingsScreen(bottomPadding = barSpace, onOpen = { nav.push(it) })
            }
        }
        PillNavigationBar(
            items = items,
            selectedIndex = nav.tab,
            onSelect = { nav.tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
