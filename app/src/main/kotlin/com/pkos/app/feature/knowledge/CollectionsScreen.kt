package com.pkos.app.feature.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.feature.item.itemStatusLabel
import com.pkos.app.ui.components.DropdownChip
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.components.KnowledgeRow
import com.pkos.app.ui.components.NavigationRowCard
import com.pkos.app.ui.components.priorityLabel
import com.pkos.app.ui.components.typeLabel
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.entity.CategoryEntity
import com.pkos.core.data.db.entity.CollectionEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.TopicEntity
import com.pkos.core.data.features.CollectionRule
import com.pkos.core.data.features.RuleCondition
import com.pkos.core.data.features.RuleField
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.SourcePlatform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionsViewModel(private val c: AppContainer) : ViewModel() {
    val list: StateFlow<List<CollectionEntity>?> = c.smartCollections.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun CollectionsRoute(onBack: () -> Unit, open: (String) -> Unit, create: (Boolean) -> Unit) {
    val vm = pkosViewModel { CollectionsViewModel(it) }
    val list by vm.list.collectAsStateWithLifecycle()
    ToolScaffold(stringResource(R.string.nav_collections), onBack, "collections_list") {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = { create(false) }, modifier = Modifier.testTag("collection_new_manual")) { Text(stringResource(R.string.collection_new_manual)) }
                OutlinedButton(onClick = { create(true) }, modifier = Modifier.testTag("collection_new_smart")) { Text(stringResource(R.string.collection_new_smart)) }
            }
        }
        val l = list ?: return@ToolScaffold
        if (l.isEmpty()) item { EmptyState(Icons.Outlined.CollectionsBookmark, stringResource(R.string.collections_empty_title), stringResource(R.string.collections_empty_body), Modifier.padding(top = 32.dp)) }
        items(l, key = { it.id }) { col ->
            NavigationRowCard(
                if (col.isSmart) Icons.Outlined.AutoAwesome else Icons.Outlined.CollectionsBookmark, col.name,
                stringResource(if (col.isSmart) R.string.collection_smart else R.string.collection_manual), { open(col.id) }, Modifier.fillMaxWidth().testTag("collection_${col.name}"),
            )
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val state: StateFlow<Pair<CollectionEntity?, List<KnowledgeItemEntity>>?> = combine(c.smartCollections.observe(), c.knowledge.counts()) { l, _ -> l.firstOrNull { it.id == id } }
        .mapLatest { col -> col to (if (col == null) emptyList() else c.smartCollections.items(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun remove(itemId: String) { viewModelScope.launch { c.smartCollections.remove(id, itemId) } }
    fun delete() { c.appScope.launch { c.smartCollections.delete(id) } }
}

@Composable
fun CollectionDetailRoute(id: String, onBack: () -> Unit, openItem: (String) -> Unit, edit: (String) -> Unit) {
    val vm = pkosViewModel(key = "col-$id") { CollectionDetailViewModel(it, id) }
    val st by vm.state.collectAsStateWithLifecycle()
    val col = st?.first
    ToolScaffold(col?.name ?: "", onBack, "collection_detail", actions = {
        if (col != null) IconButton(onClick = { edit(id) }, modifier = Modifier.testTag("collection_edit")) { Icon(Icons.Outlined.Edit, stringResource(R.string.collection_edit)) }
    }) {
        val items = st?.second ?: return@ToolScaffold
        if (col == null) return@ToolScaffold
        if (items.isEmpty()) item { Text(stringResource(R.string.collection_items_empty), modifier = Modifier.testTag("collection_empty")) }
        items(items, key = { it.id }) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                KnowledgeRow(i, onClick = { openItem(i.id) }, modifier = Modifier.weight(1f))
                if (!col.isSmart) IconButton(onClick = { vm.remove(i.id) }, modifier = Modifier.testTag("collection_remove_${i.id}")) { Icon(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.collection_remove_item)) }
            }
        }
        item { TextButton(onClick = { vm.delete(); onBack() }, modifier = Modifier.testTag("collection_delete")) { Text(stringResource(R.string.collection_delete), color = MaterialTheme.colorScheme.error) } }
    }
}

data class EditorData(val existing: CollectionEntity?, val categories: List<CategoryEntity>, val topics: List<TopicEntity>)

class CollectionEditorViewModel(private val c: AppContainer, private val id: String?) : ViewModel() {
    val data = kotlinx.coroutines.flow.MutableStateFlow<EditorData?>(null)
    init { viewModelScope.launch { data.value = EditorData(id?.let { c.database.featureDao().collection(it) }, c.database.categoryDao().all(), c.database.featureDao().topics()) } }
    fun save(name: String, smart: Boolean, rule: CollectionRule?, done: () -> Unit) {
        c.appScope.launch {
            if (id != null) c.smartCollections.update(id, name, rule)
            else if (smart && rule != null) c.smartCollections.createSmart(name, rule) else c.smartCollections.createManual(name)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { done() }
        }
    }
}

