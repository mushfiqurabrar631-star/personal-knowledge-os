package com.pkos.app.feature.item

import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.feature.topics.systemCategoryLabel
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.components.LoadingState
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ItemStatusUi
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.repository.KnowledgeItemDetail
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ItemUiState {
    data object Loading : ItemUiState
    data object NotFound : ItemUiState
    data class Ready(val detail: KnowledgeItemDetail) : ItemUiState
}

class ItemDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    val state: StateFlow<ItemUiState> = container.knowledge.detail(id)
        .map { it?.let { d -> ItemUiState.Ready(d) } ?: ItemUiState.NotFound }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemUiState.Loading)

    fun markOpened() { viewModelScope.launch { container.knowledge.markOpened(id) } }
    fun setPinned(on: Boolean) { viewModelScope.launch { container.knowledge.setPinned(id, on) } }
    fun saveNote(note: String) { viewModelScope.launch { container.knowledge.setNote(id, note) } }
    fun fileFor(a: com.pkos.core.data.db.entity.AttachmentEntity) = container.attachments.resolve(a.localPath)
}

@Composable
fun ItemDetailRoute(id: String, onBack: () -> Unit) {
    val vm = pkosViewModel(key = "item-$id") { ItemDetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.markOpened() }
    val context = androidx.compose.ui.platform.LocalContext.current
    ItemDetailScreen(
        state, onBack,
        actions = ItemActions(
            onPin = vm::setPinned,
            onSaveNote = vm::saveNote,
            onOpenUrl = { url -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } },
            onOpenFile = { a -> openAttachment(context, vm.fileFor(a), a.mimeType) },
        ),
    )
}

data class ItemActions(
    val onPin: (Boolean) -> Unit = {},
    val onSaveNote: (String) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onOpenFile: (com.pkos.core.data.db.entity.AttachmentEntity) -> Unit = {},
)

/** Opens a local attachment via the app's FileProvider with a read-only grant; never exposes a file:// URI. */
private fun openAttachment(context: android.content.Context, file: java.io.File, mime: String?) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, null)) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemDetailScreen(state: ItemUiState, onBack: () -> Unit, actions: ItemActions = ItemActions()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    val pinned = (state as? ItemUiState.Ready)?.detail?.item?.pinned
                    if (pinned != null) {
                        IconButton(onClick = { actions.onPin(!pinned) }, modifier = Modifier.testTag("item_pin")) {
                            Icon(
                                if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                stringResource(if (pinned) R.string.item_unpin else R.string.item_pin),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ItemUiState.Loading -> LoadingState(Modifier.padding(padding))
            ItemUiState.NotFound -> EmptyState(
                Icons.Outlined.SearchOff, stringResource(R.string.item_not_found_title), stringResource(R.string.item_not_found_body),
                Modifier.padding(padding),
            )
            is ItemUiState.Ready -> {
                val d = state.detail
                val item = d.item
                val status = ItemStatusUi.of(item.processingState, item.syncState)
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("item_detail"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(item.title, style = MaterialTheme.typography.headlineSmall)
                        StatusPill(stringResource(status.label), status.icon, status.tone)
                        Text(
                            stringResource(R.string.item_captured_at, Formatters.dateTime(item.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.reviewReason?.let {
                            Text(stringResource(R.string.item_review_reason, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (item.duplicateOfId != null) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("item_duplicate")) {
                            Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ContentCopy, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.width(Spacing.m))
                                Text(stringResource(R.string.item_duplicate_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    Section(stringResource(R.string.item_section_note)) {
                        var note by rememberSaveable(item.id, item.userNote) { mutableStateOf(item.userNote.orEmpty()) }
                        OutlinedTextField(
                            note, { note = it },
                            placeholder = { Text(stringResource(R.string.capture_note_hint)) },
                            modifier = Modifier.fillMaxWidth().testTag("item_note"),
                        )
                        if (note.trim() != item.userNote.orEmpty()) {
                            FilledTonalButton(onClick = { actions.onSaveNote(note) }, modifier = Modifier.testTag("item_note_save")) { Text(stringResource(R.string.capture_save_note)) }
                        }
                    }
                    if (d.attachments.isNotEmpty()) {
                        Section(stringResource(R.string.item_section_files)) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            d.attachments.forEach { a ->
                                Surface(
                                    onClick = { actions.onOpenFile(a) },
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth().testTag("attachment"),
                                ) {
                                    Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (a.mimeType?.startsWith("image/") == true) Icons.Outlined.Image else Icons.Outlined.Description, null)
                                        Spacer(Modifier.width(Spacing.m))
                                        Column(Modifier.weight(1f)) {
                                            Text(a.filename ?: a.sha256.take(12), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(Formatters.bytes(context, a.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item.summary?.takeIf { it.isNotBlank() }?.let { Section(stringResource(R.string.item_section_summary)) { Body(it) } }
                    Section(stringResource(R.string.item_section_organized)) {
                        val categoryName = d.category?.let { c -> systemCategoryLabel(c.id)?.let { stringResource(it) } ?: c.name }
                        if (categoryName == null && d.topic == null && d.tags.isEmpty()) {
                            Body(stringResource(R.string.item_not_organized))
                        } else {
                            categoryName?.let { Labeled(stringResource(R.string.item_category), it) }
                            d.topic?.let { Labeled(stringResource(R.string.item_topic), it.name) }
                            if (d.tags.isNotEmpty()) {
                                Text(stringResource(R.string.item_tags), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                    d.tags.forEach { t -> AssistChip(onClick = {}, label = { Text(t.name) }) }
                                }
                            }
                        }
                    }
                    item.rawText?.takeIf { it.isNotBlank() }?.let { Section(stringResource(R.string.item_section_original)) { SelectionContainer { Body(it) } } }
                    if (item.sourceUrl != null || d.sources.isNotEmpty()) {
                        Section(stringResource(R.string.item_section_source)) {
                            item.sourceUrl?.let { url ->
                                SelectionContainer { Body(url) }
                                OutlinedButton(onClick = { actions.onOpenUrl(url) }, modifier = Modifier.testTag("item_open_original")) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(Spacing.s)); Text(stringResource(R.string.item_open_original))
                                }
                            }
                            d.sources.forEach { s ->
                                Body(listOfNotNull(s.captureMethod.name.lowercase().replace('_', ' '), s.sourceApp, Formatters.dateTime(s.capturedAt)).joinToString(" · "))
                            }
                        }
                    }
                    Section(stringResource(R.string.item_section_history)) {
                        if (d.versions.isEmpty()) Body(stringResource(R.string.item_no_history))
                        d.versions.forEach { v -> Body(stringResource(R.string.item_version, v.version, Formatters.dateTime(v.changedAt)) + " · " + v.changeSource.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Labeled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
