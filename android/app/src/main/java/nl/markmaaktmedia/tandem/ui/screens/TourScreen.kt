package nl.markmaaktmedia.tandem.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.FeatureReport
import nl.markmaaktmedia.tandem.engine.SetupTour
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsPageFrame
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemFeature

/**
 * Everything Tandem can do on this phone, with what each thing still needs: set it up now, later (one reminder in a few days), or not for
 * me. Shown once after an update that brings a new version of it ([SetupTour.VERSION]), and always from the top of Setup.
 */
@Composable
fun TourScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    var features by remember { mutableStateOf(emptyList<TandemFeature>()) }
    // Permissions are given in the settings of Android, so the list is looked at again while the page is open.
    LaunchedEffect(Unit) {
        prefs.setTourSeen(SetupTour.VERSION)
        while (true) {
            features = SetupTour.items(FeatureReport.current(context))
            delay(1_500)
        }
    }
    val later by prefs.tourLater.collectAsState(initial = emptySet())
    val skipped by prefs.tourSkip.collectAsState(initial = emptySet())
    val todo = features.filter { !it.ready && it.id !in later && it.id !in skipped }
    val waiting = features.filter { !it.ready && it.id in later }
    val notForMe = features.filter { !it.ready && it.id in skipped }
    val works = features.filter { it.ready }

    SettingsPageFrame(stringResource(R.string.tour_title), onBack) {
        Text(
            if (todo.isEmpty()) stringResource(R.string.tour_all_done) else pluralStringResource(R.plurals.tour_intro, todo.size, todo.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        if (todo.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tour_todo))
            SettingsGroup {
                todo.forEachIndexed { index, feature ->
                    TourRow(index, todo.size, feature) {
                        Pill(stringResource(R.string.tour_set_up), primary = true) { setUp(context, feature) }
                        Pill(stringResource(R.string.tour_later)) { scope.launch { SetupTour.later(context, feature.id) } }
                        Pill(stringResource(R.string.tour_not_for_me)) { scope.launch { SetupTour.skip(context, feature.id) } }
                    }
                }
            }
        }
        if (waiting.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tour_waiting))
            SettingsGroup {
                waiting.forEachIndexed { index, feature ->
                    TourRow(index, waiting.size, feature, note = stringResource(R.string.tour_waiting_note)) {
                        Pill(stringResource(R.string.tour_set_up), primary = true) { setUp(context, feature) }
                        Pill(stringResource(R.string.tour_not_for_me)) { scope.launch { SetupTour.skip(context, feature.id) } }
                    }
                }
            }
        }
        if (works.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tour_works))
            SettingsGroup {
                works.forEachIndexed { index, feature -> TourRow(index, works.size, feature) {} }
            }
        }
        if (notForMe.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tour_not_for_you))
            SettingsGroup(Modifier.alpha(0.7f)) {
                notForMe.forEachIndexed { index, feature ->
                    TourRow(index, notForMe.size, feature, quiet = true) {
                        Pill(stringResource(R.string.tour_undo)) { scope.launch { SetupTour.undo(context, feature.id) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PrimaryPillButton(stringResource(R.string.tour_done), onBack)
        }
    }
}

/** Opens the place where it is turned on, or turns on Tandem's own switch at once. */
private fun setUp(context: Context, feature: TandemFeature) {
    val page = feature.page ?: return
    if (FeatureReport.switchOn(context, page)) return
    runCatching { context.startActivity(FeatureReport.intent(context, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun TourRow(
    index: Int,
    total: Int,
    feature: TandemFeature,
    note: String? = null,
    quiet: Boolean = false,
    actions: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val online = LocalTandemExtraColors.current.online
    GroupedRow(index, total) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (feature.ready) {
                    RowIcon(TandemIcons.Check, tint = online, container = online.copy(alpha = 0.16f))
                } else {
                    RowIcon(iconOf(feature.id))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(feature.name, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                    val line = note ?: feature.why.takeIf { !feature.ready && !quiet && it.isNotEmpty() }
                    if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            if (!feature.ready) {
                // Under the text, from where the text starts, so the buttons belong to it and the name never gets squeezed.
                Row(Modifier.padding(start = 54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        }
    }
}

/** The same small pill as on What works: filled for setting up, quiet for the others. */
@Composable
private fun Pill(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (fill, ink) = if (primary) scheme.primary to scheme.onPrimary else scheme.surfaceContainerHigh to scheme.onSurface
    Box(
        Modifier.heightIn(min = 36.dp).clip(PillShape).background(fill).bouncyClickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

@Composable
private fun iconOf(id: String): Painter = when (id) {
    "control", "pointer" -> TandemIcons.Mouse
    "notifications", "alerts" -> TandemIcons.Notifications
    "media" -> TandemIcons.Music
    "calls" -> TandemIcons.Call
    "files" -> TandemIcons.Folder
    "camera" -> TandemIcons.Camera
    "microphone" -> TandemIcons.Mic
    "background" -> TandemIcons.Battery
    "bluetooth" -> TandemIcons.Bluetooth
    "ring" -> TandemIcons.Ring
    "messages" -> painterResource(R.drawable.sym_sms)
    "focus" -> TandemIcons.Dnd
    "codes" -> TandemIcons.Key
    else -> TandemIcons.Info
}
