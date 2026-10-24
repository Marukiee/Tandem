package nl.markmaaktmedia.tandem.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import uniffi.tandem_core.TandemDevice

/**
 * On the settings of a computer that shows messages: whether it may read and send the text messages of this phone. On unless
 * the person turns it off; turning it on asks for Android's permission when that is still missing, and not before.
 */
@Composable
fun MessagesDeviceSection(device: TandemDevice) {
    val context = LocalContext.current
    if (SmsBridge.CAP_VIEW !in device.caps || !SmsAccess.available(context)) return
    val engine = context.graph.host.engine
    var allowed by remember(device.id) { mutableStateOf(engine?.messagesAllowed(device.id) ?: false) }
    LifecycleResumeEffect(device.id) {
        allowed = engine?.messagesAllowed(device.id) ?: false
        onPauseOrDispose {}
    }
    SettingsGroup {
        SwitchRow(
            0, 1, painterResource(R.drawable.sym_sms), stringResource(R.string.setting_messages), stringResource(R.string.setting_messages_sub), allowed,
            { on ->
                runCatching { engine?.setMessagesAllowed(device.id, on) }
                allowed = on
                if (on && !SmsAccess.granted(context)) context.startActivity(MessagesAccessActivity.intent(context, null))
            },
        )
    }
}
