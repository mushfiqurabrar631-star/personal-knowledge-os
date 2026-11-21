package com.pkos.app.feature.topics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.IconBadge
import com.pkos.app.ui.components.LoadingState
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.dao.CategoryWithCounts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TopicsViewModel(private val container: AppContainer) : ViewModel() {
    val categories: StateFlow<List<CategoryWithCounts>?> = container.categories.categories()
        .map<List<CategoryWithCounts>, List<CategoryWithCounts>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Near-duplicate topics (spec 9 #4). Nothing is merged without the owner's tap. */
    val merges = kotlinx.coroutines.flow.MutableStateFlow<List<com.pkos.core.data.features.TopicService.MergeSuggestion>>(emptyList())
    init { refresh() }
    private fun refresh() { viewModelScope.launch { merges.value = container.topicTools.mergeSuggestions() } }
    fun merge(m: com.pkos.core.data.features.TopicService.MergeSuggestion) { viewModelScope.launch { container.topicTools.merge(m.from.id, m.into.id); refresh() } }
    fun keep(m: com.pkos.core.data.features.TopicService.MergeSuggestion) { viewModelScope.launch { container.topicTools.dismiss(m.from.id, m.into.id); refresh() } }
}

@Composable
fun TopicsRoute(onBack: () -> Unit) {
    val vm = pkosViewModel { TopicsViewModel(it) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    val merges by vm.merges.collectAsStateWithLifecycle()
    TopicsScreen(categories, onBack, merges, vm::merge, vm::keep)
}

/** Localized display name for system starter categories; user categories show their own name. */
fun systemCategoryLabel(id: String): Int? = when (id) {
    "sys-education" -> R.string.cat_education
    "sys-technology" -> R.string.cat_technology
    "sys-programming" -> R.string.cat_programming
    "sys-projects" -> R.string.cat_projects
    "sys-business" -> R.string.cat_business
    "sys-ideas" -> R.string.cat_ideas
    "sys-health" -> R.string.cat_health
    "sys-websites" -> R.string.cat_websites
    "sys-personal" -> R.string.cat_personal
    "sys-unsorted" -> R.string.cat_unsorted
    else -> null
}

fun categoryIcon(key: String?): ImageVector = when (key) {
    "school" -> Icons.Outlined.School
    "devices" -> Icons.Outlined.Devices
    "code" -> Icons.Outlined.Code
    "work" -> Icons.Outlined.Work
    "business" -> Icons.Outlined.Business
    "lightbulb" -> Icons.Outlined.Lightbulb
    "favorite" -> Icons.Outlined.FavoriteBorder
    "language" -> Icons.Outlined.Language
    "edit_note" -> Icons.Outlined.EditNote
    "inbox" -> Icons.Outlined.Inbox
    else -> Icons.Outlined.Folder
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicsScreen(
    categories: List<CategoryWithCounts>?, onBack: () -> Unit,
    merges: List<com.pkos.core.data.features.TopicService.MergeSuggestion> = emptyList(),
    onMerge: (com.pkos.core.data.features.TopicService.MergeSuggestion) -> Unit = {}, onKeep: (com.pkos.core.data.features.TopicService.MergeSuggestion) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.topics_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.topics_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        if (categories == null) { LoadingState(Modifier.padding(padding)); return@Scaffold }
        LazyColumn(
            Modifier.padding(padding).testTag("topics_list"),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s)) {
                    Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(Spacing.m))
                        Text(stringResource(R.string.topics_starter_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            if (merges.isNotEmpty()) item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("topics_merge")) {
                    Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(stringResource(R.string.topics_merge_title), style = MaterialTheme.typography.titleSmall)
                        merges.forEach { m ->
                            Text(stringResource(R.string.topics_merge_body, m.from.name, m.into.name, m.fromCount), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                androidx.compose.material3.Button(onClick = { onMerge(m) }, modifier = Modifier.testTag("topics_merge_${m.from.id}")) { Text(stringResource(R.string.topics_merge)) }
                                androidx.compose.material3.TextButton(onClick = { onKeep(m) }, modifier = Modifier.testTag("topics_keep_${m.from.id}")) { Text(stringResource(R.string.topics_keep)) }
                            }
                        }
                    }
                }
            }
            items(categories, key = { it.category.id }) { c ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(Spacing.l).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(categoryIcon(c.category.icon))
                        Spacer(Modifier.width(Spacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(systemCategoryLabel(c.category.id)?.let { stringResource(it) } ?: c.category.name, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.topics_counts, c.itemCount, c.topicCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
