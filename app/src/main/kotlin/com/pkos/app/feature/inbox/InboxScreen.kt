package com.pkos.app.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.components.KnowledgeRow
import com.pkos.app.ui.components.LoadingState
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.dao.KnowledgeCounts
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.model.InboxFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

sealed interface InboxUiState {
    data object Loading : InboxUiState
    data class Ready(val filter: InboxFilter, val items: List<KnowledgeItemEntity>, val counts: KnowledgeCounts) : InboxUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(container: AppContainer, initial: InboxFilter) : ViewModel() {
    private val filter = MutableStateFlow(initial)
    private val repo = container.knowledge

    val state: StateFlow<InboxUiState> = combine(
        filter,
        filter.flatMapLatest { repo.inbox(it) },
        repo.counts(),
    ) { f, items, counts -> InboxUiState.Ready(f, items, counts) as InboxUiState }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState.Loading)

    fun select(f: InboxFilter) { filter.value = f }
}

@Composable
fun InboxRoute(initialFilter: InboxFilter, openItem: (String) -> Unit) {
    val vm = pkosViewModel { InboxViewModel(it, initialFilter) }
    val state by vm.state.collectAsStateWithLifecycle()
    InboxScreen(state, vm::select, openItem)
}

private fun InboxFilter.label() = when (this) {
    InboxFilter.ALL -> R.string.inbox_filter_all
    InboxFilter.PROCESSING -> R.string.inbox_filter_processing
    InboxFilter.NEEDS_REVIEW -> R.string.inbox_filter_needs_review
    InboxFilter.FAILED -> R.string.inbox_filter_failed
    InboxFilter.SYNC_PENDING -> R.string.inbox_filter_sync_pending
}

private fun KnowledgeCounts.of(f: InboxFilter) = when (f) {
    InboxFilter.ALL -> inbox
    InboxFilter.PROCESSING -> processing
    InboxFilter.NEEDS_REVIEW -> needsReview
    InboxFilter.FAILED -> actionRequired
    InboxFilter.SYNC_PENDING -> pendingSync
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(state: InboxUiState, onSelect: (InboxFilter) -> Unit, openItem: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(stringResource(R.string.inbox_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.inbox_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
        },
    ) { padding ->
        when (state) {
            InboxUiState.Loading -> LoadingState(Modifier.padding(padding))
            is InboxUiState.Ready -> Column(Modifier.padding(padding).fillMaxSize()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.testTag("inbox_filters"),
                ) {
                    items(InboxFilter.entries) { f ->
                        val count = state.counts.of(f)
                        FilterChip(
                            selected = f == state.filter,
                            onClick = { onSelect(f) },
                            label = { Text(if (count > 0) "${stringResource(f.label())} · $count" else stringResource(f.label())) },
                        )
                    }
                }
                if (state.items.isEmpty()) {
                    InboxEmpty(state.filter)
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.s, bottom = Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        items(state.items, key = { it.id }) { KnowledgeRow(it, onClick = { openItem(it.id) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxEmpty(filter: InboxFilter) {
    val (icon, title, body) = when (filter) {
        InboxFilter.ALL -> Triple(Icons.Outlined.Inbox, R.string.inbox_empty_all_title, R.string.inbox_empty_all_body)
        InboxFilter.PROCESSING -> Triple(Icons.Outlined.AutoAwesome, R.string.inbox_empty_filter_title, R.string.inbox_empty_processing_body)
        InboxFilter.NEEDS_REVIEW -> Triple(Icons.Outlined.RateReview, R.string.inbox_empty_filter_title, R.string.inbox_empty_review_body)
        InboxFilter.FAILED -> Triple(Icons.Outlined.CheckCircle, R.string.inbox_empty_filter_title, R.string.inbox_empty_failed_body)
        InboxFilter.SYNC_PENDING -> Triple(Icons.Outlined.SyncAlt, R.string.inbox_empty_filter_title, R.string.inbox_empty_sync_body)
    }
    EmptyState(icon, stringResource(title), stringResource(body), Modifier.padding(top = 48.dp).testTag("inbox_empty"))
}
