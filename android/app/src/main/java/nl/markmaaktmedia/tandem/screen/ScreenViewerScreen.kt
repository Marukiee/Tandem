package nl.markmaaktmedia.tandem.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.R
import androidx.compose.foundation.layout.width
import nl.markmaaktmedia.tandem.ui.remote.rememberPressState
import nl.markmaaktmedia.tandem.ui.remote.Joins
import nl.markmaaktmedia.tandem.ui.remote.GroupTone
import nl.markmaaktmedia.tandem.ui.remote.GroupButton
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PillLoader
import nl.markmaaktmedia.tandem.ui.components.PresenceDot
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.remote.KeyboardPanel
import nl.markmaaktmedia.tandem.ui.remote.MacKeys
import nl.markmaaktmedia.tandem.ui.remote.Touch
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemMediaInput
import kotlin.math.roundToInt

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The screen of a Mac on the phone, with the mouse and keyboard of that Mac under the fingers. */
@Composable
fun ScreenViewerScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val viewer = graph.screen
    val activity = context.findActivity()
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val devices by graph.host.devices.collectAsState()
    val device = devices.firstOrNull { it.id == id }
    val name = device?.name ?: ""
    val online = device?.online == true

    val state by viewer.state.collectAsState()
    val info by viewer.info.collectAsState()
    val picture by viewer.picture.collectAsState()
    val stalled by viewer.stalled.collectAsState()
    val stats by viewer.stats.collectAsState()

    // The session outlives the screen being rebuilt for a rotation, and ends when the person leaves.
    DisposableEffect(id) {
        viewer.open(id)
        onDispose { if (activity?.isChangingConfigurations != true) viewer.close() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewer.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewer.resume() }

    // Full screen, awake, and the system bars only on a swipe from the edge.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Where the picture sits. The numbers live outside the composition; `version` says when to look at them again.
    val transform = remember { ViewTransform() }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(info?.width, info?.height) {
        info?.let { transform.setVideo(it.width.toFloat(), it.height.toFloat()) }
        version++
    }

    var keyboard by remember { mutableStateOf(false) }
    var mods by remember { mutableIntStateOf(0) }
    var direct by remember { mutableStateOf(viewer.directTouch) }
    var showStats by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var barShownAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    // The bar is always there: after a few quiet seconds it only dims, so it is never a small handle to find at the top of the screen.
    var barVisible by remember { mutableStateOf(true) }
    var barBounds by remember { mutableStateOf(Rect.Zero) }
    var nameBounds by remember { mutableStateOf(Rect.Zero) }

    fun touchBar() {
        barShownAt = SystemClock.uptimeMillis()
        barVisible = true
    }

    // The bar steps aside after a few seconds, but stays while there is something to read in it or the keyboard is up.
    val settled = state is ViewerState.Streaming && picture && !stalled
    LaunchedEffect(barShownAt, settled, keyboard) {
        if (settled && !keyboard) {
            delay(BAR_MILLIS)
            barVisible = false
        } else {
            barVisible = true
        }
    }

    // ---- What the fingers do -----------------------------------------------------------------

    /** Where the pointer is thought to be on the picture, for keeping it in view when zoomed. Only an estimate. */
    var pointerFx by remember { mutableStateOf(0.5f) }
    var pointerFy by remember { mutableStateOf(0.5f) }
    val margin = with(density) { 56.dp.toPx() }

    fun sendKey(usage: Int, keyMods: Int, text: String = "") {
        viewer.send(TandemMediaInput.Key(usage.toUInt(), true, keyMods.toUShort(), text))
        viewer.send(TandemMediaInput.Key(usage.toUInt(), false, keyMods.toUShort(), ""))
    }

    val slop = LocalViewConfiguration.current.touchSlop
    val gestures = remember(viewer, transform, slop) {
        ViewerGestures(
            ViewerConfig(slop = slop),
            object : ViewerOutput {
                override fun pointerMove(dx: Float, dy: Float) {
                    val (x, y) = viewer.pointerMapper.move(dx, dy, transform.scale)
                    if (x == 0 && y == 0) return
                    viewer.send(TandemMediaInput.PointerRel(x.toShort(), y.toShort()))
                    if (transform.pictureWidth > 0f) {
                        pointerFx = (pointerFx + x * transform.scale / transform.pictureWidth).coerceIn(0f, 1f)
                        pointerFy = (pointerFy + y * transform.scale / transform.pictureHeight).coerceIn(0f, 1f)
                        if (transform.zoom > 1.02f) {
                            transform.keepVisible(pointerFx, pointerFy, margin)
                            version++
                        }
                    }
                }

                override fun pointerTo(x: Float, y: Float) {
                    val (fx, fy) = transform.toPicture(x, y)
                    pointerFx = fx
                    pointerFy = fy
                    viewer.send(TandemMediaInput.PointerAbs(fx, fy))
                }

                override fun button(button: Int, down: Boolean, clicks: Int) {
                    viewer.send(TandemMediaInput.Button(button.toUByte(), down, clicks.toUByte()))
                }

                override fun scroll(dx: Float, dy: Float) {
                    val (x, y) = viewer.pointerMapper.scroll(dx, dy, transform.scale)
                    if (x != 0 || y != 0) viewer.send(TandemMediaInput.Scroll(x.toShort(), y.toShort()))
                }

                override fun zoom(factor: Float, focalX: Float, focalY: Float) {
                    transform.zoomBy(factor, focalX, focalY)
                    version++
                }

                override fun pan(dx: Float, dy: Float) {
                    transform.panBy(dx, dy)
                    version++
                }

                override fun feedback(kind: ViewerFeedback) {
                    haptics.performHapticFeedback(
                        when (kind) {
                            ViewerFeedback.Click -> HapticFeedbackType.TextHandleMove
                            ViewerFeedback.LongPress -> HapticFeedbackType.LongPress
                            ViewerFeedback.DragStart -> HapticFeedbackType.GestureThresholdActivate
                        },
                    )
                }

                override fun dragging(active: Boolean) {
                    dragging = active
                }
            },
        )
    }
    gestures.direct = direct
    DisposableEffect(gestures) { onDispose { gestures.cancel() } }
    val streaming = state is ViewerState.Streaming
    LaunchedEffect(streaming) { if (!streaming) gestures.cancel() }

    fun typeText(text: String) {
        var armed = mods
        mods = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) viewer.send(TandemMediaInput.Text(plain.toString()))
            plain.clear()
        }
        for (char in text) {
            if (armed != 0 || char == '\n') {
                val stroke = MacKeys.strokeFor(char)
                val usage = stroke?.let { Keys.hidForMac(it.code) }
                if (stroke != null && usage != null) {
                    flush()
                    sendKey(usage, armed or stroke.mods)
                    armed = 0
                    continue
                }
                armed = 0
            }
            plain.append(char)
        }
        flush()
    }

    fun pressKey(code: Short) {
        val usage = Keys.hidForMac(code) ?: return
        sendKey(usage, mods)
        mods = 0
    }

    fun hardwareKey(event: KeyEvent): Boolean {
        if (!viewer.canControl) return false
        val down = when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> false
            else -> return false
        }
        val usage = Keys.hidForAndroid(event.key.nativeKeyCode) ?: return false
        var keyMods = 0
        if (event.isShiftPressed) keyMods = keyMods or Keys.SHIFT
        if (event.isCtrlPressed) keyMods = keyMods or Keys.CTRL
        if (event.isAltPressed) keyMods = keyMods or Keys.ALT
        if (event.isMetaPressed) keyMods = keyMods or Keys.META
        viewer.send(TandemMediaInput.Key(usage.toUInt(), down, keyMods.toUShort(), ""))
        return true
    }

    BackHandler {
        if (keyboard) keyboard = false else onBack()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // ---- The screen ---------------------------------------------------------------------------

    // The keyboard covers the lower part of the screen. The picture is fitted in what is left above it, and moves there with
    // a spring as the keyboard comes and goes, so what you are typing into stays in sight.
    var rootWidth by remember { mutableStateOf(0f) }
    var rootHeight by remember { mutableStateOf(0f) }
    var panelHeight by remember { mutableStateOf(0f) }
    val reserved by androidx.compose.animation.core.animateFloatAsState(
        if (keyboard) panelHeight else 0f, TandemMotion.spatial(), label = "keyboardRoom",
    )
    LaunchedEffect(rootWidth, rootHeight, reserved) {
        if (rootWidth > 0f && rootHeight > 0f) {
            transform.setView(rootWidth, (rootHeight - reserved).coerceAtLeast(rootHeight / 4f))
            version++
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                rootWidth = it.width.toFloat()
                rootHeight = it.height.toFloat()
            }
            .focusRequester(focus)
            .focusTarget()
            .onPreviewKeyEvent { if (keyboard) false else hardwareKey(it) },
    ) {
        // Reading the version is what makes the picture follow the fingers.
        val tick = version
        val picW = if (transform.videoWidth > 0f) transform.pictureWidth else transform.viewWidth
        val picH = if (transform.videoWidth > 0f) transform.pictureHeight else transform.viewHeight
        val picLeft = if (transform.videoWidth > 0f) transform.left else 0f
        val picTop = if (transform.videoWidth > 0f) transform.top else 0f

        VideoSurface(
            viewer = viewer,
            videoWidth = info?.width ?: 0,
            videoHeight = info?.height ?: 0,
            modifier = Modifier.layout { measurable, constraints ->
                val w = picW.roundToInt().coerceAtLeast(1)
                val h = picH.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(picLeft.roundToInt(), picTop.roundToInt()) }
            },
        )
        if (tick < 0) Box(Modifier.size(0.dp))

        // The fingers, over the picture.
        Box(
            Modifier
                .fillMaxSize()
                .viewerTouch(gestures) { touchBar() },
        )

        Overlay(
            state = state,
            picture = picture,
            stalled = stalled,
            online = online,
            name = name,
            onRetry = { viewer.retry() },
            onClose = onBack,
        )

        // The slim bar at the top. It stays: a quiet bar is dimmed, a touch brings it back to full.
        val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val barAlpha by androidx.compose.animation.core.animateFloatAsState(if (barVisible) 1f else 0.55f, TandemMotion.fadeSpec(), label = "barAlpha")
        Box(Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = barAlpha }) {
            ControlBar(
                name = name,
                online = online,
                state = state,
                stalled = stalled,
                control = info?.control == true,
                dragging = dragging,
                keyboardOn = keyboard,
                direct = direct,
                zoomed = transform.zoom > 1.01f,
                statsOpen = showStats,
                modifier = Modifier.windowInsetsPadding(insets).padding(horizontal = 12.dp, vertical = 8.dp),
                onBarBounds = { barBounds = it },
                onNameBounds = { nameBounds = it },
                onClose = onBack,
                onKeyboard = {
                    keyboard = !keyboard
                    touchBar()
                },
                onMode = {
                    direct = !direct
                    viewer.directTouch = direct
                    touchBar()
                },
                onFit = {
                    transform.reset()
                    version++
                    touchBar()
                },
                onStats = {
                    showStats = !showStats
                    touchBar()
                },
            )
        }

        // The numbers hang under the name, centred on it, and open and close like a drawer.
        AnimatedVisibility(
            visible = showStats,
            modifier = Modifier.layout { measurable, constraints ->
                val panel = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val gap = 8.dp.roundToPx()
                val x = (nameBounds.center.x - panel.width / 2f).roundToInt().coerceIn(gap, (constraints.maxWidth - panel.width - gap).coerceAtLeast(gap))
                val y = (barBounds.bottom + gap).roundToInt()
                layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
            },
            enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.spatial(), expandFrom = Alignment.Top) +
                scaleIn(TandemMotion.springy(), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.spatial(), shrinkTowards = Alignment.Top) +
                scaleOut(TandemMotion.fadeSpec(), targetScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)),
        ) {
            StatsPanel(stats, info)
        }

        AnimatedVisibility(
            visible = keyboard,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(TandemMotion.fadeSpec()) + slideInVertically(TandemMotion.spatial()) { it },
            exit = fadeOut(TandemMotion.fadeSpec()) + slideOutVertically(TandemMotion.spatial()) { it },
        ) {
            Column(
                Modifier
                    .onSizeChanged { panelHeight = it.height.toFloat() }
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                    .imePadding()
                    .padding(12.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))
                    .padding(12.dp),
            ) {
                KeyboardPanel(
                    mods = mods,
                    onToggleMod = { bit -> mods = mods xor bit },
                    onText = ::typeText,
                    onKey = ::pressKey,
                )
            }
        }
    }
}

