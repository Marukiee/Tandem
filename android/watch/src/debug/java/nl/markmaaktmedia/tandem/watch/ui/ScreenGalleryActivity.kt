package nl.markmaaktmedia.tandem.watch.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import nl.markmaaktmedia.tandem.watch.link.WatchUi
import nl.markmaaktmedia.tandem.watch.protocol.CallMsg
import nl.markmaaktmedia.tandem.watch.protocol.CallStatus
import nl.markmaaktmedia.tandem.watch.protocol.DeviceBattery
import nl.markmaaktmedia.tandem.watch.protocol.LinkFailure
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.PointerTarget
import nl.markmaaktmedia.tandem.watch.sleep.StageMinutes
import nl.markmaaktmedia.tandem.watch.track.LastSleep
import nl.markmaaktmedia.tandem.watch.track.LastWorkout
import nl.markmaaktmedia.tandem.watch.track.Tracking
import nl.markmaaktmedia.tandem.watch.track.TrackingKind
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme
import java.io.ByteArrayOutputStream

/**
 * Debug builds only: every screen with made up data, with no phone or call needed, to look at what falls off a round screen.
 * adb shell am start -n nl.markmaaktmedia.tandem.watch/.ui.ScreenGalleryActivity --es screen player --ez long true
 */
class ScreenGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "home"
        val long = intent.getBooleanExtra("long", false)
        Tracking.load(this)
        Tracking.publishActive(if (screen == "home_recording") TrackingKind.SLEEP else null, System.currentTimeMillis() - 83 * 60_000)
        Tracking.publishSleep(LastSleep(true, 441, 93, StageMinutes(230, 90, 121)))
        Tracking.publishWorkout(LastWorkout(47, 138))
        Tracking.publishSteps(8412)
        // The bars of a phone image, which a watch does not have.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            // Most emulators here are square; a watch is not.
            val round = Configuration(LocalConfiguration.current).apply {
                screenLayout = (screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK.inv()) or Configuration.SCREENLAYOUT_ROUND_YES
            }
            CompositionLocalProvider(LocalConfiguration provides round) {
                TandemWatchTheme {
                    AppScaffold(timeText = { TimeText() }) { Gallery(screen, long) }
                }
            }
        }
    }

    @Composable
    private fun Gallery(screen: String, long: Boolean) {
        val player = player(long)
        val art = cover(player.artKey)
        val ready = WatchUi(
            paired = true,
            connected = true,
            ready = true,
            players = listOf(player),
            cover = art,
            pointerEnabled = true,
            pointerTargets = listOf(PointerTarget("mac", computer(long), true, true)),
            batteries = listOf(DeviceBattery("phone", phone(long), 84, true), DeviceBattery("mac", computer(long), 62, false)),
        )
        when (screen) {
            "home", "home_recording" -> Home(ready)
            "home_idle" -> Home(ready.copy(players = emptyList(), cover = null))
            "home_found" -> Home(ready.copy(ringing = true, findingPhone = true))
            "home_call" -> Home(ready.copy(call = call(long, CallStatus.ACTIVE)))
            "home_unpaired" -> Home(WatchUi())
            "home_searching" -> Home(WatchUi(paired = true))
            "home_failed" -> Home(WatchUi(failure = LinkFailure.PAIRING_FAILED))
            "code" -> PairingCode("731450")
            "wait" -> PairingWait()
            "call_ring" -> CallScreen(call(long, CallStatus.RINGING))
            "call_active" -> CallScreen(call(long, CallStatus.ACTIVE))
            "player" -> PlayerScreen(player, art)
            "player_paused" -> PlayerScreen(player.copy(playing = false), art)
            "player_plain" -> PlayerScreen(player.copy(artKey = ""), null)
            "pointer_moving" -> PointerFace(computer(long), "moving", true)
            "pointer_paused" -> PointerFace(computer(long), "paused", true)
            "pointer_presentation" -> PointerFace(computer(long), "presentation", true)
            "pointer_lost" -> PointerFace(computer(long), "moving", false)
        }
    }

    private fun player(long: Boolean) = MediaPlayer(
        id = "app",
        app = "PixelPlayer",
        title = if (long) "Symfonie nr. 40 in g klein, KV 550: I. Molto allegro" else "Money For Nothing",
        artist = if (long) "Wolfgang Amadeus Mozart en het Koninklijk Concertgebouworkest" else "Dire Straits",
        album = "Brothers in Arms",
        playing = true,
        positionMs = 253_000,
        durationMs = 504_000,
        artKey = "gallery",
    )

    private fun computer(long: Boolean) = if (long) "De MacBook Pro op de zolderkamer" else "MacBook Pro"

    private fun phone(long: Boolean) = if (long) "Telefoon met een hele lange naam" else "Telefoon"

    private fun call(long: Boolean, state: CallStatus) = CallMsg(
        id = "call",
        state = state,
        incoming = true,
        number = "+31 6 12345678",
        name = if (long) "Jan-Willem van den Hoogenband de Tweede" else "Sam",
        ts = System.currentTimeMillis(),
        muted = false,
    )

    private fun cover(key: String): Message.MediaArt {
        val bitmap = Bitmap.createBitmap(152, 152, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 152f, 152f, Color.rgb(90, 140, 220), Color.rgb(240, 190, 160), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, 152f, 152f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, out)
        return Message.MediaArt(key, out.toByteArray())
    }
}
