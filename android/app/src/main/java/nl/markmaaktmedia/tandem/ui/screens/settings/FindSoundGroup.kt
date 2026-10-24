package nl.markmaaktmedia.tandem.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.FindSound
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** The sound this phone rings with when a computer looks for it, picked from the ringtones and alarms of the system. */
@Composable
internal fun FindSoundGroup() {
    val context = LocalContext.current
    var title by remember { mutableStateOf(FindSound.title(context)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            FindSound.fromPicker(context, result.data)
            title = FindSound.title(context)
        }
    }
    SectionHeader(stringResource(R.string.find_sound_section))
    SettingsGroup {
        SettingsTarget(FocusKeys.FindSound, 0, 1) {
            ActionRow(0, 1, TandemIcons.Ring, stringResource(R.string.find_sound_title), title, { picker.launch(FindSound.pickIntent(context)) })
        }
    }
}
