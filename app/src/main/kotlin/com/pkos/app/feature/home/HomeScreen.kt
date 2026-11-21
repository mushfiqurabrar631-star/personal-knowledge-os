package com.pkos.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Topic
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pkos.app.R
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.components.IconBadge
import com.pkos.app.ui.components.KnowledgeRow
import com.pkos.app.ui.components.LoadingState
import com.pkos.app.ui.components.NavigationRowCard
import com.pkos.app.ui.components.SectionHeader
import com.pkos.app.ui.components.StatTile
import com.pkos.app.ui.components.toneOf
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.model.InboxFilter

data class HomeActions(
    val openSearch: () -> Unit,
    val openCapture: () -> Unit,
    val openInbox: (InboxFilter) -> Unit,
    val openItem: (String) -> Unit,
    val openTopics: () -> Unit,
    val openAskAi: () -> Unit,
    val openBackupSync: () -> Unit,
    val openSettings: () -> Unit,
    val openTimeline: () -> Unit = {},
    val openActions: () -> Unit = {},
    val openCollections: () -> Unit = {},
    val dismissNext: (String) -> Unit = {},
)

@Composable
fun HomeRoute(actions: HomeActions) {
    val vm = pkosViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    HomeScreen(state, actions.copy(dismissNext = vm::dismissNext), onDismissSuggestion = vm::dismissSuggestion)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, onDismissSuggestion: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { AppIdentity() },
                actions = {
                    if (state is HomeUiState.Ready) {
                        val label = stringResource(state.health.label)
                        val tone = toneOf(state.health.tone)
                        IconButton(
                            onClick = actions.openBackupSync,
                            modifier = Modifier.testTag("health"),
                        ) {
                            Surface(color = tone.container, contentColor = tone.content, shape = CircleShape, modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(state.health.icon, contentDescription = stringResource(R.string.home_sync_status_cd, label), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    IconButton(onClick = actions.openSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.home_settings_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        when (state) {
            HomeUiState.Loading -> LoadingState(Modifier.padding(padding))
            is HomeUiState.Ready -> HomeContent(state, actions, onDismissSuggestion, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppIdentity() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.small, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(18.dp)) }
        }
        Spacer(Modifier.width(Spacing.m))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun HomeContent(state: HomeUiState.Ready, actions: HomeActions, onDismissSuggestion: (String) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.testTag("home_list"),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.xl),
    ) {
        item { SearchEntry(actions.openSearch) }
        if (state.counts.actionRequired > 0) {
            item { ActionBanner(state.counts.actionRequired) { actions.openInbox(InboxFilter.FAILED) } }
        }
        item { CaptureCard(actions.openCapture) }
        item { StatusSummary(state, actions) }
        item { SectionHeader(stringResource(R.string.home_continue_title)) }
        if (state.recentTopics.isNotEmpty()) {
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    items(state.recentTopics, key = { it.id }) { t ->
                        AssistChip(onClick = actions.openTopics, label = { Text(t.name) }, leadingIcon = { Icon(Icons.Outlined.Topic, null) })
                    }
                }
            }
        }
        if (state.recent.isEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = Spacing.screen).fillMaxWidth()) {
                    EmptyState(Icons.Outlined.CollectionsBookmark, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
                }
            }
        } else {
            items(state.recent, key = { it.id }) { item ->
                KnowledgeRow(item, onClick = { actions.openItem(item.id) }, modifier = Modifier.padding(horizontal = Spacing.screen))
            }
        }
        if (state.nextUseful.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_next_title)) }
            items(state.nextUseful, key = { "next-" + it.id }) { item ->
                Row(Modifier.padding(horizontal = Spacing.screen).testTag("home_next_${item.id}"), verticalAlignment = Alignment.CenterVertically) {
                    KnowledgeRow(item, onClick = { actions.openItem(item.id) }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.dismissNext(item.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.rec_dismiss)) }
                }
            }
        }
        if (state.rediscover.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_rediscover_title)) }
            items(state.rediscover, key = { "re-" + it.id }) { item ->
                KnowledgeRow(item, onClick = { actions.openItem(item.id) }, modifier = Modifier.padding(horizontal = Spacing.screen).testTag("home_rediscover_${item.id}"))
            }
        }
        if (state.suggestions.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_suggested_title)) }
            items(state.suggestions, key = { it.id }) { c ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = Spacing.screen).fillMaxWidth()) {
                    Row(Modifier.padding(start = Spacing.l, top = Spacing.s, bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Outlined.CollectionsBookmark)
                        Spacer(Modifier.width(Spacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            c.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        IconButton(onClick = { onDismissSuggestion(c.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_dismiss)) }
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.home_explore_title)) }
        item {
            Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                NavigationRowCard(Icons.Outlined.Topic, stringResource(R.string.nav_topics), stringResource(R.string.home_explore_topics_body), actions.openTopics, Modifier.fillMaxWidth())
                NavigationRowCard(Icons.Outlined.Forum, stringResource(R.string.nav_ask_ai), stringResource(R.string.home_explore_ask_body), actions.openAskAi, Modifier.fillMaxWidth())
                NavigationRowCard(Icons.Outlined.Checklist, stringResource(R.string.nav_actions), stringResource(R.string.home_explore_actions_body), actions.openActions, Modifier.fillMaxWidth().testTag("home_open_actions"))
                NavigationRowCard(Icons.Outlined.Bookmarks, stringResource(R.string.nav_collections), stringResource(R.string.home_explore_collections_body), actions.openCollections, Modifier.fillMaxWidth().testTag("home_open_collections"))
                NavigationRowCard(Icons.Outlined.Timeline, stringResource(R.string.nav_timeline), stringResource(R.string.home_explore_timeline_body), actions.openTimeline, Modifier.fillMaxWidth().testTag("home_open_timeline"))
            }
        }
    }
}

