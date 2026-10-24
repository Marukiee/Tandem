package nl.markmaaktmedia.tandem.ui.components

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.FindDevices
import nl.markmaaktmedia.tandem.engine.WakeOnLan
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemRoute

/**
 * Finding a computer, like Find My: a sound that plays on it until it is found, and when and on which network it was last seen. The row
 * always looks the same; only a press that cannot work (it is away, or its Tandem is too old) says why, with Wake when that may help.
 */
@Composable
fun FindComputerSection(device: TandemDevice) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ringing by FindDevices.ringing.collectAsState()
    val on = device.id in ringing
    var why by remember { mutableStateOf<WhyNot?>(null) }
    val scheme = MaterialTheme.colorScheme

    SectionHeader(stringResource(R.string.find_section), top = 12.dp, bottom = 0.dp)
    SettingsGroup {
        ActionRow(
            0, 1, TandemIcons.Ring,
            stringResource(if (on) R.string.find_stop_sound else R.string.find_play),
            lastSeenText(context, device) ?: stringResource(R.string.find_play_sub),
            {
                when {
                    on -> FindDevices.ring(context, device.id, false)
                    !device.online -> why = WhyNot.Offline
                    "ring" !in device.caps -> why = WhyNot.Old
                    else -> {
                        FindDevices.ring(context, device.id, true)
                        android.widget.Toast.makeText(context, context.getString(R.string.find_playing, device.name), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            iconTint = if (on) scheme.onPrimary else null,
            iconContainer = if (on) scheme.primary else null,
        )
    }

    when (why) {
        WhyNot.Offline -> {
            val wakeMac = device.status.wakeMac
            TandemDialog(
                title = stringResource(R.string.find_offline_title, device.name),
                body = listOfNotNull(
                    stringResource(R.string.find_offline_body),
                    lastSeenText(context, device),
                    if (wakeMac != null) stringResource(R.string.wake_hint) else null,
                ).joinToString("\n\n"),
                icon = TandemIcons.Ring,
                onDismiss = { why = null },
                closeLabel = stringResource(R.string.generic_ok),
                actions = {
                    if (wakeMac != null) {
                        PrimaryPillButton(stringResource(R.string.action_wake), {
                            why = null
                            scope.launch {
                                val sent = WakeOnLan.send(context, wakeMac)
                                android.widget.Toast.makeText(context, if (sent) R.string.wake_sent else R.string.wake_failed, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }, icon = TandemIcons.Power)
                    }
                    SecondaryPillButton(stringResource(R.string.generic_ok), { why = null })
                },
            )
        }
        WhyNot.Old -> TandemDialog(
            title = stringResource(R.string.find_old_title, device.name),
            body = stringResource(R.string.find_old_body),
            icon = TandemIcons.Update,
            onDismiss = { why = null },
            closeLabel = stringResource(R.string.generic_ok),
            actions = { SecondaryPillButton(stringResource(R.string.generic_ok), { why = null }) },
        )
        null -> Unit
    }
}

private enum class WhyNot { Offline, Old }

/** When and where the device was last connected to this phone, in a few words, or nothing when that is not known. */
fun lastSeenText(context: Context, device: TandemDevice): String? {
    val seen = device.lastSeen ?: return null
    val ssid = seen.ssid
    if (device.online) return ssid?.let { context.getString(R.string.find_connected_on, it) }
    val now = System.currentTimeMillis()
    val at = seen.atMs.toLong().coerceAtMost(now)
    val ago = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    return when {
        ssid != null -> context.getString(R.string.find_last_seen_on, ago, ssid)
        seen.route == TandemRoute.LAN -> context.getString(R.string.find_last_seen_lan, ago)
        seen.route == TandemRoute.TAILNET -> context.getString(R.string.find_last_seen_tailnet, ago)
        else -> context.getString(R.string.find_last_seen, ago)
    }
}
