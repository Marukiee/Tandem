package nl.markmaaktmedia.tandem.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.update.UpdateState

/** Shown on the devices screen only when there is something to say. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val updater = LocalContext.current.graph.updater
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var dismissed by remember { mutableStateOf<String?>(null) }

    val visible = when (val s = state) {
        is UpdateState.Available -> dismissed != s.release.versionName
        is UpdateState.Downloading, is UpdateState.ReadyToInstall -> true
        is UpdateState.Failed -> true
        else -> false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.sizeSpring()),
        exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.sizeSpring()),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is UpdateState.Available -> {
                    Text(stringResource(R.string.update_available, s.release.versionName), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (s.release.changelog.isNotBlank()) {
                        Text(s.release.changelog.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryPillButton(stringResource(R.string.update_now), { scope.launch { updater.downloadAndInstall(s.release) } }, icon = TandemIcons.Update)
                        SecondaryPillButton(stringResource(R.string.update_later), { dismissed = s.release.versionName })
                    }
                }
                is UpdateState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillSpinner(size = 28.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(stringResource(R.string.update_downloading, (s.progress * 100).toInt()), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(5.dp).clip(PillShape).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(s.progress.coerceAtLeast(0.02f)).height(5.dp).clip(PillShape).background(MaterialTheme.colorScheme.onPrimaryContainer))
                    }
                }
                is UpdateState.ReadyToInstall -> {
                    Text(stringResource(R.string.update_ready), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    PrimaryPillButton(stringResource(R.string.update_install), { scope.launch { updater.install(java.io.File(s.filePath)) } }, icon = TandemIcons.Update)
                }
                is UpdateState.Failed -> {
                    Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    SecondaryPillButton(stringResource(R.string.update_retry), { scope.launch { updater.check() } }, icon = TandemIcons.Refresh)
                }
                else -> Unit
            }
        }
    }
}
