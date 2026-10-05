package nl.markmaaktmedia.tandem.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Asks for what sending the phone's sound needs, one thing after the other (the permission to record audio, then the
 * system's question about capturing), and hands the answer to [SoundShareService]. It shows nothing of its own.
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
        }
        finish()
    }

    private val audio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askCapture() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peer = intent.getStringExtra(SoundShareService.EXTRA_PEER) ?: return finish()
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askCapture()
        else audio.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askCapture() {
        capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    companion object {
        fun forPeer(context: Context, peer: String): Intent =
            Intent(context, SoundShareActivity::class.java).putExtra(SoundShareService.EXTRA_PEER, peer)
    }
}
