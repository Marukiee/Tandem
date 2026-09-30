package nl.markmaaktmedia.tandem.hotspot

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** How many rows [HotspotSettingsRows] adds under the hotspot switch. */
const val HOTSPOT_EXTRA_ROWS = 5

/**
 * The rows under the "Let my Mac use my hotspot" switch: the current method, a test,
 * the roaming choice and a link to the setup guide. `first` is the index of the first
 * of them in the group, `total` the size of the whole group.
 */
@Composable
fun ColumnScope.HotspotSettingsRows(first: Int, total: Int) {
    val context = LocalContext.current
    val module = HotspotModule.get(context)
    val scope = rememberCoroutineScope()
    val shizuku by module.shizuku.state.collectAsState()
    val snapshot by module.controller.snapshot.collectAsState()
    val roaming by module.prefs.allowRoaming.collectAsState(initial = false)

    // Shizuku comes and goes without telling us while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            module.shizuku.refresh()
            delay(2_000)
        }
    }

    val methodText = when (shizuku) {
        ShizukuState.Ready -> R.string.hotspot_method_shizuku
        ShizukuState.NeedsPermission -> R.string.hotspot_method_need_permission
        ShizukuState.Denied -> R.string.hotspot_method_denied
        ShizukuState.NotRunning -> R.string.hotspot_method_not_running
        ShizukuState.NotInstalled -> R.string.hotspot_method_not_installed
        ShizukuState.TooOld -> R.string.hotspot_method_too_old
    }
    ActionRow(
        first, total, TandemIcons.Hotspot, stringResource(R.string.hotspot_method), stringResource(methodText),
        onClick = {
            when (shizuku) {
                ShizukuState.NeedsPermission -> module.shizuku.requestPermission()
                ShizukuState.NotRunning, ShizukuState.Denied -> module.shizuku.launchShizuku()
                ShizukuState.NotInstalled, ShizukuState.TooOld -> context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ShizukuState.Ready -> Unit
            }
        },
        // Allowed: a check, so nobody thinks there is still something to press.
        trailing = if (shizuku == ShizukuState.Ready) {
            {
                androidx.compose.material3.Icon(
                    TandemIcons.Check, contentDescription = null,
                    tint = nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors.current.online,
                    modifier = androidx.compose.ui.Modifier.size(24.dp),
                )
            }
        } else null,
    )

    val busy = snapshot.phase == Phase.Starting || snapshot.phase == Phase.NeedsTap
    ActionRow(
        first + 1, total, TandemIcons.Refresh,
        stringResource(if (busy) R.string.hotspot_test_busy else R.string.hotspot_test),
        stringResource(R.string.hotspot_test_sub),
        onClick = {
            if (busy) return@ActionRow
            val message = when (module.availability()) {
                BleAvailability.PrefOff -> R.string.hotspot_test_needs_pref
                BleAvailability.NoPermission -> R.string.hotspot_test_needs_bluetooth
                else -> null
            }
            if (message != null) {
                Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                return@ActionRow
            }
            module.controller.test()
            scope.launch {
                // The result is a moment away: say what came of it.
                val result = withTimeoutOrNull(25_000) {
                    module.controller.snapshot.first { it.phase != Phase.Starting }
                }
                val text = when (result?.phase) {
                    Phase.On -> R.string.hotspot_test_ok
                    Phase.NeedsTap -> R.string.hotspot_test_manual
                    Phase.Refused -> if (result.refusal == Refusal.Battery) R.string.hotspot_test_battery else R.string.hotspot_test_roaming
                    else -> R.string.hotspot_test_failed
                }
                Toast.makeText(context, context.getString(text), Toast.LENGTH_LONG).show()
            }
        },
    )

    SwitchRow(
        first + 2, total, TandemIcons.Cellular, stringResource(R.string.hotspot_roaming), stringResource(R.string.hotspot_roaming_sub), roaming,
        { scope.launch { module.prefs.setAllowRoaming(it) } },
    )

    val limitMb by module.prefs.dataLimitMb.collectAsState(initial = 0L)
    val totalBytes by module.prefs.dataUsedBytes.collectAsState(initial = 0L)
    val sessionBytes by module.controller.sessionBytes.collectAsState()
    var pickLimit by remember { mutableStateOf(false) }
    val used = android.text.format.Formatter.formatShortFileSize(context, totalBytes + if (snapshot.on) sessionBytes else 0L)
    ActionRow(
        first + 3, total, TandemIcons.Cellular, stringResource(R.string.hotspot_data_limit),
        stringResource(if (limitMb > 0) R.string.hotspot_data_limit_sub_on else R.string.hotspot_data_limit_sub_off, limitLabel(limitMb), used),
        onClick = { pickLimit = true },
    )
    if (pickLimit) {
        nl.markmaaktmedia.tandem.ui.components.TandemDialog(
            title = stringResource(R.string.hotspot_data_limit),
            body = stringResource(R.string.hotspot_data_limit_body, used),
            onDismiss = { pickLimit = false },
            icon = TandemIcons.Cellular,
            actions = {
                nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton(stringResource(R.string.hotspot_data_reset), { scope.launch { module.prefs.resetDataUsed() } })
                nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton(stringResource(R.string.action_close), { pickLimit = false })
            },
            content = {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    LIMIT_CHOICES.forEach { mb ->
                        val pick = { scope.launch { module.prefs.setDataLimitMb(mb) }; Unit }
                        if (mb == limitMb) nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton(limitLabel(mb), pick)
                        else nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton(limitLabel(mb), pick)
                    }
                }
            },
        )
    }

    ActionRow(
        first + 4, total, TandemIcons.OpenInNew, stringResource(R.string.hotspot_setup), stringResource(R.string.hotspot_setup_sub),
        onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Marukiee/Tandem/blob/main/docs/HOTSPOT.md")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

private val LIMIT_CHOICES = listOf(0L, 250L, 500L, 1024L, 2048L, 5120L)

@Composable
private fun limitLabel(mb: Long): String = when {
    mb <= 0L -> stringResource(R.string.hotspot_data_none)
    mb >= 1024L -> "${mb / 1024} GB"
    else -> "$mb MB"
}
