package nl.markmaaktmedia.tandem.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import nl.markmaaktmedia.tandem.R
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemPlatform

/**
 * Puts every paired device in Android's share sheet by name, so sharing a screenshot
 * is: Share, then the device. Offline devices are left out, so what is listed is what
 * will actually work right now.
 */
object ShareShortcuts {
    const val CATEGORY = "nl.markmaaktmedia.tandem.category.SHARE"
    const val DEVICE_PREFIX = "device:"
    const val ALL = "all"

    fun update(context: Context, devices: List<TandemDevice>) {
        val online = devices.filter { it.online }
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        if (online.size > 1) {
            shortcuts += build(context, ALL, context.getString(R.string.share_all_devices), R.drawable.sym_devices, rank = 0)
        }
        online.forEachIndexed { index, device ->
            shortcuts += build(context, DEVICE_PREFIX + device.id, device.name, iconFor(device.platform), rank = index + 1)
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))) }
    }

    private fun iconFor(platform: TandemPlatform): Int = when (platform) {
        TandemPlatform.ANDROID, TandemPlatform.IOS -> R.drawable.sym_smartphone
        TandemPlatform.MAC_OS -> R.drawable.sym_laptop_mac
        TandemPlatform.WINDOWS -> R.drawable.sym_desktop_windows
        else -> R.drawable.sym_computer
    }

    private fun build(context: Context, id: String, label: String, icon: Int, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setLongLived(true)
            .setRank(rank)
            .setCategories(setOf(CATEGORY))
            .setIcon(IconCompat.createWithAdaptiveBitmap(badge(context, icon)))
            .setIntent(Intent(context, ShareTargetActivity::class.java).setAction(Intent.ACTION_SEND))
            .build()

    /** The device symbol, white on the accent colour, drawn into an adaptive icon. */
    private fun badge(context: Context, drawable: Int): Bitmap {
        val size = 216
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF5B5BD6.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
        canvas.drawCircle(size * 0.75f, size * 0.25f, size * 0.42f, paint)
        AppCompatResources.getDrawable(context, drawable)?.let { symbol ->
            symbol.setTint(0xFFFFFFFF.toInt())
            val inset = (size * 0.30f).toInt()
            symbol.setBounds(inset, inset, size - inset, size - inset)
            symbol.draw(canvas)
        }
        return bitmap
    }
}
