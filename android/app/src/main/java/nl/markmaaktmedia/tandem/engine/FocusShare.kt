package nl.markmaaktmedia.tandem.engine

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import uniffi.tandem_core.TandemEvent

/**
 * Focus the same on every device (see `focus.rs` in the core). The phone tells the core what its Do Not Disturb is now, whatever
 * turned it on, and the core works out what is news for the others: a change that only follows another device does not go back.
 * When another device turns its Focus on, the phone turns on a mode of its own ([FocusMode]).
 */
class FocusShare(
    private val context: Context,
    private val host: EngineHost,
    private val prefs: TandemPrefs,
    private val scope: CoroutineScope,
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val reporting = Mutex()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = report()
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED))
        scope.launch {
            combine(host.state, prefs.focusShare, prefs.focusFollowOnly) { state, share, followOnly -> Triple(state, share, followOnly) }.collect { (state, share, followOnly) ->
                val engine = host.engine
                if (state != EngineState.Running || engine == null) return@collect
                engine.setFocusSharing(share && !followOnly, share)
                if (!share) FocusMode.off(context)
                // A freshly started engine takes this first reading for where the phone stands, not for a change.
                report()
            }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Reads the state when it is sent, so a quick on and off that overtake each other still end on what is true now. */
    private fun report() {
        scope.launch(Dispatchers.IO) {
            reporting.withLock {
                val filter = notifications.currentInterruptionFilter
                val on = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                runCatching { host.engine?.focusLocal(on, "") }
            }
        }
    }

    /** Another device turned its Focus on or off. Without access to Do Not Disturb the person is asked, once in a while, at the moment it matters. */
    fun follow(event: TandemEvent.Focus) {
        val device = host.device(event.origin)?.name ?: context.getString(R.string.feature_device_fallback)
        if (!FocusMode.canUse(context)) {
            if (event.on) askForAccess(device)
            return
        }
        FocusMode.turn(context, event.on, device, event.name)
    }

    private fun askForAccess(device: String) {
        val store = FocusMode.store(context)
        val now = System.currentTimeMillis()
        if (now - store.getLong(KEY_ASKED, 0L) < ASK_AGAIN_MS) return
        store.edit().putLong(KEY_ASKED, now).apply()
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, FocusMode.accessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.focus_access_text)
        val notification = NotificationCompat.Builder(context, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.focus_access_title, device))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 0x464f
        const val KEY_ASKED = "asked"
        const val ASK_AGAIN_MS = 12 * 60 * 60 * 1000L
    }
}

/**
 * Tandem's own mode, "Focus from <device>": an [AutomaticZenRule] the person sets up in the system settings like any other mode, so what
 * may still come through is theirs to choose. Made when it is first needed. The person's own Do Not Disturb is never touched.
 */
object FocusMode {
    private const val KEY_RULE = "rule"

    internal fun store(context: Context) = context.getSharedPreferences("focus", Context.MODE_PRIVATE)

    fun canUse(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Where Android lets the person give Tandem access to Do Not Disturb. */
    fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    private fun conditionId(context: Context): Uri = Condition.newId(context).appendPath("focus").build()

    /** Turns the mode on for the Focus of [device] (called [focusName] there, when it said), or off. */
    fun turn(context: Context, on: Boolean, device: String, focusName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(R.string.focus_mode_name, device)
        // A mode is not made only to be turned off.
        val id = (if (on) rule(context, name) else store(context).getString(KEY_RULE, null)) ?: return
        runCatching {
            val rule = manager.getAutomaticZenRule(id)
            if (on && rule != null && rule.name != name) manager.updateAutomaticZenRule(id, rule.apply { this.name = name })
        }
        val summary = if (focusName.isBlank()) context.getString(R.string.focus_mode_summary, device)
        else context.getString(R.string.focus_mode_summary_named, focusName, device)
        setState(context, id, on, summary)
    }

    fun off(context: Context) {
        if (!canUse(context)) return
        val id = store(context).getString(KEY_RULE, null) ?: return
        setState(context, id, false, "")
    }

    /** The page of the mode in the system settings, made first if it was not there yet; before modes, the page of Do Not Disturb. */
    fun openSettings(context: Context) {
        val id = rule(context, context.getString(R.string.focus_mode_name_any))
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && id != null) {
            Intent(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS).putExtra(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID, id)
        } else {
            Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun setState(context: Context, id: String, on: Boolean, summary: String) {
        val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
        val condition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Condition(conditionId(context), summary, state, Condition.SOURCE_CONTEXT)
        } else {
            Condition(conditionId(context), summary, state)
        }
        runCatching { context.getSystemService(NotificationManager::class.java).setAutomaticZenRuleState(id, condition) }
    }

    /** The id of the mode: the one made before, or a new one when there was none or the person removed it. */
    private fun rule(context: Context, nameIfNew: String): String? {
        if (!canUse(context)) return null
        val manager = context.getSystemService(NotificationManager::class.java)
        val saved = store(context).getString(KEY_RULE, null)
        if (saved != null && runCatching { manager.getAutomaticZenRule(saved) }.getOrNull() != null) return saved
        return runCatching { manager.addAutomaticZenRule(newRule(context, nameIfNew)) }.getOrNull()
            ?.also { store(context).edit().putString(KEY_RULE, it).apply() }
    }

    private fun newRule(context: Context, name: String): AutomaticZenRule {
        // Opening the mode from the system settings brings the person to Tandem, where the sharing is switched.
        val app = ComponentName(context, MainActivity::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return AutomaticZenRule.Builder(name, conditionId(context))
                .setConfigurationActivity(app)
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setIconResId(R.drawable.ic_stat_tandem)
                .setTriggerDescription(context.getString(R.string.focus_mode_trigger))
                // The person may also turn it on here, and then the other devices follow this phone.
                .setManualInvocationAllowed(true)
                .build()
        }
        return AutomaticZenRule(name, null, app, conditionId(context), null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
    }
}
