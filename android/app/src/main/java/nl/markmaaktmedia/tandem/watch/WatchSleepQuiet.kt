package nl.markmaaktmedia.tandem.watch

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R

/**
 * The phone's quiet while the watch records sleep, as a mode of its own. Turning a mode off cannot end the person's own
 * Do Not Disturb or another device's Focus, so it is safe to repeat at every reconnect. What the mode lets through is
 * theirs to choose in the system settings. If another device follows this phone's Focus, it follows this too.
 */
class WatchSleepQuiet(private val context: Context, private val handler: Handler) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val store = context.getSharedPreferences("watch_sleep", Context.MODE_PRIVATE)

    /** A watch that ran empty never says it woke up, and that must not keep the phone silent for days. */
    private val giveUp = AlarmManager.OnAlarmListener { turn(false) }

    fun canUse(): Boolean = manager.isNotificationPolicyAccessGranted

    fun turn(on: Boolean) {
        // The watch repeats that it is awake at every reconnect; there is only something to undo if this turned it on.
        if (!on && store.getLong(KEY_SINCE, 0L) == 0L) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(giveUp)
        if (on) {
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + LONGEST_MS, "tandem:sleep-quiet", giveUp, handler)
            store.edit().putLong(KEY_SINCE, System.currentTimeMillis()).apply()
        } else {
            store.edit().remove(KEY_SINCE).apply()
        }
        if (!canUse()) {
            Log.i(TAG, "No access to Do Not Disturb, the phone stays as it is")
            return
        }
        // A mode is not made only to be turned off.
        val id = (if (on) rule() else store.getString(KEY_RULE, null)) ?: return
        val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
        val summary = context.getString(R.string.watch_quiet_summary)
        val condition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Condition(conditionId(), summary, state, Condition.SOURCE_CONTEXT)
        } else {
            Condition(conditionId(), summary, state)
        }
        runCatching { manager.setAutomaticZenRuleState(id, condition) }.onFailure { Log.w(TAG, "Could not switch the mode", it) }
    }

    /** After a restart of the app the alarm is gone, so a night that never ended is ended here. */
    fun checkLeftOver() {
        val since = store.getLong(KEY_SINCE, 0L)
        if (since != 0L && System.currentTimeMillis() - since > LONGEST_MS) turn(false)
    }

    private fun conditionId(): Uri = Condition.newId(context).appendPath("watch-sleep").build()

    private fun rule(): String? {
        val saved = store.getString(KEY_RULE, null)
        if (saved != null && runCatching { manager.getAutomaticZenRule(saved) }.getOrNull() != null) return saved
        return runCatching { manager.addAutomaticZenRule(newRule()) }
            .onFailure { Log.w(TAG, "Could not make the mode", it) }
            .getOrNull()
            ?.also { store.edit().putString(KEY_RULE, it).apply() }
    }

    private fun newRule(): AutomaticZenRule {
        val name = context.getString(R.string.watch_quiet_mode)
        val app = ComponentName(context, MainActivity::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return AutomaticZenRule.Builder(name, conditionId())
                .setConfigurationActivity(app)
                // TYPE_BEDTIME would fit, but Android keeps that for Digital Wellbeing.
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                // Whatever else this mode holds back, nobody should sleep through their alarm because of it.
                .setZenPolicy(ZenPolicy.Builder().allowAlarms(true).build())
                .setIconResId(R.drawable.sym_bedtime)
                .setTriggerDescription(context.getString(R.string.watch_quiet_trigger))
                .setManualInvocationAllowed(false)
                .build()
        }
        return AutomaticZenRule(name, null, app, conditionId(), null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
    }

    private companion object {
        const val TAG = "TandemWatch"
        const val KEY_RULE = "rule"
        const val KEY_SINCE = "since"
        const val LONGEST_MS = 16 * 60 * 60 * 1000L
    }
}
