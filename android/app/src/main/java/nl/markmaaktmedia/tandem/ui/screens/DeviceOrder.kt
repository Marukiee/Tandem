package nl.markmaaktmedia.tandem.ui.screens

/**
 * The order of the devices on the first page: what is pinned first, then what can be reached right now, then what can
 * be reached some other way or is asleep, then the rest. Within a group by name, so the list does not shuffle itself
 * when two devices swap places in time.
 *
 * [reach] is 0 for a device that is connected, 1 for one that can be reached over Bluetooth, 2 for one that is asleep and
 * 3 for one that is off or away.
 */
fun <T> orderDevices(items: List<T>, pinned: Set<String>, id: (T) -> String, reach: (T) -> Int, name: (T) -> String): List<T> =
    items.sortedWith(
        compareBy<T> { if (id(it) in pinned) 0 else 1 }
            .thenBy { reach(it) }
            .thenBy { name(it).lowercase() },
    )

/** A phone in the circle that says it has a watch, and whether that watch is with it now (only true while the phone is connected). */
internal data class WatchHolder(val phone: String, val online: Boolean)

/** The phones in the circle that say they have a watch. */
internal fun watchHolders(devices: List<uniffi.tandem_core.TandemDevice>): List<WatchHolder> =
    devices.mapNotNull { d -> d.status.watch?.takeIf { it.paired }?.let { WatchHolder(d.id, d.online && it.online) } }

/** The one watch card the first page shows: the watch of this phone, the watch of another phone in the circle, or none. */
internal sealed interface WatchPick {
    data object Local : WatchPick
    data class Remote(val phone: String) : WatchPick
    data object None : WatchPick
}

/**
 * A watch talks to one phone at a time, but the phone it left still says it has one, only not connected. So there is one card for it:
 * where it is connected now, and else the one of this phone, so its settings stay in reach.
 */
internal fun pickWatch(localPaired: Boolean, localReady: Boolean, others: List<WatchHolder>): WatchPick = when {
    localPaired && localReady -> WatchPick.Local
    others.any { it.online } -> WatchPick.Remote(others.first { it.online }.phone)
    localPaired -> WatchPick.Local
    others.isNotEmpty() -> WatchPick.Remote(others.first().phone)
    else -> WatchPick.None
}
