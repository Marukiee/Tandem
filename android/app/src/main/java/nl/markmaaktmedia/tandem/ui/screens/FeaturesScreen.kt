package nl.markmaaktmedia.tandem.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.FeatureReport
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.NoteRow
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsPageFrame
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemFeature

/**
 * What works on each device and what does not, and why: a permission or a setting that is off. A row that needs something has a
 * button that opens the place where it is turned on, here or on that device (which shows it on its own screen).
 */
@Composable
fun FeaturesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val devices by graph.host.devices.collectAsState()
    var mine by remember { mutableStateOf(FeatureReport.current(context)) }
    // Permissions change in the settings, so this phone's own list is looked at again while the page is open.
    LaunchedEffect(Unit) {
        while (true) {
            mine = FeatureReport.current(context)
            delay(1_500)
        }
    }
    val others = devices.filter { it.id != graph.host.myId }.sortedWith(compareBy({ !it.online }, { it.name }))
    val missing = mine.count { !it.ready } + others.filter { it.online }.sumOf { d -> d.features.count { !it.ready } }

    SettingsPageFrame(stringResource(R.string.features_title), onBack) {
        Text(
            if (missing == 0) stringResource(R.string.features_all_good) else pluralStringResource(R.plurals.features_missing, missing, missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        SectionHeader(stringResource(R.string.features_this_phone))
        FeatureGroup(ordered(mine), stringResource(R.string.features_open_here)) { page ->
            context.startActivity(FeatureReport.intent(context, page).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        others.forEach { device ->
            SectionHeader(device.name)
            if (device.features.isEmpty()) {
                SettingsGroup {
                    NoteRow(
                        0, 1, TandemIcons.Info,
                        device.name,
                        if (device.online) stringResource(R.string.features_old, device.name) else stringResource(R.string.features_offline),
                    )
                }
            } else {
                Box(Modifier.alpha(if (device.online) 1f else 0.6f)) {
                    FeatureGroup(ordered(device.features), stringResource(R.string.features_open_on, device.name), enabled = device.online || device.ble) { page ->
                        openOn(context, device, page)
                    }
                }
            }
        }
    }
}

/** On a device's page: what does not work there now, and the button that opens the setting on that device. Nothing when all works. */
@Composable
fun FeatureNotice(device: TandemDevice, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val missing = if (device.online) device.features.filter { !it.ready } else emptyList()
    if (missing.isEmpty()) return
    Column(modifier) {
        SectionHeader(stringResource(R.string.features_notice, device.name), top = 12.dp, bottom = 10.dp)
        FeatureGroup(missing, stringResource(R.string.features_open_on, device.name)) { page -> openOn(context, device, page) }
    }
}

/** Asks the device to open the page, and says where to look. */
fun openOn(context: Context, device: TandemDevice, page: String) {
    val engine = context.graph.host.engine ?: return
    context.graph.scope.launch {
        val sent = runCatching { engine.openSettingsOn(device.id, page) }.isSuccess
        val text = context.getString(if (sent) R.string.features_opened else R.string.features_unreachable, device.name)
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}

/** What needs something first, then what works. */
private fun ordered(features: List<TandemFeature>) = features.filter { !it.ready } + features.filter { it.ready }

@Composable
private fun FeatureGroup(features: List<TandemFeature>, action: String, enabled: Boolean = true, open: (String) -> Unit) {
    SettingsGroup {
        features.forEachIndexed { index, feature ->
            FeatureRow(index, features.size, feature, action, enabled, open)
        }
    }
}

@Composable
private fun FeatureRow(index: Int, total: Int, feature: TandemFeature, action: String, enabled: Boolean, open: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val online = LocalTandemExtraColors.current.online
    GroupedRow(index, total) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (feature.ready) {
                RowIcon(TandemIcons.Check, tint = online, container = online.copy(alpha = 0.16f))
            } else {
                RowIcon(TandemIcons.Error, tint = scheme.onErrorContainer, container = scheme.errorContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(feature.name, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                if (!feature.ready && feature.why.isNotEmpty()) {
                    Text(feature.why, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            val page = feature.page
            if (!feature.ready && page != null) OpenPill(action, enabled) { open(page) }
        }
    }
}

@Composable
private fun OpenPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(max = 132.dp)
            .clip(PillShape)
            .background(scheme.primary)
            .alpha(if (enabled) 1f else 0.5f)
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** One thing a device is missing, under the controls it greys out: what it is, and the button that opens it on that device. */
@Composable
fun FeatureFix(device: TandemDevice, feature: TandemFeature, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(feature.why.ifEmpty { feature.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        val page = feature.page
        if (page != null) OpenPill(stringResource(R.string.features_open_on, device.name), device.online || device.ble) { openOn(context, device, page) }
    }
}

/** The feature of a device with this id when it said it is not ready, or null when it is, or when it said nothing about it (an older Tandem). */
fun TandemDevice.missing(id: String): TandemFeature? = features.firstOrNull { it.id == id && !it.ready }
