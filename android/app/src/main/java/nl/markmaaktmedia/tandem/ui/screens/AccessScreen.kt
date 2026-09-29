package nl.markmaaktmedia.tandem.ui.screens

import android.Manifest
import android.app.NotificationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PermissionCard
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemNotification

/** What Tandem may do on this phone, with a way to try each thing without a second device. */
@Composable
fun AccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    // Permissions change outside the app, so look again every time we come back to it.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var testResult by remember { mutableStateOf<String?>(null) }

    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    @Suppress("UNUSED_EXPRESSION") tick

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Text(stringResource(R.string.access_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Spacer(Modifier.height(4.dp))

        PermissionCard(
            TandemIcons.Notifications, stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_why),
            Permissions.notifications(context),
            onGrant = { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            testLabel = stringResource(R.string.perm_notifications_test),
            onTest = {
                Channels.create(context)
                context.getSystemService(NotificationManager::class.java).notify(
                    4242,
                    NotificationCompat.Builder(context, Channels.INCOMING).setSmallIcon(R.drawable.ic_stat_tandem)
                        .setContentTitle(context.getString(R.string.test_notification_title))
                        .setContentText(context.getString(R.string.test_notification_text)).build(),
                )
                testResult = context.getString(R.string.test_sent_local)
            },
        )
        PermissionCard(
            TandemIcons.Battery, stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_why),
            Permissions.batteryUnrestricted(context), onGrant = { Permissions.openBatterySettings(context) },
        )
        PermissionCard(
            TandemIcons.Screenshot, stringResource(R.string.perm_photos), stringResource(R.string.perm_photos_why),
            Permissions.photos(context), onGrant = { multiLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES)) },
        )
        PermissionCard(
            TandemIcons.Devices, stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_why),
            Permissions.notificationAccess(context),
            onGrant = { Permissions.openNotificationAccessSettings(context) },
            testLabel = stringResource(R.string.perm_listener_test),
            onTest = {
                val targets = graph.host.devices.value.filter { it.online }.map { it.id }
                scope.launch {
                    if (targets.isEmpty()) {
                        testResult = context.getString(R.string.test_no_device)
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
                        testResult = context.getString(R.string.test_sent_remote, targets.size)
                    }
                }
            },
        )
        PermissionCard(
            TandemIcons.Call, stringResource(R.string.perm_phone), stringResource(R.string.perm_phone_why),
            Permissions.phone(context), onGrant = { multiLauncher.launch(Permissions.phonePermissions) },
        )
        PermissionCard(
            TandemIcons.Bluetooth, stringResource(R.string.perm_bluetooth), stringResource(R.string.perm_bluetooth_why),
            Permissions.bluetooth(context), onGrant = { multiLauncher.launch(Permissions.bluetoothPermissions) },
        )
        PermissionCard(
            TandemIcons.QrScan, stringResource(R.string.perm_camera), stringResource(R.string.perm_camera_why),
            Permissions.camera(context), onGrant = { cameraLauncher.launch(Manifest.permission.CAMERA) },
        )
        PermissionCard(
            TandemIcons.Update, stringResource(R.string.perm_install), stringResource(R.string.perm_install_why),
            Permissions.installApps(context), onGrant = { Permissions.openInstallSettings(context) },
        )

        testResult?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
