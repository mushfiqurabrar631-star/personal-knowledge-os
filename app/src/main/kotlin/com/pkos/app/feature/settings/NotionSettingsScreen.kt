package com.pkos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.notion.NOTION_TOKEN
import com.pkos.app.ui.components.NoticeCard
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.dao.NotionSyncCounts
import com.pkos.core.data.notion.ConnectionReport
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Done(val report: ConnectionReport) : CheckState
}

class NotionSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val counts: StateFlow<NotionSyncCounts> = container.database.notionDao().observeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotionSyncCounts(0, 0, 0))
    private val _tokenSaved = MutableStateFlow(container.secrets.get(NOTION_TOKEN) != null)
    val tokenSaved: StateFlow<Boolean> = _tokenSaved
    private val _check = MutableStateFlow<CheckState>(CheckState.Idle)
    val check: StateFlow<CheckState> = _check
    private val _queued = MutableStateFlow<Int?>(null)
    val queued: StateFlow<Int?> = _queued

    /** The token goes straight into the Keystore-encrypted store; it is never shown again or logged. */
    fun saveToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) return
        container.secrets.put(NOTION_TOKEN, t)
        _tokenSaved.value = true
        runCheck()
    }

    fun removeToken() {
        container.secrets.remove(NOTION_TOKEN)
        _tokenSaved.value = false
        _check.value = CheckState.Idle
        viewModelScope.launch { container.settings.setNotionSyncEnabled(false) }
    }

    /** Live, read-only check against the real Master Database (no writes). */
    fun runCheck() {
        _check.value = CheckState.Running
        viewModelScope.launch { _check.value = CheckState.Done(container.notionSync.checkConnection()) }
    }

    fun setSync(on: Boolean) {
        viewModelScope.launch {
            container.settings.setNotionSyncEnabled(on)
            if (on) _queued.value = container.requestNotionSyncAll()
        }
    }

    fun setLargeFiles(on: Boolean) { viewModelScope.launch { container.settings.setLargeFilesViaDrive(on) } }

    fun syncNow() { viewModelScope.launch { _queued.value = container.requestNotionSyncAll(androidx.work.ExistingWorkPolicy.KEEP) } }
}

@Composable
fun NotionSettingsRoute(onBack: () -> Unit) {
    val vm = pkosViewModel { NotionSettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val saved by vm.tokenSaved.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    NotionSettingsScreen(settings, saved, check, counts, queued, onBack, vm::saveToken, vm::removeToken, vm::runCheck, vm::setSync, vm::syncNow, vm::setLargeFiles)
}

/** Notion connection (R-06): token typed only here, encrypted with Android Keystore; live check; owner-controlled sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotionSettingsScreen(
    settings: AppSettings,
    tokenSaved: Boolean,
    check: CheckState,
    counts: NotionSyncCounts,
    queued: Int?,
    onBack: () -> Unit,
    onSaveToken: (String) -> Unit,
    onRemoveToken: () -> Unit,
    onCheck: () -> Unit,
    onSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onLargeFiles: (Boolean) -> Unit = {},
) {
    var token by rememberSaveable { mutableStateOf("") }
    val verified = (check as? CheckState.Done)?.report is ConnectionReport.Ok
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notion_settings_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("notion_settings"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            NoticeCard(Icons.Outlined.Description, stringResource(R.string.notion_target_title), stringResource(R.string.notion_target_body))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(Spacing.m))
                    Text(stringResource(R.string.notion_token_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                if (tokenSaved) {
                    StatusPill(stringResource(R.string.notion_token_saved), Icons.Outlined.Shield, ToneKind.SUCCESS)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        FilledTonalButton(onClick = onCheck, enabled = check != CheckState.Running, modifier = Modifier.testTag("notion_check")) { Text(stringResource(R.string.notion_check)) }
                        OutlinedButton(onClick = onRemoveToken, modifier = Modifier.testTag("notion_token_remove")) { Text(stringResource(R.string.notion_token_remove)) }
                    }
                    Text(stringResource(R.string.notion_disconnect_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.notion_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = token, onValueChange = { token = it }, singleLine = true,
                        label = { Text(stringResource(R.string.notion_token_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("notion_token_input"),
                    )
                    Button(onClick = { onSaveToken(token); token = "" }, enabled = token.isNotBlank(), modifier = Modifier.testTag("notion_token_save")) {
                        Text(stringResource(R.string.notion_token_save))
                    }
                }
                when (check) {
                    CheckState.Idle -> Unit
                    CheckState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(Spacing.m)); Text(stringResource(R.string.notion_checking))
                    }
                    is CheckState.Done -> CheckResult(check.report)
                }
            }
            Card {
                Row(
                    Modifier.toggleable(settings.notionSyncEnabled, enabled = tokenSaved && (verified || settings.notionSyncEnabled), role = Role.Switch, onValueChange = onSync)
                        .testTag("switch_notion_sync"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.notion_sync_switch), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.notion_sync_switch_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.notionSyncEnabled, onCheckedChange = null, enabled = tokenSaved && (verified || settings.notionSyncEnabled))
                }
                Text(
                    stringResource(R.string.notion_counts, counts.synced, counts.queued, counts.action),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("notion_counts"),
                )
                queued?.let { Text(stringResource(R.string.notion_queued_now, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (settings.notionSyncEnabled) {
                    OutlinedButton(onClick = onSyncNow, modifier = Modifier.testTag("notion_sync_now")) { Text(stringResource(R.string.notion_sync_now)) }
                }
            }
            Card {
                Row(
                    Modifier.toggleable(settings.largeFilesViaDrive, enabled = settings.driveConnected || settings.largeFilesViaDrive, role = Role.Switch, onValueChange = onLargeFiles)
                        .testTag("switch_large_files_drive"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Description, null); Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.notion_large_files), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (settings.driveConnected) R.string.notion_large_files_body else R.string.notion_large_files_needs_drive), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.largeFilesViaDrive, onCheckedChange = null, enabled = settings.driveConnected || settings.largeFilesViaDrive)
                }
            }
            NoticeCard(Icons.Outlined.Shield, stringResource(R.string.notion_rules_title), stringResource(R.string.notion_rules_body))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) { content() }
    }
}

@Composable
private fun CheckResult(report: ConnectionReport) {
    when (report) {
        is ConnectionReport.Ok -> Column(Modifier.testTag("notion_check_ok"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            StatusPill(stringResource(R.string.notion_check_ok), Icons.Outlined.CheckCircle, ToneKind.SUCCESS)
            Text(stringResource(R.string.notion_check_fields, report.title, report.usableFields, report.totalFields), style = MaterialTheme.typography.bodySmall)
            if (report.missing.isNotEmpty()) Text(stringResource(R.string.notion_check_missing, report.missing.joinToString()), style = MaterialTheme.typography.bodySmall)
        }
        is ConnectionReport.Failed -> Column(Modifier.testTag("notion_check_failed"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            StatusPill(stringResource(R.string.notion_check_failed), Icons.Outlined.ErrorOutline, ToneKind.DANGER)
            Text(
                stringResource(
                    when (report.reason) {
                        "UNAUTHORIZED", "NO_TOKEN" -> R.string.notion_reason_token
                        "FORBIDDEN", "NOT_FOUND" -> R.string.notion_reason_access
                        "SCHEMA_INCOMPATIBLE" -> R.string.notion_reason_schema
                        "RATE_LIMITED" -> R.string.notion_reason_rate
                        "NETWORK" -> R.string.notion_reason_network
                        else -> R.string.notion_reason_server
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
