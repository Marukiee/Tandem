package nl.markmaaktmedia.tandem.watch.sleep

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model is only worth something if this code calculates exactly what training saw. training/export.py writes a made up night
 * with its features and outcome as Python calculates them. When this fails, the app and the training have grown apart.
 */
class SleepModelTest {
    private class Golden(val rows: Int, val columns: Int, val features: FloatArray, val logs: FloatArray, val path: IntArray)

    private fun golden(): Golden {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/golden_night.bin")).use { DataInputStream(it).readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rows = data.int
        val columns = data.int
        val features = FloatArray(rows * columns) { data.float }
        val logs = FloatArray(rows * 4) { data.float }
        val path = IntArray(rows) { data.int }
        return Golden(rows, columns, features, logs, path)
    }

    private fun night(): Night {
        val builder = NightFeatures()
        requireNotNull(javaClass.getResourceAsStream("/golden_night.tws")).use { forEachRawRecord(it, builder::add) }
        return assertNotNull(builder.finish())
    }

    @Test
    fun `features match training`() {
        val golden = golden()
        val night = night()
        assertEquals(golden.rows, night.rows.size)
        assertEquals(golden.columns, night.rows[0].size)
        for (t in 0 until golden.rows) for (c in 0 until golden.columns) {
            val expected = golden.features[t * golden.columns + c]
            assertTrue(abs(night.rows[t][c] - expected) < 2e-3f, "row $t column $c: ${night.rows[t][c]}, training has $expected")
        }
    }

    @Test
    fun `the network calculates what training calculates`() {
        val golden = golden()
        val model = SleepModel.load()
        val rows = Array(golden.rows) { t -> FloatArray(golden.columns) { golden.features[t * golden.columns + it] } }
        val logs = model.logProbabilities(rows)
        for (t in 0 until golden.rows) for (c in 0 until 4) {
            assertTrue(abs(logs[t][c] - golden.logs[t * 4 + c]) < 2e-2f, "epoch $t stage $c: ${logs[t][c]}, training has ${golden.logs[t * 4 + c]}")
        }
        val path = model.stages(rows)
        val differ = path.indices.count { path[it].ordinal != golden.path[it] }
        assertTrue(differ <= golden.rows / 100, "$differ epochs differ")
    }

    @Test
    fun `a whole recording runs from file to summary`() {
        val summary = requireNotNull(javaClass.getResourceAsStream("/golden_night.tws")).use { SleepAnalyzer.analyze(it) }
        assertNotNull(summary)
        assertTrue(summary.sleepMinutes in 60..240, "slept ${summary.sleepMinutes}")
    }

    @Test
    fun `too little data gives no result`() {
        assertNull(SleepAnalyzer.analyze(emptyList()))
        assertNull(SleepAnalyzer.analyze(listOf(RawRecord.Accel(1000, 0, 0, 2048))))
        val short = (0 until 600).map { RawRecord.Accel(1000L + it * 1000, 0, 0, 2048) }
        assertNull(SleepAnalyzer.analyze(short))
    }

    @Test
    fun `a wild heart value and a stale first one do not count`() {
        val builder = NightFeatures()
        builder.add(RawRecord.HeartRate(1, 70, 3))
        for (s in 0 until 3600) {
            builder.add(RawRecord.Accel(1_000_000L + s * 1000, 0, 0, 2048))
            if (s % 2 == 0) builder.add(RawRecord.HeartRate(1_000_000L + s * 1000, if (s == 1000) 200 else 60, 3))
        }
        builder.add(RawRecord.HeartRate(1_000_500, 0, 0))
        val night = assertNotNull(builder.finish())
        assertEquals(setOf(60), night.heartRates.filterNotNull().toSet())
    }

    @Test
    fun `off the wrist marks follow the sensor`() {
        val builder = NightFeatures()
        for (s in 0 until 3600) builder.add(RawRecord.Accel(s * 1000L, 0, 0, 2048))
        builder.add(RawRecord.Mark(600_000, MARK_OFF))
        builder.add(RawRecord.Mark(1_200_000, MARK_WORN))
        builder.add(RawRecord.Mark(3_000_000, MARK_OFF))
        val night = assertNotNull(builder.finish())
        assertTrue(night.worn[0])
        assertEquals(false, night.worn[25])
        assertEquals(true, night.worn[60])
        assertEquals(false, night.worn[110])
    }
}
