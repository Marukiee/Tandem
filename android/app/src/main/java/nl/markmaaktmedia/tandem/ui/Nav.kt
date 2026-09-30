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
    data object OnboardingPreview : Route
}

/** A small back stack. The home screen is always at the bottom. */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    var tab by mutableIntStateOf(0)

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
