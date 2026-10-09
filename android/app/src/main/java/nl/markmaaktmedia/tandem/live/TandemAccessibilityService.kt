package nl.markmaaktmedia.tandem.live

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import nl.markmaaktmedia.tandem.graph

/**
 * What lets a computer click and type on this phone while it shows the screen. Android gives an app no other way to touch
 * the screen, so this is a service the person turns on in the system settings, and it does nothing at all until a
 * computer that was allowed to control the phone sends input. It reads nothing from the screen except the field that is
 * being typed in.
 */
class TandemAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching { graph.live.accessibilityChanged() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { graph.live.accessibilityChanged() }
        super.onDestroy()
    }

    /** A press at one place for [durationMs]: a tap when it is short, a long press when it is long. */
    fun press(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y) }
        dispatch(path, durationMs)
    }

    /** A finger that goes from one place to another. */
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, done: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        dispatch(path, durationMs, done)
    }

    /** [done] is told when the gesture has been played or was cancelled, so the next one is not dispatched on top of it. */
    private fun dispatch(path: Path, durationMs: Long, done: (() -> Unit)? = null) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(10, 3000))
        val callback = done?.let {
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = it()
                override fun onCancelled(gestureDescription: GestureDescription?) = it()
            }
        }
        val started = runCatching { dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, null) }.getOrDefault(false)
        if (!started) done?.invoke()
    }

    // ---- The pointer of a computer's mouse -------------------------------------------------------------------------------------

    private var cursor: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    /** Shows the pointer of the mouse that is used on this phone at [x], [y] (pixels), or moves it there. Call on the main thread. */
    fun showCursor(x: Float, y: Float) {
        val windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = cursor ?: CursorView(this).also { cursor = it }
        val params = cursorParams ?: WindowManager.LayoutParams(
            view.size, view.size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // It never takes a touch or the focus: the touches it stands for are played by this service, under it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }.also { cursorParams = it }
        params.x = x.toInt()
        params.y = y.toInt()
        runCatching { if (view.isAttachedToWindow) windows.updateViewLayout(view, params) else windows.addView(view, params) }
    }

    fun hideCursor() {
        val view = cursor ?: return
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
        cursor = null
        cursorParams = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideCursor()
        return super.onUnbind(intent)
    }

    /** A small arrow, white with a dark edge, whose tip is the top left corner of the view. */
    private class CursorView(context: Context) : View(context) {
        val size = (context.resources.displayMetrics.density * 28).toInt()
        private val arrow = Path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6000000"); style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.4f
            strokeJoin = Paint.Join.ROUND
        }

        init {
            val d = context.resources.displayMetrics.density
            arrow.moveTo(1f * d, 1f * d)
            arrow.lineTo(1f * d, 19f * d)
            arrow.lineTo(5.2f * d, 15f * d)
            arrow.lineTo(8.6f * d, 23f * d)
            arrow.lineTo(11.6f * d, 21.6f * d)
            arrow.lineTo(8.2f * d, 13.8f * d)
            arrow.lineTo(14f * d, 13.8f * d)
            arrow.close()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, edge)
        }
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Adds [text] at the end of the field that has the keyboard. False when there is none to type in. */
    fun type(text: String): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val now = field.text?.toString().orEmpty()
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, now + text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Takes the last character off the field that has the keyboard. */
    fun backspace(): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val now = field.text?.toString().orEmpty()
        if (now.isEmpty()) return false
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, now.dropLast(1)) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    companion object {
        @Volatile
        var instance: TandemAccessibilityService? = null
            private set

        /** Whether the person has turned the service on and the system is running it. */
        val running: Boolean get() = instance != null
    }
}
