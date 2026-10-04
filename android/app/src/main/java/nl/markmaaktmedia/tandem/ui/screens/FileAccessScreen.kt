package nl.markmaaktmedia.tandem.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bleed
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.screens.settings.FocusKeys
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsTarget
import nl.markmaaktmedia.tandem.ui.screens.settings.settingsTarget
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemFilePolicy
import uniffi.tandem_core.TandemShare

/** The sizes the limit on what a device may send goes through when it is tapped. 0 is no limit. */
private val UploadLimits = listOf(0L, 10_000_000L, 100_000_000L, 1_000_000_000L, 10_000_000_000L)

/**
 * What other devices may do with the files of this phone. The choices are kept by the core, which also enforces them,
 * so this screen only shows them and changes them.
 */
@Composable
fun FileAccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val host = context.graph.host
    val devices by host.devices.collectAsState()
    val engine = host.engine

    // Null is the default for all devices; an id is one device.
    var target by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var allowed by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = Environment.isExternalStorageManager()
        version++
    }

    val policy = remember(target, version, engine) { target?.let { engine?.filePolicy(it) } ?: engine?.fileDefaultPolicy() }
    val own = remember(target, version, engine) { target?.let { engine?.hasOwnFilePolicy(it) } ?: true }
    // What other devices did while this screen is open shows up without having to leave it and come back.
    var activity by remember { mutableStateOf(engine?.fileActivity().orEmpty().take(30)) }
    androidx.compose.runtime.LaunchedEffect(engine) {
        while (true) {
            activity = engine?.fileActivity().orEmpty().take(30)
            kotlinx.coroutines.delay(3000)
        }
    }

    fun change(edit: (TandemFilePolicy) -> TandemFilePolicy) {
        val current = policy ?: return
        val next = edit(current)
        val id = target
        if (id == null) engine?.setFileDefaultPolicy(next) else engine?.setFilePolicy(id, next)
        version++
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let(::pathOfTree) ?: return@rememberLauncherForActivityResult
        change { current ->
            if (current.shares.any { it.path == path }) {
                current
            } else {
                val taken = current.shares.map { it.name }.toSet()
                val base = path.trimEnd('/').substringAfterLast('/').ifEmpty { "Phone" }
                val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
                current.copy(shares = current.shares + TandemShare(name = name, path = path, write = true))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.files_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.files_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )

        // Without this Android lets the app see almost nothing of the storage, and a device that asks gets nothing.
        SectionHeader(stringResource(R.string.files_permission_header))
        SettingsGroup {
            SettingsTarget(FocusKeys.FilesPermission, 0, 1) {
                ActionRow(
                    0, 1, TandemIcons.Folder,
                    stringResource(R.string.files_permission_title),
                    stringResource(if (allowed) R.string.files_permission_on else R.string.files_permission_off),
                    onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    trailing = { Text(stringResource(if (allowed) R.string.files_on else R.string.files_off), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }

        SectionHeader(stringResource(R.string.files_for))
        Row(Modifier.fillMaxWidth().bleed(16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.files_all_devices), target == null) { target = null }
            devices.forEach { device -> Choice(device.name, target == device.id) { target = device.id } }
        }

        val editable = policy != null && (target == null || own)
        if (target != null) {
            SettingsGroup {
                SwitchRow(
                    0, 1, TandemIcons.Shield,
                    stringResource(R.string.files_own),
                    stringResource(R.string.files_own_sub),
                    checked = own,
                    onChange = { wantOwn ->
                        val id = target ?: return@SwitchRow
                        if (wantOwn) {
                            // It starts from what it follows now, so nothing changes until a person changes it.
                            policy?.let { engine?.setFilePolicy(id, it) }
                        } else {
                            engine?.clearFilePolicy(id)
                        }
                        version++
                    },
                )
            }
        }

        if (policy != null) {
            SectionHeader(stringResource(R.string.files_rights))
            SettingsGroup {
                SettingsTarget(FocusKeys.FilesEnabled, 0, 5) {
                    SwitchRow(0, 5, TandemIcons.Folder, stringResource(R.string.files_enabled), stringResource(R.string.files_enabled_sub), policy.enabled, { on -> change { it.copy(enabled = on) } }, editable)
                }
                SettingsTarget(FocusKeys.FilesWrite, 1, 5) {
                    SwitchRow(1, 5, TandemIcons.Upload, stringResource(R.string.files_write), stringResource(R.string.files_write_sub), policy.write, { on -> change { it.copy(write = on) } }, editable)
                }
                SettingsTarget(FocusKeys.FilesDelete, 2, 5) {
                    SwitchRow(2, 5, TandemIcons.Delete, stringResource(R.string.files_delete), stringResource(R.string.files_delete_sub), policy.delete, { on -> change { it.copy(delete = on) } }, editable)
                }
                SettingsTarget(FocusKeys.FilesHidden, 3, 5) {
                    SwitchRow(3, 5, TandemIcons.File, stringResource(R.string.files_hidden), stringResource(R.string.files_hidden_sub), policy.hidden, { on -> change { it.copy(hidden = on) } }, editable)
                }
                val limit = policy.maxUpload.toLong()
                SettingsTarget(FocusKeys.FilesMax, 4, 5) {
                    ActionRow(
                        4, 5, TandemIcons.Download,
                        stringResource(R.string.files_max_upload),
                        stringResource(R.string.files_max_upload_sub),
                        onClick = {
                            if (editable) {
                                val next = UploadLimits[(UploadLimits.indexOf(limit).coerceAtLeast(-1) + 1) % UploadLimits.size]
                                change { it.copy(maxUpload = next.toULong()) }
                            }
                        },
                        trailing = {
                            Text(
                                if (limit == 0L) stringResource(R.string.files_no_limit) else android.text.format.Formatter.formatShortFileSize(context, limit),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            SectionHeader(stringResource(R.string.files_folders))
            SettingsGroup {
                val total = policy.shares.size + 1
                policy.shares.forEachIndexed { index, share ->
                    GroupedRow(index, total) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            RowIcon(TandemIcons.Folder)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(share.name, style = MaterialTheme.typography.titleSmall)
                                Text(share.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    stringResource(if (share.write) R.string.files_folder_changes else R.string.files_folder_read_only),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = share.write,
                                onCheckedChange = if (editable) ({ on -> change { current -> current.copy(shares = current.shares.map { if (it == share) it.copy(write = on) else it }) } }) else null,
                                enabled = editable,
                            )
                            TandemIconButton(
                                TandemIcons.Delete, stringResource(R.string.files_remove_folder),
                                onClick = { if (editable) change { current -> current.copy(shares = current.shares - share) } },
                            )
                        }
                    }
                }
                SettingsTarget(FocusKeys.FilesFolder, policy.shares.size, total) {
                    ActionRow(
                        policy.shares.size, total, TandemIcons.Add,
                        stringResource(R.string.files_add_folder),
                        stringResource(R.string.files_add_folder_sub),
                        onClick = { if (editable) picker.launch(null) },
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.files_activity), modifier = Modifier.settingsTarget(FocusKeys.FilesActivity, RoundedCornerShape(12.dp)))
        if (activity.isEmpty()) {
            Text(
                stringResource(R.string.files_activity_none),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            SettingsGroup {
                activity.forEachIndexed { index, item ->
                    val who = devices.firstOrNull { it.id == item.device }?.name ?: item.device.take(6)
                    val what = when (item.action) {
                        "write" -> stringResource(R.string.files_did_write)
                        "remove" -> stringResource(R.string.files_did_remove)
                        "mkdir" -> stringResource(R.string.files_did_mkdir)
                        "rename" -> stringResource(R.string.files_did_rename)
                        "read" -> stringResource(R.string.files_did_read)
                        else -> item.action
                    }
                    val ago = System.currentTimeMillis() - item.atMs.toLong()
                    val whenText = if (ago < 60_000) {
                        stringResource(R.string.files_just_now)
                    } else {
                        DateUtils.getRelativeTimeSpanString(item.atMs.toLong(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    }
                    GroupedRow(index, activity.size) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("$what ${item.path}", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            val refused = if (item.ok) "" else ", " + stringResource(R.string.files_refused, item.detail)
                            Text("$who, $whenText$refused", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/** A device, or all of them, to choose whose settings are shown. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(PillShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * The folder a tree that the system picker returned stands for, as a path. Only storage that has paths: a folder
 * of a cloud app has none, and then there is nothing to offer.
 */
private fun pathOfTree(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val relative = id.substringAfter(':', "")
    val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    return if (relative.isEmpty()) base else "$base/$relative"
}
