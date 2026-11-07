package nl.markmaaktmedia.tandem.watch.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.DeviceBattery
import nl.markmaaktmedia.tandem.watch.protocol.MediaCommandKind
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.track.TrackingUi

/**
 * What plays, right on the home screen: the cover blurred behind it, the cover itself small and sharp in the middle, the name under it,
 * and the three buttons a hand looks for, the middle one in the colour of the cover. A tap on the card opens the whole player.
 */
@Composable
internal fun TransformingLazyColumnItemScope.PlayerCard(player: MediaPlayer, cover: Message.MediaArt?, spec: TransformationSpec) {
    val context = LocalContext.current
    val image = remember(cover?.key) { cover?.let { runCatching { BitmapFactory.decodeByteArray(it.image, 0, it.image.size) }.getOrNull() } }
    val colours = remember(image) { image?.let(::coverColors) }
    Box(
        rimContent(spec)
            .height(164.dp)
            // As round as the screen it is on: a card with corners showed its hard edges against the round glass.
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { PlayerActivity.open(context) },
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp))
        }
        // Dark enough to read on any cover, darkest under the buttons.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.7f)))))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (image != null) {
                Image(
                    image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(42.dp).clip(CircleShape),
                )
            } else {
                Icon(painterResource(R.drawable.sym_music_note), null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    player.title.ifBlank { player.app },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (player.artist.isNotBlank()) {
                    Text(
                        player.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { WatchLink.sendMediaCommand(player.id, MediaCommandKind.PREVIOUS) }, modifier = Modifier.size(38.dp)) {
                    Icon(painterResource(R.drawable.sym_skip_previous), null, tint = Color.White)
                }
                FilledIconButton(
                    onClick = { WatchLink.sendMediaCommand(player.id, MediaCommandKind.PLAY_PAUSE) },
                    modifier = Modifier.size(44.dp),
                    colors = colours?.let { IconButtonDefaults.filledIconButtonColors(containerColor = it.accent, contentColor = it.onAccent) }
                        ?: IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(painterResource(if (player.playing) R.drawable.sym_pause else R.drawable.sym_play_arrow), null)
                }
                IconButton(onClick = { WatchLink.sendMediaCommand(player.id, MediaCommandKind.NEXT) }, modifier = Modifier.size(38.dp)) {
                    Icon(painterResource(R.drawable.sym_skip_next), null, tint = Color.White)
                }
            }
        }
    }
}

/** Today in three rounds instead of a sentence: the steps as a ring that fills toward the goal, the heart rate, and the last night. */
@Composable
internal fun TransformingLazyColumnItemScope.TodayTiles(tracking: TrackingUi, spec: TransformationSpec) {
    val context = LocalContext.current
    val goal = tracking.stepGoal.coerceAtLeast(1)
    Row(
        rimContent(spec)
            .clip(RoundedCornerShape(30.dp))
            .clickable { context.startActivity(Intent(context, HealthActivity::class.java)) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Round(R.drawable.sym_steps, shortSteps(tracking.stepsToday), progress = (tracking.stepsToday.toFloat() / goal).coerceIn(0f, 1f))
        Round(R.drawable.sym_favorite, tracking.heartNow?.toString() ?: "-")
        Round(R.drawable.sym_bedtime, tracking.lastSleep?.takeIf { it.found }?.let { "%d:%02d".format(it.minutes / 60, it.minutes % 60) } ?: "-")
    }
}

/** The batteries of the other devices as small rings with what kind of device it is, instead of a line of text each. */
@Composable
internal fun TransformingLazyColumnItemScope.BatteryRow(devices: List<DeviceBattery>, spec: TransformationSpec) {
    Column(rimContent(spec), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        devices.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { device ->
                    val icon = when (device.kind) {
                        DeviceBattery.COMPUTER -> R.drawable.sym_laptop
                        else -> R.drawable.sym_smartphone
                    }
                    Round(icon, "${device.level}%", progress = device.level / 100f, size = 46, label = device.name)
                }
            }
        }
    }
}

/** A round with an icon and a number in it, and a ring around it when [progress] says how full something is. */
@Composable
private fun Round(@DrawableRes icon: Int, value: String, progress: Float? = null, size: Int = 54, label: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 4.dp)
            } else {
                Box(Modifier.fillMaxSize().padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(icon), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                if (label == null) Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        // Under a device the number, so its ring stays an icon and the name is not squeezed in.
        if (label != null) Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 6.4k from 6,452: four characters fit in a round, a whole number does not. */
private fun shortSteps(steps: Long): String = when {
    steps < 1_000 -> steps.toString()
    steps < 10_000 -> "%.1fk".format(steps / 1000f)
    else -> "${steps / 1000}k"
}
