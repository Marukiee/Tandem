package nl.markmaaktmedia.tandem.watch.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.protocol.CallActionKind
import nl.markmaaktmedia.tandem.watch.protocol.CallMsg
import nl.markmaaktmedia.tandem.watch.protocol.CallStatus
import nl.markmaaktmedia.tandem.watch.ui.CallActivity

/** Rings on the wrist while the phone rings. Only control: the sound of the call stays on the phone. */
class CallAlerts(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var ringing = false

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                // The pattern below keeps going until the call is answered; a channel can only buzz once.
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    fun update(call: CallMsg?) {
        when (call?.state) {
            CallStatus.RINGING -> {
                startRinging()
                show(call, ringingNow = true)
            }
            CallStatus.ACTIVE, CallStatus.DIALING -> {
                stopRinging()
                show(call, ringingNow = false)
            }
            CallStatus.ENDED, CallStatus.MISSED, null -> {
                stopRinging()
                manager.cancel(NOTIFICATION_ID)
            }
        }
    }

    fun stopRinging() {
        if (!ringing) return
        ringing = false
        vibrator.cancel()
    }

    private fun startRinging() {
        if (ringing) return
        ringing = true
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 1200), 0)
        vibrator.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
    }

    private fun show(call: CallMsg, ringingNow: Boolean) {
        val screen = PendingIntent.getActivity(
            context,
            0,
            Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = call.name ?: call.number ?: context.getString(R.string.call_unknown)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.sym_call)
            .setContentTitle(title)
            .setContentText(context.getString(if (ringingNow) R.string.call_incoming else R.string.call_active))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setLocalOnly(true)
            .setSilent(!ringingNow)
            .setContentIntent(screen)
        if (ringingNow) {
            builder.setFullScreenIntent(screen, true)
            builder.addAction(R.drawable.sym_call_end, context.getString(R.string.reject), ActionReceiver.callIntent(context, CallActionKind.REJECT))
            builder.addAction(R.drawable.sym_call, context.getString(R.string.answer), ActionReceiver.callIntent(context, CallActionKind.ANSWER))
        } else {
            builder.addAction(R.drawable.sym_call_end, context.getString(R.string.hang_up), ActionReceiver.callIntent(context, CallActionKind.HANGUP))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private companion object {
        const val CHANNEL = "calls"
        const val NOTIFICATION_ID = 200
    }
}
