package nl.markmaaktmedia.tandem.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Debug builds only. Proves the encoder on a device that has no Mac to talk to: draws a moving test picture on the
 * encoder's surface, collects what comes out, checks it against the rules of the core (Annex B, parameter sets on every
 * keyframe) and writes it in the format of the Mac's debug harness (`FrameDump`), so the very same bytes can be played
 * into the Mac's decoder.
 *
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.live.LiveSelfCheck \
 *         --ei width 720 --ei height 1280 --ei frames 90
 *     adb exec-out run-as nl.markmaaktmedia.tandem.debug cat files/live-selfcheck.tfd > selfcheck.tfd
 */
class LiveSelfCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val width = intent.getIntExtra("width", 720)
        val height = intent.getIntExtra("height", 1280)
        val frames = intent.getIntExtra("frames", 90)
        val pending = goAsync()
        thread(name = "live-selfcheck") {
            try {
                run(context, width, height, frames)
            } finally {
                pending.finish()
            }
        }
    }

    private class Collected(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    private fun run(context: Context, width: Int, height: Int, frames: Int) {
        val collected = ArrayList<Collected>()
        var config: ByteArray? = null
        var failure: Throwable? = null
        val plan = LivePlan.plan(width, height, LivePlan.Limits(0, 0, 30, 0))
        val encoder = LiveEncoder(plan.width, plan.height, plan.fps, plan.bitrate, repeatWhenStill = false, sink = object : LiveEncoder.Sink {
            override fun onConfig(data: ByteArray) {
                config = data
            }

            override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
                synchronized(collected) { collected += Collected(data, ptsUs, keyframe) }
            }

            override fun onError(error: Throwable) {
                failure = error
            }
        })
        encoder.start()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val started = System.nanoTime()
        for (index in 0 until frames) {
            val canvas = encoder.surface.lockHardwareCanvas()
            try {
                draw(canvas, plan.width, plan.height, index, frames, paint)
            } finally {
                encoder.surface.unlockCanvasAndPost(canvas)
            }
            // 30 frames a second, as a screen would.
            val due = started + (index + 1) * 1_000_000_000L / plan.fps
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
        // Give the encoder the time it needs to drain.
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && synchronized(collected) { collected.size } < frames) Thread.sleep(50)
        encoder.stop()
        CountDownLatch(1).await(200, TimeUnit.MILLISECONDS)

        // What the core does with a keyframe that comes without parameter sets (some encoders keep them apart):
        // the last ones are put in front. Anything else is refused there, so this checks the same.
        val list = synchronized(collected) { collected.toList() }.map { frame ->
            val given = config
            if (frame.keyframe && !AnnexBScan.hasParameterSets(frame.data) && given != null) {
                Collected(given + frame.data, frame.ptsUs, true)
            } else {
                frame
            }
        }
        val problems = ArrayList<String>()
        if (failure != null) problems += "encoder error: $failure"
        if (list.isEmpty()) problems += "no frames"
        if (list.firstOrNull()?.keyframe != true) problems += "the first frame is not a keyframe"
        list.forEachIndexed { i, frame ->
            if (!AnnexBScan.startsWithStartCode(frame.data)) problems += "frame $i does not start with a start code"
            if (frame.keyframe && !AnnexBScan.hasParameterSets(frame.data)) problems += "keyframe $i has no parameter sets"
            if (frame.keyframe && !AnnexBScan.hasKeyframe(frame.data)) problems += "keyframe $i has no IDR slice"
            if (i > 0 && frame.ptsUs < list[i - 1].ptsUs) problems += "frame $i goes back in time"
        }
        val file = File(context.filesDir, "live-selfcheck.tfd")
        file.outputStream().use { out ->
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeBytes("TFD1")
                writeShort(plan.width)
                writeShort(plan.height)
                writeShort(0)
                val base = list.firstOrNull()?.ptsUs ?: 0
                list.forEach {
                    writeInt(it.data.size)
                    writeByte(if (it.keyframe) 1 else 0)
                    writeLong(it.ptsUs - base)
                    write(it.data)
                }
            }
            out.write(bytes.toByteArray())
        }
        val report = buildString {
            appendLine(if (problems.isEmpty()) "OK" else "PROBLEMS")
            appendLine("size ${plan.width}x${plan.height} at ${plan.fps} fps, target ${LivePlan.megabits(plan.bitrate)}")
            appendLine("frames ${list.size}, keyframes ${list.count { it.keyframe }}, bytes ${list.sumOf { it.data.size }}")
            appendLine("config apart from the frames: ${config?.size ?: 0} bytes, first frame ${list.firstOrNull()?.data?.size ?: 0} bytes")
            problems.take(20).forEach { appendLine(it) }
        }
        File(context.filesDir, "live-selfcheck.txt").writeText(report)
        Log.i("TandemLive", "selfcheck:\n$report")
    }

    private fun draw(canvas: Canvas, width: Int, height: Int, index: Int, total: Int, paint: Paint) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, Color.rgb(41, 43, 115), Color.rgb(242, 140, 179), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // The top edge is red and the left edge is green, as in the Mac's own test stream: a wrong turn shows at once.
        paint.color = Color.rgb(230, 26, 26)
        canvas.drawRect(0f, 0f, w, 28f, paint)
        paint.color = Color.rgb(26, 204, 77)
        canvas.drawRect(0f, 0f, 28f, h, paint)
        val t = index.toFloat() / total
        paint.color = Color.argb(235, 255, 255, 255)
        val x = w * (0.15f + 0.55f * t)
        val y = h * (0.2f + 0.5f * Math.abs(Math.sin((t * 6).toDouble())).toFloat())
        canvas.drawCircle(x + w * 0.1f, y + w * 0.1f, w * 0.1f, paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = w * 0.12f
        canvas.drawText("TOP  $index", w * 0.08f, w * 0.2f, paint)
    }
}
