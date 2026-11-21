package nl.markmaaktmedia.tandem.watch.protocol

/**
 * What the watch app did, counted per hour so a day on the wrist can say where the battery went. Only what Tandem itself does: the
 * system's own wake-ups are not visible to an app, so the battery numbers next to these are what ties them to the whole watch.
 */
enum class Tick(val key: String) {
    /** A delivery of the slow wrist movement. Each one wakes the processor, which is what makes it a wake moment. */
    ACCEL_BATCH("accel_batch"),
    STEP_BATCH("step_batch"),
    /** A measurement of our own, and what Health Services handed over. */
    HEART_OWN("heart_own"),
    PASSIVE_HEART("passive_heart"),
    PASSIVE_STEPS("passive_steps"),
    BLE_IN("ble_in"),
    BLE_OUT("ble_out"),
    BYTES_IN("bytes_in"),
    BYTES_OUT("bytes_out"),
    CONNECT("connect"),
    DROP("drop"),
    SCAN("scan"),
    ALARM("alarm"),
    SCREEN_ON("screen_on"),
    PRIORITY("priority"),
    ;

    /** Counts as the processor having been woken for it, in [PowerRecord.wakes]. */
    val wakes: Boolean get() = this in WAKE_TICKS

    private companion object {
        val WAKE_TICKS = setOf(ACCEL_BATCH, STEP_BATCH, HEART_OWN, PASSIVE_HEART, PASSIVE_STEPS, BLE_IN, ALARM, SCREEN_ON)
    }
}

/** A thing that was on for a while. The tally adds up how long, and a span that is open when a period ends goes on in the next. */
enum class Span(val key: String) {
    LINK("link"),
    SCAN_FAST("scan_fast"),
    SCAN_BALANCED("scan_balanced"),
    SCAN_LOW("scan_low"),
    LOW_PRIORITY("low_prio"),
    ACCEL("accel"),
    HEART_OWN("heart_own"),
    HEART_LIVE("heart_live"),
    RECORDING("recording"),
    SCREEN("screen"),
    CHARGING("charging"),
    WORN("worn"),
}

/** What was counted in one period. */
data class Tally(
    val counts: Map<Tick, Long>,
    val spansMs: Map<Span, Long>,
    val messagesIn: Map<String, Int>,
    val messagesOut: Map<String, Int>,
)

/** Thread safe: the link, the sensors and the screen all report here from their own threads, and each call only adds to a number. */
class PowerTally {
    private val counts = LongArray(Tick.entries.size)
    private val spans = LongArray(Span.entries.size)
    private val since = LongArray(Span.entries.size) { CLOSED }
    private val kindsIn = HashMap<String, Int>()
    private val kindsOut = HashMap<String, Int>()

    @Synchronized
    fun count(tick: Tick, n: Long = 1) {
        counts[tick.ordinal] += n
    }

    /** One message, by its kind, so an hour with many of one shows which. */
    @Synchronized
    fun message(inbound: Boolean, kind: String) {
        (if (inbound) kindsIn else kindsOut).merge(kind, 1, Int::plus)
    }

    @Synchronized
    fun open(span: Span, nowMs: Long) {
        if (since[span.ordinal] == CLOSED) since[span.ordinal] = nowMs
    }

    @Synchronized
    fun close(span: Span, nowMs: Long) {
        val from = since[span.ordinal]
        if (from == CLOSED) return
        spans[span.ordinal] += (nowMs - from).coerceAtLeast(0)
        since[span.ordinal] = CLOSED
    }

    /** [open] when [on], else [close]: the usual shape of "this is now so, or no longer". */
    fun set(span: Span, on: Boolean, nowMs: Long) = if (on) open(span, nowMs) else close(span, nowMs)

    @Synchronized
    fun isOpen(span: Span): Boolean = since[span.ordinal] != CLOSED

    /** What has been counted so far, without ending the period. */
    @Synchronized
    fun peek(nowMs: Long): Tally = snapshot(nowMs)

    /** What was counted since the last cut. The next period starts empty, and what is on at this moment stays on. */
    @Synchronized
    fun cut(nowMs: Long): Tally {
        val done = snapshot(nowMs)
        counts.fill(0)
        spans.fill(0)
        for (i in since.indices) if (since[i] != CLOSED) since[i] = nowMs
        kindsIn.clear()
        kindsOut.clear()
        return done
    }

