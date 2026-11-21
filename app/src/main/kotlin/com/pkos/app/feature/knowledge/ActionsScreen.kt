package com.pkos.app.feature.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.entity.ActionItemEntity
import com.pkos.core.data.features.ActionService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Confirm / dismiss / done — shared by the Tasks screen and the item screen. */
class ActionOps(private val c: AppContainer, private val scope: kotlinx.coroutines.CoroutineScope) {
    fun confirm(id: String, text: String, dueAt: Long?) { scope.launch { c.actions.confirm(id, text, dueAt) } }
    fun dismiss(id: String) { scope.launch { c.actions.dismiss(id) } }
    fun done(id: String, done: Boolean) { scope.launch { c.actions.done(id, done) } }
}

data class ActionsUi(val actions: List<ActionItemEntity>, val titles: Map<String, String>)

class ActionsViewModel(c: AppContainer) : ViewModel() {
    val ops = ActionOps(c, viewModelScope)
    val ui: StateFlow<ActionsUi?> = c.database.featureDao().observeActions(listOf(ActionService.PROPOSED, ActionService.CONFIRMED, ActionService.DONE))
        .map { list -> ActionsUi(list, c.database.featureDao().liveItems().associate { it.id to it.title }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ActionsRoute(onBack: () -> Unit, openItem: (String) -> Unit) {
    val vm = pkosViewModel { ActionsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    ToolScaffold(stringResource(R.string.nav_actions), onBack, "actions_list") {
        val u = ui ?: return@ToolScaffold
        val visible = u.actions.filter { it.knowledgeId in u.titles }
        if (visible.isEmpty()) item { EmptyState(Icons.Outlined.Checklist, stringResource(R.string.actions_empty_title), stringResource(R.string.actions_empty_body), Modifier.padding(top = 32.dp)) }
        listOf(
            R.string.actions_proposed to visible.filter { it.status == ActionService.PROPOSED },
            R.string.actions_confirmed to visible.filter { it.status == ActionService.CONFIRMED },
            R.string.actions_done to visible.filter { it.status == ActionService.DONE },
        ).forEach { (title, list) ->
            if (list.isNotEmpty()) {
                item { Text(stringResource(title), style = MaterialTheme.typography.titleSmall) }
                items(list, key = { it.id }) { a -> ActionCard(a, vm.ops, source = u.titles[a.knowledgeId], openSource = { openItem(a.knowledgeId) }) }
            }
        }
    }
}

@Composable
fun ActionCard(a: ActionItemEntity, ops: ActionOps, source: String? = null, openSource: (() -> Unit)? = null) {
    Panel(modifier = Modifier.testTag("action_${a.id}")) {
        when (a.status) {
            ActionService.PROPOSED -> ProposedAction(a, ops)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = a.status == ActionService.DONE, onCheckedChange = { ops.done(a.id, it) }, modifier = Modifier.testTag("action_done_${a.id}"))
                Column(Modifier.weight(1f)) {
                    Text(a.text, style = MaterialTheme.typography.bodyLarge, textDecoration = if (a.status == ActionService.DONE) TextDecoration.LineThrough else null)
                    a.dueAt?.let { Text(stringResource(R.string.action_due, Formatters.date(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (source != null && openSource != null) TextButton(onClick = openSource) { Text(stringResource(R.string.action_from, source), maxLines = 1) }
    }
}

/** A suggestion: the owner may edit the text and choose a date; nothing is created until "Confirm". */
@Composable
private fun ProposedAction(a: ActionItemEntity, ops: ActionOps) {
    var text by remember(a.id) { mutableStateOf(a.text) }
    var due by remember(a.id) { mutableStateOf<Long?>(null) }
    if (a.kind == "IDEA") StatusPill(stringResource(R.string.action_kind_idea), Icons.Outlined.Lightbulb, ToneKind.INFO)
    OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.action_text)) }, modifier = Modifier.fillMaxWidth().testTag("action_text_${a.id}"))
    val zone = ZoneId.systemDefault()
    fun day(d: LocalDate) = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    val today = LocalDate.now(zone)
    val choices = listOf(R.string.action_no_date to null, R.string.action_today to day(today), R.string.action_tomorrow to day(today.plusDays(1)), R.string.action_next_week to day(today.plusWeeks(1)))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        choices.forEachIndexed { i, (label, v) -> FilterChip(selected = due == v, onClick = { due = v }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("action_date_${i}_${a.id}")) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Button(onClick = { ops.confirm(a.id, text, due) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("action_confirm_${a.id}")) { Text(stringResource(R.string.action_confirm)) }
        TextButton(onClick = { ops.dismiss(a.id) }, modifier = Modifier.testTag("action_dismiss_${a.id}")) { Text(stringResource(R.string.action_dismiss)) }
    }
}
