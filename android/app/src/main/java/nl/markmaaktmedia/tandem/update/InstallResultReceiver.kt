package nl.markmaaktmedia.tandem.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/** What the installer reports back: a confirmation to show, a success, or a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                context.graph.updater.reportInstallFailure(context.getString(R.string.update_reason_cancelled))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                context.graph.updater.reportInstallFailure(message.ifBlank { context.getString(R.string.update_reason_installer) })
            }
        }
    }

    companion object {
        const val ACTION = "nl.markmaaktmedia.tandem.INSTALL_RESULT"
    }
}
