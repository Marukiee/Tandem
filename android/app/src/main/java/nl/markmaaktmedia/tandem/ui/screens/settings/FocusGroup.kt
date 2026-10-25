package nl.markmaaktmedia.tandem.ui.screens.settings

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.FocusMode
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.DropdownRow
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/**
 * Focus the same on every device. Without access to Do Not Disturb the switch is grey and off, not on as if it worked; a tap on it
 * or on what its mode lets through says why, with the way to allow it.
 */
@Composable
internal fun FocusGroup() {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    val share by prefs.focusShare.collectAsState(initial = true)
    val followOnly by prefs.focusFollowOnly.collectAsState(initial = false)
    var explain by remember { mutableStateOf(false) }
    // Read again on return: the access is given in the settings of Android.
    var allowed by remember { mutableStateOf(FocusMode.canUse(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = FocusMode.canUse(context) }

    SectionHeader(stringResource(R.string.focus_section))
    SettingsGroup {
        SettingsTarget(FocusKeys.FocusShare, 0, 3) {
            SwitchRow(
                0, 3, TandemIcons.Dnd, stringResource(R.string.focus_share), stringResource(R.string.focus_share_sub), share,
                { on -> scope.launch { prefs.setFocusShare(on) } },
                blocked = if (allowed) null else stringResource(R.string.focus_needs_access),
                onBlocked = { explain = true },
            )
        }
        DropdownRow(
            1, 3, TandemIcons.Sync, stringResource(R.string.focus_direction), stringResource(R.string.focus_direction_sub),
            stringResource(if (followOnly) R.string.focus_follow_only else R.string.focus_both),
            listOf(false to stringResource(R.string.focus_both), true to stringResource(R.string.focus_follow_only)),
            { scope.launch { prefs.setFocusFollowOnly(it) } },
        )
        ActionRow(
            2, 3, TandemIcons.Settings, stringResource(R.string.focus_mode_settings), stringResource(R.string.focus_mode_settings_sub),
            { if (FocusMode.canUse(context)) FocusMode.openSettings(context) else explain = true },
        )
    }

    if (explain) {
        TandemDialog(
            title = stringResource(R.string.focus_access_dialog),
            onDismiss = { explain = false },
            icon = TandemIcons.Dnd,
            body = stringResource(R.string.focus_access_body),
            actions = {
                SecondaryPillButton(stringResource(R.string.focus_later), { explain = false })
                PrimaryPillButton(stringResource(R.string.action_allow), {
                    explain = false
                    runCatching { context.startActivity(FocusMode.accessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            },
        )
    }
}
