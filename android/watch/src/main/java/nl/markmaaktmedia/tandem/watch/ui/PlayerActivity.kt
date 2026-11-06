package nl.markmaaktmedia.tandem.watch.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
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
import nl.markmaaktmedia.tandem.watch.protocol.PlayerRules
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What plays on the phone, on the whole screen. Swiping to the right goes back, as with any screen on the watch. */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TandemWatchTheme {
                AppScaffold(timeText = { TimeText() }) {
                    val ui by WatchLink.ui.collectAsState()
                    val shown = PlayerRules.shown(ui.players, ui.pickedPlayer)
                    LaunchedEffect(shown == null) { if (shown == null) finish() }
                    if (shown != null) PlayerScreen(ui.players, shown, ui.covers, WatchLink::pickPlayer)
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
    val image = rememberCover(cover)
    FilledTonalButton(
        onClick = { PlayerActivity.open(context) },
        modifier = modifier,
        transformation = transformation,
        icon = { SmallCover(image, player.playing, ButtonDefaults.LargeIconSize) },
        // Two lines and no more than that: the artist is one tap away, and a name cut off halfway is of no use to anyone.
        label = { Text(player.title.ifBlank { player.app }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun rememberCover(cover: Message.MediaArt?): Bitmap? = remember(cover?.key) {
    cover?.let { art -> runCatching { BitmapFactory.decodeByteArray(art.image, 0, art.image.size) }.getOrNull() }
}

/** A cookie while it plays, a calm circle while it is paused. */
@Composable
private fun SmallCover(image: Bitmap?, playing: Boolean, size: Dp) {
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

private data class PlayerColors(val accent: Color, val onAccent: Color)

/** Takes its colour from the cover, the way the watch face colours the rest. A cover without a clear colour leaves the theme alone. */
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
    val hue = (Math.toDegrees(atan2(y, x)).toFloat() + 360f) % 360f
    fun tone(saturation: Float, value: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    return PlayerColors(accent = tone(0.34f, 0.96f), onAccent = tone(0.75f, 0.22f))
}

private const val COLOR_SAMPLES = 12
private const val MIN_COLOR = 0.04

private val PlayerSurface = Color(0xFF1C1C1E)
private val PlayerTrack = Color(0xFF2C2C2E)
private val PlayerSecondary = Color(0xA6FFFFFF)

/**
 * How long this has been playing, 20 steps a second: the wave and the cookie move slowly, and more would only cost battery. It stands
 * still with the music, and because every step waits for a frame it also rests while the screen is off.
 */
@Composable
private fun rememberPlayingTime(playing: Boolean): LongState {
    val time = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = SystemClock.uptimeMillis()
        while (true) {
            delay(FRAME_MS)
            withFrameMillis { }
            val now = SystemClock.uptimeMillis()
            time.longValue += now - last
            last = now
        }
    }
    return time
}

private const val FRAME_MS = 50L
private const val COOKIE_TURN_MS = 24_000f

@Composable
internal fun PlayerScreen(players: List<MediaPlayer>, player: MediaPlayer, covers: Map<String, Message.MediaArt>, onPick: (String) -> Unit) {
    val image = rememberCover(covers[player.artKey])
    val colors = remember(image) { image?.let { runCatching { coverColors(it) }.getOrNull() } }
    val scheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(colors?.accent ?: scheme.primary, tween(480), label = "accent")
    val onAccent by animateColorAsState(colors?.onAccent ?: scheme.onPrimary, tween(480), label = "onAccent")
    val time = rememberPlayingTime(player.playing)

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
    val others = players.filter { it.id != player.id }

    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                }
                .pointerInput(player.id, players.map { it.id }) {
                    var travel = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f },
                        onDragEnd = {
                            // Only to the left: to the right is how every screen on a watch closes. It goes round, so all of them can be reached.
                            if (travel < -SWIPE_DP.dp.toPx() && players.size > 1) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPick(players[(players.indexOfFirst { it.id == player.id } + 1) % players.size].id)
                            }
                        },
                        onHorizontalDrag = { _, dx -> travel += dx },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            WaveRing(player, time, accent)
            Column(
                Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    OtherPlayer(others.getOrNull(1), covers, onPick)
                    TurningCover(image, player.playing, time, 50.dp)
                    OtherPlayer(others.getOrNull(0), covers, onPick)
                }
                Spacer(Modifier.height(2.dp))
                AnimatedContent(
                    targetState = player.title.ifBlank { player.app } to player.artist,
                    transitionSpec = {
                        (slideInVertically(spring(0.8f, 400f)) { it / 2 } + fadeIn(tween(220, 60)))
                            .togetherWith(slideOutVertically(spring(0.8f, 400f)) { -it / 2 } + fadeOut(tween(120)))
                    },
                    label = "title",
                ) { (title, artist) ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            // Twice is enough to read it, and text that keeps moving keeps the screen working.
                            modifier = Modifier.fillMaxWidth().fadedSides().basicMarquee(iterations = 2),
                        )
                        if (artist.isNotBlank()) {
                            Text(
                                artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = PlayerSecondary,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().fadedSides().basicMarquee(iterations = 2),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TransportGroup(player, accent, onAccent)
            }
            AnimatedVisibility(
                visible = volumeShown,
                enter = fadeIn(TandemMotion.fadeSpec()),
                exit = fadeOut(TandemMotion.fadeSpec()),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            ) {
                Icon(painterResource(if (volumeUp) R.drawable.sym_volume_up else R.drawable.sym_volume_down), null, tint = accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private const val SWIPE_DP = 24

/**
 * The cover in a cookie. The cookie's edge turns slowly while the song plays, the picture itself stays still; paused it all stands,
 * a little smaller. A new song drops its cover in over the old one.
 */
@Composable
private fun TurningCover(image: Bitmap?, playing: Boolean, time: LongState, size: Dp) {
    val scale by animateFloatAsState(if (playing) 1f else 0.86f, spring(0.55f, 380f), label = "coverSize")
    val cookie = remember { CookieShape() }
    AnimatedContent(
        targetState = image,
        transitionSpec = {
            (scaleIn(spring(0.6f, 420f), initialScale = 0.6f) + fadeIn(tween(200)))
                .togetherWith(scaleOut(tween(220), targetScale = 1.15f) + fadeOut(tween(200)))
        },
        contentAlignment = Alignment.Center,
        label = "cover",
    ) { shown ->
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = time.longValue % COOKIE_TURN_MS.toLong() * 360f / COOKIE_TURN_MS
                    shape = cookie
                    clip = true
                }
                .background(PlayerTrack),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                Image(
                    shown.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = -(time.longValue % COOKIE_TURN_MS.toLong() * 360f / COOKIE_TURN_MS)
                    },
                )
            } else {
                Icon(painterResource(R.drawable.sym_music_note), null, tint = PlayerSecondary, modifier = Modifier.size(size / 2))
            }
        }
    }
}

/** Another player: its cover, small, round and a little dimmed. A tap goes to it. An empty place stays, so the big cover stays in the middle. */
@Composable
private fun OtherPlayer(player: MediaPlayer?, covers: Map<String, Message.MediaArt>, onPick: (String) -> Unit) {
    if (player == null) {
        Spacer(Modifier.size(OTHER_TARGET))
        return
    }
    val image = rememberCover(covers[player.artKey])
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dip by animateFloatAsState(if (pressed) 0.85f else 1f, spring(0.5f, 600f), label = "otherDip")
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(OTHER_TARGET)
            .clip(CircleShape)
            .clickable(source, null, role = Role.Button, onClickLabel = stringResource(R.string.go_to_player, player.app)) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPick(player.id)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(OTHER_SIZE)
                .graphicsLayer {
                    scaleX = dip
                    scaleY = dip
                    alpha = if (player.playing) 0.85f else 0.6f
                    shape = CircleShape
                    clip = true
                }
                .background(PlayerTrack),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(image.asImageBitmap(), player.app, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(R.drawable.sym_music_note), player.app, tint = PlayerSecondary, modifier = Modifier.size(OTHER_SIZE / 2))
            }
        }
    }
}

