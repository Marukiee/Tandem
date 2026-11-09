package nl.markmaaktmedia.tandem.watch.sleep

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Re-analyses logs copied from the watch: ./gradlew :watch-sleep:analyze --args="night.tws"
 * With --epochs out.csv it also writes what the model thought per 30 seconds, and --model file.bin compares another model on the same night.
 * With --compare nights.csv (the export of other apps' nights on the phone) it holds each night against the other app's for a row of
 * weights (--boosts 0.6,1.0,1.4 and --transitions 0.4), so the sleep model can be set by real nights instead of by guessing.
 */
fun main(args: Array<String>) {
    val options = args.toMutableList()
    val epochsTo = options.valueOf("--epochs")
    val modelFrom = options.valueOf("--model")
    val compareWith = options.valueOf("--compare")
    val boosts = options.valueOf("--boosts")?.split(',')?.map { it.trim().toFloat() } ?: listOf(SleepModel.WAKE_BOOST)
    val transitions = options.valueOf("--transitions")?.split(',')?.map { it.trim().toFloat() } ?: listOf(SleepModel.TRANSITION_WEIGHT)
    if (compareWith != null) {
        val model = modelFrom?.let { path -> File(path).inputStream().use { SleepModel.read(it) } } ?: SleepModel.load()
        compare(options, readOtherNights(File(compareWith)), model, boosts, transitions)
        return
    }
    if (options.isEmpty()) {
        System.err.println("Gebruik: analyze [--epochs uit.csv] [--model model.bin] <bestand.tws> [meer bestanden]")
        return
    }
    val time = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(ZoneId.systemDefault())
    val model = modelFrom?.let { path -> File(path).inputStream().use { SleepModel.read(it) } } ?: SleepModel.load()
    for (path in options) {
        val night = NightFeatures().also { features -> File(path).inputStream().use { forEachRawRecord(it, features::add) } }.finish()
        val summary = night?.let { SleepAnalyzer.summarize(it, model.stages(it.rows)) }
        if (night != null && epochsTo != null) writeEpochs(File(epochsTo), night, model)
        println(path)
        if (summary == null) {
            println("  geen slaapperiode gevonden")
            continue
        }
        println("  in slaap   ${time.format(Instant.ofEpochMilli(summary.onsetMs))}")
        println("  wakker     ${time.format(Instant.ofEpochMilli(summary.wakeMs))}")
        println("  geslapen   ${summary.sleepMinutes / 60} u ${summary.sleepMinutes % 60} min (${summary.efficiencyPercent}%)")
        println("  wakker tussendoor ${summary.wakeAfterOnsetMinutes} min, ${summary.awakenings} keer")
        summary.stages?.let { println("  licht ${it.light} min, diep ${it.deep} min, rem ${it.rem} min") }
        println("  onrust     ${summary.restlessnessPercent}%")
        println("  hartslag   gem ${summary.meanHeartRate ?: "-"}, laagste ${summary.lowestHeartRate ?: "-"}")
    }
}

private fun MutableList<String>.valueOf(flag: String): String? {
    val at = indexOf(flag)
    if (at < 0 || at + 1 >= size) return null
    removeAt(at)
    return removeAt(at)
}

private fun writeEpochs(file: File, night: Night, model: SleepModel) {
    val logs = model.logProbabilities(night.rows)
    val stages = model.stages(night.rows)
    file.printWriter().use { out ->
        out.println("start_ms,stage,p_wake,p_light,p_deep,p_rem,bpm,moved,worn,motion_share")
        for (i in night.rows.indices) {
            val p = logs[i].joinToString(",") { "%.3f".format(java.util.Locale.ROOT, kotlin.math.exp(it)) }
            out.println("${night.startMs + i * NightFeatures.EPOCH_MS},${stages[i]},$p,${night.heartRates[i] ?: ""},${night.moved[i]},${night.worn[i]},${night.motionShare[i]}")
        }
    }
}

/** One stretch of one stage of another app's night. */
private class OtherRun(val app: String, val nightStart: Long, val nightEnd: Long, val start: Long, val end: Long, val stage: SleepStage?)

private fun readOtherNights(file: File): List<OtherRun> = file.readLines().drop(1).mapNotNull { line ->
    val cell = line.split(',')
    if (cell.size < 6) return@mapNotNull null
    val stage = when (cell[5]) {
        "wake" -> SleepStage.WAKE
        "light" -> SleepStage.LIGHT
        "deep" -> SleepStage.DEEP
        "rem" -> SleepStage.REM
        else -> null
    }
    OtherRun(cell[0], cell[1].toLong(), cell[2].toLong(), cell[3].toLong(), cell[4].toLong(), stage)
}

/** The parts of a night the way Google's Sleep Score names them, from one stage per 30 seconds (as `health_insights::quality_of`). */
private data class Parts(val toSound: Int, val restless: Int, val interrupted: Int, val awakenings: Int, val awake: Int)

private fun partsOf(stages: List<SleepStage?>): Parts {
    val asleep = stages.map { it != null && it != SleepStage.WAKE }
    var onset = asleep.indices.firstOrNull { i -> i + 20 <= asleep.size && (i until i + 20).all { asleep[it] } } ?: asleep.indexOf(true).coerceAtLeast(0)
    val last = asleep.lastIndexOf(true).coerceAtLeast(onset)
    var restless = 0
    var interrupted = 0
    var awakenings = 0
    var i = onset
    while (i <= last) {
        if (!asleep[i]) {
            var j = i
            while (j <= last && !asleep[j]) j++
            val length = j - i
            if (length >= 10) { interrupted += length; awakenings++ } else restless += length
            i = j
        } else {
            i++
        }
    }
    return Parts(onset / 2, restless / 2, interrupted / 2, awakenings, (restless + interrupted) / 2)
}

/**
 * Each watch night against the other app's night it overlaps, for every pair of weights: how often the two agree on awake or asleep per
 * 30 seconds (and Cohen's kappa for that), and the parts of the night as both see them. The weights that bring the parts closest without
 * losing agreement are the ones to set in `SleepModel`.
 */
private fun compare(paths: List<String>, others: List<OtherRun>, model: SleepModel, boosts: List<Float>, transitions: List<Float>) {
    val nights = paths.mapNotNull { path ->
        NightFeatures().also { features -> File(path).inputStream().use { forEachRawRecord(it, features::add) } }.finish()?.let { path to it }
    }
    val byNight = others.groupBy { it.app to it.nightStart }
    println("boost  trans  nights  agree  kappa   wake T/O   to sleep T/O   restless T/O   interrupt T/O   awakenings T/O")
    for (boost in boosts) for (transition in transitions) {
        var agree = 0L
        var count = 0L
        var tWake = 0L
        var oWake = 0L
        var bothWake = 0L
        val tParts = mutableListOf<Parts>()
        val oParts = mutableListOf<Parts>()
        for ((_, night) in nights) {
            val summary = SleepAnalyzer.summarize(night, model.stages(night.rows, boost, transition)) ?: continue
            val start = summary.epochs.first().startMs
            val end = summary.epochs.last().startMs + NightFeatures.EPOCH_MS
            val (_, runs) = byNight.entries.maxByOrNull { (key, _) -> overlap(key.second, runs(byNight, key).maxOf { it.nightEnd }, start, end) } ?: continue
            val otherStart = runs.minOf { it.nightStart }
            val otherEnd = runs.maxOf { it.nightEnd }
            if (overlap(otherStart, otherEnd, start, end) < 0.5) continue
            val from = minOf(start, otherStart)
            val until = maxOf(end, otherEnd)
            val epochs = ((until - from) / NightFeatures.EPOCH_MS).toInt()
            val mine = arrayOfNulls<SleepStage>(epochs)
            summary.epochs.forEach { e -> ((e.startMs - from) / NightFeatures.EPOCH_MS).toInt().takeIf { it in 0 until epochs }?.let { mine[it] = e.stage } }
            val theirs = arrayOfNulls<SleepStage>(epochs)
            runs.forEach { run ->
                var at = run.start
                while (at < run.end) {
                    ((at - from) / NightFeatures.EPOCH_MS).toInt().takeIf { it in 0 until epochs }?.let { theirs[it] = run.stage }
                    at += NightFeatures.EPOCH_MS
                }
            }
            for (k in 0 until epochs) {
                val a = mine[k] ?: continue
                val b = theirs[k] ?: continue
                val wa = a == SleepStage.WAKE
                val wb = b == SleepStage.WAKE
                count++
                if (wa == wb) agree++
                if (wa) tWake++
                if (wb) oWake++
                if (wa && wb) bothWake++
            }
            tParts += partsOf(mine.toList().dropWhile { it == null }.dropLastWhile { it == null })
            oParts += partsOf(theirs.toList().dropWhile { it == null }.dropLastWhile { it == null })
        }
        if (count == 0L) {
            println("%.2f   %.2f   geen nachten die overlappen".format(java.util.Locale.ROOT, boost, transition))
            continue
        }
        val po = agree.toDouble() / count
        val pe = (tWake.toDouble() / count) * (oWake.toDouble() / count) + (1 - tWake.toDouble() / count) * (1 - oWake.toDouble() / count)
        val kappa = if (pe < 1) (po - pe) / (1 - pe) else 0.0
        fun mean(parts: List<Parts>, pick: (Parts) -> Int) = parts.map(pick).average()
        println(
            "%.2f   %.2f   %6d  %5.1f%%  %.3f  %4.0f/%-4.0f  %5.1f/%-5.1f    %5.1f/%-5.1f    %5.1f/%-5.1f     %4.1f/%-4.1f".format(
                java.util.Locale.ROOT, boost, transition, tParts.size, po * 100, kappa,
                mean(tParts) { it.awake }, mean(oParts) { it.awake }, mean(tParts) { it.toSound }, mean(oParts) { it.toSound },
                mean(tParts) { it.restless }, mean(oParts) { it.restless }, mean(tParts) { it.interrupted }, mean(oParts) { it.interrupted },
                mean(tParts) { it.awakenings }, mean(oParts) { it.awakenings },
            ),
        )
    }
}

private fun runs(byNight: Map<Pair<String, Long>, List<OtherRun>>, key: Pair<String, Long>) = byNight.getValue(key)

private fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double {
    val shared = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
    val shortest = minOf(aEnd - aStart, bEnd - bStart)
    return if (shortest <= 0) 0.0 else shared.toDouble() / shortest
}
