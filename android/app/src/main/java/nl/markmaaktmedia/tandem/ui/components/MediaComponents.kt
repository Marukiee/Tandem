package nl.markmaaktmedia.tandem.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.media.RemotePlayers
import nl.markmaaktmedia.tandem.ui.remote.PlayerControls
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemMediaAction
import uniffi.tandem_core.TandemMediaKey
import uniffi.tandem_core.TandemMediaPlayer
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.tandemMediaVisible

/** What is on the other device, kept after it stops so the card can leave the way it came. */
private class Playing(val players: List<TandemMediaPlayer>, val entry: RemotePlayers)

/**
 * What the other device is playing, with the buttons to control it. Players that play what this
 * phone already plays are left out, so a phone that only remote controls the Mac's Spotify does
 * not show it twice.
 *
 * A computer that can be reached always has its player here, also when nothing plays: the bar and
 * the buttons stay, and play then presses the play key of the computer, which starts whatever played
 * last. For other devices the section opens when something starts and closes when it stops, and keeps
 * the last thing it showed while it closes, so the card fades out whole instead of emptying first.
 */
@Composable
fun NowPlayingSection(device: TandemDevice) {
    val graph = LocalContext.current.graph
    val share by graph.prefs.mediaShare.collectAsState(initial = true)
    val remote by graph.media.remote.collectAsState()
    val local by graph.media.local.collectAsState()
    val covers by graph.media.remoteArt.collectAsState()
    val entry = remote[device.id]
    val reachable = device.online || device.ble
    val shown = remember(share, entry, local, reachable) {
        if (share && entry != null && reachable) tandemMediaVisible(local, entry.players) else emptyList()
    }
    val current = if (entry != null && shown.isNotEmpty()) Playing(shown, entry) else null

    if (reachable && (device.platform == TandemPlatform.MAC_OS || device.platform == TandemPlatform.WINDOWS)) {
        val muted = device.status.muted == true
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(R.string.media_now_playing), top = 12.dp, bottom = 0.dp)
            if (current == null) {
                PlayerCard(device.id, null, null, null, muted)
            } else {
                PlayerStack(device.id, current.players, current.entry, covers, muted)
            }
        }
        return
    }

    val last = remember { mutableStateOf<Playing?>(null) }
    if (current != null) last.value = current
    val open = remember { MutableTransitionState(false) }
    SideEffect { open.targetState = current != null }
    val content = last.value
    // Once it has closed nothing is composed at all, so the page does not keep a gap where it was.
    if (content == null || (current == null && !open.currentState && !open.targetState)) return

    AnimatedVisibility(
        visibleState = open,
        enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
        exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(R.string.media_now_playing), top = 12.dp, bottom = 0.dp)
            PlayerStack(device.id, content.players, content.entry, covers, device.status.muted == true)
        }
    }
}

/**
 * One player in front, with all its controls, and the rest as one line each in a single slab below it, so three players
 * take little more room than one. A tap on a line brings that player to the front.
 */
