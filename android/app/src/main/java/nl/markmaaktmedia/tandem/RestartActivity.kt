package nl.markmaaktmedia.tandem

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Starts Tandem again in a process of its own, for something that only reaches a process when it starts: the connection Shizuku hands
 * out (see [nl.markmaaktmedia.tandem.hotspot.ShizukuBridge]). It runs in a process apart (":restart"), so it outlives the one it stops,
 * and it is an activity on screen, so Android lets it open Tandem again. No window of its own: it is gone in a moment.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = intent.getIntExtra(EXTRA_PID, -1)
        if (old > 0 && old != Process.myPid()) Process.killProcess(old)
        val next = MainActivity.internalIntent(this)
            .setAction(intent.getStringExtra(EXTRA_ACTION))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(next)
        finish()
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val EXTRA_PID = "pid"
        private const val EXTRA_ACTION = "action"

        /** Stops this process and opens Tandem again with [action] (see [MainActivity]). */
        fun restart(from: Activity, action: String?) {
            from.startActivity(
                Intent(from, RestartActivity::class.java)
                    .putExtra(EXTRA_PID, Process.myPid())
                    .putExtra(EXTRA_ACTION, action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            from.finishAffinity()
        }
    }
}