    private fun snapshot(nowMs: Long): Tally = Tally(
        counts = Tick.entries.associateWith { counts[it.ordinal] }.filterValues { it != 0L },
        spansMs = Span.entries.associateWith { span ->
            spans[span.ordinal] + if (since[span.ordinal] == CLOSED) 0 else (nowMs - since[span.ordinal]).coerceAtLeast(0)
        }.filterValues { it != 0L },
        messagesIn = HashMap(kindsIn),
        messagesOut = HashMap(kindsOut),
    )

    private companion object {
        const val CLOSED = -1L
    }
}

/**
 * One line of the power log: a stretch of time with the battery at both ends and what the app did in it. The stretch is mostly an hour,
 * but it is cut where the next event happens after the hour changed, so [endMs] says how long it really was.
 */
data class PowerRecord(
    val startMs: Long,
    val endMs: Long,
    val levelStart: Int?,
    val levelEnd: Int?,
    /** What the battery says it holds, in microampere hours. Finer than the percentage, but some watches update it only now and then. */
    val chargeStartUah: Long?,
    val chargeEndUah: Long?,
    val tally: Tally,
) {
    val seconds: Long get() = (endMs - startMs) / 1000

    fun count(tick: Tick): Long = tally.counts[tick] ?: 0

    fun spanSeconds(span: Span): Long = (tally.spansMs[span] ?: 0) / 1000

    /** The processor woken by something of ours, an estimate: the system wakes it for its own reasons too. */
    val wakes: Long get() = Tick.entries.filter { it.wakes }.sumOf { count(it) }

    /** How much the battery lost over the stretch, in microampere hours. Null when it was not read, or when it went up (the charger). */
    val drainUah: Long?
        get() {
            val from = chargeStartUah ?: return null
            val to = chargeEndUah ?: return null
            return (from - to).takeIf { it >= 0 && spanSeconds(Span.CHARGING) * 2 < seconds }
        }

    /** Percent of the battery in an hour, so stretches of another length compare. Null as [drainUah]. */
    val percentPerHour: Double?
        get() {
            val from = levelStart ?: return null
            val to = levelEnd ?: return null
            if (seconds <= 0 || to > from || spanSeconds(Span.CHARGING) * 2 >= seconds) return null
            return (from - to) * 3600.0 / seconds
        }

    fun toJson(): String = buildString {
        append("{\"t0\":").append(startMs).append(",\"t1\":").append(endMs)
        levelStart?.let { append(",\"lv0\":").append(it) }
        levelEnd?.let { append(",\"lv1\":").append(it) }
        chargeStartUah?.let { append(",\"uah0\":").append(it) }
        chargeEndUah?.let { append(",\"uah1\":").append(it) }
        // Whole seconds: the log is for reading a day, and a tenth of a second says nothing about it.
        obj("n", tally.counts.mapKeys { it.key.key })
        obj("s", tally.spansMs.mapValues { it.value / 1000 }.filterValues { it != 0L }.mapKeys { it.key.key })
        obj("in", tally.messagesIn)
        obj("out", tally.messagesOut)
        append('}')
    }

    private fun StringBuilder.obj(name: String, values: Map<String, Number>) {
        if (values.isEmpty()) return
        append(",\"").append(name).append("\":{")
        values.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
            if (i > 0) append(',')
            append('"').append(key).append("\":").append(value)
        }
        append('}')
    }

    companion object {
        /** Null for anything that is not a line this wrote, so a damaged file loses a line and not the whole day. */
        fun parse(line: String): PowerRecord? = runCatching { fromJson(FlatJson.parse(line)) }.getOrNull()

        private fun fromJson(json: Map<String, Any>): PowerRecord {
            fun long(key: String): Long? = (json[key] as? Long)

            @Suppress("UNCHECKED_CAST")
            fun group(key: String): Map<String, Long> = (json[key] as? Map<String, Long>).orEmpty()
            val counts = group("n").mapNotNull { (key, value) -> Tick.entries.firstOrNull { it.key == key }?.let { it to value } }.toMap()
            val spans = group("s").mapNotNull { (key, value) -> Span.entries.firstOrNull { it.key == key }?.let { it to value * 1000 } }.toMap()
            return PowerRecord(
                startMs = long("t0")!!,
                endMs = long("t1")!!,
                levelStart = long("lv0")?.toInt(),
                levelEnd = long("lv1")?.toInt(),
                chargeStartUah = long("uah0"),
                chargeEndUah = long("uah1"),
                tally = Tally(counts, spans, group("in").mapValues { it.value.toInt() }, group("out").mapValues { it.value.toInt() }),
            )
        }
    }
}

