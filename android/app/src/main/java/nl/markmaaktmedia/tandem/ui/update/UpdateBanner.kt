package nl.markmaaktmedia.tandem.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.BuildConfig
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.update.ReleaseInfo
import nl.markmaaktmedia.tandem.update.UpdateState

/**
 * The bar at the top of the app, on every screen, when a new version is out. The same shape
 * as in MarkMaaktAI: the version and where you are now, one Download button that fetches the
 * APK and hands it to the installer, and a cross that dismisses that version for good.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = context.graph
    val updater = graph.updater
    val state by updater.state.collectAsState()
    val dismissed by graph.prefs.dismissedUpdate.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val release: ReleaseInfo? = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.ReadyToInstall -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.AwaitingConfirmation -> s.release
        else -> null
    }
    val failed = state as? UpdateState.Failed
    // A failure is shown until it is retried or the app is reopened; a release once dismissed stays hidden.
    // Back from the "install unknown apps" screen with the switch on: carry on where it stopped.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        scope.launch { updater.resumeAfterPermission() }
        // An update that finished downloading while the app was out of sight asks its question now.
        updater.resumeConfirmation()
    }
    val visible = failed != null || (release != null && release.tag != dismissed)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.sizeSpring()),
        exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.sizeSpring()),
        modifier = modifier,
    ) {
        val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value != androidx.compose.material3.SwipeToDismissBoxValue.Settled && release != null) {
                    scope.launch { graph.prefs.setDismissedUpdate(release.tag) }
                    true
                } else false
            },
        )
        androidx.compose.material3.SwipeToDismissBox(state = swipe, backgroundContent = {}) {
        Surface(
            shape = CardSquircle,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().animateContentSize(TandemMotion.sizeSpring()).padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
                if (release != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(TandemIcons.Update, contentDescription = null, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.update_banner_title, release.versionName),
                                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (state) {
                                    is UpdateState.NeedsPermission -> stringResource(R.string.update_needs_permission)
                                    is UpdateState.AwaitingConfirmation -> stringResource(R.string.update_confirm_hint)
                                    else -> stringResource(R.string.update_on_version, BuildConfig.VERSION_NAME)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        when (val s = state) {
                            is UpdateState.Downloading, is UpdateState.Installing -> PillSpinner(size = 24.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            is UpdateState.AwaitingConfirmation -> PrimaryPillButton(stringResource(R.string.update_install), { updater.launchConfirmation() })
                            is UpdateState.ReadyToInstall -> PrimaryPillButton(stringResource(R.string.update_install), { scope.launch { updater.install(s.release, java.io.File(s.filePath)) } })
                            is UpdateState.NeedsPermission -> PrimaryPillButton(stringResource(R.string.update_allow), { updater.openInstallPermissionSettings() })
                            else -> PrimaryPillButton(stringResource(R.string.update_download), { scope.launch { updater.downloadAndInstall(release) } })
                        }
                        TandemIconButton(
                            TandemIcons.Close, stringResource(R.string.update_later),
                            { scope.launch { graph.prefs.setDismissedUpdate(release.tag) } },
                            tint = MaterialTheme.colorScheme.onPrimaryContainer, size = 36, iconSize = 16,
                        )
                    }
                    val downloading = state as? UpdateState.Downloading
                    // The banner grows to make room for the bar, and closes again afterwards.
                    AnimatedVisibility(
                        visible = downloading != null,
                        enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.sizeSpring(), expandFrom = Alignment.Top),
                        exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.sizeSpring(), shrinkTowards = Alignment.Top),
                    ) {
                        val last = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                        if (downloading != null) last.floatValue = downloading.progress
                        val shown by androidx.compose.animation.core.animateFloatAsState(last.floatValue, TandemMotion.spatial(), label = "downloadProgress")
                        Column(Modifier.padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            nl.markmaaktmedia.tandem.ui.components.WavyProgress(shown, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHighest)
                            Text(stringResource(R.string.update_downloading, (last.floatValue * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else if (failed != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.titleSmall)
                            Text(failed.reason, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        SecondaryPillButton(stringResource(R.string.update_retry), { scope.launch { updater.retry() } }, icon = TandemIcons.Refresh)
                    }
                }
            }
        }
        }
    }
}
