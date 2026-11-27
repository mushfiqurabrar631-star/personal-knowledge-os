package com.pkos.app.feature.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.NoticeCard
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.features.ChatAnswer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatTurn(val question: String, val answer: ChatAnswer?)

class AskViewModel(private val c: AppContainer) : ViewModel() {
    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()
    val cloudOn: StateFlow<Boolean> = c.settings.settings.map { it.cloudAiEnabled && c.cloudConfigured(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Answers only from the owner's items; cloud only when enabled (and never with local-only items). */
    fun ask(q: String) {
        val question = q.trim().takeIf { it.isNotEmpty() } ?: return
        if (_turns.value.lastOrNull()?.answer == null && _turns.value.isNotEmpty()) return
        _turns.update { it + ChatTurn(question, null) }
        viewModelScope.launch {
            val semantic = c.settings.settings.first().semanticSearchEnabled
            val a = c.chat.ask(question, c.cloudGenerator(), if (semantic) c.cloudEmbedder() else null, semantic)
            _turns.update { list -> list.dropLast(1) + ChatTurn(question, a) }
        }
    }

    fun clear() { _turns.value = emptyList() }
}

@Composable
fun AskAiScreen(onBack: () -> Unit, openIntegrations: () -> Unit, openItem: (String) -> Unit = {}) {
    val vm = pkosViewModel { AskViewModel(it) }
    val turns by vm.turns.collectAsStateWithLifecycle()
    val cloud by vm.cloudOn.collectAsStateWithLifecycle()
    AskAiContent(turns, cloud, vm::ask, vm::clear, onBack, openIntegrations, openItem)
}

/** Ask your library (spec 2.7, 9 #2): grounded answers with numbered, openable sources. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAiContent(turns: List<ChatTurn>, cloud: Boolean, onAsk: (String) -> Unit, onClear: () -> Unit, onBack: () -> Unit, openIntegrations: () -> Unit, openItem: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.answer) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { if (turns.isNotEmpty()) IconButton(onClick = onClear, modifier = Modifier.testTag("ask_clear")) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.ask_clear)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).testTag("ask_list"), state = list, contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                item {
                    NoticeCard(
                        if (cloud) Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid, stringResource(R.string.ask_title),
                        stringResource(R.string.ask_intro) + "\n" + stringResource(if (cloud) R.string.ask_intro_cloud else R.string.ask_intro_offline),
                        action = if (cloud) null else ({ OutlinedButton(onClick = openIntegrations) { Text(stringResource(R.string.ask_open_integrations)) } }),
                        modifier = Modifier.testTag("ask_intro"),
                    )
                }
                items(turns) { t -> Turn(t, openItem) }
            }
            val send = { onAsk(input); input = "" }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.ask_input_hint)) },
                trailingIcon = { IconButton(onClick = send, enabled = input.isNotBlank(), modifier = Modifier.testTag("ask_send")) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.ask_send)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                shape = CircleShape, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(Spacing.screen).testTag("ask_input"),
            )
        }
    }
}

@Composable
private fun Turn(t: ChatTurn, openItem: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(t.question, style = MaterialTheme.typography.titleMedium)
        val a = t.answer
        if (a == null) { Text(stringResource(R.string.ask_thinking), style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()); return }
        when {
            a.ungrounded -> NoticeCard(Icons.Outlined.SearchOff, stringResource(R.string.ask_sources), stringResource(R.string.ask_ungrounded), modifier = Modifier.testTag("ask_ungrounded"))
            a.noSources || a.text == null -> NoticeCard(Icons.Outlined.SearchOff, stringResource(R.string.ask_sources), stringResource(R.string.ask_no_sources), modifier = Modifier.testTag("ask_no_sources"))
            else -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth().testTag("ask_answer")) {
                Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    StatusPill(stringResource(if (a.byCloud) R.string.ask_by_cloud else R.string.ask_by_phone), if (a.byCloud) Icons.Outlined.Cloud else Icons.Outlined.FormatQuote, if (a.byCloud) ToneKind.INFO else ToneKind.NEUTRAL)
                    Text(a.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (a.sources.isNotEmpty()) {
            Text(stringResource(R.string.ask_sources), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                a.sources.forEach { s -> AssistChip(onClick = { openItem(s.itemId) }, label = { Text("[${s.n}] ${s.title}", maxLines = 1) }, modifier = Modifier.testTag("ask_source_${s.n}")) }
            }
        }
        if (a.localOnlyWithheld > 0) Text(stringResource(R.string.ask_withheld, a.localOnlyWithheld), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