/** Totals over a number of stretches, for the top of the page that shows the log. */
data class PowerSummary(
    val seconds: Long,
    /** What the battery lost, over the stretches it could be read for and that were not on the charger. */
    val drainUah: Long,
    /** The time of exactly those stretches, so [drainUah] can be said per hour. */
    val drainSeconds: Long,
    val wakes: Long,
    val screenSeconds: Long,
    val scanSeconds: Long,
    val linkSeconds: Long,
    val lowPrioritySeconds: Long,
    val messagesIn: Map<String, Int>,
    val messagesOut: Map<String, Int>,
) {
    /** Null when the battery was not read in any stretch. */
    val uahPerHour: Long? get() = if (drainSeconds <= 0) null else drainUah * 3600 / drainSeconds
}

fun summarize(records: List<PowerRecord>): PowerSummary {
    var drain = 0L
    var drainSeconds = 0L
    val kindsIn = HashMap<String, Int>()
    val kindsOut = HashMap<String, Int>()
    for (r in records) {
        r.drainUah?.let {
            drain += it
            drainSeconds += r.seconds
        }
        r.tally.messagesIn.forEach { (kind, n) -> kindsIn.merge(kind, n, Int::plus) }
        r.tally.messagesOut.forEach { (kind, n) -> kindsOut.merge(kind, n, Int::plus) }
    }
    return PowerSummary(
        seconds = records.sumOf { it.seconds },
        drainUah = drain,
        drainSeconds = drainSeconds,
        wakes = records.sumOf { it.wakes },
        screenSeconds = records.sumOf { it.spanSeconds(Span.SCREEN) },
        scanSeconds = records.sumOf { it.spanSeconds(Span.SCAN_FAST) + it.spanSeconds(Span.SCAN_BALANCED) + it.spanSeconds(Span.SCAN_LOW) },
        linkSeconds = records.sumOf { it.spanSeconds(Span.LINK) },
        lowPrioritySeconds = records.sumOf { it.spanSeconds(Span.LOW_PRIORITY) },
        messagesIn = kindsIn,
        messagesOut = kindsOut,
    )
}

/** The one shape the log has: an object of numbers and of objects of numbers. Anything else is not read. */
internal object FlatJson {
    fun parse(text: String): Map<String, Any> {
        val reader = Reader(text.trim())
        val result = reader.obj(nested = true)
        require(reader.done()) { "text after the object" }
        return result
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun done() = at == text.length

        fun obj(nested: Boolean): Map<String, Any> {
            expect('{')
            val result = LinkedHashMap<String, Any>()
            if (peek() == '}') {
                at++
                return result
            }
            while (true) {
                val key = string()
                expect(':')
                result[key] = if (peek() == '{') {
                    require(nested) { "objects go one deep" }
                    obj(nested = false)
                } else {
                    number()
                }
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val end = text.indexOf('"', at)
            require(end >= 0) { "unterminated string" }
            val value = text.substring(at, end)
            require('\\' !in value) { "escapes are not used" }
            at = end + 1
            return value
        }

        private fun number(): Long {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && text[at].isDigit()) at++
            return text.substring(start, at).toLong()
        }

        private fun peek(): Char = if (at < text.length) text[at] else throw IllegalArgumentException("unexpected end")

        private fun next(): Char = peek().also { at++ }

        private fun expect(c: Char) = require(next() == c) { "expected $c" }
    }
}

/** When a stretch of the log ends: at the hour of the local clock, so the lines of a day read as hours. */
object PowerClock {
    const val HOUR_MS = 3_600_000L

    fun hourIndex(ms: Long, offsetMs: Long): Long = Math.floorDiv(ms + offsetMs, HOUR_MS)

    /** The hour has changed since [lastCutMs]. [offsetMs] is the offset of the local clock from UTC. */
    fun due(lastCutMs: Long, nowMs: Long, offsetMs: Long): Boolean = hourIndex(nowMs, offsetMs) != hourIndex(lastCutMs, offsetMs)
}

/**
 * Counts deliveries from a sensor that hands over many values at once. The values of one delivery come within a moment of each other,
 * so a gap since the last one means a new delivery, and so a moment the processor was woken for.
 */
class DeliveryCounter(private val gapMs: Long = 1_000L) {
    private var last = Long.MIN_VALUE

    /** True when this value starts a new delivery. */
    fun onValue(nowMs: Long): Boolean {
        val fresh = last == Long.MIN_VALUE || nowMs - last >= gapMs
        last = nowMs
        return fresh
    }
}
