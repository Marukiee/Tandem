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
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import uniffi.tandem_core.TandemDevice

/** Whether the messages row is shown: a computer that shows messages, and a phone that has any to give. */
@Composable
fun messagesDeviceShown(device: TandemDevice): Boolean =
    SmsBridge.CAP_VIEW in device.caps && SmsAccess.available(LocalContext.current)

/**
 * A row in the preferences of a computer that shows messages: whether it may read and send the text messages of this phone. On unless
 * the person turns it off; turning it on asks for Android's permission when that is still missing, and not before. The caller
 * checks [messagesDeviceShown] and gives the place in the shared group.
 */
@Composable
fun MessagesDeviceRow(device: TandemDevice, index: Int, count: Int) {
    val context = LocalContext.current
    val engine = context.graph.host.engine
    var allowed by remember(device.id) { mutableStateOf(engine?.messagesAllowed(device.id) ?: false) }
    LifecycleResumeEffect(device.id) {
        allowed = engine?.messagesAllowed(device.id) ?: false
        onPauseOrDispose {}
    }
    SwitchRow(
        index, count, painterResource(R.drawable.sym_sms), stringResource(R.string.setting_messages), stringResource(R.string.setting_messages_sub), allowed,
        { on ->
            runCatching { engine?.setMessagesAllowed(device.id, on) }
            allowed = on
            if (on && !SmsAccess.granted(context)) context.startActivity(MessagesAccessActivity.intent(context, null))
        },
    )
}
