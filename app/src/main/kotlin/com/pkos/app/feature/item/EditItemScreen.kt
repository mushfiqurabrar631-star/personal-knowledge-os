package com.pkos.app.feature.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.pkos.core.data.model.ItemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class EditForm(val title: String, val summary: String, val tags: String, val status: ItemStatus)

class EditItemViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val form = MutableStateFlow<EditForm?>(null)
    val saved = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val d = c.knowledge.detail(id).first() ?: return@launch
            form.value = EditForm(d.item.title, d.item.summary.orEmpty(), d.tags.joinToString(", ") { it.name }, d.item.status)
        }
    }

    /** Works offline: the edit is saved locally; Notion is updated later only if sync is on. */
    fun save(f: EditForm) {
        c.appScope.launch {
            c.knowledge.editItem(id, f.title, f.summary, f.tags.split(',', '،', '\n'), f.status)
            c.requestNotionSync(id)
            saved.value = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditItemRoute(id: String, onDone: () -> Unit) {
    val vm = pkosViewModel(key = "edit-$id") { EditItemViewModel(it, id) }
    val form by vm.form.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) onDone() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.item_edit), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val f0 = form ?: return@Scaffold
        var title by rememberSaveable { mutableStateOf(f0.title) }
        var summary by rememberSaveable { mutableStateOf(f0.summary) }
        var tags by rememberSaveable { mutableStateOf(f0.tags) }
        var status by rememberSaveable { mutableStateOf(f0.status) }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("edit_item"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.edit_title)) }, isError = title.isBlank(), modifier = Modifier.fillMaxWidth().testTag("edit_title"))
            OutlinedTextField(summary, { summary = it }, label = { Text(stringResource(R.string.item_section_summary)) }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("edit_summary"))
            OutlinedTextField(
                tags, { tags = it }, label = { Text(stringResource(R.string.item_tags)) },
                supportingText = { Text(stringResource(R.string.edit_tags_hint)) }, modifier = Modifier.fillMaxWidth().testTag("edit_tags"),
            )
            Text(stringResource(R.string.item_status), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ItemStatus.entries.forEach { s ->
                    FilterChip(selected = status == s, onClick = { status = s }, label = { Text(itemStatusLabel(s)) }, modifier = Modifier.testTag("edit_status_${s.name}"))
                }
            }
            Text(stringResource(R.string.edit_user_wins), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.save(EditForm(title, summary, tags, status)) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("edit_save")) {
                Text(stringResource(R.string.edit_save))
            }
        }
    }
}
