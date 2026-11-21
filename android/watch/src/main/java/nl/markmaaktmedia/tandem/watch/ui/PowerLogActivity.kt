package nl.markmaaktmedia.tandem.watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.power.PowerLog
import nl.markmaaktmedia.tandem.watch.protocol.PowerRecord
import nl.markmaaktmedia.tandem.watch.protocol.PowerSummary
import nl.markmaaktmedia.tandem.watch.protocol.Span
import nl.markmaaktmedia.tandem.watch.protocol.summarize
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the last day went: what Tandem did per hour next to what the battery lost, from [PowerLog]. Read when the page opens and not
 * again, so it costs nothing while it is closed.
 */
class PowerLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TandemWatchTheme {
                AppScaffold(timeText = { TimeText() }) { PowerLogScreen() }
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, PowerLogActivity::class.java))
        }
    }
}

@Composable
private fun PowerLogScreen() {
    val context = LocalContext.current
    val list = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var done by remember { mutableStateOf<List<PowerRecord>?>(null) }
    var current by remember { mutableStateOf<PowerRecord?>(null) }
    LaunchedEffect(Unit) {
        done = withContext(Dispatchers.IO) { PowerLog.recent(context) }
        current = PowerLog.current()
    }
    val all = done.orEmpty() + listOfNotNull(current)
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding.withRoomAbove(), modifier = Modifier.fillMaxSize()) {
            item(key = "title") {
                ListHeader(modifier = rimRow(spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.power_title), color = MaterialTheme.colorScheme.primary)
                }
            }
            if (done != null && all.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.power_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = rimContent(spec).padding(horizontal = 12.dp),
                    )
                }
            }
            if (all.isNotEmpty()) {
                item(key = "total") { Total(summarize(all), rimContent(spec)) }
            }
            for (record in all.asReversed()) {
                item(key = "hour-${record.startMs}") { Hour(record, record === current, rimContent(spec)) }
            }
            if (all.isNotEmpty()) {
                item(key = "note") {
                    Text(
                        stringResource(R.string.power_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = rimContent(spec).padding(top = 8.dp, start = 12.dp, end = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Total(summary: PowerSummary, modifier: Modifier) {
    val hours = ((summary.seconds + 1800) / 3600).toInt()
    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.power_total, hours) + (summary.uahPerHour?.let { "  ·  " + stringResource(R.string.power_per_hour, mah(it)) } ?: ""),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                R.string.power_line,
                summary.wakes,
                minutes(summary.linkSeconds),
                minutes(summary.scanSeconds),
                minutes(summary.screenSeconds),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (summary.lowPrioritySeconds > 0) {
            Text(
                stringResource(R.string.power_low, minutes(summary.lowPrioritySeconds)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        kinds(summary.messagesIn)?.let { Kinds(R.string.power_in, it) }
        kinds(summary.messagesOut)?.let { Kinds(R.string.power_out, it) }
    }
}

@Composable
private fun Kinds(label: Int, text: String) {
    Text(
        stringResource(label, text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hour(record: PowerRecord, partial: Boolean, modifier: Modifier) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val drain = when {
        record.spanSeconds(Span.CHARGING) * 2 >= record.seconds -> stringResource(R.string.power_charging)
        record.drainUah != null -> stringResource(R.string.power_mah, mah(record.drainUah!!))
        record.percentPerHour != null -> "%.0f%%".format(Locale.getDefault(), record.percentPerHour)
        else -> "-"
    }
    val title = (if (partial) stringResource(R.string.power_now) else time.format(Date(record.startMs))) + "  ·  " + drain
    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(
                R.string.power_line,
                record.wakes,
                minutes(record.spanSeconds(Span.LINK)),
                minutes(record.spanSeconds(Span.SCAN_FAST) + record.spanSeconds(Span.SCAN_BALANCED) + record.spanSeconds(Span.SCAN_LOW)),
                minutes(record.spanSeconds(Span.SCREEN)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun minutes(seconds: Long): Int = ((seconds + 30) / 60).toInt()

private fun mah(uah: Long): String = String.format(Locale.getDefault(), "%.1f", uah / 1000.0)

/** The three kinds of message there were most of, as `Ping 12, Notification 8`. Null when there were none. */
private fun kinds(counts: Map<String, Int>): String? =
    counts.entries.sortedByDescending { it.value }.take(3).takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.key} ${it.value}" }
