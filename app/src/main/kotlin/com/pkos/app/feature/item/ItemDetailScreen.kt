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
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.material3.Button
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
    fun setPinned(on: Boolean) { viewModelScope.launch { container.knowledge.setPinned(id, on); container.requestNotionSync(id) } }
    fun saveNote(note: String) { viewModelScope.launch { container.knowledge.setNote(id, note) } }
    fun fileFor(a: com.pkos.core.data.db.entity.AttachmentEntity) = container.attachments.resolve(a.localPath)
    fun accept() { viewModelScope.launch { container.knowledge.acceptOrganization(id); container.requestNotionSync(id) } }
    fun setCategory(categoryId: String) { viewModelScope.launch { container.knowledge.setCategory(id, categoryId); container.requestNotionSync(id) } }
    val notion: StateFlow<NotionItemState> = kotlinx.coroutines.flow.combine(
        container.database.notionDao().observeMapping(id),
        container.database.notionDao().observeConflict(id),
        container.database.notionDao().observeJob(com.pkos.core.data.notion.NotionSyncService.jobId(id)),
    ) { m, c, j -> NotionItemState(m, c, j) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotionItemState())
    /** Owner decision on a conflict; runs through WorkManager so it survives leaving the screen. */
    fun resolve(keepNotion: Boolean) {
        container.notionScheduler.enqueue(
            id, decision = if (keepNotion) com.pkos.app.notion.NotionSyncWorker.DECISION_KEEP else com.pkos.app.notion.NotionSyncWorker.DECISION_OVERWRITE,
            policy = androidx.work.ExistingWorkPolicy.REPLACE,
        )
    }
    fun syncNow() { viewModelScope.launch { container.requestNotionSync(id) } }
    val categories: StateFlow<List<com.pkos.core.data.db.entity.CategoryEntity>> =
        container.categories.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ItemDetailRoute(id: String, onBack: () -> Unit) {
    val vm = pkosViewModel(key = "item-$id") { ItemDetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.markOpened() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val notion by vm.notion.collectAsStateWithLifecycle()
    ItemDetailScreen(
        state, onBack, categories = categories, notion = notion,
        actions = ItemActions(
            onPin = vm::setPinned,
            onSaveNote = vm::saveNote,
            onOpenUrl = { url -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } },
            onOpenFile = { a -> openAttachment(context, vm.fileFor(a), a.mimeType) },
            onAccept = vm::accept,
            onChangeCategory = vm::setCategory,
            onKeepNotion = { vm.resolve(true) },
            onOverwriteNotion = { vm.resolve(false) },
            onSyncNow = vm::syncNow,
        ),
    )
}

data class ItemActions(
    val onPin: (Boolean) -> Unit = {},
    val onSaveNote: (String) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onOpenFile: (com.pkos.core.data.db.entity.AttachmentEntity) -> Unit = {},
    val onAccept: () -> Unit = {},
    val onChangeCategory: (String) -> Unit = {},
    val onKeepNotion: () -> Unit = {},
    val onOverwriteNotion: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
)

