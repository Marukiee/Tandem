package nl.markmaaktmedia.tandem.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.hotspot.BleAvailability
import nl.markmaaktmedia.tandem.hotspot.HotspotChecklistRows
import nl.markmaaktmedia.tandem.hotspot.HotspotModule
import nl.markmaaktmedia.tandem.hotspot.HotspotUsage
import nl.markmaaktmedia.tandem.hotspot.Phase
import nl.markmaaktmedia.tandem.hotspot.Refusal
import nl.markmaaktmedia.tandem.hotspot.ShizukuState
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus
import nl.markmaaktmedia.tandem.ui.screens.settings.FocusKeys
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsTarget
import nl.markmaaktmedia.tandem.ui.screens.settings.settingsTarget
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** What a Mac may use in a day over the hotspot, from none to ten gigabytes. */
private val LIMITS_MB = listOf(0L, 100L, 250L, 500L, 1024L, 2048L, 3072L, 5120L, 10240L)

/** Everything about letting your Mac use this phone's hotspot, on a page of its own. */
@Composable
fun HotspotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val module = HotspotModule.get(context)
    val scope = rememberCoroutineScope()
    val enabled by graph.prefs.hotspotForMac.collectAsState(initial = false)
    val roaming by module.prefs.allowRoaming.collectAsState(initial = false)
    val limitMb by module.prefs.dataLimitMb.collectAsState(initial = 0L)
    val totalBytes by module.prefs.dataUsedBytes.collectAsState(initial = 0L)
    val days by module.prefs.dailyUsage.collectAsState(initial = emptyMap())
    val since by module.prefs.usageSince.collectAsState(initial = null)
    val snapshot by module.controller.snapshot.collectAsState()
    val shizuku by module.shizuku.state.collectAsState()
    val attempt by module.controller.lastAttempt.collectAsState()
    val permissions = rememberPermissionStatus()
    val ready = permissions.bluetooth && permissions.notifications

    LaunchedEffect(Unit) {
        while (true) {
            module.shizuku.refresh()
            delay(2_000)
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.settings_hotspot), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.hotspot_page_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )

        // 1. Is everything in place?
        SectionHeader(stringResource(R.string.hotspot_section_setup))
        SettingsGroup(Modifier.settingsTarget(FocusKeys.HotspotSetup, RoundedCornerShape(24.dp))) { HotspotChecklistRows() }

        // 2. Sharing: the switch, the roaming choice and a test.
        SectionHeader(stringResource(R.string.hotspot_section_sharing))
        SettingsGroup {
            SettingsTarget(FocusKeys.HotspotShare, 0, 3) {
                SwitchRow(
                    0, 3, TandemIcons.Hotspot, stringResource(R.string.settings_hotspot_for_mac),
                    stringResource(if (ready) R.string.settings_hotspot_for_mac_sub else R.string.hotspot_needs_permissions),
                    enabled && ready, { scope.launch { graph.prefs.setHotspotForMac(it) } }, enabled = ready,
                )
            }
            SettingsTarget(FocusKeys.HotspotRoaming, 1, 3) {
                SwitchRow(
                    1, 3, TandemIcons.Cellular, stringResource(R.string.hotspot_roaming), stringResource(R.string.hotspot_roaming_sub), roaming,
                    { scope.launch { module.prefs.setAllowRoaming(it) } },
                )
            }
            val busy = snapshot.phase == Phase.Starting || snapshot.phase == Phase.NeedsTap
            SettingsTarget(FocusKeys.HotspotTest, 2, 3) {
                ActionRow(
                    2, 3, TandemIcons.Refresh,
                    stringResource(if (busy) R.string.hotspot_test_busy else R.string.hotspot_test),
                    stringResource(R.string.hotspot_test_sub),
                    onClick = {
                        if (busy) return@ActionRow
                        val message = when (module.availability()) {
                            BleAvailability.PrefOff -> R.string.hotspot_test_needs_pref
                            BleAvailability.NoPermission -> R.string.hotspot_test_needs_bluetooth
                            else -> null
                        }
                        if (message != null) {
                            Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                            return@ActionRow
                        }
                        module.controller.test()
                        scope.launch {
                            val result = withTimeoutOrNull(25_000) { module.controller.snapshot.first { it.phase != Phase.Starting } }
                            val failure = module.controller.lastAttempt.value?.takeIf { it.viaShizuku && !it.ok }
                            val text = when {
                                result?.phase == Phase.NeedsTap && failure != null -> context.getString(R.string.hotspot_test_shizuku_failed, failure.detail)
                                else -> context.getString(
                                    when (result?.phase) {
                                        Phase.On -> R.string.hotspot_test_ok
                                        Phase.NeedsTap -> R.string.hotspot_test_manual
                                        Phase.Refused -> when (result.refusal) {
                                            Refusal.Battery -> R.string.hotspot_test_battery
                                            Refusal.Limit -> R.string.hotspot_test_limit
                                            else -> R.string.hotspot_test_roaming
                                        }
                                        else -> R.string.hotspot_test_failed
                                    },
                                )
                            }
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }

        // 3. Data: a limit on a slider and what has been used.
        SectionHeader(stringResource(R.string.hotspot_section_data))
        // What is used is counted as it is used and written at once, so the total and today are already up to date,
        // with the session that is running: nothing is added on top.
        val todayBytes = days[HotspotUsage.dayKey(System.currentTimeMillis())] ?: 0L
        SettingsTarget(FocusKeys.HotspotUsage, CardSquircle) {
            DataUsageCard(
                todayBytes = todayBytes,
                totalBytes = totalBytes,
                since = since,
                days = days,
                onReset = { scope.launch { module.prefs.resetDataUsed() } },
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        SettingsTarget(FocusKeys.HotspotLimit, CardSquircle) {
            DataLimitCard(
                limitMb = limitMb,
                todayBytes = todayBytes,
                onLimit = { scope.launch { module.prefs.setDataLimitMb(it) } },
            )
        }

        // 4. How it turns on, and where to read more.
        SectionHeader(stringResource(R.string.hotspot_section_help))
        SettingsGroup {
            val methodText = when (shizuku) {
                ShizukuState.Ready -> R.string.hotspot_method_shizuku
                ShizukuState.NeedsPermission -> R.string.hotspot_method_need_permission
                ShizukuState.Denied -> R.string.hotspot_method_denied
                ShizukuState.NotRunning -> R.string.hotspot_method_not_running
                ShizukuState.NotInstalled -> R.string.hotspot_method_not_installed
                ShizukuState.TooOld -> R.string.hotspot_method_too_old
            }
            val rows = if (attempt != null) 3 else 2
            SettingsTarget(FocusKeys.HotspotMethod, 0, rows) {
                ActionRow(
                    0, rows, TandemIcons.Hotspot, stringResource(R.string.hotspot_method), stringResource(methodText),
                    onClick = {
                        when (shizuku) {
                            ShizukuState.NeedsPermission -> module.shizuku.requestPermission()
                            ShizukuState.NotRunning, ShizukuState.Denied -> module.shizuku.launchShizuku()
                            ShizukuState.NotInstalled, ShizukuState.TooOld -> context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            ShizukuState.Ready -> Unit
                        }
                    },
                    trailing = if (shizuku == ShizukuState.Ready) {
                        { Icon(TandemIcons.Check, null, tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(24.dp)) }
                    } else null,
                )
            }
            attempt?.let { last ->
                val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(last.at))
                ActionRow(
                    1, rows, TandemIcons.Info, stringResource(R.string.hotspot_last_attempt),
                    when {
                        last.ok && last.viaShizuku -> stringResource(R.string.hotspot_attempt_auto_ok, time)
                        last.ok -> stringResource(R.string.hotspot_attempt_manual_ok, time)
                        last.viaShizuku -> stringResource(R.string.hotspot_attempt_shizuku_failed, time, last.detail)
                        else -> stringResource(R.string.hotspot_attempt_nobody, time)
                    },
                    onClick = {},
                    trailing = if (last.ok) {
                        { Icon(TandemIcons.Check, null, tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(24.dp)) }
                    } else null,
                )
            }
            SettingsTarget(FocusKeys.HotspotGuide, rows - 1, rows) {
                ActionRow(
                    rows - 1, rows, TandemIcons.OpenInNew, stringResource(R.string.hotspot_setup), stringResource(R.string.hotspot_setup_sub),
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Marukiee/Tandem/blob/main/docs/HOTSPOT.md")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/**
 * The limit is a slider over a few sensible steps. The thumb follows the finger freely and
 * ticks as it passes each step, then settles on the nearest one with a spring, so it feels
 * smooth to drag and still ends on a value that means something.
 */
@Composable
private fun DataLimitCard(limitMb: Long, todayBytes: Long, onLimit: (Long) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val saved = LIMITS_MB.indexOf(limitMb).coerceAtLeast(0)
    val thumb = remember { Animatable(saved.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(saved.toFloat()) }
    var lastStep by remember { mutableIntStateOf(saved) }

    // A change from outside (the limit was reset) moves the thumb, unless it is being dragged.
    LaunchedEffect(saved) {
        if (!dragging && thumb.value.roundToInt() != saved) {
            thumb.animateTo(saved.toFloat(), spring(dampingRatio = 0.7f, stiffness = 380f))
            lastStep = saved
        }
    }

    val value = if (dragging) position else thumb.value
    val step = value.roundToInt().coerceIn(0, LIMITS_MB.lastIndex)
    val shown = LIMITS_MB[step]
    val used = android.text.format.Formatter.formatShortFileSize(context, todayBytes)

    Column(
        Modifier.fillMaxWidth().padding(bottom = 2.dp)
            .clip(CardSquircle)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hotspot_data_limit), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(limitLabel(shown), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            stringResource(R.string.hotspot_data_limit_body, used),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = { moved ->
                if (!dragging) {
                    dragging = true
                    lastStep = moved.roundToInt()
                }
                position = moved
                val now = moved.roundToInt().coerceIn(0, LIMITS_MB.lastIndex)
                if (now != lastStep) {
                    lastStep = now
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            },
            onValueChangeFinished = {
                val target = position.roundToInt().coerceIn(0, LIMITS_MB.lastIndex)
                onLimit(LIMITS_MB[target])
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    thumb.snapTo(position)
                    dragging = false
                    thumb.animateTo(target.toFloat(), spring(dampingRatio = 0.7f, stiffness = 380f))
                }
            },
            valueRange = 0f..LIMITS_MB.lastIndex.toFloat(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hotspot_data_none), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("10 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What the Mac has used over the hotspot: today, the total since it was last reset by hand, and the last days one
 * under the other with a bar each. A day is the unit because a session is gone when it ends, and the total on its
 * own does not say when the data went.
 */
@Composable
private fun DataUsageCard(todayBytes: Long, totalBytes: Long, since: Long?, days: Map<String, Long>, onReset: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val today = HotspotUsage.dayKey(System.currentTimeMillis())
    val yesterday = HotspotUsage.dayKey(System.currentTimeMillis() - 24 * 3_600_000L)
    // Today is shown above, so the list is the days before it.
    val recent = HotspotUsage.recent(days.filterKeys { it != today }, 7)
    val most = recent.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val formatter = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()) }

    Column(
        Modifier.fillMaxWidth()
            .clip(CardSquircle)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.hotspot_usage_title), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.hotspot_usage_today), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(size(todayBytes), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hotspot_usage_total), style = MaterialTheme.typography.bodyMedium)
                since?.let {
                    Text(
                        stringResource(R.string.hotspot_usage_since, android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(it))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(size(totalBytes), style = MaterialTheme.typography.titleMedium)
        }
        if (recent.isEmpty() && totalBytes <= 0L) {
            Text(
                stringResource(R.string.hotspot_usage_empty),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recent.isNotEmpty()) {
            Text(
                stringResource(R.string.hotspot_usage_days),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            recent.forEach { (day, bytes) ->
                val label = when (day) {
                    yesterday -> stringResource(R.string.hotspot_usage_yesterday)
                    else -> runCatching { java.time.LocalDate.parse(day).format(formatter) }.getOrDefault(day)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.9f))
                    // The bar of the biggest day is full, the others are in proportion to it.
                    Box(
                        Modifier.weight(1.2f).height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth((bytes.toFloat() / most).coerceIn(0.04f, 1f))
                                .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(size(bytes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            SecondaryPillButton(stringResource(R.string.hotspot_data_reset), onReset)
        }
    }
}

@Composable
private fun limitLabel(mb: Long): String = when {
    mb <= 0L -> stringResource(R.string.hotspot_data_none)
    mb >= 1024L -> "${mb / 1024} GB"
    else -> "$mb MB"
}
