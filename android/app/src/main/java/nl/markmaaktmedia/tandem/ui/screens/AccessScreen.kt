package nl.markmaaktmedia.tandem.ui.screens

import android.Manifest
import android.app.NotificationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PermissionCard
import nl.markmaaktmedia.tandem.ui.components.PermissionLevel
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.blockedNote
import nl.markmaaktmedia.tandem.ui.components.phoneNote
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionRequests
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus
import nl.markmaaktmedia.tandem.ui.components.staggeredEntry
import nl.markmaaktmedia.tandem.ui.theme.GroupedSpacing
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemNotification

/** What Tandem may do on this phone, with a way to try each thing without a second device. */
@Composable
fun AccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val status = rememberPermissionStatus()
    val requests = rememberPermissionRequests(status)
    var notificationTest by remember { mutableStateOf<String?>(null) }
    var listenerTest by remember { mutableStateOf<String?>(null) }

    val total = 8

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp).staggeredEntry(0))
        Text(
            stringResource(R.string.access_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).staggeredEntry(1),
        )
        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(GroupedSpacing)) {
            PermissionCard(
                TandemIcons.Notifications, stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_why),
                granted = status.notifications,
                onGrant = requests::notifications,
                testLabel = stringResource(R.string.perm_notifications_test),
                onTest = {
                    Channels.create(context)
                    context.getSystemService(NotificationManager::class.java).notify(
                        4242,
                        NotificationCompat.Builder(context, Channels.INCOMING).setSmallIcon(R.drawable.ic_stat_tandem)
                            .setContentTitle(context.getString(R.string.test_notification_title))
                            .setContentText(context.getString(R.string.test_notification_text)).build(),
                    )
                    notificationTest = context.getString(R.string.test_sent_local)
                },
                testResult = notificationTest,
                note = blockedNote(!status.notifications && status.isBlocked(Manifest.permission.POST_NOTIFICATIONS), requests),
                index = 0, total = total,
                modifier = Modifier.staggeredEntry(2),
            )
            PermissionCard(
                TandemIcons.Battery, stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_why),
                granted = status.battery, onGrant = { Permissions.openBatterySettings(context) },
                index = 1, total = total,
                modifier = Modifier.staggeredEntry(3),
            )
            PermissionCard(
                TandemIcons.Screenshot, stringResource(R.string.perm_photos), stringResource(R.string.perm_photos_why),
                granted = status.photos, onGrant = requests::photos,
                note = blockedNote(!status.photos && status.isBlocked(Manifest.permission.READ_MEDIA_IMAGES), requests),
                index = 2, total = total,
                modifier = Modifier.staggeredEntry(4),
            )
            PermissionCard(
                TandemIcons.Devices, stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_why),
                granted = status.notificationAccess,
                onGrant = { Permissions.openNotificationAccessSettings(context) },
                testLabel = stringResource(R.string.perm_listener_test),
                onTest = {
                    val targets = graph.host.devices.value.filter { it.online }.map { it.id }
                    scope.launch {
                        if (targets.isEmpty()) {
                            listenerTest = context.getString(R.string.test_no_device)
                        } else {
                            runCatching {
                                graph.host.engine?.sendNotification(
                                    targets,
                                    TandemNotification(
                                        key = "tandem-test", appId = context.packageName, appName = "Tandem",
                                        title = context.getString(R.string.test_notification_title),
                                        text = context.getString(R.string.test_remote_text),
                                        subText = null, ts = System.currentTimeMillis().toULong(), ongoing = false, silent = false,
                                        buttons = emptyList(), otp = null, progressDone = null, progressTotal = null,
                                    ),
                                )
                            }
                            listenerTest = context.getString(R.string.test_sent_remote, targets.size)
                        }
                    }
                },
                testResult = listenerTest,
                index = 3, total = total,
                modifier = Modifier.staggeredEntry(5),
            )
            PermissionCard(
                TandemIcons.Call, stringResource(R.string.perm_phone), stringResource(R.string.perm_phone_why),
                granted = status.phone != PermissionLevel.Off,
                partly = status.phone == PermissionLevel.Partly,
                onGrant = requests::phone,
                note = phoneNote(status, requests),
                index = 4, total = total,
                modifier = Modifier.staggeredEntry(6),
            )
            PermissionCard(
                TandemIcons.Bluetooth, stringResource(R.string.perm_bluetooth), stringResource(R.string.perm_bluetooth_why),
                granted = status.bluetooth, onGrant = requests::bluetooth,
                note = blockedNote(!status.bluetooth && status.isBlocked(Permissions.bluetoothPermissions.toList()), requests),
                index = 5, total = total,
                modifier = Modifier.staggeredEntry(7),
            )
            PermissionCard(
                TandemIcons.QrScan, stringResource(R.string.perm_camera), stringResource(R.string.perm_camera_why),
                granted = status.camera, onGrant = requests::camera,
                note = blockedNote(!status.camera && status.isBlocked(Manifest.permission.CAMERA), requests),
                index = 6, total = total,
                modifier = Modifier.staggeredEntry(8),
            )
            PermissionCard(
                TandemIcons.Update, stringResource(R.string.perm_install), stringResource(R.string.perm_install_why),
                granted = status.installApps, onGrant = { Permissions.openInstallSettings(context) },
                index = 7, total = total,
                modifier = Modifier.staggeredEntry(9),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
