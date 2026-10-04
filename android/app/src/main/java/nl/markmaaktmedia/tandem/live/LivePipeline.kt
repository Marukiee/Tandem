package nl.markmaaktmedia.tandem.live

/** What a source (the screen or a camera) tells whoever is sending its pictures to the Mac. */
interface PipelineEvents {
    /** The capture runs and the size is known: the request can be answered. */
    fun onReady(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int)

    /** The size or the way up changed while running (the phone turned). Comes before the first frame of the new shape. */
    fun onFormat(width: Int, height: Int, rotation: Int)

    /** These two come on the encoder's thread and must not wait for anything. */
    fun onConfig(data: ByteArray)
    fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean)

    /** The capture could not start (before [onReady]) or broke (after it). */
    fun onFailed(reason: Failure)

    /** The system ended the capture: the person stopped sharing from the status bar, or the camera was taken away. */
    fun onEnded()

    enum class Failure { NO_ENCODER, NO_CAPTURE, CAMERA_BUSY, OTHER }
}

/** A capture feeding an encoder. All calls come on the main thread. */
interface LivePipeline {
    fun start()
    fun stop()
    fun requestKeyframe()
    fun setBitrate(bitsPerSecond: Int)

    /** Frames the encoder has produced and their size, for the notification and the tests. */
    val framesOut: Long
    val bytesOut: Long
}
