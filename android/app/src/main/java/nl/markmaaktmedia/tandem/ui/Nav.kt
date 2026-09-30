package nl.markmaaktmedia.tandem.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

sealed interface Route {
    data object Home : Route
    data class Device(val id: String) : Route
    data object Pair : Route
    data class Remote(val id: String) : Route
    data object Access : Route
    data object MirrorApps : Route
    data object Appearance : Route
    data object Developer : Route
    data object Hotspot : Route
    data object OnboardingPreview : Route
}

/** A small back stack. The home screen is always at the bottom. */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    var tab by mutableIntStateOf(0)

    // The scroll position of each home tab lives here, not in the tab: a page opened from a tab
    // takes the tab out of the composition, and coming back must not start from the top.
    val devicesList = androidx.compose.foundation.lazy.LazyListState()
    val transfersList = androidx.compose.foundation.lazy.LazyListState()
    val settingsList = androidx.compose.foundation.lazy.LazyListState()

    val top: Route get() = stack.last()

    fun push(route: Route) {
        if (top != route) stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popTo(route: Route) {
        while (stack.size > 1 && top != route) stack.removeAt(stack.lastIndex)
    }
}

/**
 * Keeps the back stack and the selected tab through a rotation or any other recreation of
 * the activity. Without it turning the phone in the trackpad screen dropped you on the
 * home screen.
 */
val NavSaver: androidx.compose.runtime.saveable.Saver<Nav, Any> = androidx.compose.runtime.saveable.listSaver<Nav, String>(
    save = { nav -> listOf(nav.tab.toString()) + nav.stack.map(::routeKey) },
    restore = { saved ->
        Nav().also { nav ->
            nav.tab = saved.firstOrNull()?.toIntOrNull() ?: 0
            nav.stack.clear()
            nav.stack.addAll(saved.drop(1).mapNotNull(::routeFromKey).ifEmpty { listOf(Route.Home) })
        }
    },
)

private fun routeFromKey(key: String): Route? = when {
    key == "home" -> Route.Home
    key.startsWith("device:") -> Route.Device(key.removePrefix("device:"))
    key == "pair" -> Route.Pair
    key.startsWith("remote:") -> Route.Remote(key.removePrefix("remote:"))
    key == "access" -> Route.Access
    key == "mirror" -> Route.MirrorApps
    key == "appearance" -> Route.Appearance
    key == "developer" -> Route.Developer
    key == "hotspot" -> Route.Hotspot
    key == "onboarding-preview" -> Route.OnboardingPreview
    else -> null
}
