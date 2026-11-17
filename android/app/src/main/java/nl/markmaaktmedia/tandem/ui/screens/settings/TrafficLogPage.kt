package nl.markmaaktmedia.tandem.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemTrafficSummary
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val PREFS = "traffic_log"
private const val SINCE = "since"

/**
 * What the traffic log of the core has counted: totals since a time the person chooses, per kind of message and per device, and per hour
 * for the last 24 hours. The days are also copied to the folder of the app that `adb pull` reaches, and can be shared from here.
 */
@Composable
internal fun TrafficLogPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = context.graph.host.engine
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var since by remember { mutableLongStateOf(prefs.getLong(SINCE, System.currentTimeMillis() - 24 * 3_600_000L)) }
    var summary by remember { mutableStateOf<TandemTrafficSummary?>(null) }
    // Read once when the page opens and when the start changes, from the files: nothing runs while the page is closed.
    LaunchedEffect(since) {
        summary = withContext(Dispatchers.IO) { runCatching { engine?.trafficSummary(since.toULong()) }.getOrNull() }
    }

    SettingsPageFrame(stringResource(R.string.traffic_title), onBack) {
        SectionHeader(stringResource(R.string.traffic_since, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(since))))
        SettingsGroup {
            ActionRow(
                0, 2, TandemIcons.Restart, stringResource(R.string.traffic_reset), stringResource(R.string.traffic_reset_sub),
                {
                    since = System.currentTimeMillis()
                    prefs.edit().putLong(SINCE, since).apply()
                },
            )
            ActionRow(1, 2, TandemIcons.OpenInNew, stringResource(R.string.traffic_share), stringResource(R.string.traffic_share_sub), { share(context) })
        }

        val s = summary
        if (s == null || (s.byKind.isEmpty() && s.byDevice.isEmpty())) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.traffic_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SettingsPageFrame
        }
        SectionHeader(stringResource(R.string.traffic_devices))
        Lines(s.byDevice.map { it.name.ifBlank { it.id.take(8) } to "${it.packetsTx + it.packetsRx} packets, ${it.sentCount + it.recvCount} messages, ${bytes(it.sentBytes + it.recvBytes)}" })
        SectionHeader(stringResource(R.string.traffic_kinds))
        Lines(s.byKind.take(15).map { it.kind to "${it.sentCount} out, ${it.recvCount} in, ${bytes(it.sentBytes + it.recvBytes)}" })
        SectionHeader(stringResource(R.string.traffic_hours))
        val hour = DateFormat.getTimeInstance(DateFormat.SHORT)
        Lines(s.byHour.reversed().map { hour.format(Date(it.hourStartMs.toLong())) to "${it.packets} packets, ${it.sentCount + it.recvCount} messages" })
    }
}

@Composable
private fun Lines(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        for ((left, right) in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(left, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(right, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun bytes(n: ULong): String = if (n < 1000uL) "$n B" else "${(n + 500uL) / 1000uL} kB"

/** The day files, as they are in the folder `adb pull` reaches. */
private fun share(context: Context) {
    val files = context.getExternalFilesDir("traffic")?.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy(File::getName).orEmpty()
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.clipboard", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
