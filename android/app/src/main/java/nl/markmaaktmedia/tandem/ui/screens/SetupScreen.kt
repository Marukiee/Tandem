package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.screens.settings.ShizukuContent
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** The parts of the page Setup, and where a link to one of them starts. */
enum class SetupSection { Permissions, Shizuku, Files }

/**
 * What Tandem needs on this phone, on one page instead of three (asked 2026-10-25): the permissions, Shizuku, and what other devices may
 * do with the files. A link to one of them (a switch that needs a permission, a search) opens this page at that part.
 */
@Composable
fun SetupScreen(onBack: () -> Unit, onOpen: (Route) -> Unit, start: SetupSection) {
    val scroll = rememberScrollState()
    val tops = remember { mutableStateMapOf<SetupSection, Int>() }
    var arrived by rememberSaveable { mutableStateOf(start == SetupSection.Permissions) }
    LaunchedEffect(tops[start]) {
        val top = tops[start] ?: return@LaunchedEffect
        if (!arrived) {
            arrived = true
            scroll.scrollTo(top)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(scroll).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Part(SetupSection.Permissions, R.string.settings_access, tops) { AccessContent() }
        Part(SetupSection.Shizuku, R.string.shizuku_title, tops) { ShizukuContent(onOpen) }
        Part(SetupSection.Files, R.string.files_title, tops) { FileAccessContent(withPermission = false) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Part(section: SetupSection, title: Int, tops: MutableMap<SetupSection, Int>, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().onGloballyPositioned { tops[section] = it.positionInParent().y.toInt() }) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 28.dp, bottom = 2.dp),
        )
        content()
    }
}
