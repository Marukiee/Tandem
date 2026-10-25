package nl.markmaaktmedia.tandem.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.mirror.MirrorListener
import uniffi.tandem_core.TandemCall
import uniffi.tandem_core.TandemCallAction
import uniffi.tandem_core.TandemCallState
import uniffi.tandem_core.TandemEvent

/**
 * Shows incoming calls on your other devices and lets you answer or decline there.
 *
 * Only the call itself is mirrored: who is calling and what state it is in. The audio
 * stays on the phone, because Android gives an app no way to carry it elsewhere.
 */
class CallMonitor(
    private val context: Context,
    private val host: EngineHost,
    private val prefs: TandemPrefs,
    private val scope: CoroutineScope,
) {
    private var callId = ""
    private var incoming = false
    private var number: String? = null
    private var name: String? = null
    private var answered = false
    private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    /** A number a computer asked this phone to call, and when: the call that starts right after is that one. */
    @Volatile private var dialed: Pair<String, Long>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            scope.launch { onState(state, incomingNumber) }
        }
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private suspend fun onState(state: String, incomingNumber: String?) {
        if (!prefs.callMirror.first() || !Permissions.phone(context)) return
        val targets = host.devices.value.filter { it.online }.map { it.id }
        if (targets.isEmpty()) return

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (lastState != TelephonyManager.EXTRA_STATE_RINGING) {
                    callId = System.currentTimeMillis().toString()
                    incoming = true
                    answered = false
                }
                if (incomingNumber != null) {
                    number = incomingNumber
                    name = lookup(incomingNumber)
                }
                send(targets, TandemCallState.RINGING)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState == TelephonyManager.EXTRA_STATE_IDLE) {
                    callId = System.currentTimeMillis().toString()
                    incoming = false
                    // A call this phone makes brings no number with it; one a computer asked for a moment ago is that one.
                    dialed?.takeIf { System.currentTimeMillis() - it.second < DIALED_MS }?.let { (to, _) ->
                        number = to
                        name = lookup(to)
                    }
                    dialed = null
                }
                answered = true
                send(targets, TandemCallState.ACTIVE)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState != TelephonyManager.EXTRA_STATE_IDLE) {
                    send(targets, if (incoming && !answered) TandemCallState.MISSED else TandemCallState.ENDED)
                    number = null
                    name = null
                }
            }
        }
        lastState = state
        if (state != TelephonyManager.EXTRA_STATE_IDLE && number == null && name == null) fromNotification(targets, state)
    }

    /**
     * Without a number, who the call is with comes from the notification of the phone app (see [MirrorListener.callTitle]), which can come a
     * moment after the call state: looked at a few times, and the card on the computers gets the name as soon as there is one.
     */
    private fun fromNotification(targets: List<String>, state: String) {
        val id = callId
        scope.launch {
            for (wait in listOf(0L, 600L, 1_500L, 3_000L)) {
                kotlinx.coroutines.delay(wait)
                if (callId != id || lastState != state || number != null || name != null) return@launch
                val title = MirrorListener.callTitle() ?: continue
                if (title.any(Char::isLetter)) name = title else {
                    number = title
                    name = lookup(title)
                }
                send(targets, if (state == TelephonyManager.EXTRA_STATE_RINGING) TandemCallState.RINGING else TandemCallState.ACTIVE)
                return@launch
            }
        }
    }

    private suspend fun send(targets: List<String>, state: TandemCallState) {
        runCatching {
            host.engine?.sendCall(
                targets,
                TandemCall(callId, state, incoming, number, name, System.currentTimeMillis().toULong()),
            )
        }
    }

    private fun lookup(number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** An action pressed on the other device. */
    fun handle(event: TandemEvent.CallAction) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        runCatching {
            when (val action = event.action) {
                TandemCallAction.Answer -> telecom.acceptRingingCall()
                TandemCallAction.Reject, TandemCallAction.Hangup -> @Suppress("DEPRECATION") telecom.endCall()
                TandemCallAction.Silence -> silence()
                is TandemCallAction.RejectWithMessage -> {
                    val to = number
                    @Suppress("DEPRECATION") telecom.endCall()
                    if (to != null) SmsManager.getDefault().sendTextMessage(to, null, action.message, null, null)
                }
            }
        }
    }

    private companion object {
        /** How long after a computer asked for it a call that starts is the one it asked for. */
        const val DIALED_MS = 20_000L
    }

    private fun silence() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_RING, 0, 0) }
    }

    /** The Mac asked to dial a number. */
    fun dial(number: String) {
        if (!Permissions.phone(context)) return
        dialed = number to System.currentTimeMillis()
        val telecom = context.getSystemService(TelecomManager::class.java)
        runCatching { telecom.placeCall(Uri.fromParts("tel", number, null), Bundle()) }
    }
}
