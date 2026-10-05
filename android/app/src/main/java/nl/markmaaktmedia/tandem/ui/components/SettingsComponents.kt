package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/** A round tonal badge holding an icon, used at the start of every settings row. */
@Composable
fun RowIcon(icon: Painter, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSecondaryContainer, container: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Box(modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier = Modifier, danger: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row with a switch. The whole row is the target, so the thumb is not a tiny bullseye. */
@Composable
fun SwitchRow(
    index: Int,
    total: Int,
    icon: Painter,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    /**
     * Why the switch cannot be used yet, when the permission it needs is missing. The row goes grey, says it instead of its
     * description, and a tap on it calls [onBlocked] (which takes the person to where it can be allowed).
     */
    blocked: String? = null,
    onBlocked: (() -> Unit)? = null,
) {
    val usable = enabled && blocked == null
    GroupedRow(index, total, onClick = if (blocked != null) onBlocked else if (enabled) ({ onChange(!checked) }) else null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).then(if (blocked != null) Modifier.alpha(0.6f) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(icon)
            RowText(title, blocked ?: subtitle, Modifier.weight(1f))
            Switch(
                checked = checked && blocked == null,
                onCheckedChange = null,
                enabled = usable,
                colors = SwitchDefaults.colors(),
            )
        }
    }
}

/** A row that does something when tapped, with a chevron or a custom trailing item. */
@Composable
fun ActionRow(
    index: Int,
    total: Int,
    icon: Painter,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    danger: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    GroupedRow(index, total, modifier = modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RowIcon(
                icon,
                tint = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                container = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            )
            RowText(title, subtitle, Modifier.weight(1f), danger)
            if (trailing != null) trailing() else Icon(TandemIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** A row that shows a value and does nothing. */
@Composable
fun InfoRow(index: Int, total: Int, icon: Painter, title: String, value: String) {
    GroupedRow(index, total) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RowIcon(icon)
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row holding arbitrary content, like a segmented control under a title. */
@Composable
fun ContentRow(index: Int, total: Int, icon: Painter, title: String, content: @Composable () -> Unit) {
    GroupedRow(index, total) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RowIcon(icon)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}

/** The dot next to a permission: green when granted, dim when not. */
@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        if (on) nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors.current.online else MaterialTheme.colorScheme.outline,
        TandemMotion.colourSpec(), label = "statusDot",
    )
    Box(modifier.size(10.dp).background(color, CircleShape))
}
