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

    // Predictive back: while the finger drags, the screen shrinks and slides and the one
    // below it shows through. Letting go past the threshold finishes the slide and then
    // pops, without the usual route transition on top (the gesture already was the animation).
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { androidx.compose.runtime.mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var committed by remember { androidx.compose.runtime.mutableStateOf(false) }
    // True from the moment the outgoing page has slid away until the page underneath has been
    // composed for real. Hiding the layer keeps the gap to a couple of cheap frames.
    var hideTop by remember { androidx.compose.runtime.mutableStateOf(false) }
    // Read as a derived value: reading backProgress.value here would recompose this whole
    // screen on every touch event of the gesture, which is what made it stutter.
    val showBelow by remember { androidx.compose.runtime.derivedStateOf { backProgress.value > 0f } }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = nav.stack.size > 1) { events ->
        try {
            events.collect { event ->
                backEdge = event.swipeEdge
                backProgress.snapTo(event.progress)
            }
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f, TandemMotion.spatial()) }
            throw e
        }
        // The finish runs in this screen's own scope, not in the handler's: popping the last
        // page switches the handler off, and that would cancel the finish halfway and leave
        // the outgoing page hidden for good.
        scope.launch {
            committed = true
            try {
                backProgress.animateTo(1f, tween(TandemMotion.DurationFast))
                // The page underneath is already on screen, so it can take over without a
                // seam: hide the outgoing layer, let the real page compose behind the copy,
                // then swap in one frame. The route transition is decided while the next frame
                // is composed, so the "no transition" flag has to outlive the pop by a couple
                // of frames.
                hideTop = true
                nav.pop()
                withFrameNanos { }
                withFrameNanos { }
            } finally {
                withContext(NonCancellable) {
                    backProgress.snapTo(0f)
                    hideTop = false
                    committed = false
                }
            }
        }
    }

    var lastSize by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val target = nav.top
    val below = nav.stack.getOrNull(nav.stack.size - 2)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (below != null && showBelow) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = backProgress.value
                    val s = 0.94f + 0.06f * p
                    scaleX = s
                    scaleY = s
                },
            ) { RouteContent(below, nav) }
        }
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
        AnimatedContent(
            targetState = target,
            transitionSpec = {
                if (committed) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val deeper = nav.stack.size >= lastSize
                    lastSize = nav.stack.size
                    // Pages open out of the item that was tapped (shared bounds), so the page
                    // itself only fades and settles instead of sliding across the screen.
                    if (deeper) {
                        (fadeIn(tween(TandemMotion.DurationMedium)) + scaleIn(TandemMotion.spatial(), initialScale = 0.97f)) togetherWith
                            fadeOut(tween(TandemMotion.DurationFast))
                    } else {
                        fadeIn(tween(TandemMotion.DurationMedium)) togetherWith
                            (fadeOut(tween(TandemMotion.DurationFast)) + scaleOut(TandemMotion.spatial(), targetScale = 0.97f))
                    }
                }
            },
            label = "route",
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val p = backProgress.value
                if (p > 0f) {
                    val s = 1f - 0.1f * p
                    scaleX = s
                    scaleY = s
                    val direction = if (backEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                    translationX = direction * p * 48.dp.toPx()
                    shape = RoundedCornerShape((28 * p).dp)
                    clip = true
                }
                // Not faded: an alpha below one forces the whole screen into an offscreen layer.
                if (hideTop) alpha = 0f
            },
        ) { route ->
            CompositionLocalProvider(LocalRouteVisibility provides this) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { RouteContent(route, nav) }
            }
        }
        }
        }
        // Over every screen: an update is worth seeing wherever you are in the app.
        UpdateBanner(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
    }
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
