package nl.markmaaktmedia.tandem.watch.sleep

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Re-analyses logs copied from the watch: ./gradlew :watch-sleep:analyze --args="night.tws" */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Gebruik: analyze <bestand.tws> [meer bestanden]")
        return
    }
    val time = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(ZoneId.systemDefault())
    for (path in args) {
        val summary = File(path).inputStream().use { SleepAnalyzer.analyze(it) }
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
