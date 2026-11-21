package com.pkos.app.feature.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.entity.ActionItemEntity
import com.pkos.core.data.db.entity.CollectionEntity
import com.pkos.core.data.features.ActionService
import com.pkos.core.data.features.Edge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ItemKnowledgeViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val ops = ActionOps(c, viewModelScope)
    private val bump = MutableStateFlow(0)
    val actions: StateFlow<List<ActionItemEntity>> = c.database.featureDao().observeActionsFor(id).map { l -> l.filter { it.status != ActionService.DISMISSED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val related: StateFlow<List<Edge>> = combine(c.knowledge.counts(), bump) { _, _ -> }.mapLatest { runCatching { c.recommender.relatedTo(id, 5) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val manual: StateFlow<List<CollectionEntity>> = c.smartCollections.observe().map { l -> l.filter { !it.isSmart && !it.isSuggested } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val memberOf: StateFlow<Set<String>> = c.database.featureDao().observeCollectionsOf(id).map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun dismissRelated(target: String) { viewModelScope.launch { c.recommender.dismissRelated(id, target); bump.value++ } }
    fun toggle(collectionId: String, on: Boolean) { viewModelScope.launch { if (on) c.smartCollections.add(collectionId, id) else c.smartCollections.remove(collectionId, id) } }
}

/** Item screen extras: suggested tasks (need confirmation), related items (dismissible), connections, collections. */
@Composable
fun ItemKnowledgeSection(id: String, openItem: (String) -> Unit, openConnections: (String) -> Unit) {
    val vm = pkosViewModel(key = "item-knowledge-$id") { ItemKnowledgeViewModel(it, id) }
    val actions by vm.actions.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val manual by vm.manual.collectAsStateWithLifecycle()
    val memberOf by vm.memberOf.collectAsStateWithLifecycle()
    if (actions.isNotEmpty()) Panel(stringResource(R.string.item_section_actions), Modifier.testTag("item_actions")) {
        actions.forEach { ActionCard(it, vm.ops) }
    }
    if (related.isNotEmpty()) Panel(stringResource(R.string.item_section_related), Modifier.testTag("item_related")) {
        related.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openItem(e.id) }, modifier = Modifier.weight(1f)) {
                    Text(e.label + " · " + edgeReasonLabel(e.reason), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { vm.dismissRelated(e.id) }, modifier = Modifier.testTag("related_dismiss_${e.id}")) { Icon(Icons.Outlined.Close, stringResource(R.string.rec_dismiss)) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(onClick = { openConnections(id) }, modifier = Modifier.testTag("item_connections")) { Icon(Icons.Outlined.Hub, null); Text(" " + stringResource(R.string.item_open_connections)) }
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, enabled = manual.isNotEmpty(), modifier = Modifier.testTag("item_add_collection")) { Icon(Icons.Outlined.PlaylistAdd, null); Text(" " + stringResource(R.string.item_add_collection)) }
            DropdownMenu(open, { open = false }) {
                manual.forEach { col ->
                    val on = col.id in memberOf
                    DropdownMenuItem(text = { Text(col.name) }, leadingIcon = { Checkbox(on, null) }, onClick = { vm.toggle(col.id, !on) }, modifier = Modifier.testTag("add_to_${col.name}"))
                }
            }
        }
    }
}
