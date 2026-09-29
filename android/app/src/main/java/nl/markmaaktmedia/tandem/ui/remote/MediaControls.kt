package nl.markmaaktmedia.tandem.ui.remote

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemMediaKey

val MediaHeight = 52.dp
val MouseHeight = 56.dp

/**
 * Mute, volume, previous, play or pause, next, volume, as one connected group. Every
 * button is the same size: the play button used to be bigger, which made the row look
 * like a mistake. It keeps its accent colour, that is enough to find it.
 *
 * The Mac does not report whether something is playing, so the icon follows the
 * presses instead: each press flips it, which is right until the Mac and the phone
 * disagree once, and then a single press puts them back in step.
 */
@Composable
fun MediaControls(onKey: (TandemMediaKey) -> Unit, modifier: Modifier = Modifier) {
    var playing by remember { mutableStateOf(false) }

    val items = listOf(
        MediaItem(TandemMediaKey.MUTE, { TandemIcons.VolumeOff }, R.string.remote_media_mute),
        MediaItem(TandemMediaKey.VOLUME_DOWN, { TandemIcons.VolumeDown }, R.string.remote_media_volume_down),
        MediaItem(TandemMediaKey.PREVIOUS, { TandemIcons.Previous }, R.string.remote_media_previous),
        MediaItem(TandemMediaKey.PLAY_PAUSE, null, R.string.remote_media_play_pause),
        MediaItem(TandemMediaKey.NEXT, { TandemIcons.Next }, R.string.remote_media_next),
        MediaItem(TandemMediaKey.VOLUME_UP, { TandemIcons.VolumeUp }, R.string.remote_media_volume_up),
    )

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(JoinGap)) {
        items.forEachIndexed { index, item ->
            val isPlay = item.key == TandemMediaKey.PLAY_PAUSE
            GroupButton(
                press = rememberPressState(),
                height = MediaHeight,
                joins = Joins.row(index, items.size),
                colors = if (isPlay) GroupTone.accent() else GroupTone.neutral(),
                modifier = Modifier.weight(1f),
                description = stringResource(item.label),
                onUp = { inside ->
                    if (inside) {
                        if (isPlay) playing = !playing
                        onKey(item.key)
                    }
                },
            ) { tint ->
                val icon = item.icon
                if (icon == null) {
                    // The glyph swaps with a springy scale and a quick fade, so it reads as
                    // the same button changing its mind rather than two icons cut together.
                    AnimatedContent(
                        targetState = playing,
                        transitionSpec = {
                            (scaleIn(TandemMotion.springy(), initialScale = 0.4f) + fadeIn(TandemMotion.fadeSpec())) togetherWith
                                (scaleOut(TandemMotion.springy(), targetScale = 0.4f) + fadeOut(TandemMotion.fadeSpec()))
                        },
                        label = "playPause",
                    ) { isPlaying ->
                        Icon(if (isPlaying) TandemIcons.Pause else TandemIcons.Play, null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                } else {
                    Icon(icon(), null, tint = tint, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

private class MediaItem(val key: TandemMediaKey, val icon: (@Composable () -> Painter)?, val label: Int)

/**
 * Left and right mouse button, joined. Both work as a real button: the press goes
 * down when the finger lands and up when it lifts, so holding one and dragging on the
 * trackpad with another finger drags.
 *
 * While either is held the pair comes apart, the gap opens and the inner corners round
 * out, and it closes again on release. The pair moving is what tells you a button is
 * down, since a finger covers the button itself.
 */
@Composable
fun MouseButtons(onButton: (button: Int, down: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val left = rememberPressState()
    val right = rememberPressState()
    val apart = left.down || right.down

    val gap by animateDpAsState(if (apart) 10.dp else JoinGap, TandemMotion.springy(), label = "mouseGap")
    val join = if (apart) MouseHeight / 2 else JoinRadius

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        MouseButton(left, Joins.row(0, 2), stringResource(R.string.remote_left), join, Modifier.weight(1f)) { onButton(0, it) }
        MouseButton(right, Joins.row(1, 2), stringResource(R.string.remote_right), join, Modifier.weight(1f)) { onButton(1, it) }
    }
}

@Composable
private fun MouseButton(
    press: PressState,
    joins: Joins,
    label: String,
    join: Dp,
    modifier: Modifier,
    onButton: (Boolean) -> Unit,
) {
    GroupButton(
        press = press,
        height = MouseHeight,
        joins = joins,
        colors = GroupTone.held(),
        modifier = modifier,
        description = label,
        joinRadius = join,
        onDown = { onButton(true) },
        onUp = { onButton(false) },
    ) { tint ->
        Text(label, style = MaterialTheme.typography.titleSmall, color = tint, maxLines = 1)
    }
}
