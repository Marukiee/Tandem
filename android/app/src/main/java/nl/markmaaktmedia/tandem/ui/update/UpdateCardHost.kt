package nl.markmaaktmedia.tandem.ui.update

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.markupdate.MarkUpdate
import nl.markmaaktmedia.markupdate.UpdateState
import nl.markmaaktmedia.markupdate.ui.UpdateCard
import nl.markmaaktmedia.markupdate.ui.UpdateCardStyle
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors

/** The distance above and under the card. The pages sit under it without the margin at its foot (see `AppRoot`). */
val UpdateBannerMargin = 8.dp

/**
 * The update card of the shared markupdate module, over every screen. The margins live here and grow and shrink with the card, so the
 * height starts at nothing; [shown] is false where the card has no place (the screen of a computer).
 */
@Composable
fun TandemUpdateCard(modifier: Modifier = Modifier, shown: Boolean = true) {
    val updater = MarkUpdate.get()
    val state by updater.state.collectAsState()
    val dismissed by updater.dismissed.collectAsState()
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.ReadyToInstall -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.AwaitingConfirmation -> s.release
        else -> null
    }
    val visible = shown && (state is UpdateState.Failed || (release != null && release.tag != dismissed))
    val margin: Dp by animateDpAsState(if (visible) UpdateBannerMargin else 0.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "updateMargin")
    if (!shown) return
    UpdateCard(
        modifier.padding(start = 16.dp, end = 16.dp, top = margin, bottom = margin),
        style = UpdateCardStyle(shape = CardSquircle, successColor = LocalTandemExtraColors.current.online),
    )
}
