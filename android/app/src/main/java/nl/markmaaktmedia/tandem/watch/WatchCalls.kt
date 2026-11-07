package nl.markmaaktmedia.tandem.watch

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import nl.markmaaktmedia.tandem.mirror.MirrorListener
import nl.markmaaktmedia.tandem.watch.protocol.CallActionKind
import nl.markmaaktmedia.tandem.watch.protocol.CallMsg
import nl.markmaaktmedia.tandem.watch.protocol.CallStatus

/**
 * Follows the phone's call state and lets the watch control the call. Audio stays on the phone: this only answers,
 * rejects, silences and hangs up. Apart from [nl.markmaaktmedia.tandem.calls.CallMonitor] on purpose: that one stops
 * when no computer is online, and the watch still has to ring then.
 */
class WatchCalls(
    private val context: Context,
    private val handler: Handler,
    private val onChange: (CallMsg?) -> Unit,
) {
    private var current: CallMsg? = null

    /** A call is going on, so the link should stay free for it. */
    val active: Boolean get() = current != null
    private var nextId = 1L
    private var listening = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            handler.post { onState(state, number) }
        }
    }

    fun start() {
        if (listening) return
        listening = true
        // Protected system broadcast. Exported, or it never arrives on Android 14 and later.
        context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { context.unregisterReceiver(receiver) }
        handler.removeCallbacksAndMessages(LOOKUP_TOKEN)
        current = null
    }

    val state: CallMsg? get() = current

    private fun onState(state: String, number: String?) {
        val previous = current
        val next = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallMsg(
                id = previous?.id ?: newId(),
                state = CallStatus.RINGING,
                incoming = true,
                // The system sends this broadcast twice: once without the number when READ_CALL_LOG is missing.
                number = number ?: previous?.number,
                name = lookupName(number) ?: previous?.name,
                ts = previous?.ts ?: System.currentTimeMillis(),
                muted = false,
            )
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallMsg(
                id = previous?.id ?: newId(),
                state = CallStatus.ACTIVE,
                incoming = previous?.incoming == true,
                number = number ?: previous?.number,
                name = lookupName(number) ?: previous?.name,
                ts = if (previous?.state == CallStatus.ACTIVE) previous.ts else System.currentTimeMillis(),
                muted = false,
            )
            else -> null
        }
        if (next != previous) {
            current = next
            onChange(next)
        }
        if (next != null && next.number == null && next.name == null) nameFromNotification(next.id)
    }

    /**
     * Android keeps the number back from an app that did not come from a store, and never gives it for a call the phone
     * makes. The notification of the phone app has it either way, though it can come a moment after the call state.
     */
    private fun nameFromNotification(id: String) {
        handler.removeCallbacksAndMessages(LOOKUP_TOKEN)
        LOOKUP_WAITS_MS.forEach { wait ->
            handler.postDelayed({
                val call = current?.takeIf { it.id == id && it.number == null && it.name == null } ?: return@postDelayed
                val title = MirrorListener.callTitle() ?: return@postDelayed
                val next = if (title.any(Char::isLetter)) call.copy(name = title) else call.copy(number = title, name = lookupName(title))
                current = next
                onChange(next)
            }, LOOKUP_TOKEN, wait)
        }
    }

    @SuppressLint("MissingPermission") // Checked in hasCallControl; a revoked permission only logs.
    fun perform(kind: CallActionKind) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        if (!hasCallControl()) return
        try {
            when (kind) {
                CallActionKind.ANSWER -> @Suppress("DEPRECATION") telecom.acceptRingingCall()
                CallActionKind.REJECT, CallActionKind.HANGUP -> @Suppress("DEPRECATION") telecom.endCall()
                CallActionKind.SILENCE -> telecom.silenceRinger()
                // The watch does not offer these.
                CallActionKind.REJECT_WITH_MESSAGE, CallActionKind.MUTE_MIC -> Unit
            }
        } catch (e: SecurityException) {
            Log.i(TAG, "Call action refused: ${e.message}")
        }
    }

    private fun hasCallControl() =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    private fun newId() = (nextId++).toString()

    private fun lookupName(number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver
            .query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private companion object {
        const val TAG = "TandemWatch"
        val LOOKUP_TOKEN = Any()
        val LOOKUP_WAITS_MS = longArrayOf(0, 600, 1_500, 3_000)
    }
}
