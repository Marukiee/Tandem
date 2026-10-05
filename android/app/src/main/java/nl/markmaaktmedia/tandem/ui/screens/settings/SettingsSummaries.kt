package nl.markmaaktmedia.tandem.ui.screens.settings

import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.markmaaktmedia.tandem.BuildConfig
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PermissionLevel
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus
import nl.markmaaktmedia.tandem.ui.screens.seedLabel
import nl.markmaaktmedia.tandem.ui.theme.LocalAppearance
import nl.markmaaktmedia.tandem.ui.theme.ThemeMode
import nl.markmaaktmedia.tandem.update.UpdateState

/**
 * The line under each category on the overview, read live from where the settings are kept. Every line says
 * something a person can act on without opening the page: what is chosen, how much is on, or what is missing.
 */
@Composable
internal fun rememberCategorySummaries(): Map<SettingsCategory, String> {
    val context = LocalContext.current
    val graph = context.graph
    val prefs = graph.prefs

    // Appearance and language.
    val appearance = LocalAppearance.current
    val locale = LocalConfiguration.current.locales[0]
    val languageName = when (locale.language) {
        "nl" -> stringResource(R.string.language_nl)
        "en" -> stringResource(R.string.language_en)
        else -> locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }
    // Following the system is the default and says nothing, so only a chosen theme is named.
    val mode = when (appearance.mode) {
        ThemeMode.LIGHT -> stringResource(R.string.appearance_light)
        ThemeMode.DARK -> stringResource(R.string.appearance_dark)
        ThemeMode.SYSTEM -> null
    }
    val look = listOfNotNull(mode, stringResource(seedLabel(appearance.seed)), languageName).joinToString(", ")

    // Switches, counted per page.
    val screenshot by prefs.screenshotPrompt.collectAsState(initial = true)
    val ble by prefs.bluetoothMessages.collectAsState(initial = true)
    val mediaShare by prefs.mediaShare.collectAsState(initial = true)
    val audioOutput by prefs.audioOutput.collectAsState(initial = true)
    val mirror by prefs.mirrorNotifications.collectAsState(initial = true)
    val codes by prefs.copyCodes.collectAsState(initial = true)
    val calls by prefs.callMirror.collectAsState(initial = true)
    val sharing = onOf(listOf(screenshot, ble, mediaShare, audioOutput))
    val notifications = onOf(listOf(mirror, codes, calls))

    // Permissions, the same eight the permissions page lists. Calls count as on when anything of them works.
    val status = rememberPermissionStatus()
    val granted = listOf(
        status.notifications, status.battery, status.photos, status.notificationAccess,
        status.phone != PermissionLevel.Off, status.bluetooth, status.camera, status.installApps,
    )

    // Quick Share.
    val quickShareOn by graph.quickShare.enabled.collectAsState()

    // Hotspot.
    val hotspot by prefs.hotspotForMac.collectAsState(initial = false)
    val hotspotReady = status.bluetooth && status.notifications

    // Files.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val engine = graph.host.engine
    val filesAllowed = remember(resumes) { Environment.isExternalStorageManager() }
    val policy = remember(resumes, engine) { engine?.fileDefaultPolicy() }

    // Updates.
    val updateState by graph.updater.state.collectAsState()
    val version = BuildConfig.VERSION_NAME

    return mapOf(
        SettingsCategory.Look to look,
        SettingsCategory.Sharing to summaryOnOf(sharing.first, sharing.second),
        SettingsCategory.QuickShare to stringResource(if (quickShareOn) R.string.quickshare_row_on else R.string.quickshare_row_off),
        SettingsCategory.Notifications to summaryOnOf(notifications.first, notifications.second),
        SettingsCategory.Permissions to stringResource(R.string.settings_sum_permissions, granted.count { it }, granted.size),
        SettingsCategory.Hotspot to stringResource(
            when {
                !hotspotReady -> R.string.hotspot_row_setup
                hotspot -> R.string.hotspot_row_on
                else -> R.string.hotspot_row_off
            },
        ),
        SettingsCategory.Files to when {
            !filesAllowed -> stringResource(R.string.settings_sum_files_needs_access)
            policy == null -> stringResource(R.string.files_row_sub)
            !policy.enabled -> stringResource(R.string.settings_sum_files_off)
            policy.shares.isEmpty() -> stringResource(R.string.settings_sum_files_on)
            else -> pluralStringResource(R.plurals.settings_sum_folders, policy.shares.size, policy.shares.size)
        },
        SettingsCategory.Updates to when (val state = updateState) {
            is UpdateState.Available -> stringResource(R.string.update_available, state.release.versionName)
            is UpdateState.UpToDate -> stringResource(R.string.settings_sum_up_to_date, version)
            else -> stringResource(R.string.settings_sum_version, version)
        },
        SettingsCategory.About to stringResource(R.string.settings_license),
    )
}

private fun onOf(switches: List<Boolean>) = switches.count { it } to switches.size

@Composable
private fun summaryOnOf(done: Int, of: Int): String = when (done) {
    of -> stringResource(R.string.settings_sum_all_on)
    0 -> stringResource(R.string.settings_sum_all_off)
    else -> stringResource(R.string.settings_sum_on_of, done, of)
}
