package nl.markmaaktmedia.tandem.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.groupedShape

/** Nothing yet, enough to work but not everything, or all of it. */
enum class PermissionLevel { Off, Partly, On }

/** A follow-up under a card: what is wrong and what the person can do about it. */
class PermissionNote(val text: String, val actions: List<PermissionNoteAction>)

class PermissionNoteAction(val label: String, val onClick: () -> Unit, val primary: Boolean = false)

/**
 * Live answers to "does Tandem have this yet", for every card on a screen.
 *
 * Permissions change outside the app, in a system dialog or a settings screen, so the
 * answers are re-read whenever the screen comes back to the front and whenever a
 * request returns. Each getter reads [version] inside the composition that asks for
 * it, which is what makes a card move to its new state the moment the person is back
 * instead of the next time something else happens to recompose it.
 */
@Stable
class PermissionStatus internal constructor(private val context: Context) {
    private var version by mutableIntStateOf(0)

    // Kept across launches: Android will not say again that it stopped asking, and the
    // card has to keep pointing at app info after the app is closed and opened again.
    private val store = context.getSharedPreferences("permission_state", Context.MODE_PRIVATE)

    // Permissions Android has stopped showing a dialog for. Only known after a denial.
    private val blocked = mutableStateMapOf<String, Boolean>().also { map ->
        store.getStringSet(BlockedKey, emptySet()).orEmpty().forEach { map[it] = true }
    }

    /** True while a system dialog is up or one is about to follow, so a card does not say "denied" mid-way. */
    var asking by mutableStateOf(false)
        internal set

    fun refresh() {
        version++
    }

    private inline fun <T> read(value: () -> T): T {
        @Suppress("UNUSED_EXPRESSION") version
        return value()
    }

    val notifications get() = read { Permissions.notifications(context) }
    val battery get() = read { Permissions.batteryUnrestricted(context) }
    val photos get() = read { Permissions.photos(context) }
    val camera get() = read { Permissions.camera(context) }
    val notificationAccess get() = read { Permissions.notificationAccess(context) }
    val bluetooth get() = read { Permissions.bluetooth(context) }
    val installApps get() = read { Permissions.installApps(context) }

    /** On means everything, Partly means calls work but an extra is still missing. */
    val phone: PermissionLevel
        get() = read {
            when {
                !Permissions.phone(context) -> PermissionLevel.Off
                Permissions.phoneOptionalMissing(context).isEmpty() -> PermissionLevel.On
                else -> PermissionLevel.Partly
            }
        }

    /** The extras that are still off, as permission names. */
    val phoneMissing: List<String> get() = read { Permissions.phoneOptionalMissing(context) }

    /** True when at least one of these has been turned down for good. */
    fun isBlocked(permissions: Collection<String>) = permissions.any { blocked[it] == true }

    fun isBlocked(permission: String) = blocked[permission] == true

    internal fun record(results: Map<String, Boolean>, activity: Activity?) {
        results.forEach { (permission, granted) ->
            if (granted) {
                blocked.remove(permission)
            } else if (activity != null) {
                // Straight after a request, "no rationale" means the dialog will not come back.
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                    blocked.remove(permission)
                } else {
                    blocked[permission] = true
                }
            }
        }
        store.edit().putStringSet(BlockedKey, blocked.filterValues { it }.keys.toSet()).apply()
        version++
    }

    private companion object {
        const val BlockedKey = "blocked"
    }
}

