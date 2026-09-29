package nl.markmaaktmedia.tandem.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.BuildConfig
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.InfoRow
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemConfirmDialog
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.update.UpdateState

@Composable
fun SettingsScreen(bottomPadding: Dp, onOpen: (Route) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = context.graph
    val prefs = graph.prefs
    val scope = rememberCoroutineScope()

    val deviceName by prefs.deviceName.collectAsState(initial = null)
    val screenshot by prefs.screenshotPrompt.collectAsState(initial = true)
    val mirror by prefs.mirrorNotifications.collectAsState(initial = true)
    val calls by prefs.callMirror.collectAsState(initial = true)
    val hotspot by prefs.hotspotForMac.collectAsState(initial = false)
    val autoUpdate by prefs.autoUpdateCheck.collectAsState(initial = true)
    val copyCodes by prefs.copyCodes.collectAsState(initial = true)
    val updateState by graph.updater.state.collectAsState()

    var renaming by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val currentLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-').ifBlank { "system" }
    val languages = listOf("system", "en", "nl")

    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
    ) {
        item {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
        }

        item {
            SectionHeader(stringResource(R.string.settings_this_phone))
            SettingsGroup {
                ActionRow(0, 2, TandemIcons.Phone, stringResource(R.string.settings_name), deviceName ?: graph.host.myName, { renaming = true })
                InfoRow(1, 2, TandemIcons.Key, stringResource(R.string.settings_id), graph.host.myId.take(10))
            }
        }

        item {
            SectionHeader(stringResource(R.string.settings_look))
            SettingsGroup {
                ActionRow(0, 2, TandemIcons.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub), { onOpen(Route.Appearance) })
                ContentRow(1, 2, TandemIcons.Language, stringResource(R.string.settings_language)) {
                    SegmentedPillRow(
                        options = languages,
                        selected = if (currentLanguage in languages) currentLanguage else "system",
                        label = { context.getString(when (it) { "en" -> R.string.language_en; "nl" -> R.string.language_nl; else -> R.string.language_system }) },
                        onSelect = { AppCompatDelegate.setApplicationLocales(if (it == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.settings_sharing))
            SettingsGroup {
                SwitchRow(0, 2, TandemIcons.Screenshot, stringResource(R.string.settings_screenshot), stringResource(R.string.settings_screenshot_sub), screenshot, { scope.launch { prefs.setScreenshotPrompt(it) } })
                ActionRow(1, 2, TandemIcons.Paste, stringResource(R.string.settings_clip_tile), stringResource(R.string.settings_clip_tile_sub), { nl.markmaaktmedia.tandem.share.ClipboardTileService.requestAdd(context) })
            }
        }

        item {
            SectionHeader(stringResource(R.string.settings_notifications))
            SettingsGroup {
                SwitchRow(0, 4, TandemIcons.Notifications, stringResource(R.string.settings_mirror), stringResource(R.string.settings_mirror_sub), mirror, { scope.launch { prefs.setMirrorNotifications(it) } })
                ActionRow(1, 4, TandemIcons.Devices, stringResource(R.string.settings_mirror_apps), stringResource(R.string.settings_mirror_apps_sub), { onOpen(Route.MirrorApps) })
                SwitchRow(2, 4, TandemIcons.Key, stringResource(R.string.settings_codes), stringResource(R.string.settings_codes_sub), copyCodes, { scope.launch { prefs.setCopyCodes(it) } })
                SwitchRow(3, 4, TandemIcons.Call, stringResource(R.string.settings_calls), stringResource(R.string.settings_calls_sub), calls, { scope.launch { prefs.setCallMirror(it) } })
            }
        }

        item {
            SectionHeader(stringResource(R.string.settings_hotspot))
            SettingsGroup {
                SwitchRow(0, 1, TandemIcons.Hotspot, stringResource(R.string.settings_hotspot_for_mac), stringResource(R.string.settings_hotspot_for_mac_sub), hotspot, { scope.launch { prefs.setHotspotForMac(it) } })
            }
        }

        item {
            SectionHeader(stringResource(R.string.settings_access))
            SettingsGroup {
                ActionRow(0, 1, TandemIcons.Shield, stringResource(R.string.settings_access_row), stringResource(R.string.settings_access_sub), { onOpen(Route.Access) })
            }
        }

        item {
            SectionHeader(stringResource(R.string.settings_updates))
            SettingsGroup {
                InfoRow(0, 3, TandemIcons.Info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
                SwitchRow(1, 3, TandemIcons.Update, stringResource(R.string.settings_auto_update), stringResource(R.string.settings_auto_update_sub), autoUpdate, { scope.launch { prefs.setAutoUpdateCheck(it) } })
                ActionRow(
                    2, 3, TandemIcons.Refresh, stringResource(R.string.settings_check_update),
                    when (updateState) {
                        is UpdateState.Checking -> stringResource(R.string.settings_checking)
                        is UpdateState.UpToDate -> stringResource(R.string.settings_up_to_date)
                        is UpdateState.Available -> stringResource(R.string.update_available, (updateState as UpdateState.Available).release.versionName)
                        is UpdateState.Failed -> (updateState as UpdateState.Failed).reason
                        else -> null
                    },
                    { scope.launch { graph.updater.check() } },
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.settings_about))
            SettingsGroup {
                ActionRow(0, 2, TandemIcons.OpenInNew, "github.com/Marukiee/Tandem", stringResource(R.string.settings_license), {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Marukiee/Tandem")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
                ActionRow(1, 2, TandemIcons.Restart, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_sub), { confirmReset = true }, danger = true)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(deviceName ?: graph.host.myName) }
        TandemDialog(
            title = stringResource(R.string.settings_name),
            onDismiss = { renaming = false },
            icon = TandemIcons.Phone,
            actions = {
                SecondaryPillButton(stringResource(R.string.action_cancel), { renaming = false })
                PrimaryPillButton(stringResource(R.string.action_save), {
                    val name = text.trim()
                    if (name.isNotEmpty()) scope.launch {
                        prefs.setDeviceName(name)
                        runCatching { graph.host.engine?.renameSelf(name) }
                    }
                    renaming = false
                })
            },
            content = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, keyboardOptions = KeyboardOptions.Default, shape = MaterialTheme.shapes.large)
            },
        )
    }

    if (confirmReset) {
        TandemConfirmDialog(
            title = stringResource(R.string.settings_reset),
            body = stringResource(R.string.settings_reset_body),
            confirmLabel = stringResource(R.string.settings_reset_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                graph.host.stop()
                nl.markmaaktmedia.tandem.engine.TandemService.stop(context)
                (context.getSystemService(android.app.ActivityManager::class.java)).clearApplicationUserData()
            },
            onDismiss = { confirmReset = false },
        )
    }
}