@Composable
private fun PlayerStack(deviceId: String, players: List<TandemMediaPlayer>, entry: RemotePlayers, covers: Map<Long, Bitmap>, muted: Boolean) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val main = players.firstOrNull { it.id == chosen } ?: players.firstOrNull { it.playing } ?: players.first()
    key(main.id) { PlayerCard(deviceId, main, entry, covers[main.art.toLong()], muted) }
    val others = players.filter { it.id != main.id }
    if (others.isNotEmpty()) {
        val media = LocalContext.current.graph.media
        Column(Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 6.dp)) {
            others.forEach { player ->
                key(player.id) {
                    Row(
                        Modifier.fillMaxWidth().bouncyClickable(onClick = { chosen = player.id }).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SmallCover(covers[player.art.toLong()], player.art != 0UL)
                        Column(Modifier.weight(1f)) {
                            Text(player.title.ifBlank { player.app }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(player.artist, player.app).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val label = stringResource(if (player.playing) R.string.media_pause else R.string.media_play)
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .bouncyClickable(onClickLabel = label, onClick = { media.commandMac(deviceId, player.id, TandemMediaAction.TOGGLE) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (player.playing) TandemIcons.Pause else TandemIcons.Play, label, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A cover for a line of the list: it keeps the last picture while the next one arrives. */
@Composable
private fun SmallCover(cover: Bitmap?, hasArt: Boolean) {
    val held = remember { arrayOfNulls<Bitmap>(1) }
    if (cover != null) held[0] = cover
    val shown = if (hasArt) cover ?: held[0] else null
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (shown != null) {
            val image = remember(shown) { shown.asImageBitmap() }
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(TandemIcons.Music, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** [player] is null while nothing plays: the card is then the empty player, with the buttons that still make sense. */
@Composable
private fun PlayerCard(deviceId: String, player: TandemMediaPlayer?, entry: RemotePlayers?, cover: Bitmap?, muted: Boolean) {
    val media = LocalContext.current.graph.media
    // The side padding is on the rows, not on the card: the seek bar takes the whole width, because its touch area
    // reaches past the bar to the edges of the card.
    Column(Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The key of the cover changes with each track, and the picture follows a moment later: until it is there the
            // last one stays, instead of an empty square that blinks.
            val held = remember { arrayOfNulls<Bitmap>(1) }
            if (cover != null) held[0] = cover
            Cover(if (player == null || player.art == 0UL) null else cover ?: held[0], playing = player?.playing == true)
            // A new track slides its text in from below and the old one up and out, so a skip is seen.
            val idleTitle = stringResource(R.string.media_idle_title)
            val idleHint = stringResource(R.string.media_idle_hint)
            AnimatedContent(
                targetState = if (player == null) idleTitle to idleHint
                else player.title to listOf(player.artist, player.app).filter { it.isNotBlank() }.joinToString(" · "),
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(TandemMotion.fadeSpec()) + slideInVertically(TandemMotion.spatial()) { it / 3 }) togetherWith
                        (fadeOut(TandemMotion.fadeSpec()) + slideOutVertically(TandemMotion.spatial()) { -it / 3 })
                },
                label = "track",
            ) { (title, subtitle) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        val duration = player?.durationMs?.toLong() ?: 0L
        val start = player?.positionMs?.toLong()
        val seekable = player != null && entry != null && duration > 0 && start != null
        // The bar is always there, so the card does not change shape when music starts. It brings 12dp of touch area
        // above and below it, so it asks 12dp less of the gap around it.
        Spacer(Modifier.height(2.dp))
        // The position is only sent when something changes, so it is counted on from there while it plays.
        val playingNow = player?.playing == true
        // Not while the app is out of sight: nothing is looking at the bar, and it catches up when the app is back.
        val inFront = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
        val now by produceState(SystemClock.elapsedRealtime(), playingNow, entry, inFront) {
            while (playingNow && inFront) {
                value = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
        val natural = if (seekable) ((start!! + if (playingNow) (now - entry!!.at) else 0L).toFloat() / duration).coerceIn(0f, 1f) else 0f
        SeekBar(
            fraction = natural,
            duration = duration,
            enabled = seekable && player!!.canSeek,
            playing = playingNow,
            report = entry?.at ?: 0L,
            onSeek = { target -> if (player != null) media.commandMac(deviceId, player.id, TandemMediaAction.SEEK, (target * duration).toLong()) },
        )
        Spacer(Modifier.height(2.dp))

        // The trackpad's buttons, with volume: the Mac's own keys for that, the player's own for the rest.
        PlayerControls(
            modifier = Modifier.padding(horizontal = 16.dp),
            playing = player?.playing == true,
            muted = muted,
            canPrevious = player?.canPrev == true,
            canNext = player?.canNext == true,
            onPrevious = { if (player != null) media.commandMac(deviceId, player.id, TandemMediaAction.PREVIOUS) },
            // Nothing playing: the play key of the computer, which starts whatever played last.
            onToggle = {
                if (player != null) media.commandMac(deviceId, player.id, TandemMediaAction.TOGGLE)
                else media.pressMacKey(deviceId, TandemMediaKey.PLAY_PAUSE)
            },
            onNext = { if (player != null) media.commandMac(deviceId, player.id, TandemMediaAction.NEXT) },
            onKey = { media.pressMacKey(deviceId, it) },
        )
    }
}

/** The cover, or a note until it arrives, and a change from one to the other is a fade. A paused cover sinks back a little. */
@Composable
private fun Cover(cover: Bitmap?, playing: Boolean) {
    val scale by animateFloatAsState(if (playing) 1f else 0.9f, TandemMotion.springy(), label = "coverScale")
    Box(
        Modifier
            .size(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = cover, animationSpec = TandemMotion.fadeSpec(), label = "cover") { shown ->
            if (shown != null) {
                val image = remember(shown) { shown.asImageBitmap() }
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(TandemIcons.Music, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }
    }
}


/**
 * A progress bar that can be dragged. It follows the finger, shows where it would land, and jumps
 * there when let go, and holds that place until the other device reports where the music really is.
 * A tap is a jump too.
 *
 * The played part is a wave while the music plays, like the wavy progress of Material 3 Expressive, and eases flat when it
 * stops. The wave moves at [WaveFrameMs], not on every frame of the screen, and only while it plays and the page is in
 * front: a wave that moves on every frame keeps the whole screen drawing 60 times a second for as long as the card is open.
 */
@Composable
private fun SeekBar(fraction: Float, duration: Long, enabled: Boolean, playing: Boolean, report: Long, onSeek: (Float) -> Unit) {
    val haptics = LocalHapticFeedback.current
    // The gesture blocks below keep the values they started with; these always hold the latest.
    val latestReport by rememberUpdatedState(report)
    val latestSeek by rememberUpdatedState(onSeek)
    var drag by remember { mutableStateOf<Float?>(null) }
    // Where it was let go, when, and which report it was waiting past.
    var held by remember { mutableStateOf<Triple<Float, Long, Long>?>(null) }
    val now = SystemClock.elapsedRealtime()
    val hold = held?.takeIf { it.third == report && now - it.second < 2500 }
    val shown = drag ?: hold?.first ?: fraction
    val active = drag != null
    val thickness by animateDpAsState(if (active) 8.dp else 6.dp, TandemMotion.springy(), label = "seekThickness")
    // The handle narrows and grows while it is held, instead of changing at once.
    val knob by animateFloatAsState(if (active) 1f else 0f, TandemMotion.springy(), label = "seekKnob")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // The fill fades toward the track while the music is paused, so the bar says it is standing still.
    val primary by animateColorAsState(
        if (playing) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, track, 0.55f),
        TandemMotion.colourSpec(), label = "seekFill",
    )

    // The wave rises when the music starts and eases flat when it stops. A spring, since it is a size.
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, TandemMotion.spatial(), label = "seekWave")
    val context = LocalContext.current
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Animations switched off in the system settings keep the wave but let it stand still.
    val moving = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    // Only read while drawing, so a tick redraws the bar and recomposes nothing.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, moving, resumed.isAtLeast(Lifecycle.State.RESUMED)) {
        if (!playing || !moving || !resumed.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        var last = SystemClock.uptimeMillis()
        while (true) {
            delay(WaveFrameMs)
            val tick = SystemClock.uptimeMillis()
            phase.floatValue = (phase.floatValue + (tick - last) * WaveCyclesPerMs) % 1f
            last = tick
        }
    }
    val wave = remember { Path() }

    // The strip is as wide as the card and 12dp taller than the bar and its times on both sides, and all of it takes
    // the finger. The bar itself is inset by the card's padding, so a touch maps onto the bar, not onto the strip.
    val inset = 16.dp
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val edge = inset.toPx()
                fun along(x: Float) = ((x - edge) / (size.width - 2 * edge).coerceAtLeast(1f)).coerceIn(0f, 1f)
                detectTapGestures { offset ->
                    val target = along(offset.x)
                    held = Triple(target, SystemClock.elapsedRealtime(), latestReport)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    latestSeek(target)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val edge = inset.toPx()
                fun along(x: Float) = ((x - edge) / (size.width - 2 * edge).coerceAtLeast(1f)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        drag = along(offset.x)
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    },
                    onDragEnd = {
                        drag?.let { target ->
                            held = Triple(target, SystemClock.elapsedRealtime(), latestReport)
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            latestSeek(target)
                        }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    change.consume()
                    drag = along(change.position.x)
                }
            },
    ) {
        Spacer(Modifier.height(12.dp))
        // Its own layer, so a step of the wave redraws the bar and not the card around it.
        Canvas(Modifier.fillMaxWidth().padding(horizontal = inset).height(28.dp).graphicsLayer {}) {
            val h = thickness.toPx()
            val mid = size.height / 2
            // The handle sits where the music is, with a gap on both sides, and the played part and the rest stop at the gap.
            val handleW = (5f - 2f * knob).dp.toPx()
            val handleH = (20f + 8f * knob).dp.toPx()
            val gap = 5.dp.toPx()
            val cx = (size.width * shown).coerceIn(handleW / 2, size.width - handleW / 2)

            val restFrom = cx + handleW / 2 + gap + h / 2
            val restTo = size.width - h / 2
            if (restTo > restFrom) {
                drawLine(track, Offset(restFrom, mid), Offset(restTo, mid), h, StrokeCap.Round)
            }

            val playedFrom = h / 2
            val playedTo = cx - handleW / 2 - gap - h / 2
            if (playedTo > playedFrom) {
                val height = amplitude.coerceAtLeast(0f) * WaveAmplitude.toPx()
                if (height < 0.05f) {
                    drawLine(primary, Offset(playedFrom, mid), Offset(playedTo, mid), h, StrokeCap.Round)
                } else {
                    val length = WaveLength.toPx()
                    val taper = WaveTaper.toPx()
                    val shift = phase.floatValue * 2f * PI.toFloat()
                    val step = 4f
                    wave.reset()
                    var x = playedFrom
                    while (true) {
                        // The wave flattens toward the handle, so the line meets it at its middle.
                        val near = ((playedTo - x) / taper).coerceIn(0f, 1f)
                        val y = mid + height * near * near * (3f - 2f * near) * sin((x - playedFrom) / length * 2f * PI.toFloat() - shift)
                        if (x == playedFrom) wave.moveTo(x, y) else wave.lineTo(x, y)
                        if (x >= playedTo) break
                        x = minOf(x + step, playedTo)
                    }
                    drawPath(wave, primary, style = Stroke(h, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            drawRoundRect(primary, Offset(cx - handleW / 2, mid - handleH / 2), Size(handleW, handleH), CornerRadius(handleW / 2))
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = inset)) {
            Text(clock((shown * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(clock(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Time between two steps of the wave, 20 a second: the wave drifts about a dp per step, so more would only cost battery. */
private const val WaveFrameMs = 50L

/** How far the wave travels per millisecond, in wavelengths. A little over half a wavelength a second. */
private const val WaveCyclesPerMs = 0.00055f

/** How far the wave leaves the middle line to either side, how long one wave is, and how far before the handle it flattens. */
private val WaveAmplitude = 3.dp
private val WaveLength = 36.dp
private val WaveTaper = 20.dp

/** 3:07, or 1:02:09 for a long one. */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
