package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.TandemConfirmDialog
import nl.markmaaktmedia.tandem.ui.routeBounds
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** The language of the app as stored: "system" for no choice, else a language tag like "nl". */
internal fun appLanguage(): String =
    AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-').ifBlank { "system" }

internal fun setAppLanguage(language: String) {
    AppCompatDelegate.setApplicationLocales(if (language == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language))
}

private val Languages = listOf("system", "en", "nl")

/** Look and language: the appearance screen, and the language of the app. */
@Composable
internal fun LookPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    var pendingLanguage by remember { mutableStateOf<String?>(null) }
    val current = appLanguage()

    SettingsPageFrame(stringResource(R.string.settings_look), onBack) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            ActionRow(
                0, 2, TandemIcons.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub),
                { onOpen(Route.Appearance) }, modifier = Modifier.routeBounds(routeKey(Route.Appearance)),
            )
            SettingsTarget(FocusKeys.Language, 1, 2) {
                ContentRow(1, 2, TandemIcons.Language, stringResource(R.string.settings_language)) {
                    SegmentedPillRow(
                        options = Languages,
                        selected = if (current in Languages) current else "system",
                        label = { context.getString(when (it) { "en" -> R.string.language_en; "nl" -> R.string.language_nl; else -> R.string.language_system }) },
                        // Switching language recreates the app, so ask before doing it.
                        onSelect = { if (it != current) pendingLanguage = it },
                        modifier = Modifier.fillMaxWidth(),
                        equalWidth = true,
                    )
                }
            }
        }
    }

    pendingLanguage?.let { language ->
        TandemConfirmDialog(
            title = stringResource(R.string.language_restart_title),
            body = stringResource(R.string.language_restart_body),
            confirmLabel = stringResource(R.string.language_restart_confirm),
            cancelLabel = stringResource(R.string.language_restart_cancel),
            destructive = false,
            onConfirm = {
                pendingLanguage = null
                setAppLanguage(language)
            },
            onDismiss = { pendingLanguage = null },
        )
    }
}
