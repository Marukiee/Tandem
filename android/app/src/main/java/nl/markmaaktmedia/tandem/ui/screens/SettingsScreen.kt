package nl.markmaaktmedia.tandem.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.routeBounds
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsCatalog
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsCategory
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsEntry
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsResults
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsSearch
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsSearchField
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsViewModel
import nl.markmaaktmedia.tandem.ui.screens.settings.rememberCategorySummaries
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * The Settings tab: a search field and the categories, each of which opens a page. Typing swaps the categories for
 * the results, and a result opens the page that holds the setting and lights the row up.
 *
 * Everything that used to be on this one long page lives on those pages now (see `ui/screens/settings`).
 */
@Composable
fun SettingsScreen(bottomPadding: Dp, onOpen: (Route) -> Unit, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val state = viewModel<SettingsViewModel>()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0]
    // Built again when the language changes, because the words searched are the words shown.
    val index = remember(language) { SettingsCatalog.index(context) }
    val hits = remember(index, state.query) { SettingsSearch.search(index.entries, state.query) }

    var focused by remember { mutableStateOf(false) }
    val searching = state.query.isNotBlank()
    // The title makes room while there is something to search: the keyboard takes half the screen.
    val active = focused || state.query.isNotEmpty()
    val fieldFocus = remember { FocusRequester() }

    fun endSearch() {
        state.query = ""
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Back clears the search before it leaves the tab.
    BackHandler(enabled = active) { endSearch() }

    // The keyboard going away by itself (the Back gesture closes it first) also ends the focus on the field.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            imeWasVisible = false
            focusManager.clearFocus()
        }
    }

    // A request left over from an earlier search has nothing to light up on this page.
    LaunchedEffect(Unit) { state.focus.clear() }

    fun open(entry: SettingsEntry) {
        keyboard?.hide()
        focusManager.clearFocus()
        state.focus.request(entry.focus)
        // The page that normally leads to the screen goes underneath, so Back climbs the same way the person would.
        entry.via?.let(onOpen)
        onOpen(entry.route)
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        AnimatedVisibility(
            visible = !active,
            enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
            exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
        ) {
            Text(
                stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
            )
        }
        SettingsSearchField(
            query = state.query,
            onQueryChange = { state.query = it },
            active = active,
            onFocusChange = { focused = it },
            onBack = ::endSearch,
            onSearch = { keyboard?.hide(); focusManager.clearFocus() },
            focusRequester = fieldFocus,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (active) 12.dp else 4.dp, bottom = 12.dp),
        )

        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            AnimatedContent(
                targetState = searching,
                transitionSpec = { fadeIn(TandemMotion.fadeSpec()) togetherWith fadeOut(TandemMotion.fadeSpec()) },
                label = "settingsContent",
            ) { showResults ->
                if (showResults) {
                    SettingsResults(
                        hits = hits,
                        index = index,
                        query = state.query,
                        bottomPadding = if (imeVisible) 16.dp else bottomPadding + 24.dp,
                        onDragStart = { keyboard?.hide() },
                        onPick = ::open,
                    )
                } else {
                    Categories(bottomPadding, listState, onOpen)
                }
            }
        }
    }
}

/** The groups of categories, in the order of the list in [SettingsCategory]. */
private val Groups = listOf(
    listOf(SettingsCategory.Look, SettingsCategory.Sharing, SettingsCategory.Notifications),
    listOf(SettingsCategory.Permissions, SettingsCategory.Hotspot, SettingsCategory.Files),
    listOf(SettingsCategory.Updates, SettingsCategory.About),
)

@Composable
private fun Categories(bottomPadding: Dp, listState: LazyListState, onOpen: (Route) -> Unit) {
    val summaries = rememberCategorySummaries()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(Groups.size) { group ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Groups[group].forEachIndexed { position, category ->
                    CategoryRow(
                        position, Groups[group].size, category, summaries[category].orEmpty(),
                        onClick = { onOpen(category.route) },
                        modifier = Modifier.routeBounds(routeKey(category.route)),
                    )
                }
            }
        }
    }
}

/** One category: its icon, its name, and a line that says how it is set right now. */
@Composable
private fun CategoryRow(index: Int, total: Int, category: SettingsCategory, summary: String, onClick: () -> Unit, modifier: Modifier) {
    GroupedRow(index, total, modifier = modifier, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(category.icon())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(category.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(TandemIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
