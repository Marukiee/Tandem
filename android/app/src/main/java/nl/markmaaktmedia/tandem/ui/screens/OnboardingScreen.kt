package nl.markmaaktmedia.tandem.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PermissionCard
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * First run: what this is, what it may ask for (all optional), and pairing.
 * Three pages, and the permission page can be skipped without losing anything
 * that cannot be turned on later from Settings.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 3 }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick

    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    fun next() = scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = TandemMotion.spatial()) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = true) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.onb_perm_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.onb_perm_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    PermissionCard(TandemIcons.Notifications, stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_why), Permissions.notifications(context), { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
                    PermissionCard(TandemIcons.Battery, stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_why), Permissions.batteryUnrestricted(context), { Permissions.openBatterySettings(context) })
                    PermissionCard(TandemIcons.Screenshot, stringResource(R.string.perm_photos), stringResource(R.string.perm_photos_why), Permissions.photos(context), { multiLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES)) })
                    PermissionCard(TandemIcons.Devices, stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_why), Permissions.notificationAccess(context), { Permissions.openNotificationAccessSettings(context) })
                    PermissionCard(TandemIcons.Call, stringResource(R.string.perm_phone), stringResource(R.string.perm_phone_why), Permissions.phone(context), { multiLauncher.launch(Permissions.phonePermissions) })
                    Spacer(Modifier.height(12.dp))
                }
                else -> PairIntroPage()
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Dots(count = 3, current = pager.currentPage)
            Spacer(Modifier.weight(1f))
            when (pager.currentPage) {
                0 -> PrimaryPillButton(stringResource(R.string.onb_start), { next() })
                1 -> {
                    SecondaryPillButton(stringResource(R.string.onb_skip), { next() })
                    Spacer(Modifier.size(8.dp))
                    PrimaryPillButton(stringResource(R.string.onb_continue), { next() })
                }
                else -> {
                    SecondaryPillButton(stringResource(R.string.onb_later), onFinished)
                    Spacer(Modifier.size(8.dp))
                    PrimaryPillButton(stringResource(R.string.onb_pair_now), {
                        context.graph.startAtPair.value = true
                        onFinished()
                    }, icon = TandemIcons.QrScan)
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    val turn by animateFloatAsState(1f, TandemMotion.springy(), label = "welcomeIn")
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(150.dp).scale(turn), contentAlignment = Alignment.Center) {
            Box(Modifier.size(58.dp, 130.dp).rotate(-32f).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer).padding(0.dp).align(Alignment.CenterEnd))
            Box(Modifier.size(58.dp, 130.dp).rotate(-32f).clip(CircleShape).background(MaterialTheme.colorScheme.primary).align(Alignment.CenterStart))
        }
        Spacer(Modifier.height(36.dp))
        Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onb_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PairIntroPage() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(TandemIcons.QrScan, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onb_pair_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onb_pair_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Page dots where the current one stretches into a pill. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 26.dp else 8.dp, TandemMotion.springy(), label = "dot")
            Box(
                Modifier.height(8.dp).size(width, 8.dp).clip(CircleShape)
                    .background(if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}
