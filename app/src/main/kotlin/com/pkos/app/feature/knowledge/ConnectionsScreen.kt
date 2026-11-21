package com.pkos.app.feature.knowledge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.features.Edge
import com.pkos.core.data.features.NodeType
import com.pkos.core.data.model.FieldOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val edges: StateFlow<Pair<KnowledgeItemEntity?, List<Edge>>?> = combine(c.knowledge.counts(), c.database.featureDao().observeLinks(id)) { _, _ -> }
        .mapLatest { c.database.knowledgeDao().get(id) to c.graph.neighbors(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val query = MutableStateFlow("")
    val candidates = MutableStateFlow<List<KnowledgeItemEntity>>(emptyList())
    fun find(q: String) { query.value = q; viewModelScope.launch { candidates.value = if (q.isBlank()) emptyList() else c.search.search(q, semantic = false, limit = 6).map { it.item }.filter { it.id != id } } }
    fun link(other: String) { viewModelScope.launch { c.graph.link(id, other); query.value = ""; candidates.value = emptyList() } }
    fun unlink(linkId: String) { viewModelScope.launch { c.graph.unlink(linkId) } }
}

/** Knowledge graph around one item (spec 9 #3): a small map plus the list with why each link exists. */
@Composable
fun ConnectionsRoute(id: String, onBack: () -> Unit, openItem: (String) -> Unit) {
    val vm = pkosViewModel(key = "graph-$id") { ConnectionsViewModel(it, id) }
    val st by vm.edges.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val cands by vm.candidates.collectAsStateWithLifecycle()
    ConnectionsScreen(st?.first, st?.second, q, cands, vm::find, vm::link, vm::unlink, onBack, openItem)
}

@Composable
fun ConnectionsScreen(
    item: KnowledgeItemEntity?, edges: List<Edge>?, query: String, candidates: List<KnowledgeItemEntity>,
    onFind: (String) -> Unit, onLink: (String) -> Unit, onUnlink: (String) -> Unit, onBack: () -> Unit, openItem: (String) -> Unit,
) {
    ToolScaffold(stringResource(R.string.nav_connections), onBack, "connections_screen") {
        item { Text(stringResource(R.string.graph_intro), style = MaterialTheme.typography.bodyMedium) }
        val e = edges ?: return@ToolScaffold
        if (item != null && e.isNotEmpty()) item { GraphCanvas(item.title, e) }
        if (e.isEmpty()) item { Text(stringResource(R.string.graph_empty), modifier = Modifier.testTag("graph_empty")) }
        val groups = e.filter { it.type != NodeType.ITEM }
        if (groups.isNotEmpty()) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                groups.forEach { g -> AssistChip(onClick = {}, label = { Text("${edgeReasonLabel(g.reason)}: ${g.label}" + if (g.origin == FieldOrigin.USER) " ✓" else "") }) }
            }
        }
        items(e.filter { it.type == NodeType.ITEM }, key = { it.id }) { edge ->
            Panel(modifier = Modifier.testTag("graph_item_${edge.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { openItem(edge.id) }) {
                        Text(edge.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(edgeReasonLabel(edge.reason) + " · " + (edge.confidence * 100).toInt() + "%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    edge.linkId?.let { lid -> IconButton(onClick = { onUnlink(lid) }, modifier = Modifier.testTag("graph_unlink_${edge.id}")) { Icon(Icons.Outlined.LinkOff, stringResource(R.string.graph_unlink)) } }
                }
            }
        }
        item {
            Panel(stringResource(R.string.graph_link_title)) {
                OutlinedTextField(query, onFind, label = { Text(stringResource(R.string.graph_link_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("graph_link_field"))
                candidates.forEach { c -> TextButton(onClick = { onLink(c.id) }, modifier = Modifier.testTag("graph_link_${c.id}")) { Text("+ " + c.title, maxLines = 1) } }
            }
        }
    }
}

@Composable
private fun GraphCanvas(center: String, edges: List<Edge>) {
    val shown = edges.take(16)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.fillMaxWidth().height(220.dp).padding(vertical = Spacing.s).semantics { contentDescription = "$center: ${shown.size}" }.testTag("graph_canvas")) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = minOf(size.width, size.height) / 2 - 18.dp.toPx()
        shown.forEachIndexed { i, e ->
            val a = 2 * Math.PI * i / shown.size
            val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            val color = when (e.type) { NodeType.ITEM -> if (e.origin == FieldOrigin.USER) primary else secondary; NodeType.TAG -> tertiary; else -> outline }
            drawLine(color.copy(alpha = 0.35f + 0.5f * e.confidence.toFloat()), c, p, strokeWidth = (1 + 3 * e.confidence).dp.toPx())
            drawCircle(color, radius = if (e.type == NodeType.ITEM) 9.dp.toPx() else 6.dp.toPx(), center = p)
        }
        drawCircle(primary, radius = 16.dp.toPx(), center = c)
        drawCircle(primary.copy(alpha = 0.3f), radius = 22.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
    }
}