@Composable
fun rememberPermissionStatus(): PermissionStatus {
    val context = LocalContext.current
    val status = remember { PermissionStatus(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status.refresh() }
    return status
}

/** What each Allow button does. A permission Android will no longer ask about goes to app info instead. */
@Stable
class PermissionRequests internal constructor(
    private val context: Context,
    private val status: PermissionStatus,
) {
    internal var launch: (Array<String>) -> Unit = {}
    private var afterResult: (() -> Unit)? = null

    internal fun onResult(results: Map<String, Boolean>) {
        status.asking = false
        status.record(results, context.findActivity())
        val next = afterResult
        afterResult = null
        next?.invoke()
    }

    private fun ask(permissions: List<String>, then: (() -> Unit)? = null) {
        val missing = permissions.filterNot { Permissions.isGranted(context, it) }
        if (missing.isEmpty()) {
            then?.invoke()
            return
        }
        afterResult = then
        status.asking = true
        launch(missing.toTypedArray())
    }

    private fun askOrOpenSettings(permissions: List<String>) {
        if (status.isBlocked(permissions)) Permissions.openAppSettings(context) else ask(permissions)
    }

    fun notifications() {
        // Before Android 13 there is no dialog to show, only the notification settings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || status.isBlocked(Manifest.permission.POST_NOTIFICATIONS)) {
            openNotificationSettings()
        } else {
            ask(listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    fun photos() = askOrOpenSettings(listOf(Manifest.permission.READ_MEDIA_IMAGES))

    fun camera() = askOrOpenSettings(listOf(Manifest.permission.CAMERA))

    fun bluetooth() = askOrOpenSettings(Permissions.bluetoothPermissions.toList())

    /** The basics first, then the extras, so a refusal of an extra never hides that calls already work. */
    fun phone() {
        val essential = Permissions.phoneEssential.filterNot { Permissions.isGranted(context, it) }
        when {
            // Restricted permissions never show a dialog on a sideloaded app. When asking changes
            // nothing, the place to fix it is the app info screen, so go there instead of
            // leaving a button that only animates.
            essential.isEmpty() -> ask(Permissions.phoneOptional.toList()) { if (Permissions.phoneOptionalMissing(context).isNotEmpty()) openAppInfo() }
            status.isBlocked(essential) -> Permissions.openAppSettings(context)
            else -> ask(essential) { if (Permissions.phone(context)) phoneExtras() }
        }
    }

    /** Asked even when a dialog was refused before: after "Allow restricted settings" it can work. */
    fun phoneExtras() = ask(Permissions.phoneOptional.toList())

    fun openAppInfo() = Permissions.openAppSettings(context)

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Permissions.openAppSettings(context) }
    }
}

@Composable
fun rememberPermissionRequests(status: PermissionStatus): PermissionRequests {
    val context = LocalContext.current
    val requests = remember(status) { PermissionRequests(context, status) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requests.onResult(it) }
    SideEffect { requests.launch = { launcher.launch(it) } }
    return requests
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** For a permission Android has stopped asking about: say so, and go where it can be turned on. */
@Composable
fun blockedNote(blocked: Boolean, requests: PermissionRequests): PermissionNote? {
    if (!blocked) return null
    return PermissionNote(
        stringResource(R.string.perm_blocked),
        listOf(PermissionNoteAction(stringResource(R.string.perm_open_app_info), requests::openAppInfo, primary = true)),
    )
}

/**
 * What to tell the person under the phone card. Calls working is good news and is said
 * first, then which extras are off and, for the two Android locks on sideloaded apps,
 * where the lock is opened.
 */
@Composable
fun phoneNote(status: PermissionStatus, requests: PermissionRequests): PermissionNote? {
    if (status.asking) return null
    when (status.phone) {
        PermissionLevel.On -> return null
        PermissionLevel.Off -> return blockedNote(status.isBlocked(Permissions.phoneEssential.toList()), requests)
        PermissionLevel.Partly -> Unit
    }
    val missing = status.phoneMissing
    val names = missing.map {
        when (it) {
            Manifest.permission.READ_CALL_LOG -> stringResource(R.string.perm_phone_opt_calllog)
            Manifest.permission.SEND_SMS -> stringResource(R.string.perm_phone_opt_sms)
            else -> stringResource(R.string.perm_phone_opt_dial)
        }
    }
    // Only once Android has refused without a dialog: a plain "no" needs no lecture.
    val restricted = missing.any { it in Permissions.phoneRestricted && status.isBlocked(it) }
    val text = buildString {
        append(stringResource(R.string.perm_phone_missing, names.joinToString(", ")))
        if (restricted) append(' ').append(stringResource(R.string.perm_phone_restricted))
    }
    return PermissionNote(
        text,
        listOf(
            PermissionNoteAction(stringResource(R.string.perm_open_app_info), requests::openAppInfo, primary = restricted),
            PermissionNoteAction(stringResource(R.string.perm_try_again), requests::phoneExtras),
        ),
    )
}

/**
 * One thing Tandem may ask for: what it is, why, and whether it has it. Used in the
 * first-run flow and on the Access page, so the wording is the same in both.
 *
 * The answer sits in a slot of fixed size at the end of the title row. Allow, Partly
 * and the checkmark are the same pill changing colour and content, never three
 * different widgets, so nothing beside it moves when a permission comes through.
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
    testResult: String? = null,
    /** With [granted]: the essentials work but an extra is still off. */
    partly: Boolean = false,
    note: PermissionNote? = null,
    /** Position in a slab of cards, for the corner treatment. */
    index: Int = 0,
    total: Int = 1,
) {
    val level = when {
        !granted -> PermissionLevel.Off
        partly -> PermissionLevel.Partly
        else -> PermissionLevel.On
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(groupedShape(index, total))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    ) {
        // The pill is a column of its own, so the small print wraps beside it instead of
        // running on underneath it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RowIcon(icon)
            // Title and small print are one block, level with the top of the icon. Centring the
            // title on the icon pushed the two lines apart for no reason.
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) { PermissionPill(level, onGrant) }
        }
        AnimatedVisibility(
            visible = note != null,
            enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
            exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
        ) {
            // Kept while it animates out, so the box does not empty itself before it closes.
            val shown = remember { mutableStateOf(note) }
            if (note != null) shown.value = note
            shown.value?.let { NoteBox(it, Modifier.padding(top = 12.dp)) }
        }
        AnimatedVisibility(
            visible = granted && onTest != null && testLabel != null,
            enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
            exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
        ) {
            Column(Modifier.padding(start = IconColumn, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (testLabel != null && onTest != null) SmallPill(testLabel, onTest, primary = false, icon = TandemIcons.Send)
                AnimatedVisibility(
                    visible = testResult != null,
                    enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
                    exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
                ) {
                    val last = remember { mutableStateOf(testResult) }
                    if (testResult != null) last.value = testResult
                    Text(
                        last.value.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Lifts an item into place a moment after the one before it.
 *
 * Position rides a spring and opacity a tween, so the item arrives with a little life
 * and never flashes. The stagger is what makes a list read as one thing being laid
 * down instead of everything appearing at once.
 */
fun Modifier.staggeredEntry(index: Int, startDelayMillis: Int = 0, distance: Dp = 28.dp): Modifier = composed {
    val lift = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(startDelayMillis + index * 55L)
        launch { alpha.animateTo(1f, TandemMotion.fadeSpec()) }
        lift.animateTo(1f, TandemMotion.spatial())
    }
    graphicsLayer {
        translationY = (1f - lift.value) * distance.toPx()
        this.alpha = alpha.value
    }
}

/** Icon width plus the gap after it, so the small print lines up with the title. */
private val IconColumn = 54.dp

private val PillWidth = 92.dp
private val PillHeight = 40.dp

/**
 * The Allow pill, and what it turns into.
 *
 * Colour is a tween and the checkmark arrives on a spring, the same split as the rest of
 * the app. The pill keeps its size in every state on purpose: the title next to it is
 * laid out once and never has to make room.
 */
@Composable
private fun PermissionPill(level: PermissionLevel, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val online = LocalTandemExtraColors.current.online
    val container by animateColorAsState(
        when (level) {
            PermissionLevel.Off -> scheme.primary
            PermissionLevel.Partly -> scheme.tertiaryContainer
            PermissionLevel.On -> online
        },
        TandemMotion.colourSpec(), label = "pillContainer",
    )
    val content by animateColorAsState(
        when (level) {
            PermissionLevel.Off -> scheme.onPrimary
            PermissionLevel.Partly -> scheme.onTertiaryContainer
            PermissionLevel.On -> online.shade(0.2f)
        },
        TandemMotion.colourSpec(), label = "pillContent",
    )

    // A small pop when a permission comes through while looking at it, not on every visit.
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(level) }
    LaunchedEffect(level) {
        if (level == PermissionLevel.On && previous != PermissionLevel.On) {
            pop.animateTo(1.1f, spring(dampingRatio = 0.6f, stiffness = 900f))
            pop.animateTo(1f, TandemMotion.springy())
        }
        previous = level
    }

    Box(
        Modifier
            .size(PillWidth, PillHeight)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .clip(PillShape)
            .background(container)
            .bouncyClickable(enabled = level != PermissionLevel.On, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = level,
            transitionSpec = {
                (fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), 0.5f)) togetherWith
                    (fadeOut(TandemMotion.fadeSpec()) + scaleOut(TandemMotion.springy(), 0.7f))
            },
            contentAlignment = Alignment.Center,
            label = "pillContent",
        ) { shown ->
            when (shown) {
                PermissionLevel.On -> Icon(TandemIcons.Check, stringResource(R.string.access_allowed), tint = content, modifier = Modifier.size(22.dp))
                PermissionLevel.Partly -> PillLabel(stringResource(R.string.perm_partly), content)
                PermissionLevel.Off -> PillLabel(stringResource(R.string.action_allow), content)
            }
        }
    }
}

@Composable
private fun PillLabel(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
}

/** A darker take on a theme colour, for a mark that sits on top of it. */
private fun Color.shade(factor: Float) = Color(red * factor, green * factor, blue * factor, alpha)

@Composable
private fun NoteBox(note: PermissionNote, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(SquircleShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TandemIcons.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Text(note.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            note.actions.forEach { SmallPill(it.label, it.onClick, it.primary) }
        }
    }
}

/** The compact button used inside a card, where the full-size pill would outweigh the card. */
@Composable
private fun SmallPill(label: String, onClick: () -> Unit, primary: Boolean, icon: Painter? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(38.dp)
            .clip(PillShape)
            .background(if (primary) scheme.primary else scheme.surfaceContainerHighest)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (primary) scheme.onPrimary else scheme.onSurface, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) scheme.onPrimary else scheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}
