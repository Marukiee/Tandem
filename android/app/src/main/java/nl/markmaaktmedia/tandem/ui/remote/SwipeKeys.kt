package nl.markmaaktmedia.tandem.ui.remote

import uniffi.tandem_core.TandemPlatform

/** A key and what is held with it. */
data class KeyChord(val code: Short, val mods: Int)

/**
 * What three fingers do on the computer that is steered. Swiping left pulls in the desktop to the right, as on a real
 * trackpad. Linux has no shortcut for an overview that all its desktops share, so there only left and right do something.
 */
fun swipeKeys(platform: TandemPlatform?, direction: SwipeDirection): KeyChord? = when (platform) {
    TandemPlatform.WINDOWS -> when (direction) {
        SwipeDirection.Up -> KeyChord(MacKeys.TAB, Mods.SYSTEM)
        SwipeDirection.Down -> KeyChord(MacKeys.D, Mods.SYSTEM)
        SwipeDirection.Left -> KeyChord(MacKeys.RIGHT, Mods.SYSTEM or Mods.CTRL)
        SwipeDirection.Right -> KeyChord(MacKeys.LEFT, Mods.SYSTEM or Mods.CTRL)
    }
    TandemPlatform.LINUX -> when (direction) {
        SwipeDirection.Left -> KeyChord(MacKeys.RIGHT, Mods.CTRL or Mods.OPTION)
        SwipeDirection.Right -> KeyChord(MacKeys.LEFT, Mods.CTRL or Mods.OPTION)
        else -> null
    }
    // The Mac maps all four to Ctrl plus an arrow: Mission Control, App Exposé, and changing space.
    else -> when (direction) {
        SwipeDirection.Up -> KeyChord(MacKeys.UP, Mods.CTRL)
        SwipeDirection.Down -> KeyChord(MacKeys.DOWN, Mods.CTRL)
        SwipeDirection.Left -> KeyChord(MacKeys.RIGHT, Mods.CTRL)
        SwipeDirection.Right -> KeyChord(MacKeys.LEFT, Mods.CTRL)
    }
}
