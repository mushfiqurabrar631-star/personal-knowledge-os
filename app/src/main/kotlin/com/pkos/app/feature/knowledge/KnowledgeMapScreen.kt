package com.pkos.app.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.features.EdgeReason
import com.pkos.core.data.features.KnowledgeMap
import com.pkos.core.data.features.MapNode
import com.pkos.core.data.features.NodeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class MapUi(val map: KnowledgeMap, val titles: Map<String, String>, val loaded: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeMapViewModel(c: AppContainer) : ViewModel() {
    val ui: StateFlow<MapUi> = c.knowledge.counts().mapLatest {
        val m = c.graph.overview()
        val titles = c.database.featureDao().liveItems().associate { it.id to it.title }
        MapUi(m, titles, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUi(KnowledgeMap(emptyList(), emptyList()), emptyMap(), false))
}

@Composable
fun KnowledgeMapRoute(onBack: () -> Unit, openItem: (String) -> Unit) {
    val vm = pkosViewModel { KnowledgeMapViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    KnowledgeMapScreen(ui, onBack, openItem)
}

/**
 * Library map (spec 9 #3): projects with their topics and concepts, concepts that join projects, and links between
 * projects. A list, not a picture, so it reads well with TalkBack and large text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnowledgeMapScreen(ui: MapUi, onBack: () -> Unit, openItem: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val m = ui.map
    ToolScaffold(stringResource(R.string.nav_knowledge_map), onBack, "knowledge_map") {
        if (ui.loaded && m.nodes.isEmpty()) {
            item { EmptyState(Icons.Outlined.Hub, stringResource(R.string.map_empty_title), stringResource(R.string.map_empty_body), Modifier.padding(top = 32.dp)) }
            return@ToolScaffold
        }
        item { Text(stringResource(R.string.map_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val projects = m.nodes.filter { it.type == NodeType.CATEGORY }
        items(projects, key = { "p:" + it.id }) { p ->
            val pk = m.key(p)
            val topics = m.edges.filter { it.reason == EdgeReason.IN_PROJECT && it.to == pk }.mapNotNull { e -> m.node(e.from)?.let { it to e.weight } }
            val concepts = m.edges.filter { it.reason == EdgeReason.CONCEPT_IN && it.to == pk }.mapNotNull { e -> m.node(e.from) }
            val crossTo = m.edges.filter { it.reason == EdgeReason.CROSS_LINK && (it.from == pk || it.to == pk) }
                .mapNotNull { e -> m.node(if (e.from == pk) e.to else e.from)?.let { it to e.weight } }
            Panel(p.label, Modifier.testTag("map_project")) {
                Text(pluralStringResource(R.plurals.map_items, p.items, p.items), style = MaterialTheme.typography.bodyMedium)
                if (topics.isNotEmpty()) Text(stringResource(R.string.map_topics, topics.joinToString(", ") { "${it.first.label} (${it.second})" }), style = MaterialTheme.typography.bodySmall)
                if (concepts.isNotEmpty()) ConceptChips(concepts, open) { open = if (open == it) null else it }
                if (crossTo.isNotEmpty()) Text(stringResource(R.string.map_cross, crossTo.joinToString(", ") { "${it.first.label} (${it.second})" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                open?.let { key -> m.node(key)?.takeIf { n -> concepts.any { m.key(it) == key } }?.let { ItemsOf(it, ui.titles, openItem) } }
                ItemsOfProject(p, ui.titles, openItem)
            }
        }
        val bridges = m.nodes.filter { it.type == NodeType.CONCEPT }
            .map { c -> c to m.edges.filter { it.reason == EdgeReason.CONCEPT_IN && it.from == m.key(c) }.mapNotNull { e -> m.node(e.to)?.label } }
            .filter { it.second.size >= 2 }
        if (bridges.isNotEmpty()) item {
            Panel(stringResource(R.string.map_bridges), Modifier.testTag("map_bridges")) {
                bridges.forEach { (c, ps) -> Text("• ${c.label}: ${ps.joinToString(" · ")}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        val pairs = m.edges.filter { it.reason == EdgeReason.CO_OCCURS }.take(12)
        if (pairs.isNotEmpty()) item {
            Panel(stringResource(R.string.map_together), Modifier.testTag("map_together")) {
                pairs.forEach { e -> Text("• ${m.node(e.from)?.label} + ${m.node(e.to)?.label} (${e.weight})", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConceptChips(concepts: List<MapNode>, open: String?, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        concepts.forEach { c ->
            val key = "${c.type.name}:${c.id}"
            FilterChip(selected = open == key, onClick = { onToggle(key) }, label = { Text("${c.label} (${c.items})") }, modifier = Modifier.testTag("map_concept_${c.id}"))
        }
    }
}

@Composable
private fun ItemsOf(n: MapNode, titles: Map<String, String>, openItem: (String) -> Unit) {
    n.sampleItemIds.forEach { id ->
        Text("• " + (titles[id] ?: "…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1,
            modifier = Modifier.fillMaxWidth().clickable { openItem(id) }.padding(vertical = Spacing.xs).testTag("map_item"))
    }
}

@Composable
private fun ItemsOfProject(p: MapNode, titles: Map<String, String>, openItem: (String) -> Unit) {
    if (p.sampleItemIds.isEmpty()) return
    Text(stringResource(R.string.map_recent), style = MaterialTheme.typography.labelMedium)
    ItemsOf(p, titles, openItem)
}
