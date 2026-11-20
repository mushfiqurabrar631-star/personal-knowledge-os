package com.pkos.app.feature.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.feature.topics.systemCategoryLabel
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.components.LoadingState
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ItemStatusUi
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.repository.KnowledgeItemDetail
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ItemUiState {
    data object Loading : ItemUiState
    data object NotFound : ItemUiState
    data class Ready(val detail: KnowledgeItemDetail) : ItemUiState
}

class ItemDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    val state: StateFlow<ItemUiState> = container.knowledge.detail(id)
        .map { it?.let { d -> ItemUiState.Ready(d) } ?: ItemUiState.NotFound }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemUiState.Loading)

    fun markOpened() { viewModelScope.launch { container.knowledge.markOpened(id) } }
}

@Composable
fun ItemDetailRoute(id: String, onBack: () -> Unit) {
    val vm = pkosViewModel(key = "item-$id") { ItemDetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.markOpened() }
    ItemDetailScreen(state, onBack)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemDetailScreen(state: ItemUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        when (state) {
            ItemUiState.Loading -> LoadingState(Modifier.padding(padding))
            ItemUiState.NotFound -> EmptyState(
                Icons.Outlined.SearchOff, stringResource(R.string.item_not_found_title), stringResource(R.string.item_not_found_body),
                Modifier.padding(padding),
            )
            is ItemUiState.Ready -> {
                val d = state.detail
                val item = d.item
                val status = ItemStatusUi.of(item.processingState, item.syncState)
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("item_detail"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(item.title, style = MaterialTheme.typography.headlineSmall)
                        StatusPill(stringResource(status.label), status.icon, status.tone)
                        Text(
                            stringResource(R.string.item_captured_at, Formatters.dateTime(item.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.reviewReason?.let {
                            Text(stringResource(R.string.item_review_reason, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item.summary?.takeIf { it.isNotBlank() }?.let { Section(stringResource(R.string.item_section_summary)) { Body(it) } }
                    Section(stringResource(R.string.item_section_organized)) {
                        val categoryName = d.category?.let { c -> systemCategoryLabel(c.id)?.let { stringResource(it) } ?: c.name }
                        if (categoryName == null && d.topic == null && d.tags.isEmpty()) {
                            Body(stringResource(R.string.item_not_organized))
                        } else {
                            categoryName?.let { Labeled(stringResource(R.string.item_category), it) }
                            d.topic?.let { Labeled(stringResource(R.string.item_topic), it.name) }
                            if (d.tags.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                    d.tags.forEach { t -> AssistChip(onClick = {}, label = { Text(t.name) }) }
                                }
                            }
                        }
                    }
                    item.rawText?.takeIf { it.isNotBlank() }?.let { Section(stringResource(R.string.item_section_original)) { SelectionContainer { Body(it) } } }
                    if (item.sourceUrl != null || d.sources.isNotEmpty()) {
                        Section(stringResource(R.string.item_section_source)) {
                            item.sourceUrl?.let { SelectionContainer { Body(it) } }
                            d.sources.forEach { s ->
                                Body(listOfNotNull(s.captureMethod.name.lowercase().replace('_', ' '), s.sourceApp, Formatters.dateTime(s.capturedAt)).joinToString(" · "))
                            }
                        }
                    }
                    Section(stringResource(R.string.item_section_history)) {
                        if (d.versions.isEmpty()) Body(stringResource(R.string.item_no_history))
                        d.versions.forEach { v -> Body(stringResource(R.string.item_version, v.version, Formatters.dateTime(v.changedAt)) + " · " + v.changeSource.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Labeled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
