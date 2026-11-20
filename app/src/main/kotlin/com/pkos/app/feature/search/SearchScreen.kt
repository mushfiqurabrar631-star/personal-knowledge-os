package com.pkos.app.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.components.KnowledgeRow
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SearchResults(val query: String, val items: List<KnowledgeItemEntity>)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(container: AppContainer) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Local keyword search (works offline). Semantic search is M6. */
    val results: StateFlow<SearchResults> = _query
        .debounce(200)
        .flatMapLatest { q -> container.knowledge.search(q).map { SearchResults(q.trim(), it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults("", emptyList()))

    fun onQuery(q: String) { _query.value = q }
}

@Composable
fun SearchRoute(openItem: (String) -> Unit) {
    val vm = pkosViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    SearchScreen(query, results, vm::onQuery, openItem)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(query: String, results: SearchResults, onQuery: (String) -> Unit, openItem: (String) -> Unit, autoFocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.search_title), style = MaterialTheme.typography.titleLarge) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.search_field_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.search_clear)) }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.padding(horizontal = Spacing.screen).fillMaxWidth().focusRequester(focus).testTag("search_field"),
            )
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.m).fillMaxWidth()) {
                SegmentedButton(selected = true, onClick = {}, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.search_mode_keyword)) }
                SegmentedButton(selected = false, onClick = {}, enabled = false, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.search_mode_semantic) + " · M6")
                }
            }
            when {
                query.isBlank() -> EmptyState(
                    Icons.Outlined.ManageSearch, stringResource(R.string.search_start_title),
                    stringResource(R.string.search_start_body) + "\n" + stringResource(R.string.search_semantic_note),
                    Modifier.padding(top = 32.dp),
                )
                results.query == query.trim() && results.items.isEmpty() -> EmptyState(
                    Icons.Outlined.SearchOff, stringResource(R.string.search_no_results_title, query.trim()),
                    stringResource(R.string.search_no_results_body), Modifier.padding(top = 32.dp).testTag("search_no_results"),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.testTag("search_results"),
                ) {
                    item {
                        Text(
                            stringResource(R.string.search_results_count, results.items.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(results.items, key = { it.id }) { KnowledgeRow(it, onClick = { openItem(it.id) }) }
                }
            }
        }
    }
}
