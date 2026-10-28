package nl.markmaaktmedia.tandem.audio

/**
 * What makes a hole or a correction in 16 bit sound inaudible. Every hard edge in a wave is a click: a hole filled with silence
 * starts and ends with one, and throwing a piece of sound away cuts the wave in two. These bend the sound instead. Pure, so the
 * tests can hear it without a speaker.
 */
object PcmSmoothing {
    /**
     * Sound for a hole of [bytes]: the packet before it again, fading to nothing, so the sound dies away instead of stopping dead.
     * Longer than that packet, it is repeated; the fade runs over the whole hole.
     */
    fun conceal(last: ByteArray, bytes: Int, frameBytes: Int): ByteArray {
        val out = ByteArray(bytes / frameBytes * frameBytes)
        if (last.isEmpty() || out.isEmpty()) return out
        val samples = out.size / 2
        val lastSamples = last.size / 2
        for (i in 0 until samples) {
            val gain = 1f - i.toFloat() / samples
            write(out, i, (read(last, i % lastSamples) * gain).toInt())
        }
        return out
    }

    /** Fades in the first [frames] frames of [pcm], in place: sound coming back after a hole does not start with a jump. */
    fun fadeIn(pcm: ByteArray, frames: Int, frameBytes: Int) {
        val channels = frameBytes / 2
        val total = minOf(frames, pcm.size / frameBytes)
        for (frame in 0 until total) {
            val gain = (frame + 1).toFloat() / (total + 1)
            for (channel in 0 until channels) {
                val i = frame * channels + channel
                write(pcm, i, (read(pcm, i) * gain).toInt())
            }
        }
    }

    /**
     * [pcm] played [drop] frames shorter (longer for a negative [drop]), by reading it a little faster or slower from its first frame
     * to its last. Both ends stay where they were, so it joins the packets around it without an edge; a few percent cannot be heard.
     */
    fun squeeze(pcm: ByteArray, drop: Int, frameBytes: Int): ByteArray {
        val channels = frameBytes / 2
        val frames = pcm.size / frameBytes
        val outFrames = frames - drop
        if (drop == 0 || frames < 2 || outFrames < 2) return pcm
        val out = ByteArray(outFrames * frameBytes)
        val step = (frames - 1).toDouble() / (outFrames - 1)
        for (frame in 0 until outFrames) {
            val at = frame * step
            val a = at.toInt().coerceAtMost(frames - 1)
            val b = (a + 1).coerceAtMost(frames - 1)
            val t = (at - a).toFloat()
            for (channel in 0 until channels) {
                val value = read(pcm, a * channels + channel) * (1 - t) + read(pcm, b * channels + channel) * t
                write(out, frame * channels + channel, value.toInt())
            }
        }
        return out
    }

    private fun read(pcm: ByteArray, sample: Int): Int =
        (pcm[sample * 2].toInt() and 0xFF) or (pcm[sample * 2 + 1].toInt() shl 8)

    private fun write(pcm: ByteArray, sample: Int, value: Int) {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm[sample * 2] = (v and 0xFF).toByte()
        pcm[sample * 2 + 1] = (v shr 8).toByte()
    }
}