/** Looks like a field, behaves as a button that opens Search (spec 2.3 #2). */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val hint = stringResource(R.string.home_search_hint)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.padding(horizontal = Spacing.screen).fillMaxWidth().heightIn(min = 56.dp)
            .semantics { role = Role.Button; contentDescription = hint }
            .testTag("home_search"),
    ) {
        Row(Modifier.padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.m))
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionBanner(count: Int, onClick: () -> Unit) {
    val tone = toneOf(ToneKind.DANGER)
    Surface(onClick = onClick, color = tone.container, contentColor = tone.content, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = Spacing.screen).fillMaxWidth()) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null)
            Spacer(Modifier.width(Spacing.m))
            Text(pluralStringResource(R.plurals.home_action_banner, count, count), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Prominent but calm primary capture action; Share integration explained separately (spec 2.3 #3). */
@Composable
private fun CaptureCard(onCapture: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = Spacing.screen).fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_capture_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.home_capture_body), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.width(Spacing.m))
                Button(onClick = onCapture, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).testTag("home_capture")) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.home_capture_button))
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.IosShare, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.m))
                    Text(stringResource(R.string.home_capture_share_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusSummary(state: HomeUiState.Ready, actions: HomeActions) {
    val c = state.counts
    Column {
        SectionHeader(stringResource(R.string.home_status_title))
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                StatTile(stringResource(R.string.home_stat_saved), "${c.saved}", Icons.Outlined.Inventory2, ToneKind.NEUTRAL, Modifier.weight(1f)) { actions.openInbox(InboxFilter.ALL) }
                StatTile(
                    stringResource(R.string.home_stat_needs_review), "${c.needsReview}", Icons.Outlined.RateReview,
                    if (c.needsReview > 0) ToneKind.WARNING else ToneKind.NEUTRAL, Modifier.weight(1f),
                ) { actions.openInbox(InboxFilter.NEEDS_REVIEW) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                StatTile(
                    stringResource(R.string.home_stat_pending_sync), "${c.pendingSync}", Icons.Outlined.SyncAlt,
                    if (c.pendingSync > 0) ToneKind.INFO else ToneKind.NEUTRAL, Modifier.weight(1f),
                ) { actions.openInbox(InboxFilter.SYNC_PENDING) }
                StatTile(
                    stringResource(R.string.home_stat_last_backup),
                    state.lastBackupAt?.let { Formatters.dateTime(it) } ?: stringResource(R.string.home_stat_backup_never),
                    Icons.Outlined.CloudQueue,
                    if (state.lastBackupAt != null) ToneKind.SUCCESS else ToneKind.NEUTRAL,
                    Modifier.weight(1f),
                    actions.openBackupSync,
                )
            }
        }
    }
}
