package nl.markmaaktmedia.tandem.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import kotlin.math.max
import kotlin.math.roundToInt

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}

/**
 * The batteries of this phone and of the other devices, in the language of a Pixel widget: the colours of the wallpaper (Material You), each
 * device in a shape of Material 3 Expressive inside a thick rounded ring, and Material Symbols Rounded. Dark and light come from the system.
 */
class BatteryWidget : GlanceAppWidget() {
    // Exact: one set of ring pictures at the real size. Responsive would send a set for every size, and the pictures of six rings in three
    // sizes come close to what one update of a widget may carry.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by BatteryWidgetStore.version.collectAsState()
            GlanceTheme { Content(load(LocalContext.current, version)) }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun load(context: Context, version: Long): List<WidgetBattery> {
        val (others, selfName) = BatteryWidgetStore.read(context)
        val own = BatteryWidgetStore.ownBattery(context)?.let { (level, charging) ->
            WidgetBattery(SELF, selfName ?: Build.MODEL, "phone", level, charging, away = false, playing = BatteryWidgetStore.selfPlaying(context),
                chargeChangedAt = BatteryWidgetStore.selfChargeChangedAt)
        }
        return listOfNotNull(own) + others
    }

    companion object {
        const val SELF = "this"
    }
}

// Google Sans Flex where the system has it (a Pixel), otherwise the system's own sans.
private val expressive = FontFamily("google-sans-flex")

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
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(GlanceTheme.colors.widgetBackground).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (shown.isEmpty()) {
            Text(
                LocalContext.current.getString(R.string.battery_widget_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontFamily = expressive, textAlign = TextAlign.Center),
            )
        } else {
            // Inside the padding of 12 on each side.
            val layout = BatteryWidgetLogic.fit(kind, shown.size, size.width.value - 24, size.height.value - 24)
            Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                shown.chunked(layout.columns).forEachIndexed { index, row ->
                    if (index > 0) Spacer(GlanceModifier.height(BatteryWidgetLogic.rowGap(kind).dp))
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
    val colors = GlanceTheme.colors
    Column(modifier.clickable(actionStartActivity(openIntent(context, device.id))), horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidRemoteViews(ring(context, device, diameter), GlanceModifier.size(diameter.dp))
        Spacer(GlanceModifier.height((diameter * 0.08f).dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (device.charging) {
                Image(
                    ImageProvider(R.drawable.sym_bolt_filled), null, GlanceModifier.size(max(13f, diameter * 0.2f).dp),
                    colorFilter = ColorFilter.tint(green),
                )
                Spacer(GlanceModifier.width(1.dp))
            }
            Text(
                "${device.level}%",
                style = TextStyle(
                    color = if (device.away) colors.onSurfaceVariant else colors.onSurface,
                    fontSize = max(13f, diameter * 0.22f).sp, fontWeight = FontWeight.Bold, fontFamily = expressive,
                ),
                maxLines = 1,
            )
        }
        if (names) {
            Text(
                device.name,
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = expressive, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

// The charger: green, as on a Pixel's own battery widget.
private val green = ColorProvider(day = Color(0xFF1E8E3E), night = Color(0xFF81C995))

/**
 * The ring of one device as plain views (layout/widget_ring.xml), which is what lets it move: on the charger the arc, the shape and the icon go
 * green, and while something plays the shape turns. Each state is a layer of its own that the frame fades in and out. The colours are
 * resources with a day and a night value, so the ring follows the system between two updates as the rest of the widget does.
 */
private fun ring(context: Context, device: WidgetBattery, diameter: Int): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_ring)
    val density = context.resources.displayMetrics.density
    val charging = device.charging && !device.away
    val playing = device.playing && !device.away
    val arcColor = when (BatteryWidgetColors.tone(device.level, device.charging, device.away)) {
        BatteryTone.Normal -> R.color.widget_primary
        BatteryTone.Low -> R.color.widget_amber
        BatteryTone.Critical -> R.color.widget_error
        BatteryTone.Away -> R.color.widget_outline
    }
    // This phone in the strong primary, the others in the soft secondary container, a device that is away in grey: so this phone stands out.
    val (container, onContainer) = when {
        device.away -> R.color.widget_surface_variant to R.color.widget_on_surface_variant
        device.id == BatteryWidget.SELF -> R.color.widget_primary to R.color.widget_on_primary
        else -> R.color.widget_secondary_container to R.color.widget_on_secondary_container
    }
    val cookie = BatteryWidgetColors.shapeOf(device.icon) == BatteryShape.Cookie

    val arc = arc(context, diameter, device.level)
    views.setImageViewBitmap(R.id.ring_arc, arc)
    views.setImageViewBitmap(R.id.ring_arc_green, arc)
    tint(views, R.id.ring_track, R.color.widget_surface_variant)
    tint(views, R.id.ring_arc, arcColor)
    tint(views, R.id.ring_arc_green, R.color.widget_green)

    val shapeInset = (diameter * (1 - SHAPE) / 2 * density).roundToInt()
    val shape = if (cookie) R.drawable.widget_shape_cookie else R.drawable.widget_shape_clover
    for (id in listOf(R.id.ring_shape, R.id.ring_shape_green)) views.setImageViewResource(id, shape)
    tint(views, R.id.ring_shape, container)
    tint(views, R.id.ring_shape_green, R.color.widget_green_container)
    for ((id, color) in listOf(
        R.id.ring_spin_cookie to container, R.id.ring_spin_cookie_green to R.color.widget_green_container,
        R.id.ring_spin_clover to container, R.id.ring_spin_clover_green to R.color.widget_green_container,
    )) {
        views.setColorStateList(id, "setIndeterminateTintList", color)
    }
    for (id in listOf(R.id.ring_shape, R.id.ring_shape_green, R.id.ring_spin_cookie, R.id.ring_spin_cookie_green, R.id.ring_spin_clover, R.id.ring_spin_clover_green)) {
        views.setViewPadding(id, shapeInset, shapeInset, shapeInset, shapeInset)
    }

    val iconInset = (diameter * (1 - ICON) / 2 * density).roundToInt()
    for (id in listOf(R.id.ring_icon, R.id.ring_icon_green)) {
        views.setImageViewResource(id, iconRes(device.icon))
        views.setViewPadding(id, iconInset, iconInset, iconInset, iconInset)
    }
    tint(views, R.id.ring_icon, onContainer)
    tint(views, R.id.ring_icon_green, R.color.widget_on_green_container)

    // The change of the charger, played once by the view that appears with it: only in the update that comes with the change.
    val justChanged = !device.away && System.currentTimeMillis() - device.chargeChangedAt < CHANGE_SHOWN_MS
    views.setColorStateList(R.id.ring_charge_on, "setIndeterminateTintList", R.color.widget_green)
    views.setColorStateList(R.id.ring_charge_off, "setIndeterminateTintList", R.color.widget_green_container)
    for (id in listOf(R.id.ring_charge_on, R.id.ring_charge_off)) views.setViewPadding(id, shapeInset, shapeInset, shapeInset, shapeInset)

    fun show(id: Int, visible: Boolean) = views.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
    show(R.id.ring_charge_on, justChanged && charging)
    show(R.id.ring_charge_off, justChanged && !charging)
    show(R.id.ring_arc, !charging)
    show(R.id.ring_arc_green, charging)
    show(R.id.ring_shape, !playing && !charging)
    show(R.id.ring_shape_green, !playing && charging)
    show(R.id.ring_spin_cookie, playing && cookie && !charging)
    show(R.id.ring_spin_cookie_green, playing && cookie && charging)
    show(R.id.ring_spin_clover, playing && !cookie && !charging)
    show(R.id.ring_spin_clover_green, playing && !cookie && charging)
    show(R.id.ring_icon, !charging)
    show(R.id.ring_icon_green, charging)
    return views
}

private fun tint(views: RemoteViews, id: Int, color: Int) = views.setColorStateList(id, "setImageTintList", color)

/** The icon takes this part of the ring. */
private const val ICON = 0.32f

/** How long after a change of the charger an update still plays it. */
private const val CHANGE_SHOWN_MS = 8_000L

/** The shape fills this part of the ring, leaving a sliver of the card between them. */
private const val SHAPE = 0.66f

/** A thick ring, as the progress indicators of Material 3 Expressive: an eighth of the ring wide. Keep in step with widget_ring_track. */
private const val LINE = 0.125f

private fun maskDensity(context: Context) = context.resources.displayMetrics.density.coerceAtMost(2f)

/**
 * The coloured part of a ring, as a mask the widget tints: one byte a pixel, drawn at no more than twice the size in dp, so six rings stay
 * far below what one update may carry. The track under it is a vector.
 */
private fun arc(context: Context, diameter: Int, level: Int): Bitmap {
    val density = maskDensity(context)
    val px = max(1, (diameter * density).roundToInt())
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ALPHA_8)
    val line = px * LINE
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = line
        strokeCap = Paint.Cap.ROUND
        color = android.graphics.Color.BLACK
    }
    Canvas(bitmap).drawArc(RectF(line / 2, line / 2, px - line / 2, px - line / 2), -90f, BatteryWidgetColors.sweep(level), false, paint)
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
