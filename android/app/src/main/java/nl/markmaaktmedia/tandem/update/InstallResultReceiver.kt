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
                // Android only lets the screen with the question open over an app that is in front. The updater knows
                // whether that is so, and otherwise keeps the question for when the person comes back.
                if (confirm != null) context.graph.updater.onConfirmationNeeded(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> context.graph.updater.clearConfirmation()
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
