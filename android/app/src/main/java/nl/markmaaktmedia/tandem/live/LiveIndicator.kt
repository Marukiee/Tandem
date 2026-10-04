package nl.markmaaktmedia.tandem.live

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small pill at the top of the screen for as long as something is being shown: a red dot, who is looking, and a way
 * to stop. Android already draws its own chip for screen sharing and a dot for the camera; this one says which Mac, and
 * stays out of the way. It needs the permission to draw over other apps, which the person gives in the system settings,
 * and when it is not given the notification is all there is.
 */
class LiveIndicator(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show(text: String, onStop: () -> Unit) {
        if (!canShow()) return
        hide()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(8), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(235, 24, 24, 28))
            }
            elevation = dp(6).toFloat()
        }
        pill.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(232, 97, 60))
            }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(9) }
        })
        pill.addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
        })
        pill.addView(TextView(context).apply {
            this.text = context.getString(nl.markmaaktmedia.tandem.R.string.live_stop)
            setTextColor(Color.rgb(255, 176, 160))
            textSize = 13f
            setPadding(dp(14), dp(4), dp(10), dp(4))
            setOnClickListener { onStop() }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
        }
        runCatching {
            windows.addView(pill, params)
            view = pill
        }
    }

    fun hide() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }
}
