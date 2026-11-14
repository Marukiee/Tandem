package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** Shows the question of [nl.markmaaktmedia.tandem.engine.SpaceAsker] (P5). Put it once on every screen that sends files. */
@Composable
fun SpaceQuestion() {
    val asker = LocalContext.current.graph.host.spaceAsker
    // Only while the screen is in sight: a question on a screen nobody sees would hold the send for good.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(asker, lifecycle) {
        var attached = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START && !attached) { attached = true; asker.attach() }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && attached) { attached = false; asker.detach() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (attached) asker.detach()
        }
    }
    val question by asker.pending.collectAsState()
    val asked = question ?: return
    TandemDialog(
        title = stringResource(R.string.space_title, asked.devices.joinToString(", ")),
        onDismiss = { asker.reply(asked, false) },
        icon = TandemIcons.Error,
        body = stringResource(R.string.space_body),
        showClose = false,
        actions = {
            SecondaryPillButton(stringResource(R.string.action_cancel), { asker.reply(asked, false) })
            PrimaryPillButton(stringResource(R.string.space_send_anyway), { asker.reply(asked, true) })
        },
    )
}