data class NotionItemState(
    val mapping: com.pkos.core.data.db.entity.NotionMappingEntity? = null,
    val conflict: com.pkos.core.data.db.entity.NotionConflictEntity? = null,
    val job: com.pkos.core.data.db.entity.SyncJobEntity? = null,
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
fun ItemDetailScreen(
    state: ItemUiState,
    onBack: () -> Unit,
    actions: ItemActions = ItemActions(),
    categories: List<com.pkos.core.data.db.entity.CategoryEntity> = emptyList(),
    notion: NotionItemState = NotionItemState(),
) {
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
                        item.reviewReason?.let { raw ->
                            val it = reviewReasonText(raw)
                            Text(stringResource(R.string.item_review_reason, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    NotionConflictCard(notion, actions)
                    AiReviewCard(d, categories, actions)
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
                                            Text(
                                                Formatters.bytes(context, a.sizeBytes) + " · " + stringResource(extractionLabel(a)),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
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
                            item.purpose?.takeIf { it.isNotBlank() }?.let { Labeled(stringResource(R.string.item_purpose), purposeLabels(it)) }
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
                    d.attachments.mapNotNull { it.extractedText }.takeIf { it.isNotEmpty() }?.let { texts ->
                        Section(stringResource(R.string.item_section_file_text)) {
                            SelectionContainer { Body(texts.joinToString("\n\n").take(4_000)) }
                        }
                    }
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
                    NotionStatusSection(d.item, notion, actions)
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

private fun extractionLabel(a: com.pkos.core.data.db.entity.AttachmentEntity): Int = when (a.extractionStatus) {
    com.pkos.core.data.model.ExtractionStatus.DONE -> if (a.extractedText.isNullOrBlank()) R.string.extract_done_empty else R.string.extract_done
    com.pkos.core.data.model.ExtractionStatus.PENDING -> R.string.extract_pending
    com.pkos.core.data.model.ExtractionStatus.FAILED -> R.string.extract_failed
    com.pkos.core.data.model.ExtractionStatus.UNSUPPORTED -> R.string.extract_unsupported
    com.pkos.core.data.model.ExtractionStatus.NOT_NEEDED -> R.string.extract_not_needed
}

@Composable
private fun purposeLabels(csv: String): String = csv.split(',').map { p ->
    when (p.trim()) {
        "learn" -> stringResource(R.string.purpose_learn); "reference" -> stringResource(R.string.purpose_reference)
        "build" -> stringResource(R.string.purpose_build); "decide" -> stringResource(R.string.purpose_decide)
        "buy" -> stringResource(R.string.purpose_buy); "revisit" -> stringResource(R.string.purpose_revisit)
        "inspiration" -> stringResource(R.string.purpose_inspiration); else -> p
    }
}.joinToString(", ")

/** Shows who organised the item and how sure it was; lets the owner accept or pick another category (spec 4.5). */
@Composable
private fun AiReviewCard(d: KnowledgeItemDetail, categories: List<com.pkos.core.data.db.entity.CategoryEntity>, actions: ItemActions) {
    val item = d.item
    val aiCategory = runCatching { org.json.JSONObject(item.fieldOrigins).optString("category") == "AI" }.getOrDefault(false)
    val review = item.processingState == com.pkos.core.data.model.ProcessingState.NEEDS_REVIEW
    if (!review && !aiCategory) return
    var choose by remember { mutableStateOf(false) }
    val pct = ((item.confidence ?: 0.0) * 100).toInt()
    val onPhone = d.lastAiRun?.provider == com.pkos.core.data.processing.LocalClassifier.PROVIDER
    Surface(
        color = if (review) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("ai_review"),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null)
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(if (review) R.string.ai_review_title else R.string.ai_organized_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                stringResource(R.string.ai_confidence_line, pct, stringResource(if (onPhone) R.string.ai_by_phone else R.string.ai_by_cloud)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (!review) {
                    FilledTonalButton(onClick = actions.onAccept, modifier = Modifier.testTag("ai_accept")) { Text(stringResource(R.string.ai_accept)) }
                }
                OutlinedButton(onClick = { choose = true }, modifier = Modifier.testTag("ai_change_category")) { Text(stringResource(R.string.ai_change_category)) }
            }
        }
    }
    if (choose) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choose = false },
            title = { Text(stringResource(R.string.ai_change_category)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    categories.forEach { c ->
                        val label = systemCategoryLabel(c.id)?.let { stringResource(it) } ?: c.name
                        Surface(
                            onClick = { choose = false; actions.onChangeCategory(c.id) },
                            color = if (c.id == item.categoryId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().testTag("category_option_${c.id}"),
                        ) { Text(label, Modifier.padding(Spacing.m), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { choose = false }) { Text(stringResource(R.string.capture_dialog_cancel)) } },
        )
    }
}

@Composable
private fun reviewReasonText(raw: String): String = when (raw) {
    com.pkos.core.data.processing.ReviewReason.NO_TOPIC -> stringResource(R.string.reason_no_topic)
    com.pkos.core.data.processing.ReviewReason.AMBIGUOUS -> stringResource(R.string.reason_ambiguous)
    com.pkos.core.data.processing.ReviewReason.UNKNOWN_CATEGORY -> stringResource(R.string.reason_unknown_category)
    com.pkos.core.data.processing.ReviewReason.LOW_CONFIDENCE -> stringResource(R.string.reason_low_confidence)
    com.pkos.core.data.processing.ReviewReason.CLOUD_INVALID -> stringResource(R.string.reason_cloud_invalid)
    com.pkos.core.data.processing.ReviewReason.CLOUD_QUOTA -> stringResource(R.string.reason_cloud_quota)
    com.pkos.core.data.processing.ReviewReason.CLOUD_AUTH -> stringResource(R.string.reason_cloud_auth)
    com.pkos.core.data.processing.ReviewReason.CLOUD_UNAVAILABLE -> stringResource(R.string.reason_cloud_unavailable)
    else -> raw
}

/** Honest Notion state for this item: link only after a verified sync, clear reasons otherwise. */
@Composable
private fun NotionStatusSection(item: com.pkos.core.data.db.entity.KnowledgeItemEntity, n: NotionItemState, actions: ItemActions) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = item.syncState
    if (state == com.pkos.core.data.model.SyncState.LOCAL_ONLY && n.mapping == null && n.job == null) return
    Section(stringResource(R.string.item_section_notion)) {
        val line = when (state) {
            com.pkos.core.data.model.SyncState.NOTION_SYNCED -> stringResource(R.string.notion_item_synced, n.mapping?.lastSyncedAt?.let { Formatters.dateTime(it) } ?: "")
            com.pkos.core.data.model.SyncState.NOTION_QUEUED -> stringResource(R.string.notion_item_queued)
            com.pkos.core.data.model.SyncState.ERROR_RETRYABLE -> stringResource(R.string.notion_item_retry, notionReason(n.job?.lastErrorCode))
            com.pkos.core.data.model.SyncState.ERROR_ACTION_REQUIRED -> stringResource(R.string.notion_item_action, notionReason(n.job?.lastErrorCode))
            com.pkos.core.data.model.SyncState.CONFLICT -> stringResource(R.string.notion_conflict_title)
            else -> stringResource(R.string.notion_item_local)
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("notion_item_status"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            n.mapping?.notionUrl?.let { url ->
                OutlinedButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } }, modifier = Modifier.testTag("notion_open")) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(Spacing.s)); Text(stringResource(R.string.notion_open))
                }
            }
            if (state == com.pkos.core.data.model.SyncState.ERROR_ACTION_REQUIRED || state == com.pkos.core.data.model.SyncState.ERROR_RETRYABLE) {
                FilledTonalButton(onClick = actions.onSyncNow, modifier = Modifier.testTag("notion_retry")) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun notionReason(code: String?): String = when {
    code == null -> ""
    code.startsWith("RATE_LIMITED") || code.endsWith("RATE_LIMITED") -> stringResource(R.string.notion_reason_rate)
    code.startsWith("NETWORK") || code.endsWith("NETWORK") -> stringResource(R.string.notion_reason_network)
    code.startsWith("SERVER") || code.endsWith("SERVER") -> stringResource(R.string.notion_reason_server)
    code.startsWith("AWAITING_REMOTE_INDEX") -> stringResource(R.string.notion_reason_index)
    code.startsWith("NO_TOKEN") || code.startsWith("UNAUTHORIZED") -> stringResource(R.string.notion_reason_token)
    code.startsWith("NO_ACCESS") || code.startsWith("FORBIDDEN") || code.startsWith("DATA_SOURCE_NOT_FOUND") -> stringResource(R.string.notion_reason_access)
    code.startsWith("SCHEMA_INCOMPATIBLE") -> stringResource(R.string.notion_reason_schema)
    code.startsWith("PAGE_IN_TRASH") || code.startsWith("PAGE_MISSING") -> stringResource(R.string.notion_reason_page_gone)
    code.startsWith("DUPLICATE_REMOTE_PAGES") -> stringResource(R.string.notion_reason_duplicate)
    else -> code.substringBefore(':')
}

/** Conflict: show what differs and let the owner decide. Nothing is written until then (spec 6.2). */
@Composable
private fun NotionConflictCard(n: NotionItemState, actions: ItemActions) {
    val c = n.conflict ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val props = com.pkos.core.data.notion.NotionSyncService.jsonList(c.properties).sorted()
    val remote = runCatching { org.json.JSONObject(c.remoteValues) }.getOrDefault(org.json.JSONObject())
    val local = runCatching { org.json.JSONObject(c.localValues) }.getOrDefault(org.json.JSONObject())
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("notion_conflict")) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.notion_conflict_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(if (c.kind == "ADOPTION_REVIEW") R.string.notion_conflict_adoption else R.string.notion_conflict_body),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            props.forEach { p ->
                Column {
                    Text(p.trim(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.notion_conflict_in_notion, remote.optString(p).ifBlank { "—" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.notion_conflict_in_pkos, local.optString(p).ifBlank { "—" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = actions.onKeepNotion, modifier = Modifier.testTag("notion_keep")) { Text(stringResource(R.string.notion_keep)) }
                OutlinedButton(onClick = actions.onOverwriteNotion, modifier = Modifier.testTag("notion_overwrite")) { Text(stringResource(R.string.notion_overwrite)) }
                c.pageUrl?.let { url ->
                    androidx.compose.material3.TextButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } }) {
                        Text(stringResource(R.string.notion_open))
                    }
                }
            }
        }
    }
}
