package nl.markmaaktmedia.tandem.watch.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import nl.markmaaktmedia.tandem.watch.link.WatchLink

/** What the installer says. On success the app is replaced and starts again by itself (`BootReceiver`), so there is nothing to do. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getLongExtra(EXTRA_CODE, 0)
        val app = context.applicationContext
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                WatchLink.post { SelfUpdate.askToConfirm(app, code, intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)) }
            PackageInstaller.STATUS_SUCCESS -> WatchLink.post { SelfUpdate.clear(app) }
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                // Only these can never work, like another key: a question that was put away can be answered at the next try.
                val final = status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE || status == PackageInstaller.STATUS_FAILURE_CONFLICT
                WatchLink.post { SelfUpdate.fail(app, code, detail, final) }
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
