package nl.markmaaktmedia.tandem.watch.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The sound a lost watch makes. Not a ringtone: a melody is pleasant but hard to point at, and a soft one does not carry through a room.
 * Short rising chirps are easy to point at: a sharp start tells the ear where a sound comes from, and between 2 and 4 kHz a small speaker
 * is at its loudest and an ear at its most sensitive. Three chirps and a pause, so it is not mistaken for the alarm or timer of another device.
 */
object FindTone {
    const val SAMPLE_RATE = 48_000

    private const val CHIRPS = 3
    private const val CHIRP_MS = 110
    private const val GAP_MS = 60
    private const val PAUSE_MS = 420
    private const val LOW_HZ = 2_000.0
    private const val HIGH_HZ = 3_600.0

    /** Fast enough to be sharp, slow enough not to click. */
    private const val ATTACK_MS = 4
    private const val RELEASE_MS = 25

    /** Pushes the sine toward a square: the overtones make it sound louder from the same small speaker, and harder to miss. */
    private const val DRIVE = 2.5
    private const val PEAK = 0.95

    /** One round of chirps and the pause after them, as 16-bit mono samples that can be played again and again without a click. */
    fun cycle(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val chirp = frames(CHIRP_MS, sampleRate)
        val gap = frames(GAP_MS, sampleRate)
        val attack = frames(ATTACK_MS, sampleRate)
        val release = frames(RELEASE_MS, sampleRate)
        val out = ShortArray(CHIRPS * chirp + (CHIRPS - 1) * gap + frames(PAUSE_MS, sampleRate))
        val norm = tanh(DRIVE)
        val octaves = ln(HIGH_HZ / LOW_HZ)
        for (c in 0 until CHIRPS) {
            val start = c * (chirp + gap)
            var phase = 0.0
            for (i in 0 until chirp) {
                // Up by the same musical step every moment, which the ear hears as one straight sweep.
                phase += 2 * PI * LOW_HZ * exp(octaves * i / chirp) / sampleRate
                val envelope = when {
                    i < attack -> 0.5 - 0.5 * cos(PI * i / attack)
                    i >= chirp - release -> 0.5 - 0.5 * cos(PI * (chirp - i) / release)
                    else -> 1.0
                }
                out[start + i] = (PEAK * envelope * tanh(DRIVE * sin(phase)) / norm * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private fun frames(ms: Int, sampleRate: Int) = ms * sampleRate / 1000
}
