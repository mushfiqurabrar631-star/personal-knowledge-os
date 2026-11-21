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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
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
        /** M6: "next useful" (dismissible) and opt-in "rediscover" items, computed on the phone. */
        val nextUseful: List<KnowledgeItemEntity> = emptyList(),
        val rediscover: List<KnowledgeItemEntity> = emptyList(),
    ) : HomeUiState {
        val isEmpty: Boolean get() = counts.saved == 0
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val integrations = container.integrations.current()
    private val bump = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val extras = combine(container.knowledge.counts(), container.settings.settings, bump) { _, s, _ -> s }.mapLatest { s ->
        val next = runCatching { container.recommender.nextUseful(3) }.getOrDefault(emptyList())
        val re = if (s.remindersEnabled) runCatching { container.reminders.candidates(s.reminderIntervalDays, limit = 2) }.getOrDefault(emptyList()).filter { r -> next.none { it.id == r.id } } else emptyList()
        next to re
    }

    private val base: kotlinx.coroutines.flow.Flow<HomeUiState> = combine(
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
    }

    val state: StateFlow<HomeUiState> = combine(base, extras.onStart { emit(emptyList<KnowledgeItemEntity>() to emptyList()) }) { b, (n, r) ->
        if (b is HomeUiState.Ready) b.recent.map { it.id }.toSet().let { shown -> b.copy(nextUseful = n.filter { it.id !in shown }, rediscover = r.filter { it.id !in shown }) } else b
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun dismissNext(id: String) { viewModelScope.launch { container.recommender.dismissHome(id); bump.value++ } }

    fun dismissSuggestion(id: String) {
        viewModelScope.launch { container.collections.dismiss(id) }
    }
}
