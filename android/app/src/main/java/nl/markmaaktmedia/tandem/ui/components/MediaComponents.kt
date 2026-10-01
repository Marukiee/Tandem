package nl.markmaaktmedia.tandem.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.media.RemotePlayers
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemMediaAction
import uniffi.tandem_core.TandemMediaPlayer
import uniffi.tandem_core.tandemMediaVisible

/**
 * What the other device is playing, with the buttons to control it. Players that play what this
 * phone already plays are left out, so a phone that only remote controls the Mac's Spotify does
 * not show it twice.
 */
@Composable
fun NowPlayingSection(device: TandemDevice) {
    val graph = LocalContext.current.graph
    val share by graph.prefs.mediaShare.collectAsState(initial = true)
    val remote by graph.media.remote.collectAsState()
    val local by graph.media.local.collectAsState()
    val covers by graph.media.remoteArt.collectAsState()
    val entry = remote[device.id]
    if (!share || entry == null || !(device.online || device.ble)) return
    val shown = remember(entry, local) { tandemMediaVisible(local, entry.players) }
    if (shown.isEmpty()) return

    SectionHeader(stringResource(R.string.media_now_playing), top = 12.dp, bottom = 0.dp)
    shown.forEach { player ->
        PlayerCard(device.id, player, entry, covers[player.art.toLong()])
    }
}

@Composable
private fun PlayerCard(deviceId: String, player: TandemMediaPlayer, entry: RemotePlayers, cover: android.graphics.Bitmap?) {
    val media = LocalContext.current.graph.media
    Column(
        Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(cover.asImageBitmap(), null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
                } else {
                    Icon(TandemIcons.Music, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(player.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(player.artist, player.app).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val duration = player.durationMs?.toLong() ?: 0L
        val start = player.positionMs?.toLong()
        if (duration > 0 && start != null) {
            // The position is only sent when something changes, so it is counted on from there while it plays.
            val now by produceState(SystemClock.elapsedRealtime(), player.playing, entry) {
                while (player.playing) {
                    value = SystemClock.elapsedRealtime()
                    delay(500)
                }
            }
            val natural = ((start + if (player.playing) (now - entry.at) else 0L).toFloat() / duration).coerceIn(0f, 1f)
            SeekBar(
                fraction = natural,
                duration = duration,
                enabled = player.canSeek,
                report = entry.at,
                onSeek = { target -> media.commandMac(deviceId, player.id, TandemMediaAction.SEEK, (target * duration).toLong()) },
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(
                TandemIcons.SkipPrevious, stringResource(R.string.media_previous),
                { media.commandMac(deviceId, player.id, TandemMediaAction.PREVIOUS) },
                enabled = player.canPrev, tint = MaterialTheme.colorScheme.onSurface, size = 52, iconSize = 26,
            )
            TandemIconButton(
                if (player.playing) TandemIcons.Pause else TandemIcons.Play,
                stringResource(if (player.playing) R.string.media_pause else R.string.media_play),
                { media.commandMac(deviceId, player.id, TandemMediaAction.TOGGLE) },
                modifier = Modifier.padding(horizontal = 10.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer, background = MaterialTheme.colorScheme.primaryContainer,
                size = 60, iconSize = 30,
            )
            TandemIconButton(
                TandemIcons.SkipNext, stringResource(R.string.media_next),
                { media.commandMac(deviceId, player.id, TandemMediaAction.NEXT) },
                enabled = player.canNext, tint = MaterialTheme.colorScheme.onSurface, size = 52, iconSize = 26,
            )
        }
    }
}


/**
 * A progress bar that can be dragged. It follows the finger, shows where it would land, and jumps
 * there when let go, and holds that place until the other device reports where the music really is.
 * A tap is a jump too.
 */
@Composable
private fun SeekBar(fraction: Float, duration: Long, enabled: Boolean, report: Long, onSeek: (Float) -> Unit) {
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
    val thickness by animateDpAsState(if (active) 10.dp else 6.dp, label = "seekThickness")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val target = (offset.x / size.width).coerceIn(0f, 1f)
                        held = Triple(target, SystemClock.elapsedRealtime(), latestReport)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        latestSeek(target)
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            drag = (offset.x / size.width).coerceIn(0f, 1f)
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
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val h = thickness.toPx()
                val top = (size.height - h) / 2
                drawRoundRect(track, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(primary, Offset(0f, top), Size(maxOf(h, size.width * shown), h), CornerRadius(h / 2))
                if (active) drawCircle(primary, radius = 9.dp.toPx(), center = Offset((size.width * shown).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx()), size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(clock((shown * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(clock(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 3:07, or 1:02:09 for a long one. */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
