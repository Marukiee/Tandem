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
    private const val DEVICE_PREFIX = "device:"
    private const val ALL = "all"

    /** Who a shortcut picked in the share sheet points at. */
    sealed interface Target {
        data object All : Target
        data class Device(val id: String) : Target
    }

    /**
     * The shortcut a share came with, and whether its id carries the secret of this install. Any app can start the share
     * screen with any shortcut id (before, "all" sent to every device without a tap), but only this app, the launcher and
     * the system can read the ids of these shortcuts. So the secret proves that the person picked the device in the share
     * sheet; without it the share screen asks with one tap.
     */
    data class Picked(val target: Target, val proven: Boolean)

    fun update(context: Context, devices: List<TandemDevice>) {
        val online = devices.filter { it.online }
        val secret = secret(context)
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        if (online.size > 1) {
            shortcuts += build(context, "$ALL:$secret", context.getString(R.string.share_target_all), R.drawable.sym_devices, rank = 0)
        }
        online.forEachIndexed { index, device ->
            shortcuts += build(context, "$DEVICE_PREFIX$secret:${device.id}", context.getString(R.string.share_target_to, device.name), iconFor(device.platform), rank = index + 1)
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))) }
    }

    fun parse(context: Context, shortcutId: String): Picked? = parse(shortcutId, secret(context))

    /** Reads a shortcut id: `all:<secret>` and `device:<secret>:<id>`, or the forms without a secret of older versions. */
    internal fun parse(shortcutId: String, secret: String): Picked? {
        fun proves(token: String) = java.security.MessageDigest.isEqual(token.toByteArray(), secret.toByteArray())
        return when {
            shortcutId == ALL -> Picked(Target.All, proven = false)
            shortcutId.startsWith("$ALL:") -> Picked(Target.All, proves(shortcutId.removePrefix("$ALL:")))
            shortcutId.startsWith(DEVICE_PREFIX) -> {
                val rest = shortcutId.removePrefix(DEVICE_PREFIX)
                val token = rest.substringBefore(':', missingDelimiterValue = "")
                val id = rest.substringAfter(':')
                if (id.isBlank()) null else Picked(Target.Device(id), token.isNotEmpty() && proves(token))
            }
            else -> null
        }
    }

    /** Made once per install. It stays the same, so the share sheet keeps learning which device is picked most. */
    @Synchronized
    private fun secret(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("share_shortcuts", Context.MODE_PRIVATE)
        prefs.getString("secret", null)?.let { return it }
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val made = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("secret", made).commit()
        return made
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
