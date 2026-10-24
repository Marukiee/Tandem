package nl.markmaaktmedia.tandem.messages

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemSmsEvent
import uniffi.tandem_core.TandemSmsMessage
import uniffi.tandem_core.TandemSmsRefusal
import uniffi.tandem_core.TandemSmsRequest

/**
 * Messages on the phone's side: answers the questions of the computers from [SmsReader], sends what they write, and tells
 * them about every message that is new or changed. Who may ask is decided in the core (the switch per device); this only
 * looks at whether Android lets Tandem read and send, and reads nothing until it does.
 */
class SmsBridge(
    private val context: Context,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) {
    private val reader = SmsReader(context)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val work = Dispatchers.IO.limitedParallelism(1)
    private var watcher: Job? = null
    private var looking: Job? = null
    private var observer: ContentObserver? = null

    /** What the newest messages looked like at the last look, so the next one finds what changed. */
    private val seen = HashMap<String, Int>()
    private var primed = false

    fun start() {
        if (!SmsAccess.available(context)) return
        // The permission is given and taken in the settings, outside the app: looked at again now and then.
        watcher = scope.launch {
            while (isActive) {
                val granted = SmsAccess.granted(context)
                if (granted && observer == null) watch() else if (!granted && observer != null) unwatch()
                delay(5_000)
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        unwatch()
    }

    fun onEvent(event: TandemEvent.Sms) {
        when (val msg = event.msg) {
            is TandemSmsEvent.Request -> scope.launch(Dispatchers.IO) { answer(event.from, msg.req, msg.request) }
            is TandemSmsEvent.AskAccess -> askAccess(event.from)
            else -> Unit
        }
    }

    // ---- Questions ---------------------------------------------------------------------------------------------------------------

    private suspend fun answer(from: String, req: ULong, request: TandemSmsRequest) {
        val engine = host.engine ?: return
        runCatching {
            if (!SmsAccess.granted(context)) {
                engine.smsRefuse(from, req, TandemSmsRefusal.NO_PERMISSION)
                return
            }
            when (request) {
                is TandemSmsRequest.ListConversations -> {
                    val page = safely { reader.conversations(request.before.toLong(), request.limit.toInt()) } ?: SmsReader.Page(emptyList(), false)
                    engine.smsAnswerConversations(from, req, page.items, page.more)
                }
                is TandemSmsRequest.ListMessages -> {
                    val thread = request.thread.toLong()
                    val page = safely { reader.messages(thread, request.before.toLong(), request.limit.toInt()) } ?: SmsReader.Page(emptyList(), false)
                    engine.smsAnswerMessages(from, req, request.thread, page.items, page.more)
                }
                is TandemSmsRequest.Send -> {
                    val (thread, error) = send(request.to, request.text)
                    engine.smsAnswerSent(from, req, thread?.toULong(), error)
                }
                is TandemSmsRequest.FindContacts -> engine.smsAnswerContacts(from, req, safely { reader.contacts(request.query) }.orEmpty())
                // Nothing back is a picture that cannot be shown; the computer keeps its placeholder.
                is TandemSmsRequest.Picture -> engine.smsAnswerPicture(from, req, safely { reader.picture(request.part.toLong()) } ?: ByteArray(0))
            }
        }.onFailure { Log.w(TAG, "could not answer a question about messages", it) }
    }

    private fun <T> safely(read: () -> T): T? = runCatching(read).onFailure { Log.w(TAG, "the message store would not answer", it) }.getOrNull()

    /** Sends the text as SMS, in as many parts as it takes, over the default SIM for messages. Returns its conversation, or why not. */
    private fun send(to: List<String>, text: String): Pair<Long?, String?> {
        val numbers = to.map { it.trim() }.filter { it.isNotEmpty() }
        // A group is an MMS, which Android only lets the messages app send.
        if (numbers.size != 1) return null to "a group conversation can only be answered on the phone"
        val number = numbers[0]
        val thread = runCatching { Telephony.Threads.getOrCreateThreadId(context, number) }.getOrNull()
        return runCatching {
            val manager = manager(thread)
            val parts = manager.divideMessage(text)
            // Android writes what it sent into the store itself, for an app that is not the messages app; the change shows from there.
            if (parts.size > 1) manager.sendMultipartTextMessage(number, null, parts, null, null)
            else manager.sendTextMessage(number, null, text, null, null)
            thread to null
        }.getOrElse { thread to (it.message ?: it.toString()) }
    }

    /**
     * The SIM to send with: the one set for messages. A phone set to ask every time has none, and an app in the background cannot ask,
     * so then the SIM the conversation used last, or the first one there is.
     */
    private fun manager(thread: Long?): SmsManager {
        val base = context.getSystemService(SmsManager::class.java)
        val default = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (default != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return base.createForSubscriptionId(default)
        val active = runCatching {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
        }.getOrDefault(emptyList())
        val chosen = thread?.let { reader.lastSubscription(it) }?.takeIf { it in active } ?: active.firstOrNull()
        return chosen?.let { base.createForSubscriptionId(it) } ?: base
    }

    // ---- Changes -----------------------------------------------------------------------------------------------------------------

    private fun watch() {
        val handler = Handler(Looper.getMainLooper())
        val watching = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = changed()
        }
        runCatching {
            for (uri in WATCHED) context.contentResolver.registerContentObserver(uri, true, watching)
            observer = watching
            scope.launch(work) { look() }
        }.onFailure { Log.w(TAG, "could not watch the messages", it) }
    }

    private fun unwatch() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        primed = false
    }

    /** One message comes with several notices of the store; one look after they settle is enough. */
    private fun changed() {
        looking?.cancel()
        looking = scope.launch(work) {
            delay(400)
            look()
        }
    }

    private suspend fun look() {
        val engine = host.engine ?: return
        if (!SmsAccess.granted(context)) return
        val newest = safely { reader.newest(60, 20) } ?: return
        val changed = newest.filter { seen[key(it)] != signature(it) }
        seen.clear()
        newest.forEach { seen[key(it)] = signature(it) }
        if (!primed) {
            primed = true
            return
        }
        if (changed.isEmpty() || !anyReader()) return
        val conversations = safely { reader.conversations(changed.map { it.thread.toLong() }.toSet()) }.orEmpty()
        runCatching { engine.smsChanged(conversations, changed) }
    }

    private fun key(m: TandemSmsMessage) = (if (m.mms) "m" else "s") + m.id

    private fun signature(m: TandemSmsMessage) = listOf(m.status, m.read, m.body, m.date, m.pictures.size).hashCode()

    /** A computer that shows messages is there and allowed; the core checks the same before it sends, this only spares the reading. */
    private fun anyReader(): Boolean {
        val engine = host.engine ?: return false
        return host.devices.value.any { it.online && CAP_VIEW in it.caps && engine.messagesAllowed(it.id) }
    }

    // ---- Letting a computer in ---------------------------------------------------------------------------------------------------

    /** A computer asks to read the messages: what it needs is asked here, where the person is. */
    private fun askAccess(from: String) {
        val open = MessagesAccessActivity.intent(context, from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.graph.appVisible) {
            runCatching { context.startActivity(open) }
            return
        }
        val name = host.device(from)?.name ?: context.getString(R.string.feature_device_fallback)
        val id = NOTIFICATION + (from.hashCode() and 0xFF)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.messages_ask_title, name))
            .setContentText(context.getString(R.string.messages_ask_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(300_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    companion object {
        private const val TAG = "SmsBridge"
        private const val NOTIFICATION = 0x534D00
        const val CAP_VIEW = "sms.view"
        private val WATCHED = listOf(Uri.parse("content://sms"), Uri.parse("content://mms"), Uri.parse("content://mms-sms/"))

        /** What this phone says it can do: messages, when it has a SIM slot to have them. */
        fun caps(context: Context): List<String> = if (SmsAccess.available(context)) listOf("sms") else emptyList()
    }
}
