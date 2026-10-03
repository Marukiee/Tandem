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
