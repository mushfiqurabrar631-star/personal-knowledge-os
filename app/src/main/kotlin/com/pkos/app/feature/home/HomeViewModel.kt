package com.pkos.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pkos.app.di.AppContainer
import com.pkos.app.di.IntegrationStatus
import com.pkos.app.ui.status.AppHealthUi
import com.pkos.core.data.db.dao.KnowledgeCounts
import com.pkos.core.data.db.entity.CollectionEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.TopicEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(
        val counts: KnowledgeCounts,
        val recent: List<KnowledgeItemEntity>,
        val recentTopics: List<TopicEntity>,
        val suggestions: List<CollectionEntity>,
        /** Epoch millis of the last VERIFIED backup, or null. */
        val lastBackupAt: Long?,
        val health: AppHealthUi,
        val integrations: IntegrationStatus,
    ) : HomeUiState {
        val isEmpty: Boolean get() = counts.saved == 0
    }
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val integrations = container.integrations.current()

    val state: StateFlow<HomeUiState> = combine(
        container.knowledge.counts(),
        container.knowledge.recent(limit = 5),
        container.categories.recentlyOpenedTopics(limit = 4),
        container.collections.suggestions(),
        combine(container.syncOverview.lastVerifiedBackup(), container.syncOverview.queueCounts()) { b, q -> b to q },
    ) { counts, recent, topics, suggestions, (backup, queue) ->
        HomeUiState.Ready(
            counts = counts,
            recent = recent,
            recentTopics = topics,
            suggestions = suggestions,
            lastBackupAt = backup?.createdAt,
            health = AppHealthUi.of(counts, queue.actionRequired, integrations.anyDestinationConnected),
            integrations = integrations,
        ) as HomeUiState
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun dismissSuggestion(id: String) {
        viewModelScope.launch { container.collections.dismiss(id) }
    }
}
