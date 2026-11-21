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
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
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
import androidx.compose.ui.res.pluralStringResource
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.material3.FilterChip
import com.pkos.app.feature.item.itemStatusLabel
import com.pkos.app.ui.components.DropdownChip
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.components.priorityLabel
import com.pkos.app.ui.components.typeLabel
import com.pkos.app.ui.status.ToneKind
import com.pkos.core.data.db.entity.CategoryEntity
import com.pkos.core.data.features.MatchReason
import com.pkos.core.data.features.SearchFilters
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

data class SearchResults(val query: String, val items: List<KnowledgeItemEntity>, val reasons: Map<String, Set<MatchReason>> = emptyMap(), val filters: SearchFilters = SearchFilters())

data class SearchOptions(val semantic: Boolean, val cloudMeaning: Boolean, val categories: List<CategoryEntity>)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    val options: StateFlow<SearchOptions> = combine(container.settings.settings, container.categories.all()) { s, c ->
        SearchOptions(s.semanticSearchEnabled, container.cloudEmbedder() != null, c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchOptions(true, false, emptyList()))

    /**
     * Offline FTS keyword search always runs; with "Meaning" on, on-phone related words / spelling are added, and cloud
     * embeddings only when the owner enabled cloud AI. Any cloud failure silently falls back to the offline result.
     * Re-runs when the library changes.
     */
    val results: StateFlow<SearchResults> = combine(_query.debounce(200), _filters, container.settings.settings.map { it.semanticSearchEnabled }.distinctUntilChanged(), container.knowledge.counts()) { q, f, sem, _ -> Triple(q, f, sem) }
        .mapLatest { (q, f, sem) ->
            val hits = container.search.search(q, f, semantic = sem, embedder = if (sem && q.isNotBlank()) container.cloudEmbedder() else null)
            SearchResults(q.trim(), hits.map { it.item }, hits.associate { it.item.id to it.reasons }, f)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults("", emptyList()))

    fun onQuery(q: String) { _query.value = q }
    fun onFilters(f: SearchFilters) { _filters.value = f }
    fun setSemantic(on: Boolean) { viewModelScope.launch { container.settings.updateFeatures(semantic = on, reminders = null, reminderDays = null) } }
}

