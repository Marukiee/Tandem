package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape

/**
 * The app's own dialog.
 *
 * Material's AlertDialog paints itself on `surfaceContainerHigh` with its own
 * elevation tint, which on the pure black theme comes out lighter than everything
 * around it and reads as a light-mode panel dropped into a dark app. This one takes
 * its colours from the same roles as the rest of the screens, so it belongs to the
 * theme rather than sitting on top of it.
 *
 * It also arrives properly: a spring-scaled entry rather than the platform fade, in
 * the same motion language as everything else that appears.
 */
@Composable
fun TandemDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    body: String? = null,
    dismissOnOutsideTap: Boolean = true,
    actions: @Composable () -> Unit = {},
    /** Spoken label for the corner close button. */
    closeLabel: String = "",
    content: (@Composable () -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.88f,
        animationSpec = TandemMotion.springy(),
        label = "dialogScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = TandemMotion.fadeSpec(),
        label = "dialogAlpha",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnOutsideTap,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(SquircleShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            /*
             * Closing lives in the corner, not in the button row.
             *
             * It is the one action every dialog has, so giving it a pill next to the
             * ones that actually do something made the row longer and the real choices
             * harder to pick out. In the corner it is always in the same place and
             * never competes for width.
             */
            // Floated rather than laid out, so the padding stays symmetric and the
            // title lands in the middle of the dialog instead of a little to the left.
            Box(modifier = Modifier.fillMaxWidth()) {
                TandemIconButton(
                    icon = TandemIcons.Close,
                    contentDescription = closeLabel,
                    onClick = onDismiss,
                    size = 34,
                    iconSize = 17,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-8).dp),
                )
            }

            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(PillShape)
                        .background(iconTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp),
                    )
                }
                VSpace(14)
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            if (body != null) {
                VSpace(10)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }

            if (content != null) {
                VSpace(16)
                content()
            }

            VSpace(20)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions()
            }
        }
    }
}

/**
 * The dialog shown when something failed.
 *
 * Always has a copy button. A failure message is the one piece of text in the app
 * that someone needs to get off the phone and into a report, and selecting a
 * paragraph out of a dialog with a fingertip is not a way to do that.
 */
@Composable
fun MarkErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    copyLabel: String,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    TandemDialog(
        title = title,
        body = message,
        icon = TandemIcons.Error,
        iconTint = MaterialTheme.colorScheme.error,
        onDismiss = onDismiss,
        closeLabel = confirmLabel,
        actions = {
            SecondaryPillButton(
                label = copyLabel,
                icon = TandemIcons.Copy,
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(message))
                },
            )
            if (retryLabel != null && onRetry != null) {
                PrimaryPillButton(
                    label = retryLabel,
                    icon = TandemIcons.Refresh,
                    onClick = { onDismiss(); onRetry() },
                )
            }
        },
    )
}

/** Confirmation for something that cannot be undone. */
@Composable
fun MarkConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    TandemDialog(
        title = title,
        body = body,
        icon = if (destructive) TandemIcons.Delete else TandemIcons.Info,
        iconTint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        onDismiss = onDismiss,
        closeLabel = cancelLabel,
        actions = {
            SecondaryPillButton(label = cancelLabel, onClick = onDismiss)
            PrimaryPillButton(
                label = confirmLabel,
                onClick = { onDismiss(); onConfirm() },
                container = if (destructive) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primary,
                content = if (destructive) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimary,
            )
        },
    )
}