private val OTHER_TARGET = 34.dp
private val OTHER_SIZE = 26.dp

private const val RING_START = 300f
private const val RING_SWEEP = 300f
private val RING_INSET = 5.dp
private val WAVE_AMPLITUDE = 2.dp
private val WAVE_LENGTH = 24.dp
private val WAVE_TAPER = 14.dp
private const val WAVE_CYCLES_PER_MS = 0.00055f

/**
 * How far the song is, along the rim, the way a player in Drip draws it: the played part and the rest as two lines with an upright
 * handle between them and a gap on both sides. The played part is a wave while the music plays; it rises on a spring when the music
 * starts and eases flat when it stops, and it flattens towards the handle so the line meets it in its middle. A radio has no length:
 * the wave then runs the whole way, without a handle. The top stays open for the time.
 */
@Composable
private fun WaveRing(player: MediaPlayer, time: LongState, accent: Color) {
    val since = remember(player.id, player.positionMs, player.playing) { SystemClock.elapsedRealtime() }
    val amplitude by animateFloatAsState(if (player.playing) 1f else 0f, spring(0.9f, 220f), label = "wave")
    val fill by animateColorAsState(if (player.playing) accent else lerp(accent, PlayerTrack, 0.5f), tween(320), label = "fill")
    val wave = remember { Path() }
    val live = player.durationMs <= 0
    Canvas(Modifier.fillMaxSize()) {
        // Read here and not above: a new step then redraws this and nothing else.
        val played = time.longValue.let { player.positionMs + if (player.playing) SystemClock.elapsedRealtime() - since else 0 }
        val fraction = if (live) 1f else (played.toFloat() / player.durationMs).coerceIn(0f, 1f)
        val width = 4.dp.toPx()
        val radius = size.minDimension / 2 - RING_INSET.toPx()
        val toRadians = PI.toFloat() / 180f
        fun at(degrees: Float, reach: Float) = Offset(
            center.x + reach * cos(degrees * toRadians),
            center.y + reach * sin(degrees * toRadians),
        )
        val handle = RING_START + RING_SWEEP * fraction
        // What a gap of this many pixels is in degrees, at this distance from the middle.
        val gap = (2.dp.toPx() + 3.dp.toPx() + width / 2) / radius / toRadians
        val square = Size(radius * 2, radius * 2)
        val corner = Offset(center.x - radius, center.y - radius)

        if (!live && handle + gap < RING_START + RING_SWEEP) {
            drawArc(
                PlayerTrack, handle + gap, RING_START + RING_SWEEP - handle - gap, false, corner, square,
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
        val playedTo = if (live) handle else handle - gap
        if (playedTo > RING_START) {
            val height = amplitude.coerceAtLeast(0f) * WAVE_AMPLITUDE.toPx()
            if (height < 0.05f) {
                drawArc(fill, RING_START, playedTo - RING_START, false, corner, square, style = Stroke(width, cap = StrokeCap.Round))
            } else {
                val length = WAVE_LENGTH.toPx()
                val taper = WAVE_TAPER.toPx()
                val shift = (time.longValue * WAVE_CYCLES_PER_MS) % 1f * 2f * PI.toFloat()
                val step = 3f / radius / toRadians
                wave.reset()
                var degrees = RING_START
                while (true) {
                    val along = (degrees - RING_START) * toRadians * radius
                    val near = if (live) 1f else ((playedTo - degrees) * toRadians * radius / taper).coerceIn(0f, 1f)
                    val point = at(degrees, radius + height * near * near * (3f - 2f * near) * sin(along / length * 2f * PI.toFloat() - shift))
                    if (degrees == RING_START) wave.moveTo(point.x, point.y) else wave.lineTo(point.x, point.y)
                    if (degrees >= playedTo) break
                    degrees = minOf(degrees + step, playedTo)
                }
                drawPath(wave, fill, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (!live) drawLine(fill, at(handle, radius - 5.dp.toPx()), at(handle, radius + 5.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    }
}

private val TRANSPORT_HEIGHT = 46.dp
private val TRANSPORT_OUTER = TRANSPORT_HEIGHT / 2
private val TRANSPORT_SIDE = 40.dp

/**
 * Previous, play and next as one Material 3 Expressive button group: joined, round on the outside, with small corners where they
 * meet. A pressed button swells and rounds its inner corners; its neighbours make room.
 */
@Composable
private fun TransportGroup(player: MediaPlayer, accent: Color, onAccent: Color) {
    val haptics = LocalHapticFeedback.current
    val press = { kind: MediaCommandKind ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        WatchLink.sendMediaCommand(player.id, kind)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        GroupButton(painterResource(R.drawable.sym_skip_previous), stringResource(R.string.previous), start = true) { press(MediaCommandKind.PREVIOUS) }
        PlayButton(player.playing, accent, onAccent) { press(MediaCommandKind.PLAY_PAUSE) }
        GroupButton(painterResource(R.drawable.sym_skip_next), stringResource(R.string.next), start = false) { press(MediaCommandKind.NEXT) }
    }
}

@Composable
private fun GroupButton(icon: Painter, label: String, start: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val inner by animateDpAsState(if (pressed) TRANSPORT_OUTER else 8.dp, spring(0.55f, 600f), label = "inner")
    val grow by animateDpAsState(if (pressed) TRANSPORT_SIDE + 6.dp else TRANSPORT_SIDE, spring(0.5f, 600f), label = "grow")
    val shape = if (start) {
        RoundedCornerShape(TRANSPORT_OUTER, inner, inner, TRANSPORT_OUTER)
    } else {
        RoundedCornerShape(inner, TRANSPORT_OUTER, TRANSPORT_OUTER, inner)
    }
    Box(
        Modifier
            .size(grow, TRANSPORT_HEIGHT)
            .clip(shape)
            .background(PlayerSurface)
            .clickable(source, null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Play is a pill and pause a squircle: the button morphs between the two, the way Material 3 Expressive buttons do. */
@Composable
private fun PlayButton(playing: Boolean, accent: Color, onAccent: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(
        when {
            pressed -> TRANSPORT_OUTER
            playing -> 11.dp
            else -> TRANSPORT_OUTER
        },
        spring(0.55f, 500f),
        label = "playCorner",
    )
    val width by animateDpAsState(
        when {
            pressed -> 64.dp
            playing -> 58.dp
            else -> 52.dp
        },
        spring(0.55f, 500f),
        label = "playWidth",
    )
    Box(
        Modifier
            .size(width, TRANSPORT_HEIGHT)
            .clip(RoundedCornerShape(corner))
            .background(accent)
            .clickable(source, null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(spring(0.5f, 700f), initialScale = 0.4f) + fadeIn(tween(120)))
                    .togetherWith(scaleOut(tween(120), targetScale = 0.4f) + fadeOut(tween(100)))
            },
            label = "playIcon",
        ) { now ->
            Icon(
                painterResource(if (now) R.drawable.sym_pause else R.drawable.sym_play_arrow),
                stringResource(if (now) R.string.pause else R.string.play),
                tint = onAccent,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