@Composable
fun SearchRoute(openItem: (String) -> Unit) {
    val vm = pkosViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    SearchScreen(query, results, vm::onQuery, openItem, filters = filters, onFilters = vm::onFilters, options = options, onSemantic = vm::setSemantic)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String, results: SearchResults, onQuery: (String) -> Unit, openItem: (String) -> Unit, autoFocus: Boolean = true,
    filters: SearchFilters = SearchFilters(), onFilters: (SearchFilters) -> Unit = {}, options: SearchOptions = SearchOptions(true, false, emptyList()),
    onSemantic: (Boolean) -> Unit = {},
) {
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
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.s).fillMaxWidth()) {
                SegmentedButton(selected = !options.semantic, onClick = { onSemantic(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("search_mode_keyword")) { Text(stringResource(R.string.search_mode_keyword)) }
                SegmentedButton(selected = options.semantic, onClick = { onSemantic(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("search_mode_semantic")) {
                    Text(stringResource(R.string.search_mode_semantic))
                }
            }
            if (options.semantic) Text(
                stringResource(if (options.cloudMeaning) R.string.search_cloud_meaning else R.string.search_offline_meaning),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen).testTag("search_meaning_note"),
            )
            FilterRow(filters, onFilters, options.categories)
            when {
                query.isBlank() && filters.isEmpty -> EmptyState(
                    Icons.AutoMirrored.Outlined.ManageSearch, stringResource(R.string.search_start_title),
                    stringResource(R.string.search_start_body) + "\n" + stringResource(R.string.search_semantic_note),
                    Modifier.padding(top = 32.dp),
                )
                // Debounce in flight: show progress instead of a stale "0 results".
                results.query != query.trim() || results.filters != filters -> androidx.compose.material3.LinearProgressIndicator(
                    Modifier.padding(horizontal = Spacing.screen).fillMaxWidth().testTag("search_progress"),
                )
                results.items.isEmpty() -> EmptyState(
                    Icons.Outlined.SearchOff, stringResource(R.string.search_no_results_title, query.trim().ifEmpty { stringResource(R.string.search_filters) }),
                    stringResource(R.string.search_no_results_body), Modifier.padding(top = 32.dp).testTag("search_no_results"),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.testTag("search_results"),
                ) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.search_results_count, results.items.size, results.items.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(results.items, key = { it.id }) { item ->
                        Column {
                            KnowledgeRow(item, onClick = { openItem(item.id) })
                            val why = results.reasons[item.id].orEmpty()
                            if (why.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(start = Spacing.s, top = Spacing.xs).testTag("search_reasons_${item.id}"),
                            ) { why.sortedBy { it.ordinal }.forEach { ReasonBadge(it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasonBadge(r: MatchReason) {
    val (label, tone) = when (r) {
        MatchReason.KEYWORD -> R.string.search_reason_keyword to ToneKind.NEUTRAL
        MatchReason.RELATED_WORD -> R.string.search_reason_related to ToneKind.INFO
        MatchReason.SIMILAR_SPELLING -> R.string.search_reason_spelling to ToneKind.WARNING
        MatchReason.MEANING -> R.string.search_reason_meaning to ToneKind.SUCCESS
    }
    StatusPill(stringResource(label), Icons.Outlined.Search, tone)
}

@Composable
private fun FilterRow(f: SearchFilters, on: (SearchFilters) -> Unit, categories: List<CategoryEntity>) {
    val day = 86_400_000L
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs).testTag("search_filters"),
    ) {
        if (!f.isEmpty) item { androidx.compose.material3.AssistChip(onClick = { on(SearchFilters()) }, label = { Text(stringResource(R.string.search_filter_clear)) }, modifier = Modifier.testTag("filter_clear")) }
        item { DropdownChip(stringResource(R.string.search_filter_category), categories.firstOrNull { it.id == f.categoryId }, categories, { it.name }, { on(f.copy(categoryId = it?.id)) }, "filter_category") }
        item { DropdownChip(stringResource(R.string.search_filter_status), f.status, ItemStatus.entries, { itemStatusLabel(it) }, { on(f.copy(status = it)) }, "filter_status") }
        item { DropdownChip(stringResource(R.string.search_filter_type), f.type, ContentType.entries, { typeLabel(it) }, { on(f.copy(type = it)) }, "filter_type") }
        item { DropdownChip(stringResource(R.string.search_filter_priority), f.priority, ItemPriority.entries, { priorityLabel(it) }, { on(f.copy(priority = it)) }, "filter_priority") }
        item {
            val ranges = listOf(7, 30, 365)
            val cur = f.from?.let { from -> ranges.firstOrNull { (f.to ?: 0) - from == it * day } }
            DropdownChip(stringResource(R.string.search_filter_date), cur, ranges, {
                stringResource(when (it) { 7 -> R.string.search_date_7; 30 -> R.string.search_date_30; else -> R.string.search_date_365 })
            }, { d -> val now = System.currentTimeMillis(); on(if (d == null) f.copy(from = null, to = null) else f.copy(from = now - d * day, to = now)) }, "filter_date")
        }
        item { FilterChip(selected = f.pinned == true, onClick = { on(f.copy(pinned = if (f.pinned == true) null else true)) }, label = { Text(stringResource(R.string.search_filter_pinned)) }, modifier = Modifier.testTag("filter_pinned")) }
        item { FilterChip(selected = f.notionSynced == true, onClick = { on(f.copy(notionSynced = if (f.notionSynced == true) null else true)) }, label = { Text(stringResource(R.string.search_filter_notion)) }, modifier = Modifier.testTag("filter_notion")) }
    }
}
