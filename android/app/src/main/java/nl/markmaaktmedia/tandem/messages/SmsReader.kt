package nl.markmaaktmedia.tandem.messages

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import nl.markmaaktmedia.tandem.R
import uniffi.tandem_core.TandemPhoneContact
import uniffi.tandem_core.TandemSmsContact
import uniffi.tandem_core.TandemSmsConversation
import uniffi.tandem_core.TandemSmsMessage
import uniffi.tandem_core.TandemSmsStatus
import java.io.ByteArrayOutputStream

/**
 * Reads the conversations of this phone from Android's own store of SMS and MMS, which every messages app writes to. Nothing
 * is kept here but the names of the contacts for a while: each question is answered from the store as it is now.
 */
class SmsReader(private val context: Context) {
    private val resolver get() = context.contentResolver

    data class Page<T>(val items: List<T>, val more: Boolean)

    /** The conversations at or before [before] (milliseconds, 0 for the newest), newest first. */
    fun conversations(before: Long, limit: Int): Page<TandemSmsConversation> {
        val rows = mutableListOf<ThreadRow>()
        var more = false
        val selection = if (before > 0) "${Telephony.Threads.DATE} <= ?" else null
        val args = if (before > 0) arrayOf(before.toString()) else null
        // The simple list is the table of threads itself, always newest first, and the cursor is read only as far as the page goes.
        resolver.query(THREADS, THREAD_COLUMNS, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) == 0) continue
                if (rows.size == limit) {
                    more = true
                    break
                }
                rows += ThreadRow(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(4).orEmpty())
            }
        }
        return Page(summaries(rows), more)
    }

    /** The conversations of these threads as they are now, for a change. */
    fun conversations(threads: Collection<Long>): List<TandemSmsConversation> {
        if (threads.isEmpty()) return emptyList()
        val rows = mutableListOf<ThreadRow>()
        val selection = "${Telephony.Threads._ID} IN (${threads.joinToString(",")})"
        resolver.query(THREADS, THREAD_COLUMNS, selection, null, null)?.use { c ->
            while (c.moveToNext()) rows += ThreadRow(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(4).orEmpty())
        }
        return summaries(rows)
    }

    /** The messages of one conversation at or before [before], newest first. SMS and MMS together. */
    fun messages(thread: Long, before: Long, limit: Int): Page<TandemSmsMessage> {
        val sms = mutableListOf<TandemSmsMessage>()
        var moreSms = false
        val smsSelection = buildString {
            append("${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}")
            if (before > 0) append(" AND ${Telephony.Sms.DATE} <= ?")
        }
        val smsArgs = listOfNotNull(thread.toString(), before.takeIf { it > 0 }?.toString()).toTypedArray()
        resolver.query(Telephony.Sms.CONTENT_URI, SMS_COLUMNS, smsSelection, smsArgs, "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                if (sms.size == limit) {
                    moreSms = true
                    break
                }
                sms += smsMessage(c)
            }
        }

        val mms = mutableListOf<TandemSmsMessage>()
        var moreMms = false
        val mmsSelection = buildString {
            append("${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}")
            // MMS keep their time in seconds.
            if (before > 0) append(" AND ${Telephony.Mms.DATE} <= ?")
        }
        val mmsArgs = listOfNotNull(thread.toString(), before.takeIf { it > 0 }?.let { (it / 1000).toString() }).toTypedArray()
        resolver.query(Telephony.Mms.CONTENT_URI, MMS_COLUMNS, mmsSelection, mmsArgs, "${Telephony.Mms.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                if (mms.size == limit) {
                    moreMms = true
                    break
                }
                mms += mmsMessage(c)
            }
        }

        val both = (sms + mms).sortedByDescending { it.date }
        return Page(both.take(limit), moreSms || moreMms || both.size > limit)
    }

    /** The newest messages of the whole phone, to see what changed since the last look. */
    fun newest(smsCount: Int, mmsCount: Int): List<TandemSmsMessage> {
        val out = mutableListOf<TandemSmsMessage>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, SMS_COLUMNS, "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}", null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c -> while (out.size < smsCount && c.moveToNext()) out += smsMessage(c) }
        val sms = out.size
        resolver.query(
            Telephony.Mms.CONTENT_URI, MMS_COLUMNS, "${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}", null,
            "${Telephony.Mms.DATE} DESC",
        )?.use { c -> while (out.size - sms < mmsCount && c.moveToNext()) out += mmsMessage(c) }
        return out
    }

    /** The SIM the last message of [thread] went over, as Android numbers its subscriptions, or null when it does not say. */
    fun lastSubscription(thread: Long): Int? = runCatching {
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.SUBSCRIPTION_ID), "${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
    }.getOrNull()

    /** Contacts whose name or number contains [query], one row for each number. Empty without access to the contacts. */
    fun contacts(query: String): List<TandemSmsContact> {
        if (query.isBlank() || !SmsAccess.contacts(context)) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val out = LinkedHashMap<String, TandemSmsContact>()
        runCatching {
            resolver.query(uri, columns, null, null, null)?.use { c ->
                while (out.size < MAX_CONTACTS && c.moveToNext()) {
                    val number = c.getString(1)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                    out.putIfAbsent(digits(number), TandemSmsContact(c.getString(0).orEmpty(), number, label))
                }
            }
        }
        return out.values.toList()
    }

    /** The people with a number whose contact id is above [after], by that id, for the search of a computer. Empty without the permission. */
    fun allContacts(after: Long, limit: Int): Page<TandemPhoneContact> {
        if (!SmsAccess.contacts(context)) return Page(emptyList(), false)
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} > ?"
        val order = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} ASC, ${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC"
        val mobile = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        return resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, selection, arrayOf(after.toString()), order)?.use { c ->
            // Read only as far as the page goes: the cursor is a window on the store, not the whole list in memory.
            val rows = generateSequence { if (c.moveToNext()) c else null }.map {
                val type = it.getInt(3)
                ContactBook.Row(
                    contact = it.getLong(0),
                    name = it.getString(1).orEmpty(),
                    number = it.getString(2).orEmpty(),
                    label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, it.getString(4)).toString(),
                    mobile = type == mobile,
                    default = it.getInt(5) != 0,
                )
            }
            val (items, more) = ContactBook.page(rows, limit)
            Page(items, more)
        } ?: Page(emptyList(), false)
    }

    /** One picture of an MMS, made small enough to send: at most [MAX_SIDE] pixels on its long side, as a JPEG. */
    fun picture(part: Long): ByteArray? = runCatching {
        val uri = Uri.parse("content://mms/part/$part")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return@runCatching null
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        var quality = 82
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
            quality -= 15
        } while (bytes.size > MAX_PICTURE_BYTES && quality > 20)
        bytes.takeIf { it.size <= MAX_PICTURE_BYTES }
    }.getOrNull()

    // ---- Rows ------------------------------------------------------------------------------------------------------------------------

    private data class ThreadRow(val id: Long, val date: Long, val recipients: String, val snippet: String)

    private fun summaries(rows: List<ThreadRow>): List<TandemSmsConversation> {
        if (rows.isEmpty()) return emptyList()
        val numbers = addresses(rows.flatMap { ids(it.recipients) }.toSet())
        val unread = unread(rows.map { it.id })
        return rows.map { row ->
            val people = ids(row.recipients).mapNotNull { numbers[it] }
            val last = lastMessage(row.id)
            TandemSmsConversation(
                thread = row.id.toULong(),
                addresses = people,
                name = people.mapNotNull { name(it) }.joinToString(", "),
                snippet = last?.body?.takeIf { it.isNotBlank() } ?: row.snippet.ifBlank {
                    if (last?.pictures?.isNotEmpty() == true) context.getString(R.string.messages_picture) else ""
                },
                date = (last?.date?.toLong() ?: row.date).coerceAtLeast(row.date).toULong(),
                unread = (unread[row.id] ?: 0).toUInt(),
                fromMe = last?.let { it.status != TandemSmsStatus.RECEIVED } ?: false,
            )
        }
    }

    private fun ids(recipients: String) = recipients.split(' ').mapNotNull { it.trim().toLongOrNull() }

    /** The numbers behind the ids a thread keeps for its people. */
    private fun addresses(ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        fun read(selection: String?) = resolver.query(CANONICAL, arrayOf("_id", "address"), selection, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getLong(0) in ids) c.getString(1)?.let { out[c.getLong(0)] = it }
        }
        // Some versions of the store do not take a selection on this table; then the whole of it is read.
        runCatching { read("_id IN (${ids.joinToString(",")})") }.onFailure { runCatching { read(null) } }
        return out
    }

    private fun unread(threads: List<Long>): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        val list = threads.joinToString(",")
        runCatching {
            resolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.THREAD_ID} IN ($list)", null, null)
                ?.use { c -> while (c.moveToNext()) out.merge(c.getLong(0), 1, Int::plus) }
        }
        runCatching {
            resolver.query(Telephony.Mms.Inbox.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID), "${Telephony.Mms.READ} = 0 AND ${Telephony.Mms.THREAD_ID} IN ($list)", null, null)
                ?.use { c -> while (c.moveToNext()) out.merge(c.getLong(0), 1, Int::plus) }
        }
        return out
    }

    /** The newest message of a thread, SMS or MMS, for the line under its name. */
    private fun lastMessage(thread: Long): TandemSmsMessage? = runCatching { messages(thread, 0, 1).items.firstOrNull() }.getOrNull()

    private fun smsMessage(c: Cursor): TandemSmsMessage {
        val status = when (c.getInt(5)) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> TandemSmsStatus.RECEIVED
            Telephony.Sms.MESSAGE_TYPE_SENT -> TandemSmsStatus.SENT
            Telephony.Sms.MESSAGE_TYPE_FAILED -> TandemSmsStatus.FAILED
            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> TandemSmsStatus.SENDING
            else -> TandemSmsStatus.SENT
        }
        return TandemSmsMessage(
            id = c.getLong(0).toULong(),
            mms = false,
            thread = c.getLong(1).toULong(),
            address = if (status == TandemSmsStatus.RECEIVED) c.getString(2).orEmpty() else "",
            body = c.getString(3).orEmpty(),
            date = c.getLong(4).toULong(),
            status = status,
            read = c.getInt(6) != 0,
            pictures = emptyList(),
        )
    }

    private fun mmsMessage(c: Cursor): TandemSmsMessage {
        val id = c.getLong(0)
        val status = when (c.getInt(3)) {
            Telephony.Mms.MESSAGE_BOX_INBOX -> TandemSmsStatus.RECEIVED
            Telephony.Mms.MESSAGE_BOX_OUTBOX -> TandemSmsStatus.SENDING
            Telephony.Mms.MESSAGE_BOX_FAILED -> TandemSmsStatus.FAILED
            else -> TandemSmsStatus.SENT
        }
        val text = StringBuilder()
        val pictures = mutableListOf<ULong>()
        runCatching {
            resolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(id.toString()), null)?.use { p ->
                while (p.moveToNext()) {
                    val type = p.getString(1).orEmpty()
                    when {
                        type == "text/plain" -> p.getString(2)?.takeIf { it.isNotBlank() }?.let {
                            if (text.isNotEmpty()) text.append('\n')
                            text.append(it)
                        }
                        type.startsWith("image/") -> pictures += p.getLong(0).toULong()
                    }
                }
            }
        }
        return TandemSmsMessage(
            id = id.toULong(),
            mms = true,
            thread = c.getLong(1).toULong(),
            address = if (status == TandemSmsStatus.RECEIVED) mmsSender(id).orEmpty() else "",
            body = text.toString(),
            date = (c.getLong(2) * 1000).toULong(),
            status = status,
            read = c.getInt(4) != 0,
            pictures = pictures,
        )
    }

    private fun mmsSender(id: Long): String? = runCatching {
        resolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), "type = $PDU_FROM", null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ---- Names -----------------------------------------------------------------------------------------------------------------------

    private val names = HashMap<String, Pair<String?, Long>>()

    /** The name of a number in the contacts, kept for a few minutes: a page asks for the same people again and again. */
    fun name(number: String): String? {
        if (!SmsAccess.contacts(context)) return null
        val now = System.currentTimeMillis()
        synchronized(names) { names[number]?.takeIf { now - it.second < NAME_TTL }?.let { return it.first } }
        val found = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        synchronized(names) {
            if (names.size > 500) names.clear()
            names[number] = found to now
        }
        return found
    }

    companion object {
        private val THREADS: Uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        private val CANONICAL: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        private val THREAD_COLUMNS = arrayOf(
            Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.RECIPIENT_IDS, Telephony.Threads.MESSAGE_COUNT, Telephony.Threads.SNIPPET,
        )
        private val SMS_COLUMNS = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ,
        )
        private val MMS_COLUMNS = arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ)
        /** The address of an MMS that says who sent it (PduHeaders.FROM). */
        private const val PDU_FROM = 137
        private const val MAX_CONTACTS = 25
        private const val MAX_SIDE = 1024
        private const val MAX_PICTURE_BYTES = 500 * 1024
        private const val NAME_TTL = 5 * 60_000L

        /** The digits of a number, to tell two spellings of the same one apart from two numbers. */
        fun digits(number: String): String = number.filter { it.isDigit() }.takeLast(9)
    }
}
