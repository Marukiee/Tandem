package nl.markmaaktmedia.tandem.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Image
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
            val position = (start + if (player.playing) (now - entry.at) else 0L).coerceIn(0L, duration)
            LinearProgressIndicator(
                progress = { position.toFloat() / duration },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
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
