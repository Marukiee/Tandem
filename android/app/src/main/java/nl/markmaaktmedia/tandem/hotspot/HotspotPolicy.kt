package nl.markmaaktmedia.tandem.hotspot

enum class Refusal { Disabled, Battery, Roaming }

/** When this phone says no to a request, whoever asked and however they asked. */
object HotspotPolicy {
    const val MIN_BATTERY_PERCENT = 15

    /**
     * Null means go ahead. A phone on a charger has no battery to protect, so it is
     * allowed below the limit. An unknown level (no battery info) is not a reason to refuse.
     */
    fun refusal(
        enabled: Boolean,
        batteryPercent: Int?,
        charging: Boolean,
        roaming: Boolean,
        allowRoaming: Boolean,
    ): Refusal? {
        if (!enabled) return Refusal.Disabled
        if (batteryPercent != null && batteryPercent <= MIN_BATTERY_PERCENT && !charging) return Refusal.Battery
        if (roaming && !allowRoaming) return Refusal.Roaming
        return null
    }

    fun stateFor(refusal: Refusal): BleState = when (refusal) {
        Refusal.Disabled -> BleState.Disabled
        Refusal.Battery -> BleState.RefusedBattery
        Refusal.Roaming -> BleState.RefusedRoaming
    }

    /** The short codes carried in the `error` field of a QUIC `HotspotMsg::State`. */
    fun errorCode(refusal: Refusal): String = when (refusal) {
        Refusal.Disabled -> "disabled"
        Refusal.Battery -> "battery"
        Refusal.Roaming -> "roaming"
    }
}

/**
 * Says when a hotspot we switched on has been empty long enough to switch off again.
 * Feed it the current time and whether anything is using the hotspot.
 */
class IdleTracker(private val limitMs: Long = DEFAULT_LIMIT_MS) {
    private var idleSince: Long? = null

    /** True when the hotspot should now be turned off. */
    fun shouldTurnOff(now: Long, inUse: Boolean): Boolean {
        if (inUse) {
            idleSince = null
            return false
        }
        val since = idleSince ?: now.also { idleSince = it }
        return now - since >= limitMs
    }

    fun reset() {
        idleSince = null
    }

    companion object {
        const val DEFAULT_LIMIT_MS = 5 * 60_000L
    }
}