/** Name + (for smart collections) a rule builder: field, value, "not", match all/any. Inline — no dialogs. */
@Composable
fun CollectionEditorRoute(id: String?, smart: Boolean, onDone: () -> Unit) {
    val vm = pkosViewModel(key = "col-edit-$id-$smart") { CollectionEditorViewModel(it, id) }
    val d by vm.data.collectAsStateWithLifecycle()
    val data = d ?: return
    val isSmart = data.existing?.isSmart ?: smart
    var name by remember { mutableStateOf(data.existing?.name ?: "") }
    val initial = CollectionRule.fromJson(data.existing?.ruleJson)
    var matchAll by remember { mutableStateOf(initial?.matchAll ?: true) }
    val conds = remember { mutableStateListOf<RuleCondition>().apply { addAll(initial?.conditions.orEmpty()) } }
    ToolScaffold(stringResource(if (isSmart) R.string.collection_new_smart else R.string.collection_new_manual), onDone, "collection_editor") {
        item { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.collection_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("collection_name")) }
        if (isSmart) {
            item { ToggleRow(stringResource(if (matchAll) R.string.collection_match_all else R.string.collection_match_any), null, matchAll, { matchAll = it }, "collection_match_all") }
            itemsIndexed(conds) { i, cnd -> ConditionRow(cnd, data, { conds[i] = it }, { conds.removeAt(i) }, i) }
            item { OutlinedButton(onClick = { conds += RuleCondition(RuleField.TAG, "") }, modifier = Modifier.testTag("collection_add_rule")) { Text(stringResource(R.string.collection_add_rule)) } }
        }
        item {
            val valid = conds.filter { it.value.isNotBlank() }
            Button(
                onClick = { vm.save(name, isSmart, if (isSmart) CollectionRule(matchAll, valid) else null, onDone) },
                enabled = name.isNotBlank() && (!isSmart || valid.isNotEmpty()), modifier = Modifier.testTag("collection_save"),
            ) { Text(stringResource(R.string.collection_save)) }
        }
    }
}

@Composable
private fun fieldLabel(f: RuleField) = stringResource(
    when (f) {
        RuleField.CATEGORY -> R.string.rule_category; RuleField.TOPIC -> R.string.rule_topic; RuleField.STATUS -> R.string.rule_status
        RuleField.PRIORITY -> R.string.rule_priority; RuleField.TYPE -> R.string.rule_type; RuleField.PLATFORM -> R.string.rule_platform
        RuleField.TAG -> R.string.rule_tag; RuleField.PINNED -> R.string.rule_pinned; RuleField.CREATED_WITHIN_DAYS -> R.string.rule_created_within
    },
)

@Composable
private fun ConditionRow(c: RuleCondition, data: EditorData, onChange: (RuleCondition) -> Unit, onRemove: () -> Unit, index: Int) {
    Panel(modifier = Modifier.testTag("rule_$index")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            DropdownChip(fieldLabel(c.field), null as RuleField?, RuleField.entries, { fieldLabel(it) }, { f -> if (f != null) onChange(RuleCondition(f, if (f == RuleField.PINNED) "true" else "", c.negate)) }, "rule_field_$index")
            FilterChip(selected = c.negate, onClick = { onChange(c.copy(negate = !c.negate)) }, label = { Text(stringResource(R.string.collection_rule_not)) }, modifier = Modifier.testTag("rule_not_$index"))
            IconButton(onClick = onRemove, modifier = Modifier.testTag("rule_remove_$index")) { Icon(Icons.Outlined.Close, stringResource(R.string.collection_remove_rule)) }
        }
        val label = stringResource(R.string.collection_value)
        val set = { v: String? -> onChange(c.copy(value = v ?: "")) }
        when (c.field) {
            RuleField.CATEGORY -> DropdownChip(label, data.categories.firstOrNull { it.id == c.value }, data.categories, { it.name }, { set(it?.id) }, "rule_value_$index")
            RuleField.TOPIC -> DropdownChip(label, data.topics.firstOrNull { it.id == c.value }, data.topics, { it.name }, { set(it?.id) }, "rule_value_$index")
            RuleField.STATUS -> DropdownChip(label, ItemStatus.entries.firstOrNull { it.name == c.value }, ItemStatus.entries, { itemStatusLabel(it) }, { set(it?.name) }, "rule_value_$index")
            RuleField.PRIORITY -> DropdownChip(label, ItemPriority.entries.firstOrNull { it.name == c.value }, ItemPriority.entries, { priorityLabel(it) }, { set(it?.name) }, "rule_value_$index")
            RuleField.TYPE -> DropdownChip(label, ContentType.entries.firstOrNull { it.name == c.value }, ContentType.entries, { typeLabel(it) }, { set(it?.name) }, "rule_value_$index")
            RuleField.PLATFORM -> DropdownChip(label, SourcePlatform.entries.firstOrNull { it.name == c.value }, SourcePlatform.entries, { it.name.lowercase() }, { set(it?.name) }, "rule_value_$index")
            RuleField.PINNED -> ToggleRow(fieldLabel(RuleField.PINNED), null, c.value == "true", { set(it.toString()) }, "rule_pinned_$index")
            RuleField.TAG, RuleField.CREATED_WITHIN_DAYS -> OutlinedTextField(
                c.value, { v -> set(if (c.field == RuleField.CREATED_WITHIN_DAYS) v.filter(Char::isDigit).take(4) else v.trim().removePrefix("#")) },
                label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("rule_text_$index"),
            )
        }
    }
}
