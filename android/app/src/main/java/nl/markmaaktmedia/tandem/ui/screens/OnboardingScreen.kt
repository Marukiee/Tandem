package nl.markmaaktmedia.tandem.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PermissionCard
import nl.markmaaktmedia.tandem.ui.components.PermissionLevel
import nl.markmaaktmedia.tandem.ui.components.blockedNote
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.components.phoneNote
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionRequests
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus
import nl.markmaaktmedia.tandem.ui.components.staggeredEntry
import nl.markmaaktmedia.tandem.ui.theme.GroupedSpacing
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val PageCount = 3
private const val LastPage = PageCount - 1

/**
 * First run: what this is, what it may ask for (all optional), and pairing.
 * Three pages, and the permission page can be skipped without losing anything
 * that cannot be turned on later from Settings.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, preview: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { PageCount }
    val page = pager.currentPage

    fun goTo(target: Int) {
        scope.launch { pager.animateScrollToPage(target.coerceIn(0, LastPage), animationSpec = TandemMotion.spatial()) }
    }

    // Back walks the pages first, so leaving the app is what a back press on page one does.
    BackHandler(enabled = page > 0) { goTo(page - 1) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            Box(Modifier.fillMaxSize().pageEffect(pager, index)) {
                when (index) {
                    0 -> WelcomePage()
                    1 -> PermissionsPage(preview)
                    else -> PairPage()
                }
            }
        }

        PageDots(pager, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 20.dp))

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = page > 0,
                enter = expandHorizontally(TandemMotion.spatial()) + fadeIn(TandemMotion.fadeSpec()),
                exit = shrinkHorizontally(TandemMotion.spatial()) + fadeOut(TandemMotion.fadeSpec()),
            ) {
                Row {
                    Box(
                        Modifier
                            .size(ButtonHeight)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .bouncyClickable { goTo(page - 1) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TandemIcons.Back, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
            AnimatedVisibility(
                visible = page == LastPage,
                enter = expandHorizontally(TandemMotion.spatial()) + fadeIn(TandemMotion.fadeSpec()),
                exit = shrinkHorizontally(TandemMotion.spatial()) + fadeOut(TandemMotion.fadeSpec()),
            ) {
                Row {
                    OnboardingButton(onClick = onFinished, primary = false) {
                        Text(stringResource(R.string.onb_later), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
            OnboardingButton(
                onClick = {
                    if (page < LastPage) {
                        goTo(page + 1)
                    } else {
                        // A preview walks through the buttons but changes nothing.
                        if (!preview) context.graph.startAtPair.value = true
                        onFinished()
                    }
                },
                primary = true,
                modifier = Modifier.weight(1f),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (fadeIn(TandemMotion.fadeSpec()) + slideInVertically(TandemMotion.spatial()) { it / 2 }) togetherWith
                            (fadeOut(TandemMotion.fadeSpec()) + slideOutVertically(TandemMotion.spatial()) { -it / 2 })
                    },
                    contentAlignment = Alignment.Center,
                    label = "primaryLabel",
                ) { shown ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (shown == LastPage) Icon(TandemIcons.QrScan, null, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(
                                when (shown) {
                                    0 -> R.string.onb_start
                                    LastPage -> R.string.onb_pair_now
                                    else -> R.string.onb_continue
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (shown < LastPage) Icon(TandemIcons.ChevronRight, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private val ButtonHeight = 56.dp

/** One size and one shape for every button on the bottom row, so the row reads as a single system. */
@Composable
private fun OnboardingButton(
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(
        LocalContentColor provides if (primary) scheme.onPrimary else scheme.onSurface,
    ) {
        Row(
            modifier
                .height(ButtonHeight)
                .clip(PillShape)
                .background(if (primary) scheme.primary else scheme.surfaceContainerHigh)
                .bouncyClickable(onClick = onClick)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * Pages recede as they leave: a little smaller and fainter the further they are from
 * the middle. Driven by the scroll position itself, so it follows a finger exactly and
 * the same on a button press.
 */
private fun Modifier.pageEffect(pager: PagerState, page: Int): Modifier = graphicsLayer {
    val distance = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
    alpha = 1f - distance * 0.75f
    val s = 1f - distance * 0.06f
    scaleX = s
    scaleY = s
}

/**
 * Round dots. One primary dot rides over the grey ones with the scroll, so it is a
 * single thing travelling rather than three dots each changing colour.
 */
@Composable
private fun PageDots(pager: PagerState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val slot = 20.dp
    val idle = 8.dp
    val active = 10.dp
    val description = stringResource(R.string.onboarding_page, pager.currentPage + 1, PageCount)
    Box(modifier) {
        Row(Modifier.semantics { contentDescription = description }) {
            repeat(PageCount) {
                Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(idle).clip(CircleShape).background(scheme.outlineVariant))
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(((pager.currentPage + pager.currentPageOffsetFraction) * slot.toPx()).roundToInt(), 0) }
                .size(slot),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(active).clip(CircleShape).background(scheme.primary))
        }
    }
}

// ---- Welcome ---------------------------------------------------------------------

@Composable
private fun WelcomePage() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TandemMark(size = 148.dp)
        Spacer(Modifier.height(36.dp))
        Text(
            stringResource(R.string.onb_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.staggeredEntry(1, startDelayMillis = 120),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onb_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.staggeredEntry(2, startDelayMillis = 120),
        )
        Spacer(Modifier.height(28.dp))
        Chips(
            listOf(
                TandemIcons.Folder to stringResource(R.string.action_files),
                TandemIcons.Paste to stringResource(R.string.action_clipboard),
                TandemIcons.Notifications to stringResource(R.string.perm_notifications),
            ),
            Modifier.staggeredEntry(3, startDelayMillis = 120),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(items: List<Pair<Painter, String>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (icon, label) ->
            Row(
                Modifier.clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/**
 * The launcher icon itself: the same two layers the home screen draws, so the welcome page
 * shows exactly what is on the phone (and what is on the Mac). The layers are 108 wide with
 * 72 of them visible, so they are drawn at one and a half times the tile and cropped by it.
 * The pills settle in on a spring.
 */
@Composable
private fun TandemMark(size: Dp) {
    val enter = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, TandemMotion.fadeSpec()) }
        enter.animateTo(1f, TandemMotion.bouncy())
    }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 0.7f + 0.3f * enter.value
                scaleX = s
                scaleY = s
                alpha = fade.value
            }
            .clip(SquircleShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painterResource(R.drawable.ic_launcher_background), null,
            Modifier.requiredSize(size * 1.5f),
        )
        androidx.compose.foundation.Image(
            painterResource(R.drawable.ic_launcher_foreground), null,
            Modifier.requiredSize(size * 1.5f).graphicsLayer {
                rotationZ = -(1f - enter.value) * 40f
                val pair = 0.85f + 0.15f * enter.value
                scaleX = pair
                scaleY = pair
            },
        )
    }
}

