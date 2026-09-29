package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * One thing Tandem may ask for: what it is, why, and whether it has it. Used in the
 * first-run flow and on the Access page, so the wording is the same in both.
 */
@Composable
fun PermissionCard(
    icon: Painter,
    title: String,
    why: String,
    granted: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    testLabel: String? = null,
    onTest: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().clip(SquircleShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RowIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedContent(
                targetState = granted,
                transitionSpec = { (fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), 0.6f)) togetherWith (fadeOut(TandemMotion.fadeSpec()) + scaleOut()) },
                label = "granted",
            ) { on ->
                if (on) {
                    Icon(TandemIcons.CheckCircleFilled, stringResource(R.string.access_allowed), tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(28.dp))
                } else {
                    PrimaryPillButton(stringResource(R.string.action_allow), onGrant)
                }
            }
        }
        if (granted && onTest != null && testLabel != null) {
            SecondaryPillButton(testLabel, onTest)
        }
    }
}
