package nl.markmaaktmedia.tandem.wifi

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice

/**
 * On the page of a computer: take over its Wi-Fi when the phone is not on it, and the other way round. Android keeps the
 * password of a network from apps, so that one can never work; it looks like the rest and says so when it is pressed.
 */
@Composable
fun WifiDeviceSection(device: TandemDevice) {
    val context = LocalContext.current
    val share = remember { WifiShare.get(context) }
    val networks by share.networks.collectAsState()
    val phoneSsid by share.phoneSsid.collectAsState()
    val phoneOnWifi by share.phoneOnWifi.collectAsState()
    var explain by remember { mutableStateOf(false) }

    val network = networks[device.id]
    val take = network?.takeIf { (device.online || device.ble) && WifiOfferPolicy.joinable(it) && it.ssid != phoneSsid }
    val give = phoneOnWifi && "wifi.join" in device.caps && (phoneSsid == null || network?.ssid != phoneSsid)
    val rows = (if (take != null) 1 else 0) + (if (give) 1 else 0)
    if (rows == 0) return

    SectionHeader(stringResource(R.string.wifi_section), top = 12.dp, bottom = 0.dp)
    SettingsGroup {
        var row = 0
        if (take != null) {
            ActionRow(
                row++, rows, TandemIcons.Wifi, stringResource(R.string.wifi_use_row, device.name), take.ssid,
                { context.startActivity(WifiJoinActivity.asking(context, device.id)) },
            )
        }
        if (give) {
            ActionRow(
                row++, rows, TandemIcons.Wifi, stringResource(R.string.wifi_give_row, device.name), phoneSsid,
                { explain = true },
            )
        }
    }
    if (explain) {
        TandemDialog(
            title = stringResource(R.string.wifi_give_title),
            icon = TandemIcons.Wifi,
            body = stringResource(R.string.wifi_give_body, device.name),
            onDismiss = { explain = false },
            closeLabel = stringResource(R.string.wifi_close),
            actions = { PrimaryPillButton(stringResource(R.string.wifi_close), { explain = false }) },
        )
    }
}

/** What the phone offers now, for [wifiOfferItem]. */
@Composable
fun rememberWifiOffer(): State<WifiShare.Offer?> {
    val context = LocalContext.current
    return remember { WifiShare.get(context) }.offer.collectAsState()
}

/** The offer on the list of devices: the same question as the notification, for when the app is open. */
fun LazyListScope.wifiOfferItem(offer: State<WifiShare.Offer?>) {
    val shown = offer.value ?: return
    item(key = "wifi-offer") { WifiOfferCard(shown, Modifier.animateItem()) }
}

@Composable
private fun WifiOfferCard(shown: WifiShare.Offer, modifier: Modifier) {
    val context = LocalContext.current
    val share = remember { WifiShare.get(context) }
    val name = context.graph.host.device(shown.computer)?.name ?: stringResource(R.string.wifi_computer_fallback)
    Column(
        modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RowIcon(TandemIcons.Wifi, tint = MaterialTheme.colorScheme.onPrimary, container = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.wifi_offer_title, name), style = MaterialTheme.typography.titleMedium)
                Text(shown.network.ssid, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SecondaryPillButton(stringResource(R.string.wifi_not_now), { share.decline(shown.network.ssid) })
            PrimaryPillButton(stringResource(R.string.wifi_use), {
                if (context.graph.host.device(shown.computer).let { it != null && (it.online || it.ble) }) {
                    context.startActivity(WifiJoinActivity.asking(context, shown.computer))
                } else {
                    Toast.makeText(context, context.getString(R.string.wifi_unreachable, name), Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}
