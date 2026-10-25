package nl.markmaaktmedia.tandem.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.widget.BatteryWidgetColors as C
import kotlin.math.max
import kotlin.math.roundToInt

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}

/**
 * The batteries of this phone and of the other devices, as rings on a dark card (the Batteries widget of iOS). The look is fixed, the same in
 * dark and light: see [BatteryWidgetColors].
 */
class BatteryWidget : GlanceAppWidget() {
    // Exact: one set of ring pictures at the real size. Responsive would send a set for every size, and the pictures of six rings in three
    // sizes come close to what one update of a widget may carry.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by BatteryWidgetStore.version.collectAsState()
            Content(load(LocalContext.current, version))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun load(context: Context, version: Long): List<WidgetBattery> {
        val (others, selfName) = BatteryWidgetStore.read(context)
        val own = BatteryWidgetStore.ownBattery(context)?.let { (level, charging) ->
            WidgetBattery(SELF, selfName ?: Build.MODEL, "phone", level, charging, away = false)
        }
        return listOfNotNull(own) + others
    }

    companion object {
        const val SELF = "this"
    }
}

@Composable
private fun Content(devices: List<WidgetBattery>) {
    val size = LocalSize.current
    val kind = when {
        size.width < 200.dp -> BatteryWidgetLogic.Size.Small
        size.height < 200.dp -> BatteryWidgetLogic.Size.Medium
        else -> BatteryWidgetLogic.Size.Large
    }
    val shown = devices.take(kind.capacity)
    Box(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(ColorProvider(Color(C.BACKGROUND))).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (shown.isEmpty()) {
            Text(
                LocalContext.current.getString(R.string.battery_widget_empty),
                style = TextStyle(color = ColorProvider(Color(C.SECONDARY)), fontSize = 12.sp, textAlign = TextAlign.Center),
            )
        } else {
            val layout = BatteryWidgetLogic.layout(kind, shown.size)
            Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                shown.chunked(layout.columns).forEachIndexed { index, row ->
                    if (index > 0) Spacer(GlanceModifier.height(if (kind == BatteryWidgetLogic.Size.Small) 8.dp else 18.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { Item(it, layout.diameter, layout.names, GlanceModifier.defaultWeight()) }
                        // A short last row keeps the columns of the rows above it.
                        repeat(layout.columns - row.size) { Spacer(GlanceModifier.defaultWeight()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Item(device: WidgetBattery, diameter: Int, names: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val alpha = if (device.away) C.AWAY_ALPHA else 1f
    val text = ColorProvider(Color(C.solid(C.TEXT, alpha)))
    Column(modifier.clickable(actionStartActivity(openIntent(context, device.id))), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.size(diameter.dp), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(R.drawable.widget_ring_track), null, GlanceModifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(ColorProvider(Color(C.solid(C.TRACK, alpha)))),
            )
            Image(
                ImageProvider(arc(context, diameter, device.level)), null, GlanceModifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(ColorProvider(Color(C.solid(C.tint(device.level, device.charging), alpha)))),
            )
            Image(
                ImageProvider(iconRes(device.icon)), null, GlanceModifier.size((diameter * 0.42f).dp),
                colorFilter = ColorFilter.tint(text),
            )
        }
        Spacer(GlanceModifier.height((diameter * 0.08f).dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (device.charging) {
                Image(
                    ImageProvider(R.drawable.sym_bolt_filled), null, GlanceModifier.size(max(12f, diameter * 0.2f).dp),
                    colorFilter = ColorFilter.tint(ColorProvider(Color(C.solid(C.GREEN, alpha)))),
                )
                Spacer(GlanceModifier.width(1.dp))
            }
            Text(
                "${device.level}%",
                style = TextStyle(color = text, fontSize = max(12f, diameter * 0.22f).sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        if (names) {
            Text(
                device.name,
                style = TextStyle(color = ColorProvider(Color(C.solid(C.SECONDARY, alpha))), fontSize = 11.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

/**
 * The coloured part of a ring, as a mask the widget tints: one byte a pixel, and drawn at no more than twice the size in dp, so six rings
 * stay far below what one update may carry. The track under it is a vector.
 */
private fun arc(context: Context, diameter: Int, level: Int): Bitmap {
    val density = context.resources.displayMetrics.density.coerceAtMost(2f)
    val px = max(1, (diameter * density).roundToInt())
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ALPHA_8)
    // Same proportion as the track: a line of a tenth of the ring, at least 3 dp.
    val line = max(3f * density, px * 0.1f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = line
        strokeCap = Paint.Cap.ROUND
        color = android.graphics.Color.BLACK
    }
    Canvas(bitmap).drawArc(RectF(line / 2, line / 2, px - line / 2, px - line / 2), -90f, C.sweep(level), false, paint)
    return bitmap
}

private fun iconRes(key: String): Int = when (key) {
    "phone" -> R.drawable.sym_smartphone
    "tablet" -> R.drawable.sym_tablet_android
    "laptop" -> R.drawable.sym_laptop_mac
    "desktop" -> R.drawable.sym_desktop_mac
    "watch" -> R.drawable.sym_watch
    "tv" -> R.drawable.sym_tv
    "windows" -> R.drawable.sym_desktop_windows
    else -> R.drawable.sym_computer
}

/** A tap opens the page of that device; this phone's own ring opens the app. */
private fun openIntent(context: Context, id: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (id != BatteryWidget.SELF) {
            action = MainActivity.ACTION_OPEN_DEVICE
            putExtra(MainActivity.EXTRA_DEVICE, id)
            // Each ring its own intent, or Android hands every ring the same one. Not tandem://, which the app reads as a pairing link.
            data = Uri.parse("tandem-widget://device/" + Uri.encode(id))
        }
    }
