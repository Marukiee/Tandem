package nl.markmaaktmedia.tandem.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.engine.TandemService
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance

/**
 * Where Messages is let in, when someone wants to use it: a computer asked (from its Messages page), or the person turned it on
 * for a computer here. First whether that computer may, then Android's permission, and when Android will not ask (an app that
 * did not come from a store) how to get past that.
 */
class MessagesAccessActivity : ComponentActivity() {
    private enum class Step { Allow, Permission, Blocked }

    private var device: String? = null
    private var step by mutableStateOf(Step.Permission)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SmsAccess.granted(this)) {
            done()
        } else {
            // No dialog came back, or it was turned down for good: Android will not ask again, so the way is through App info.
            val again = SmsAccess.permissions.any { shouldShowRequestPermissionRationale(it) }
            step = if (again) Step.Permission else Step.Blocked
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TandemService.start(this)
        device = intent.getStringExtra(EXTRA_DEVICE)
        val allowed = device?.let { graph.host.engine?.messagesAllowed(it) } ?: true
        step = if (!allowed) Step.Allow else Step.Permission
        if (allowed && SmsAccess.granted(this)) return done()

        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance, applySystemBarStyle = false) {
                val name = device?.let { graph.host.device(it)?.name } ?: stringResource(R.string.feature_device_fallback)
                val icon = painterResource(R.drawable.sym_sms)
                when (step) {
                    Step.Allow -> TandemDialog(
                        title = stringResource(R.string.messages_allow_title, name),
                        icon = icon,
                        body = stringResource(R.string.messages_allow_body, name),
                        onDismiss = { finish() },
                        closeLabel = stringResource(R.string.action_cancel),
                        actions = {
                            Buttons(stringResource(R.string.action_allow), { allow() }, stringResource(R.string.messages_not_now))
                        },
                    )
                    Step.Permission -> TandemDialog(
                        title = stringResource(R.string.messages_permission_title),
                        icon = icon,
                        body = stringResource(R.string.messages_permission_body),
                        onDismiss = { finish() },
                        closeLabel = stringResource(R.string.action_cancel),
                        actions = {
                            Buttons(stringResource(R.string.action_allow), { ask.launch(SmsAccess.permissions) }, stringResource(R.string.messages_not_now))
                        },
                    )
                    Step.Blocked -> TandemDialog(
                        title = stringResource(R.string.messages_blocked_title),
                        icon = icon,
                        body = stringResource(R.string.messages_blocked_body),
                        onDismiss = { finish() },
                        closeLabel = stringResource(R.string.action_cancel),
                        actions = {
                            Buttons(stringResource(R.string.perm_open_app_info), { Permissions.openAppSettings(this) }, stringResource(R.string.perm_try_again)) {
                                ask.launch(SmsAccess.permissions)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from App info with the restricted setting allowed and the permission given there.
        if (step == Step.Blocked && SmsAccess.granted(this)) done()
    }

    private fun allow() {
        device?.let { runCatching { graph.host.engine?.setMessagesAllowed(it, true) } }
        graph.host.refreshDevices()
        if (SmsAccess.granted(this)) done() else ask.launch(SmsAccess.permissions)
    }

    private fun done() {
        val name = device?.let { graph.host.device(it)?.name }
        Toast.makeText(this, if (name != null) getString(R.string.messages_ready_on, name) else getString(R.string.messages_ready), Toast.LENGTH_SHORT).show()
        finish()
    }

    @androidx.compose.runtime.Composable
    private fun Buttons(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit = { finish() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryPillButton(primary, onPrimary, Modifier.fillMaxWidth())
            SecondaryPillButton(secondary, onSecondary, Modifier.fillMaxWidth())
        }
    }

    companion object {
        private const val EXTRA_DEVICE = "device"

        /** For [device], or for the permission alone when it is null. */
        fun intent(context: Context, device: String?): Intent =
            Intent(context, MessagesAccessActivity::class.java).apply { device?.let { putExtra(EXTRA_DEVICE, it) } }
    }
}
