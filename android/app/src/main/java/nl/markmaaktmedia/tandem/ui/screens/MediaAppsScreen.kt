package nl.markmaaktmedia.tandem.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.PillLoader
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

private data class MediaApp(val packageName: String, val label: String)

/** Which apps may show what they play on your other devices. Everything is allowed until switched off. */
@Composable
fun MediaAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val excluded by graph.prefs.mediaExcluded.collectAsState(initial = emptySet())
    val access by graph.media.hasAccess.collectAsState()

    val apps by produceState<List<MediaApp>?>(null, excluded) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            // The apps that offer their music to other apps, plus whatever has a session right now (a
            // browser has none of the first), plus the ones already left out so they can be let back in.
            val declared = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).map { it.serviceInfo.packageName }
            (declared + graph.media.activePackages() + excluded)
                .filter { it != context.packageName }
                .distinct()
                .map { pkg ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    MediaApp(pkg, label)
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.settings_media_apps), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.media_apps_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        if (!access) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CardSquircle).background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.media_access_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                PrimaryPillButton(stringResource(R.string.action_allow), { Permissions.openNotificationAccessSettings(context) })
            }
        }
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PillLoader(label = stringResource(R.string.mirror_loading)) }
        } else if (list.isEmpty()) {
            Text(stringResource(R.string.media_apps_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list, key = { it.packageName }) { app ->
                    val index = list.indexOf(app)
                    val on = app.packageName !in excluded
                    GroupedRow(index, list.size, onClick = { scope.launch { graph.prefs.setMediaApp(app.packageName, !on) } }) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            MediaAppIcon(app.packageName)
                            Text(app.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                            Switch(checked = on, onCheckedChange = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96) }.getOrNull() }
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp)) }
    }
}
