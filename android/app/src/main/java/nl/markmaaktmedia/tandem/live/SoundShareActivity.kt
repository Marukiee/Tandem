package nl.markmaaktmedia.tandem.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * Asks for what sending the phone's sound needs, one thing after the other (the permission to record audio, then the
 * system's question about capturing, which answers itself once the app-op of [ProjectionGrant] is set), and hands the answer
 * to [SoundShareService]. [PhoneSound] decides whether this is the way at all. It shows nothing of its own but a short
 * word when something is missing, and what the person can expect once it runs.
 */
class SoundShareActivity : ComponentActivity() {
    private lateinit var peer: String

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, SoundShareService::class.java).setAction(SoundShareService.ACTION_BEGIN)
                    .putExtra(SoundShareService.EXTRA_PEER, peer)
                    .putExtra(SoundShareService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(SoundShareService.EXTRA_RESULT_DATA, data),
            )
            // The capture only hears apps that let it: said now, so silence from one of them is not taken for a fault.
            say(getString(R.string.output_only_some_apps, name()))
        } else {
            say(getString(R.string.output_consent_needed))
        }
        finish()
    }

    private val audio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            askCapture()
        } else {
            say(getString(R.string.output_record_needed))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peer = intent.getStringExtra(SoundShareService.EXTRA_PEER) ?: return finish()
        if (savedInstanceState != null) return
        // Its sound plays here: sent back, the two would echo each other.
        if (graph.audio.isPlayingFrom(peer)) {
            say(getString(R.string.output_loop, name()))
            return finish()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askCapture()
        else audio.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askCapture() {
        capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun name() = graph.host.device(peer)?.name ?: getString(R.string.feature_device_fallback)

    private fun say(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    companion object {
        fun forPeer(context: Context, peer: String): Intent =
            Intent(context, SoundShareActivity::class.java).putExtra(SoundShareService.EXTRA_PEER, peer)
    }
}