private const val BAR_MILLIS = 3500L

/** Feeds the fingers on this element to the gestures, the way the trackpad of the remote screen does. */
private fun Modifier.viewerTouch(gestures: ViewerGestures, onTouch: () -> Unit): Modifier = pointerInput(gestures) {
    awaitEachGesture {
        try {
            val first = awaitFirstDown(requireUnconsumed = false)
            first.consume()
            gestures.onFrame(first.uptimeMillis, listOf(Touch(first.id.value, first.position.x, first.position.y)))
            while (true) {
                val wait = gestures.deadline()?.let { (it - SystemClock.uptimeMillis()).coerceAtLeast(1L) }
                val event = if (wait == null) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
                if (event == null) {
                    gestures.onTimer(SystemClock.uptimeMillis())
                    continue
                }
                val down = event.changes.filter { it.pressed }
                gestures.onFrame(event.changes.maxOf { it.uptimeMillis }, down.map { Touch(it.id.value, it.position.x, it.position.y) })
                event.changes.forEach { it.consume() }
                if (down.isEmpty()) break
            }
        } finally {
            // A finger that goes away mid-gesture must not leave a button held on the Mac.
            gestures.cancel()
        }
    }
}

/** The surface the decoder draws on. It is as large as the picture is shown, so the phone does the scaling once. */
@Composable
private fun VideoSurface(viewer: ScreenViewer, videoWidth: Int, videoHeight: Int, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                keepScreenOn = true
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = viewer.setSurface(holder.surface)

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) = viewer.setSurface(null)
                })
            }
        },
        update = { surface ->
            // The buffer is the size of the video, so nothing is scaled before the compositor does it.
            if (videoWidth > 0 && videoHeight > 0) surface.holder.setFixedSize(videoWidth, videoHeight)
        },
    )
}

