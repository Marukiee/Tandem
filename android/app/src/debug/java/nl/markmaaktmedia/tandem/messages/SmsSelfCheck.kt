package nl.markmaaktmedia.tandem.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import nl.markmaaktmedia.tandem.graph
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only. Reads the message store the way a computer's questions do and writes what came out, so the queries can
 * be checked on an emulator (which takes text messages with `adb emu sms send`) without a computer. With `send` it also sends
 * one the way a computer's Send does, to see Android write it into the store.
 *
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.messages.SmsSelfCheck \
 *         [--es send 5551234] [--es text "Hello"] [--es query an]
 *     adb exec-out run-as nl.markmaaktmedia.tandem.debug cat files/sms-selfcheck.txt
 */
class SmsSelfCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val send = intent.getStringExtra("send")
        val text = intent.getStringExtra("text") ?: "Hello from the self check"
        val query = intent.getStringExtra("query") ?: "a"
        val pending = goAsync()
        thread(name = "sms-selfcheck") {
            val report = StringBuilder()
            try {
                run(context, send, text, query, report)
            } catch (e: Throwable) {
                report.append("FAILED: ").append(e).append('\n')
            } finally {
                File(context.filesDir, "sms-selfcheck.txt").writeText(report.toString())
                report.lines().forEach { Log.i("SmsSelfCheck", it) }
                pending.finish()
            }
        }
    }

    private fun run(context: Context, send: String?, text: String, query: String, out: StringBuilder) {
        out.append("granted=").append(SmsAccess.granted(context)).append(" contacts=").append(SmsAccess.contacts(context))
            .append(" available=").append(SmsAccess.available(context)).append('\n')
        if (send != null) {
            val bridge = SmsBridge(context, context.graph.host, CoroutineScope(Dispatchers.IO))
            val (thread, error) = bridge.send(listOf(send), text)
            out.append("send thread=").append(thread).append(" error=").append(error).append('\n')
            Thread.sleep(4000)
        }
        val reader = SmsReader(context)
        val page = reader.conversations(0, 40)
        out.append("conversations=").append(page.items.size).append(" more=").append(page.more).append('\n')
        page.items.forEach {
            out.append("  thread=").append(it.thread).append(" addresses=").append(it.addresses).append(" name=").append(it.name)
                .append(" unread=").append(it.unread).append(" fromMe=").append(it.fromMe).append(" date=").append(it.date)
                .append(" snippet=").append(it.snippet).append('\n')
        }
        page.items.firstOrNull()?.let { first ->
            val messages = reader.messages(first.thread.toLong(), 0, 3)
            out.append("messages of ").append(first.thread).append(" (3)=").append(messages.items.size).append(" more=").append(messages.more).append('\n')
            messages.items.forEach { out.append("  ").append(it).append('\n') }
            messages.items.lastOrNull()?.let { last ->
                val older = reader.messages(first.thread.toLong(), last.date.toLong(), 3)
                out.append("older from ").append(last.date).append("=").append(older.items.map { it.id }).append(" more=").append(older.more).append('\n')
            }
            out.append("again=").append(reader.conversations(setOf(first.thread.toLong())).map { it.thread to it.unread }).append('\n')
        }
        out.append("newest=").append(reader.newest(5, 2).map { (if (it.mms) "m" else "s") + it.id + ":" + it.status }).append('\n')
        out.append("contacts(").append(query).append(")=").append(reader.contacts(query)).append('\n')
    }
}