// ---- Permissions ------------------------------------------------------------------

@Composable
private fun PermissionsPage(preview: Boolean = false) {
    val context = LocalContext.current
    val status = rememberPermissionStatus()
    val requests = rememberPermissionRequests(status)
    val background = MaterialTheme.colorScheme.background
    val total = 8
    // In a preview the cards flip on and off by themselves, so the morph can be watched
    // without asking Android for anything.
    val demo = remember { mutableStateMapOf<Int, Boolean>() }
    fun granted(index: Int, real: Boolean) = if (preview) demo[index] == true else real
    fun grant(index: Int, real: () -> Unit): () -> Unit = if (preview) ({ demo[index] = demo[index] != true }) else real

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.onb_perm_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 4.dp).staggeredEntry(0))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onb_perm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp).staggeredEntry(1),
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(GroupedSpacing)) {
                PermissionCard(
                    TandemIcons.Notifications, stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_why),
                    granted = granted(0, status.notifications), onGrant = grant(0, requests::notifications),
                    note = blockedNote(!status.notifications && status.isBlocked(Manifest.permission.POST_NOTIFICATIONS), requests),
                    index = 0, total = total, modifier = Modifier.staggeredEntry(2),
                )
                PermissionCard(
                    TandemIcons.Battery, stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_why),
                    granted = granted(1, status.battery), onGrant = grant(1) { Permissions.openBatterySettings(context) },
                    index = 1, total = total, modifier = Modifier.staggeredEntry(3),
                )
                PermissionCard(
                    TandemIcons.Screenshot, stringResource(R.string.perm_photos), stringResource(R.string.perm_photos_why),
                    granted = granted(2, status.photos), onGrant = grant(2, requests::photos),
                    note = blockedNote(!status.photos && status.isBlocked(Manifest.permission.READ_MEDIA_IMAGES), requests),
                    index = 2, total = total, modifier = Modifier.staggeredEntry(4),
                )
                PermissionCard(
                    TandemIcons.Devices, stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_why),
                    granted = granted(3, status.notificationAccess), onGrant = grant(3) { Permissions.openNotificationAccessSettings(context) },
                    index = 3, total = total, modifier = Modifier.staggeredEntry(5),
                )
                PermissionCard(
                    TandemIcons.Call, stringResource(R.string.perm_phone), stringResource(R.string.perm_phone_why),
                    granted = granted(4, status.phone != PermissionLevel.Off), partly = !preview && status.phone == PermissionLevel.Partly,
                    onGrant = grant(4, requests::phone), note = if (preview) null else phoneNote(status, requests),
                    index = 4, total = total, modifier = Modifier.staggeredEntry(6),
                )
                PermissionCard(
                    TandemIcons.QrScan, stringResource(R.string.perm_camera), stringResource(R.string.perm_camera_why),
                    granted = granted(5, status.camera), onGrant = grant(5, requests::camera),
                    note = blockedNote(!status.camera && status.isBlocked(Manifest.permission.CAMERA), requests),
                    index = 5, total = total, modifier = Modifier.staggeredEntry(7),
                )
                PermissionCard(
                    TandemIcons.Bluetooth, stringResource(R.string.perm_bluetooth), stringResource(R.string.perm_bluetooth_why),
                    granted = granted(6, status.bluetooth), onGrant = grant(6, requests::bluetooth),
                    note = blockedNote(!status.bluetooth && status.isBlocked(Permissions.bluetoothPermissions.toList()), requests),
                    index = 6, total = total, modifier = Modifier.staggeredEntry(8),
                )
                PermissionCard(
                    TandemIcons.Update, stringResource(R.string.perm_install), stringResource(R.string.perm_install_why),
                    granted = granted(7, status.installApps), onGrant = grant(7) { Permissions.openInstallSettings(context) },
                    index = 7, total = total, modifier = Modifier.staggeredEntry(9),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
        // The list runs on under the dots, so it fades out instead of being cut off.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
        )
    }
}

