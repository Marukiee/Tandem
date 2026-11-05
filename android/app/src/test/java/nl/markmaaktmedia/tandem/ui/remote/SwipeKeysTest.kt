package nl.markmaaktmedia.tandem.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.tandem_core.TandemPlatform

class SwipeKeysTest {
    @Test
    fun a_mac_keeps_control_and_an_arrow() {
        assertEquals(KeyChord(MacKeys.UP, Mods.CTRL), swipeKeys(TandemPlatform.MAC_OS, SwipeDirection.Up))
        assertEquals(KeyChord(MacKeys.RIGHT, Mods.CTRL), swipeKeys(TandemPlatform.MAC_OS, SwipeDirection.Left))
    }

    @Test
    fun windows_gets_task_view_and_its_desktops() {
        assertEquals(KeyChord(MacKeys.TAB, Mods.SYSTEM), swipeKeys(TandemPlatform.WINDOWS, SwipeDirection.Up))
        assertEquals(KeyChord(MacKeys.D, Mods.SYSTEM), swipeKeys(TandemPlatform.WINDOWS, SwipeDirection.Down))
        assertEquals(KeyChord(MacKeys.RIGHT, Mods.SYSTEM or Mods.CTRL), swipeKeys(TandemPlatform.WINDOWS, SwipeDirection.Left))
    }

    @Test
    fun linux_only_changes_workspace() {
        assertEquals(KeyChord(MacKeys.LEFT, Mods.CTRL or Mods.OPTION), swipeKeys(TandemPlatform.LINUX, SwipeDirection.Right))
        assertNull(swipeKeys(TandemPlatform.LINUX, SwipeDirection.Up))
    }
}
