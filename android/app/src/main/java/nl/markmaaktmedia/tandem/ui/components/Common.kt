package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.ui.theme.groupedShape

/** The small round count on a navigation icon. */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.error,
    contentColor: Color = MaterialTheme.colorScheme.onError,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(containerColor)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** A pill label: category, state, count. */
@Composable
fun PillBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

/**
 * A round icon button.
 *
 * The 44dp box is deliberate and used everywhere: it is the minimum comfortable
 * target, and one size for every icon button is what keeps a row of them optically
 * aligned without anyone nudging padding by a pixel.
 */
@Composable
fun TandemIconButton(
    icon: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = Color.Transparent,
    size: Int = 44,
    iconSize: Int = 21,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(PillShape)
            .background(background)
            .bouncyClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/**
 * The question mark next to a setting that needs a sentence of explanation.
 *
 * It opens a small panel rather than expanding in place. Expanding pushed every row
 * below it down the screen, so reading one explanation moved everything the user was
 * looking at, and closing it moved it all back. A panel leaves the page alone.
 *
 * Settings that explain themselves do not get one: a page where every row has a help
 * button is a page that failed to name its rows.
 */
@Composable
fun HelpTip(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    Box(
        // Required, not preferred: inside a Row that is short of space a plain size
        // gets overruled and the circle comes out as an oval.
        modifier = modifier
            .requiredSize(22.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            // Outlined rather than filled, so it matches the switches it sits next to
            // and whatever is behind the bar shows through instead of a solid disc.
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .bouncyClickable(onClickLabel = text) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = TandemIcons.Help,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
    }

    if (open) {
        TandemDialog(
            title = title ?: stringResource(nl.markmaaktmedia.tandem.R.string.help_title),
            body = text,
            icon = TandemIcons.Idea,
            onDismiss = { open = false },
            actions = {
                PrimaryPillButton(
                    label = stringResource(nl.markmaaktmedia.tandem.R.string.generic_ok),
                    onClick = { open = false },
                )
            },
        )
    }
}

/** The label above a group of settings rows. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    top: androidx.compose.ui.unit.Dp = 12.dp,
    bottom: androidx.compose.ui.unit.Dp = 10.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = top, bottom = bottom),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        trailing?.invoke()
    }
}

/**
 * One block of rows that reads as a single slab.
 *
 * Children are handed their index so each takes the right corner treatment, and the
 * tiny gap between them turns a solid rectangle into a stack of tiles without needing
 * a divider line anywhere.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** The surface every settings row sits on, shaped for its place in the group. */
@Composable
fun GroupedRow(
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = groupedShape(index, total)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(color)

    Column(
        modifier = if (onClick != null) base.bouncyClickable(onClick = onClick) else base,
        content = content,
    )
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                )
            }
        } else {
            PillMark(size = 54.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/**
 * Swipe an item aside to delete it, in either direction. Ported line by line from the route
 * planner in MarkMySteps (its web version was itself ported from this Compose original), so
 * the numbers are the same and so is the feel:
 *
 * - tension: for the first 60dp of travel the row moves 20dp at most, so scrolling a list never
 *   nudges rows sideways;
 * - release: past that it springs (stiffness 200, damping 0.8) up to the finger, with a tick;
 * - free: from there it follows through a stiff critically damped spring (10000, 1). It is a
 *   spring rather than a straight copy of the finger on purpose: the row carries the finger's
 *   velocity, so letting go continues the motion instead of stopping dead;
 * - cancel: letting go short of the threshold (35 percent of the row) is an elastic settle back
 *   (1500, 0.75) that starts from that velocity, which is where the bounce comes from;
 * - commit: past the threshold the row is flung off (110 percent of its width, 260ms) in the
 *   direction it was going while its height collapses in step, and the delete fires at the
 *   start of the fling so the gap closes as the row glides away.
 *
 * The reveal is its own rounded panel that grows out of the edge being uncovered. The icon
 * hugs that edge, the label sits inward of it, and both fade in over the first 56dp.
 *
 * [key] must be stable for the row, not the row's data. Keying the gesture state on a data
 * class meant any unrelated update to the item replaced the state mid swipe, and the row
 * snapped back to zero instead of springing.
 */
@Composable
fun <T> SwipeToDelete(
    item: T,
    key: Any,
    onDelete: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(nl.markmaaktmedia.tandem.R.string.swipe_delete),
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.large,
    background: Color = MaterialTheme.colorScheme.error,
    iconTint: Color = MaterialTheme.colorScheme.onError,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()

    val offset = remember(key) { Animatable(0f) }
    var accumulated by remember(key) { mutableFloatStateOf(0f) }
    var loose by remember(key) { mutableStateOf(false) }
    var armed by remember(key) { mutableStateOf(false) }
    var removed by remember(key) { mutableStateOf(false) }
    var widthPx by remember(key) { mutableIntStateOf(1) }

    val tensionTravel = with(density) { 60.dp.toPx() }
    val tensionMax = with(density) { 20.dp.toPx() }
    val revealFade = with(density) { 56.dp.toPx() }

    // dampingRatio 1 is critically damped, and below that it overshoots.
    fun springTo(target: Float, stiffness: Float, damping: Float) {
        scope.launch { offset.animateTo(target, spring(dampingRatio = damping, stiffness = stiffness)) }
    }

    AnimatedVisibility(
        visible = !removed,
        exit = shrinkVertically(animationSpec = TandemMotion.sizeSpring()) +
            fadeOut(animationSpec = TandemMotion.fadeSpec()),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) },
        ) {
            val x = offset.value
            val shown = kotlin.math.abs(x)
            // Anchored to the edge being uncovered: swiping left uncovers the right edge.
            val toLeft = x < 0f
            if (shown > 1f) {
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = if (toLeft) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(with(density) { shown.toDp() })
                            .clip(shape)
                            .background(background),
                        contentAlignment = if (toLeft) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Row(
                            // Measured at its full width whatever the panel is, and pinned to the
                            // edge being uncovered: the icon at the edge shows first, the label
                            // fills in behind it. Squeezed into the narrow panel instead, the row
                            // lost its icon to the overflow and the label came first.
                            modifier = Modifier
                                .wrapContentWidth(align = if (toLeft) Alignment.End else Alignment.Start, unbounded = true)
                                .padding(horizontal = 18.dp)
                                .graphicsLayer { alpha = (shown / revealFade).coerceIn(0f, 1f) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val icon = @Composable {
                                Icon(painter = TandemIcons.Delete, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                            }
                            val text = @Composable {
                                Text(label, style = MaterialTheme.typography.labelLarge, color = iconTint, maxLines = 1, softWrap = false)
                            }
                            // The icon hugs the swiped edge, so the order flips with the side.
                            if (toLeft) { text(); icon() } else { icon(); text() }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(offset.value.toInt(), 0) }
                    .pointerInput(key, enabled) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = {
                                accumulated = 0f
                                loose = false
                                armed = false
                            },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                accumulated += delta
                                val travelled = kotlin.math.abs(accumulated)
                                val dir = kotlin.math.sign(accumulated)
                                val commitPx = widthPx * CommitFraction

                                if (!loose) {
                                    if (travelled < tensionTravel) {
                                        scope.launch { offset.snapTo(dir * tensionMax * (travelled / tensionTravel)) }
                                        return@detectHorizontalDragGestures
                                    }
                                    // Comes loose: springs up to the finger instead of jumping to it.
                                    loose = true
                                    view.performHapticFeedback(GestureThresholdActivate)
                                    springTo(accumulated, stiffness = 200f, damping = 0.8f)
                                    return@detectHorizontalDragGestures
                                }

                                val nowArmed = travelled > commitPx
                                if (nowArmed != armed) {
                                    armed = nowArmed
                                    view.performHapticFeedback(if (nowArmed) GestureThresholdActivate else GestureThresholdDeactivate)
                                }
                                springTo(accumulated, stiffness = 10_000f, damping = 1f)
                            },
                            onDragEnd = {
                                val commitPx = widthPx * CommitFraction
                                if (loose && kotlin.math.abs(accumulated) > commitPx) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                    removed = true
                                    // Fired now, not after the fling, so the gap closes in step
                                    // with the row gliding away.
                                    onDelete(item)
                                    scope.launch {
                                        offset.animateTo(
                                            kotlin.math.sign(accumulated) * widthPx * 1.1f,
                                            tween(durationMillis = 260, easing = TandemMotion.Standard),
                                        )
                                    }
                                } else {
                                    // Cancelled: an elastic settle back, from the velocity it had.
                                    springTo(0f, stiffness = 1500f, damping = 0.75f)
                                }
                                loose = false
                                armed = false
                                accumulated = 0f
                            },
                            onDragCancel = {
                                springTo(0f, stiffness = 1500f, damping = 0.75f)
                                loose = false
                                armed = false
                                accumulated = 0f
                            },
                        )
                    },
            ) {
                content()
            }
        }
    }
}

/**
 * The system ticks for crossing a gesture threshold, which is exactly what arming and
 * disarming a swipe is. Available from Android 13, and harmless to ask for below it.
 */
private val GestureThresholdActivate =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
    } else {
        android.view.HapticFeedbackConstants.LONG_PRESS
    }

private val GestureThresholdDeactivate =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
    } else {
        android.view.HapticFeedbackConstants.LONG_PRESS
    }

/** How far across the row the gesture has to go before letting go deletes. */
private const val CommitFraction = 0.35f

/** A tappable suggestion under an empty chat. */
@Composable
fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The filled pill used for the one primary action on a screen. */
@Composable
fun PrimaryPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    /** Sits after the label. For a button that moves you forward, this is the one. */
    trailingIcon: Painter? = null,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHigh)
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
        if (trailingIcon != null) {
            Icon(
                painter = trailingIcon,
                contentDescription = null,
                tint = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The quieter sibling, for the action next to the primary one. */
@Composable
fun SecondaryPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    PrimaryPillButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurface,
    )
}

/** A hairline that separates without drawing attention to itself. */
@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

/** Vertical breathing room, so a Column does not need a Spacer spelled out. */
@Composable
fun VSpace(height: Int) = Spacer(Modifier.height(height.dp))
