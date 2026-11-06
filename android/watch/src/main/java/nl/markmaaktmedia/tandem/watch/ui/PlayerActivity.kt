package nl.markmaaktmedia.tandem.watch.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlinx.coroutines.delay
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.pointer.CrownSteps
import nl.markmaaktmedia.tandem.watch.protocol.MediaCommandKind
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What plays on the phone, on the whole screen. Swiping away goes back, as with any screen on the watch. */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TandemWatchTheme {
                AppScaffold(timeText = { TimeText() }) {
                    val ui by WatchLink.ui.collectAsState()
                    val player = ui.players.firstOrNull()
                    LaunchedEffect(player == null) { if (player == null) finish() }
                    if (player != null) PlayerScreen(player, ui.cover)
                }
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, PlayerActivity::class.java))
        }
    }
}

/** The way in from the list: only what plays. */
@Composable
fun NowPlayingButton(player: MediaPlayer, cover: Message.MediaArt?, modifier: Modifier, transformation: SurfaceTransformation) {
    val context = LocalContext.current
    val image = rememberCover(player, cover)
    FilledTonalButton(
        onClick = { PlayerActivity.open(context) },
        modifier = modifier,
        transformation = transformation,
        icon = { Cover(image, player.playing, ButtonDefaults.LargeIconSize) },
        // Two lines and no more than that: the artist is one tap away, and a name cut off halfway is of no use to anyone.
        label = { Text(player.title.ifBlank { player.app }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun rememberCover(player: MediaPlayer, cover: Message.MediaArt?): Bitmap? = remember(cover, player.artKey) {
    cover?.takeIf { it.key == player.artKey }?.let { art ->
        runCatching { BitmapFactory.decodeByteArray(art.image, 0, art.image.size) }.getOrNull()
    }
}

/** A cookie while it plays, a calm circle while it is paused. */
@Composable
private fun Cover(image: Bitmap?, playing: Boolean, size: Dp) {
    val depth by animateFloatAsState(if (playing) COOKIE_DEPTH else 0f, TandemMotion.shapeSpec(), label = "coverShape")
    Crossfade(targetState = image, animationSpec = TandemMotion.fadeSpec(), label = "cover") { shown ->
        if (shown != null) {
            Image(
                shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CookieShape(depth = depth)),
            )
        } else {
            CookieIcon(R.drawable.sym_music_note, size = size)
        }
    }
}

private data class PlayerColors(val side: Color, val onSide: Color, val middle: Color, val onMiddle: Color)

/** Takes its colours from the cover, the way the watch face colours the rest. A cover without a clear colour leaves the theme alone. */
private fun coverColors(cover: Bitmap): PlayerColors? {
    val small = Bitmap.createScaledBitmap(cover, COLOR_SAMPLES, COLOR_SAMPLES, true)
    val hsv = FloatArray(3)
    var x = 0.0
    var y = 0.0
    for (row in 0 until COLOR_SAMPLES) {
        for (column in 0 until COLOR_SAMPLES) {
            android.graphics.Color.colorToHSV(small.getPixel(column, row), hsv)
            // Grey and black say nothing about the colour of a cover, however much of it there is.
            val weight = hsv[1] * hsv[2]
            val angle = Math.toRadians(hsv[0].toDouble())
            x += cos(angle) * weight
            y += sin(angle) * weight
        }
    }
    if (hypot(x, y) < COLOR_SAMPLES * COLOR_SAMPLES * MIN_COLOR) return null
    val hue = Math.toDegrees(atan2(y, x)).toFloat()
    fun tone(shift: Float, saturation: Float, value: Float) =
        Color(android.graphics.Color.HSVToColor(floatArrayOf((hue + shift + 360f) % 360f, saturation, value)))
    return PlayerColors(
        side = tone(0f, 0.32f, 0.96f),
        onSide = tone(0f, 0.75f, 0.22f),
        middle = tone(45f, 0.30f, 0.96f),
        onMiddle = tone(45f, 0.75f, 0.22f),
    )
}

private const val COLOR_SAMPLES = 12
private const val MIN_COLOR = 0.04

@Composable
internal fun PlayerScreen(player: MediaPlayer, cover: Message.MediaArt?) {
    val image = rememberCover(player, cover)
    val colors = remember(image) { image?.let { runCatching { coverColors(it) }.getOrNull() } }
    val scheme = MaterialTheme.colorScheme
    val side by animateColorAsState(colors?.side ?: scheme.primary, TandemMotion.fadeSpec(), label = "sideFill")
    val onSide by animateColorAsState(colors?.onSide ?: scheme.onPrimary, TandemMotion.fadeSpec(), label = "sideIcon")
    val middle by animateColorAsState(colors?.middle ?: scheme.tertiary, TandemMotion.fadeSpec(), label = "middleFill")
    val onMiddle by animateColorAsState(colors?.onMiddle ?: scheme.onTertiary, TandemMotion.fadeSpec(), label = "middleIcon")

    val focus = remember { FocusRequester() }
    val crown = remember { CrownSteps() }
    val haptics = LocalHapticFeedback.current
    var volumeUp by remember { mutableStateOf(true) }
    var volumeTurns by remember { mutableIntStateOf(0) }
    var volumeShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(volumeTurns) {
        if (volumeTurns == 0) return@LaunchedEffect
        volumeShown = true
        delay(900)
        volumeShown = false
    }

    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onRotaryScrollEvent { event ->
                    // The crown is the volume, as in Google's own media controls: the screen has room for the music and nothing else.
                    val steps = crown.update(event.verticalScrollPixels)
                    if (steps != 0) {
                        volumeUp = steps > 0
                        volumeTurns++
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val kind = if (steps > 0) MediaCommandKind.VOLUME_UP else MediaCommandKind.VOLUME_DOWN
                        repeat(abs(steps)) { WatchLink.sendMediaCommand(player.id, kind) }
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = image, animationSpec = TandemMotion.fadeSpec(), label = "backdrop") { shown ->
                if (shown != null) {
                    Image(
                        shown.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(18.dp),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                }
            }
            PositionRing(player, side)
            Column(
                Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Cover(image, player.playing, 52.dp)
                Spacer(Modifier.height(3.dp))
                Text(
                    player.title.ifBlank { player.app },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    // Twice is enough to read it, and text that keeps moving keeps the screen working.
                    modifier = Modifier.fillMaxWidth().fadedSides().basicMarquee(iterations = 2),
                )
                if (player.artist.isNotBlank()) {
                    Text(
                        player.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().fadedSides().basicMarquee(iterations = 2),
                    )
                }
                Spacer(Modifier.height(6.dp))
                TransportControls(player, side, onSide, middle, onMiddle)
            }
            AnimatedVisibility(
                visible = volumeShown,
                enter = fadeIn(TandemMotion.fadeSpec()),
                exit = fadeOut(TandemMotion.fadeSpec()),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                Icon(painterResource(if (volumeUp) R.drawable.sym_volume_up else R.drawable.sym_volume_down), null, tint = side, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** How far the track is, along the rim. It leaves the top open for the time. */
@Composable
private fun PositionRing(player: MediaPlayer, color: Color) {
    if (player.durationMs <= 0) return
    val since = remember(player.positionMs, player.playing) { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val played = player.positionMs + if (player.playing) (now - since).coerceAtLeast(0) else 0
    CircularProgressIndicator(
        progress = { (played.toFloat() / player.durationMs).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxSize().padding(2.dp),
        startAngle = 300f,
        endAngle = 240f,
        colors = ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = color.copy(alpha = 0.25f)),
        strokeWidth = 4.dp,
    )
}

private enum class Transport { PREVIOUS, PLAY_PAUSE, NEXT }

private val TRANSPORT_HEIGHT = 46.dp

/** Three wide buttons, and the one pressed pushes the others aside. The middle one also says what is going on: a rounded square while it plays, a pill when paused. */
@Composable
private fun TransportControls(player: MediaPlayer, side: Color, onSide: Color, middle: Color, onMiddle: Color) {
    var pressed by remember { mutableStateOf<Transport?>(null) }
    var presses by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(presses) {
        if (presses == 0) return@LaunchedEffect
        // A new track takes a moment to arrive, so a skip stays visible for longer.
        delay(if (pressed == Transport.PLAY_PAUSE) 220 else 500)
        pressed = null
    }
    val press = { button: Transport, kind: MediaCommandKind ->
        pressed = button
        presses++
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        WatchLink.sendMediaCommand(player.id, kind)
    }
    val corner by animateDpAsState(if (player.playing) 15.dp else TRANSPORT_HEIGHT / 2, TandemMotion.shapeSpec(), label = "playShape")
    Row(Modifier.fillMaxWidth().height(TRANSPORT_HEIGHT), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportButton(Transport.PREVIOUS, pressed, CircleShape, side, onClick = { press(Transport.PREVIOUS, MediaCommandKind.PREVIOUS) }) {
            Icon(painterResource(R.drawable.sym_skip_previous), stringResource(R.string.previous), tint = onSide)
        }
        TransportButton(Transport.PLAY_PAUSE, pressed, RoundedCornerShape(corner), middle, onClick = { press(Transport.PLAY_PAUSE, MediaCommandKind.PLAY_PAUSE) }) {
            Crossfade(targetState = player.playing, animationSpec = TandemMotion.fadeSpec(), label = "playIcon") { playing ->
                Icon(
                    painterResource(if (playing) R.drawable.sym_pause else R.drawable.sym_play_arrow),
                    stringResource(if (playing) R.string.pause else R.string.play),
                    tint = onMiddle,
                )
            }
        }
        TransportButton(Transport.NEXT, pressed, CircleShape, side, onClick = { press(Transport.NEXT, MediaCommandKind.NEXT) }) {
            Icon(painterResource(R.drawable.sym_skip_next), stringResource(R.string.next), tint = onSide)
        }
    }
}

@Composable
private fun RowScope.TransportButton(
    button: Transport,
    pressed: Transport?,
    shape: Shape,
    color: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val width by animateFloatAsState(
        when (pressed) {
            null -> 1f
            button -> 1.25f
            else -> 0.8f
        },
        TandemMotion.shapeSpec(),
        label = "transportWidth",
    )
    Box(
        Modifier
            .weight(width)
            .fillMaxHeight()
            .clip(shape)
            .background(color)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