// ---- Bar --------------------------------------------------------------------------------------

@Composable
private fun ControlBar(
    name: String,
    online: Boolean,
    state: ViewerState,
    stalled: Boolean,
    control: Boolean,
    dragging: Boolean,
    keyboardOn: Boolean,
    direct: Boolean,
    zoomed: Boolean,
    statsOpen: Boolean,
    modifier: Modifier,
    onBarBounds: (Rect) -> Unit,
    onNameBounds: (Rect) -> Unit,
    onClose: () -> Unit,
    onKeyboard: () -> Unit,
    onMode: () -> Unit,
    onFit: () -> Unit,
    onStats: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .onGloballyPositioned { onBarBounds(it.boundsInRoot()) }
            .clip(PillShape)
            .background(scheme.surfaceContainer.copy(alpha = 0.94f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TandemIconButton(TandemIcons.Close, stringResource(R.string.screen_close), onClose)
        Row(
            Modifier
                .weight(1f, fill = false)
                .onGloballyPositioned { onNameBounds(it.boundsInRoot()) }
                .clip(PillShape)
                .bouncyClickable(onLongClick = onStats, onClick = onStats)
                .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PresenceDot(online && state is ViewerState.Streaming && !stalled)
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    barStatus(state, stalled, online, control, dragging),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // A small arrow says that the name opens something: the numbers of the connection.
            val arrow by androidx.compose.animation.core.animateFloatAsState(if (statsOpen) 180f else 0f, TandemMotion.springy(), label = "statsArrow")
            Icon(
                TandemIcons.ChevronDown, stringResource(R.string.screen_stats_toggle), tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrow },
            )
        }
        if (zoomed) {
            TandemIconButton(TandemIcons.FitScreen, stringResource(R.string.screen_fit), onFit)
        }
        // The two buttons are one group, like the buttons of the trackpad page: they touch each other with small corners
        // and round out one by one while a finger is on them.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val modePress = rememberPressState()
            val keyboardPress = rememberPressState()
            GroupButton(
                press = modePress, height = 40.dp, joins = Joins.row(0, 2), colors = GroupTone.neutral(),
                // Round while it is on, like the buttons of the trackpad page: touching directly is the mode that is turned on.
                round = direct,
                modifier = Modifier.width(48.dp),
                description = stringResource(if (direct) R.string.screen_trackpad_mode else R.string.screen_touch_mode),
                onUp = { inside -> if (inside) onMode() },
            ) { tint ->
                Icon(if (direct) TandemIcons.Touch else TandemIcons.Mouse, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            GroupButton(
                press = keyboardPress, height = 40.dp, joins = Joins.row(1, 2),
                colors = if (keyboardOn) GroupTone.selected() else GroupTone.neutral(),
                round = keyboardOn,
                modifier = Modifier.width(48.dp),
                description = stringResource(R.string.screen_keyboard),
                onUp = { inside -> if (inside) onKeyboard() },
            ) { tint ->
                Icon(TandemIcons.Keyboard, null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun barStatus(state: ViewerState, stalled: Boolean, online: Boolean, control: Boolean, dragging: Boolean): String = when {
    state is ViewerState.Streaming && !online -> stringResource(R.string.screen_reconnecting)
    state is ViewerState.Streaming && stalled -> stringResource(R.string.screen_no_picture)
    state is ViewerState.Streaming && dragging -> stringResource(R.string.remote_dragging)
    state is ViewerState.Streaming && control -> stringResource(R.string.remote_ready)
    state is ViewerState.Streaming -> stringResource(R.string.screen_view_only)
    state is ViewerState.Reconnecting -> stringResource(R.string.screen_reconnecting)
    state is ViewerState.Connecting || state is ViewerState.WaitingForApproval -> stringResource(R.string.pair_connecting)
    else -> stringResource(R.string.status_offline)
}

// ---- Overlays ---------------------------------------------------------------------------------

@Composable
private fun Overlay(
    state: ViewerState,
    picture: Boolean,
    stalled: Boolean,
    online: Boolean,
    name: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val extras = LocalTandemExtraColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Waiting for something: the pill from the icon, with a line that says for what.
        val loading = when (state) {
            ViewerState.Connecting -> stringResource(R.string.screen_connecting, name)
            ViewerState.WaitingForApproval -> stringResource(R.string.screen_waiting_approval, name)
            is ViewerState.Reconnecting -> if (!picture) stringResource(R.string.screen_reconnecting) else null
            ViewerState.Streaming -> if (!picture) stringResource(R.string.screen_first_picture) else null
            else -> null
        }
        AnimatedVisibility(
            visible = loading != null,
            enter = fadeIn(TandemMotion.fadeSpec()),
            exit = fadeOut(TandemMotion.fadeSpec()),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(32.dp)) {
                PillLoader(label = loading ?: "")
                if (state == ViewerState.WaitingForApproval) {
                    Text(
                        stringResource(R.string.screen_waiting_approval_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
                if (state is ViewerState.Reconnecting) {
                    Text(
                        stringResource(R.string.screen_reconnecting_try, state.attempt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // A picture that is there but stands still, or a link that is gone: a chip on top of the last picture.
        val notice = when {
            state is ViewerState.Reconnecting && picture -> stringResource(R.string.screen_reconnecting)
            state is ViewerState.Streaming && picture && !online -> stringResource(R.string.screen_reconnecting)
            state is ViewerState.Streaming && picture && stalled -> stringResource(R.string.screen_no_picture)
            else -> null
        }
        AnimatedVisibility(
            visible = notice != null,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(24.dp),
            enter = fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), initialScale = 0.85f),
            exit = fadeOut(TandemMotion.fadeSpec()) + scaleOut(TandemMotion.fadeSpec(), targetScale = 0.9f),
        ) {
            Row(
                Modifier.clip(PillShape).background(extras.urgentContainer).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(TandemIcons.Sync, null, tint = extras.onUrgentContainer, modifier = Modifier.size(18.dp))
                Text(notice ?: "", style = MaterialTheme.typography.labelLarge, color = extras.onUrgentContainer)
            }
        }

        // Over for good: why, and what to do about it.
        val finished = when (state) {
            is ViewerState.Ended -> when (state.outcome) {
                Outcome.StoppedByMac -> Message(TandemIcons.Desktop, R.string.screen_stopped_title, R.string.screen_stopped_body)
                Outcome.Denied -> Message(TandemIcons.Shield, R.string.screen_denied_title, R.string.screen_denied_body)
                Outcome.Busy -> Message(TandemIcons.Person, R.string.screen_busy_title, R.string.screen_busy_body)
                Outcome.Unavailable -> Message(TandemIcons.Error, R.string.screen_unavailable_title, R.string.screen_unavailable_body)
                Outcome.NoAnswer -> Message(TandemIcons.Sleep, R.string.screen_noanswer_title, R.string.screen_noanswer_body)
                Outcome.Retry -> null
            }
            ViewerState.Lost -> Message(TandemIcons.CloudOff, R.string.screen_lost_title, R.string.screen_lost_body, named = false)
            is ViewerState.DecoderFailed -> Message(TandemIcons.Error, R.string.screen_decoder_title, R.string.screen_decoder_body, named = false)
            else -> null
        }
        AnimatedVisibility(
            visible = finished != null,
            enter = fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), initialScale = 0.9f),
            exit = fadeOut(TandemMotion.fadeSpec()),
        ) {
            // Kept while it fades out, when the state has already moved on.
            val shown = remember { mutableStateOf<Message?>(null) }
            if (finished != null) shown.value = finished
            shown.value?.let { message ->
                EndedCard(message, name, onRetry, onClose)
            }
        }
    }
}

private class Message(val icon: @Composable () -> Painter, val title: Int, val body: Int, val named: Boolean = true) {
    constructor(icon: Painter, title: Int, body: Int, named: Boolean = true) : this({ icon }, title, body, named)
}

@Composable
private fun EndedCard(message: Message, name: String, onRetry: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(24.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainer)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The icon sits in a circle and is under half its size.
        Box(Modifier.size(72.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(message.icon(), null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(30.dp))
        }
        Text(
            if (message.named) stringResource(message.title, name) else stringResource(message.title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(message.body),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryPillButton(stringResource(R.string.screen_close), onClose)
            PrimaryPillButton(stringResource(R.string.screen_try_again), onRetry, icon = TandemIcons.Refresh)
        }
    }
}

// ---- Stats ------------------------------------------------------------------------------------

@Composable
private fun StatsPanel(stats: ViewStats, info: StreamInfo?) {
    val scheme = MaterialTheme.colorScheme
    val delay = stats.decoder.delayMs + (stats.rttMs ?: 0) / 2
    val core = stats.core
    Column(
        Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.94f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${stringResource(R.string.screen_stats_fps, stats.decoder.fps)}  ·  ${stringResource(R.string.screen_stats_bitrate, stats.receivedBps / 1_000_000f)}  ·  ${stringResource(R.string.screen_stats_delay, delay)}",
            style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        val detail = buildList {
            info?.let { add(stringResource(R.string.screen_stats_size, it.width, it.height)) }
            core?.let {
                add(stringResource(R.string.screen_stats_target, it.targetBitrate.toLong() / 1_000_000f))
                add(stringResource(R.string.screen_stats_lost, it.lost.toLong(), stats.decoder.framesDropped))
            }
            if (stats.decoder.codecName.isNotEmpty()) add(stats.decoder.codecName)
        }
        detail.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
