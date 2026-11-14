package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.markmaaktmedia.tandem.R

/**
 * Why a file ended without arriving, in the words of the person. The core gives a fixed code (R-V13, docs/TRANSFERS.md); anything
 * else, also a sentence of an older core, is only "Failed": the text itself is never shown.
 */
@Composable
fun transferReason(error: String?, incoming: Boolean, peerName: String): String = when (error) {
    "cancelled" -> stringResource(R.string.transfer_cancelled)
    "declined" -> stringResource(R.string.transfer_declined)
    "expired" -> stringResource(R.string.transfer_expired)
    "gone" -> if (incoming) stringResource(R.string.transfer_gone_in, peerName) else stringResource(R.string.transfer_gone_out)
    "changed" -> if (incoming) stringResource(R.string.transfer_changed_in, peerName) else stringResource(R.string.transfer_changed_out)
    "no space" -> if (incoming) stringResource(R.string.transfer_no_space_in) else stringResource(R.string.transfer_no_space_out, peerName)
    "connection lost" -> stringResource(R.string.transfer_connection_lost, peerName)
    "checksum failed" -> stringResource(R.string.transfer_checksum_failed)
    "not saved" -> if (incoming) stringResource(R.string.transfer_not_saved_in) else stringResource(R.string.transfer_not_saved_out, peerName)
    else -> stringResource(R.string.transfer_failed)
}