// ---- Pair ------------------------------------------------------------------------

@Composable
private fun PairPage() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PairHero()
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.onb_pair_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.staggeredEntry(1, startDelayMillis = 120),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onb_pair_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.staggeredEntry(2, startDelayMillis = 120),
        )
        Spacer(Modifier.height(24.dp))
        Chips(
            listOf(
                TandemIcons.QrScan to stringResource(R.string.pair_scan),
                TandemIcons.QrShow to stringResource(R.string.pair_show),
            ),
            Modifier.staggeredEntry(3, startDelayMillis = 120),
        )
    }
}

/** A phone and a laptop that slide out from behind the code tile, the two being paired. */
@Composable
private fun PairHero() {
    val enter = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, TandemMotion.fadeSpec()) }
        enter.animateTo(1f, TandemMotion.bouncy())
    }
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(236.dp, 176.dp).graphicsLayer { alpha = fade.value }, contentAlignment = Alignment.Center) {
        Satellite(
            TandemIcons.Phone, scheme.tertiaryContainer, scheme.onTertiaryContainer,
            Modifier.align(Alignment.TopStart).graphicsLayer {
                val gap = 1f - enter.value
                translationX = gap * 70.dp.toPx()
                translationY = gap * 54.dp.toPx()
            },
        )
        Satellite(
            TandemIcons.Laptop, scheme.secondaryContainer, scheme.onSecondaryContainer,
            Modifier.align(Alignment.BottomEnd).graphicsLayer {
                val gap = 1f - enter.value
                translationX = -gap * 70.dp.toPx()
                translationY = -gap * 54.dp.toPx()
            },
        )
        Box(
            Modifier
                .size(120.dp)
                .graphicsLayer {
                    val s = 0.6f + 0.4f * enter.value
                    scaleX = s
                    scaleY = s
                }
                .clip(SquircleShape(40.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TandemIcons.QrScan, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
private fun Satellite(icon: Painter, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(60.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(28.dp))
    }
}
