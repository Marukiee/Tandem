package nl.markmaaktmedia.tandem.watch.sleep

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Re-analyses logs copied from the watch: ./gradlew :watch-sleep:analyze --args="night.tws"
 * With --epochs out.csv it also writes what the model thought per 30 seconds, and --model file.bin compares another model on the same night.
 */
fun main(args: Array<String>) {
    val options = args.toMutableList()
    val epochsTo = options.valueOf("--epochs")
    val modelFrom = options.valueOf("--model")
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
