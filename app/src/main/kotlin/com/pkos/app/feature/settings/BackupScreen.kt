package com.pkos.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.backup.AppRestart
import com.pkos.app.backup.DriveAuthState
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.backup.BackupRunResult
import com.pkos.core.data.backup.CorruptBackupException
import com.pkos.core.data.backup.DriveFile
import com.pkos.core.data.backup.DriveQuota
import com.pkos.core.data.backup.IncompatibleBackupException
import com.pkos.core.data.backup.RestorePreview
import com.pkos.core.data.backup.RestoreResult
import com.pkos.core.data.backup.WrongRecoveryKeyException
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import com.pkos.core.data.db.entity.RecoveryMetadataEntity
import com.pkos.core.data.model.BackupStatus
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RestoreUi {
    data object Idle : RestoreUi
    data object Loading : RestoreUi
    data class Pick(val backups: List<DriveFile>) : RestoreUi
    data class Preview(val preview: RestorePreview, val file: DriveFile) : RestoreUi
    data class Error(val message: Int) : RestoreUi
    data object Applying : RestoreUi
    data object Done : RestoreUi
}

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val recent: StateFlow<List<BackupSnapshotEntity>> = c.database.backupDao().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recovery: StateFlow<RecoveryMetadataEntity?> = c.database.backupDao().observeRecovery().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lastResult = c.lastBackupResult
    val keyOnPhone = MutableStateFlow(c.backupKek() != null)
    val newKey = MutableStateFlow<String?>(null)
    val quota = MutableStateFlow<DriveQuota?>(null)
    val authIntent = MutableStateFlow<android.content.IntentSender?>(null)
    val error = MutableStateFlow<String?>(null)
    val restore = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
    val running = MutableStateFlow(false)

    init { viewModelScope.launch { if (c.settings.settings.first().driveConnected) refreshQuota() } }

    fun connect() = viewModelScope.launch { handleAuth(c.driveAuth.authorize()) }
    fun consumeAuthIntent() { authIntent.value = null }
    fun onAuthResult(data: android.content.Intent?) = viewModelScope.launch { handleAuth(c.driveAuth.fromIntent(data)) }

    private suspend fun handleAuth(s: DriveAuthState) {
        when (s) {
            is DriveAuthState.Token -> { c.settings.updateBackup { it.copy(driveConnected = true) }; error.value = null; refreshQuota() }
            is DriveAuthState.NeedsUser -> authIntent.value = s.intentSender
            is DriveAuthState.Failed -> error.value = s.reason
        }
    }

    /** null = not tried yet, true = Google grant revoked, false = revoke could not be confirmed (manual link shown). */
    val revoked = MutableStateFlow<Boolean?>(null)

    /** Stops all Drive use, then revokes PKOS's Google grant. Backups already in Drive and local data are kept. */
    fun disconnect() = viewModelScope.launch {
        c.settings.updateBackup { it.copy(driveConnected = false, backupAutoEnabled = false) }
        c.backupScheduler.cancelAll(); quota.value = null
        revoked.value = runCatching { c.driveRevoker() }.getOrNull() ?: false
    }

    fun refreshQuota() = viewModelScope.launch { quota.value = runCatching { withContext(Dispatchers.IO) { c.backupService.quota() } }.getOrNull() }

    /** New recovery key: shown once; only its derived key is stored (Keystore-encrypted). */
    fun createKey() = viewModelScope.launch {
        val k = withContext(Dispatchers.Default) { c.backupService.createRecoveryKey() }
        c.storeBackupKek(k.kek); keyOnPhone.value = true; newKey.value = k.recoveryKey
    }
    fun keyWrittenDown() { newKey.value = null }

    fun unlock(key: String) = viewModelScope.launch {
        val kek = withContext(Dispatchers.Default) { runCatching { c.backupService.unlockWith(key) }.getOrNull() }
        if (kek == null) error.value = "WRONG_KEY" else { c.storeBackupKek(kek); keyOnPhone.value = true; error.value = null }
    }

    fun backupNow() = viewModelScope.launch {
        running.value = true
        try { c.lastBackupResult.value = withContext(Dispatchers.IO) { c.backupService.run() } } finally { running.value = false }
        val r = c.lastBackupResult.value
        if (r is BackupRunResult.Retry) c.backupScheduler.runNow(r.delayMs, settings.value.backupOnlyWhileCharging)
        refreshQuota()
    }

    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch {
        c.settings.updateBackup(t)
        val s = c.settings.settings.first()
        c.backupScheduler.setDaily(s.backupAutoEnabled && s.driveConnected && !s.backupPaused, s.backupOnlyWhileCharging)
    }

    fun loadRemote() = viewModelScope.launch {
        restore.value = RestoreUi.Loading
        restore.value = runCatching { withContext(Dispatchers.IO) { c.backupService.remoteBackups() } }
            .fold({ if (it.isEmpty()) RestoreUi.Error(R.string.restore_none) else RestoreUi.Pick(it) }, { RestoreUi.Error(R.string.restore_drive_error) })
    }

    fun prepare(file: DriveFile, key: String?) = viewModelScope.launch {
        restore.value = RestoreUi.Loading
        restore.value = try {
            RestoreUi.Preview(withContext(Dispatchers.IO) { c.restoreService.prepareFromDrive(file, key?.takeIf { it.isNotBlank() }, c.backupKek()) }, file)
        } catch (e: WrongRecoveryKeyException) { RestoreUi.Error(R.string.restore_wrong_key) }
        catch (e: CorruptBackupException) { RestoreUi.Error(R.string.restore_corrupt) }
        catch (e: IncompatibleBackupException) { RestoreUi.Error(R.string.restore_newer) }
        catch (e: Exception) { RestoreUi.Error(R.string.restore_drive_error) }
    }

    /** Explicitly confirmed by the owner. The current library is kept as a pre-restore safety snapshot. */
    fun apply(p: RestorePreview, context: android.content.Context) = viewModelScope.launch {
        restore.value = RestoreUi.Applying
        val result = withContext(Dispatchers.IO) { c.restoreService.apply(p) { c.database.close() } }
        if (result is RestoreResult.Applied) {
            c.storeBackupKek(p.kek)
            restore.value = RestoreUi.Done
            AppRestart.restart(context.applicationContext)
        } else {
            restore.value = RestoreUi.Error(R.string.restore_rolled_back)
            AppRestart.restart(context.applicationContext) // the database was closed; reopen it cleanly
        }
    }

    fun cancelRestore() { c.restoreService.discardStaging(); restore.value = RestoreUi.Idle }
}

@Composable
fun BackupSyncRoute(onBack: () -> Unit) {
    val vm = pkosViewModel { BackupViewModel(it) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.onAuthResult(it.data) }
    val intent by vm.authIntent.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(intent) {
        intent?.let { vm.consumeAuthIntent(); launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    BackupScreen(vm, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(vm: BackupViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val recovery by vm.recovery.collectAsStateWithLifecycle()
    val keyOnPhone by vm.keyOnPhone.collectAsStateWithLifecycle()
    val newKey by vm.newKey.collectAsStateWithLifecycle()
    val quota by vm.quota.collectAsStateWithLifecycle()
    val revoked by vm.revoked.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val last by vm.lastResult.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val restore by vm.restore.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lastVerified = recent.firstOrNull { it.status == BackupStatus.VERIFIED }

    DetailScaffold(R.string.backup_title, onBack) {
        // 1. Status
        Card("backup_status") {
            Text(stringResource(R.string.backup_last), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(lastVerified?.let { Formatters.dateTime(it.verifiedAt ?: it.createdAt) } ?: stringResource(R.string.backup_none), style = MaterialTheme.typography.headlineSmall)
            lastVerified?.let { Text(stringResource(R.string.backup_last_detail, it.itemCount, it.attachmentCount, Formatters.bytes(context, it.sizeBytes)), style = MaterialTheme.typography.bodyMedium) }
            if (!s.driveConnected) {
                StatusPill(stringResource(R.string.health_local_only), Icons.Outlined.CloudOff, ToneKind.NEUTRAL)
                Text(stringResource(R.string.backup_not_configured), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            last?.let { ResultLine(it) }
            recent.firstOrNull { it.status in setOf(BackupStatus.CREATING, BackupStatus.ENCRYPTED, BackupStatus.UPLOADING, BackupStatus.UPLOADED) }?.let { u ->
                Text(stringResource(R.string.backup_in_progress, u.uploadedBytes * 100 / maxOf(u.sizeBytes, 1)), style = MaterialTheme.typography.bodySmall)
                if (u.sizeBytes > 0) LinearProgressIndicator(progress = { u.uploadedBytes.toFloat() / u.sizeBytes }, modifier = Modifier.fillMaxWidth())
            }
        }
        // 2. Google Drive
        Card("backup_drive") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AddToDrive, null); Spacer(Modifier.width(Spacing.m))
                Text(stringResource(R.string.drive_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.drive_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.driveConnected) {
                StatusPill(stringResource(R.string.drive_connected), Icons.Outlined.CheckCircle, ToneKind.SUCCESS)
                quota?.let { QuotaLine(it, lastVerified?.sizeBytes ?: 0) }
                Text(stringResource(R.string.drive_disconnect_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.disconnect() }, modifier = Modifier.testTag("drive_disconnect")) { Text(stringResource(R.string.drive_disconnect)) }
            } else {
                revoked?.let { Text(stringResource(if (it) R.string.drive_revoked else R.string.drive_revoke_manual), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(if (it) "drive_revoked" else "drive_revoke_manual")) }
                Button(onClick = { vm.connect() }, modifier = Modifier.testTag("drive_connect")) { Text(stringResource(R.string.drive_connect)) }
            }
            error?.takeIf { it != "WRONG_KEY" }?.let { Text(stringResource(R.string.drive_auth_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        // 3. Recovery key
        Card("backup_key") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(Spacing.m))
                Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.recovery_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            when {
                newKey != null -> NewKeyPanel(newKey!!) { vm.keyWrittenDown() }
                recovery != null && keyOnPhone -> StatusPill(stringResource(R.string.recovery_ready, recovery!!.keyId.take(6)), Icons.Outlined.CheckCircle, ToneKind.SUCCESS)
                recovery != null -> UnlockField(error == "WRONG_KEY") { vm.unlock(it) }
                else -> Button(onClick = { vm.createKey() }, modifier = Modifier.testTag("recovery_create")) { Text(stringResource(R.string.recovery_create)) }
            }
        }
        // 4. Backup controls (spec 10.1: backup now / pause)
        Card("backup_controls") {
            val ready = s.driveConnected && keyOnPhone && recovery != null
            Toggle(R.string.backup_auto, R.string.backup_auto_sub, s.backupAutoEnabled, ready, "backup_auto") { on -> vm.update { it.copy(backupAutoEnabled = on) } }
            Toggle(R.string.backup_pause, R.string.backup_pause_sub, s.backupPaused, s.driveConnected, "backup_pause") { on -> vm.update { it.copy(backupPaused = on) } }
            Toggle(R.string.backup_charging, R.string.backup_charging_sub, s.backupOnlyWhileCharging, true, "backup_charging") { on -> vm.update { it.copy(backupOnlyWhileCharging = on) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.backupNow() }, enabled = ready && !running && !s.backupPaused, modifier = Modifier.testTag("backup_now")) { Text(stringResource(R.string.backup_now)) }
                if (running) { Spacer(Modifier.width(Spacing.m)); CircularProgressIndicator(Modifier.width(20.dp)) }
            }
        }
        // 5. Retention (spec 8.1, user-adjustable)
        Card("backup_retention") {
            Text(stringResource(R.string.retention_title), style = MaterialTheme.typography.titleSmall)
            Stepper(R.string.retention_daily, s.retentionDaily, 1, 60) { v -> vm.update { it.copy(retentionDaily = v) } }
            Stepper(R.string.retention_weekly, s.retentionWeekly, 0, 52) { v -> vm.update { it.copy(retentionWeekly = v) } }
            Stepper(R.string.retention_monthly, s.retentionMonthly, 0, 60) { v -> vm.update { it.copy(retentionMonthly = v) } }
        }
        // 6. Restore
        Card("backup_restore") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(Spacing.m))
                Text(stringResource(R.string.backup_restore), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.restore_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { vm.loadRemote() }, enabled = s.driveConnected, modifier = Modifier.testTag("restore_start")) { Text(stringResource(R.string.restore_choose)) }
        }
        // History
        if (recent.isNotEmpty()) Card("backup_history") {
            Text(stringResource(R.string.backup_history), style = MaterialTheme.typography.titleSmall)
            recent.take(8).forEach { b ->
                Text("${Formatters.dateTime(b.createdAt)} · ${statusLabel(b.status)}" + (b.errorCode?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    RestoreDialogs(restore, keyOnPhone, onPick = { f, k -> vm.prepare(f, k) }, onConfirm = { vm.apply(it, context) }, onCancel = { vm.cancelRestore() })
}

@Composable
private fun Card(tag: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) { content() }
    }
}

@Composable
private fun Toggle(title: Int, sub: Int, value: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag("switch_$tag"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Stepper(label: Int, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(value - 1) }, enabled = value > min) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange(value + 1) }, enabled = value < max) { Text("+") }
    }
}

@Composable
private fun QuotaLine(q: DriveQuota, nextBackup: Long) {
    val context = LocalContext.current
    val limit = q.limitBytes
    if (limit == null) { Text(stringResource(R.string.drive_quota_unlimited, Formatters.bytes(context, q.usageBytes)), style = MaterialTheme.typography.bodySmall); return }
    LinearProgressIndicator(progress = { (q.usageBytes.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("drive_quota"))
    Text(stringResource(R.string.drive_quota, Formatters.bytes(context, q.usageBytes), Formatters.bytes(context, limit)), style = MaterialTheme.typography.bodySmall)
    if (q.usageBytes + nextBackup > limit * 0.9) {
        StatusPill(stringResource(R.string.drive_quota_warning), Icons.Outlined.Warning, ToneKind.WARNING)
    }
}

@Composable
private fun ResultLine(r: BackupRunResult) {
    val (text, tone) = when (r) {
        is BackupRunResult.Verified -> stringResource(if (r.quotaWarning) R.string.backup_result_verified_quota else R.string.backup_result_verified) to ToneKind.SUCCESS
        is BackupRunResult.Retry -> stringResource(R.string.backup_result_retry, reasonText(r.code)) to ToneKind.WARNING
        is BackupRunResult.ActionRequired -> stringResource(R.string.backup_result_action, reasonText(r.code)) to ToneKind.DANGER
        is BackupRunResult.Failed -> stringResource(R.string.backup_result_failed, reasonText(r.code)) to ToneKind.DANGER
    }
    StatusPill(text, if (tone == ToneKind.SUCCESS) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, tone)
}

@Composable
private fun reasonText(code: String): String = when (code) {
    "NO_RECOVERY_KEY" -> stringResource(R.string.backup_reason_no_key)
    "KEY_MISMATCH" -> stringResource(R.string.backup_reason_key_mismatch)
    "DRIVE_AUTH" -> stringResource(R.string.backup_reason_auth)
    "DRIVE_FULL" -> stringResource(R.string.backup_reason_full)
    "VERIFY_MISMATCH" -> stringResource(R.string.backup_reason_verify)
    "LOCAL_IO" -> stringResource(R.string.backup_reason_local)
    "NETWORK" -> stringResource(R.string.notion_reason_network)
    "RATE_LIMITED" -> stringResource(R.string.notion_reason_rate)
    else -> code
}

@Composable
private fun statusLabel(s: BackupStatus): String = stringResource(
    when (s) {
        BackupStatus.VERIFIED -> R.string.backup_status_verified
        BackupStatus.FAILED -> R.string.backup_status_failed
        BackupStatus.PRUNED -> R.string.backup_status_pruned
        else -> R.string.backup_status_running
    },
)

@Composable
private fun UnlockField(wrong: Boolean, onUnlock: (String) -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.recovery_enter), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(key, { key = it }, singleLine = true, label = { Text(stringResource(R.string.recovery_label)) }, isError = wrong, modifier = Modifier.fillMaxWidth().testTag("recovery_input"))
    if (wrong) Text(stringResource(R.string.restore_wrong_key), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onUnlock(key) }, enabled = key.isNotBlank(), modifier = Modifier.testTag("recovery_unlock")) { Text(stringResource(R.string.recovery_unlock)) }
}

/** Shown inline (not in a dismissible dialog) until the owner confirms they wrote the key down. */
@Composable
private fun NewKeyPanel(key: String, onDone: () -> Unit) {
    var confirm by remember { mutableStateOf("") }
    var written by remember { mutableStateOf(false) }
    val lastGroup = key.substringAfterLast('-')
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("recovery_new")) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.recovery_new_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.recovery_new_body), style = MaterialTheme.typography.bodySmall)
            Text(key, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("recovery_key_text"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(written, { written = it }, modifier = Modifier.testTag("recovery_written"))
                Text(stringResource(R.string.recovery_written), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(confirm, { confirm = it }, singleLine = true, label = { Text(stringResource(R.string.recovery_confirm_last)) }, modifier = Modifier.fillMaxWidth().testTag("recovery_confirm"))
            Button(onClick = onDone, enabled = written && confirm.trim().equals(lastGroup, ignoreCase = true), modifier = Modifier.testTag("recovery_done")) {
                Text(stringResource(R.string.capture_done))
            }
        }
    }
}

@Composable
private fun RestoreDialogs(state: RestoreUi, keyOnPhone: Boolean, onPick: (DriveFile, String?) -> Unit, onConfirm: (RestorePreview) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    when (state) {
        RestoreUi.Idle, RestoreUi.Done -> Unit
        RestoreUi.Loading, RestoreUi.Applying -> AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(24.dp)); Spacer(Modifier.width(Spacing.m)); Text(stringResource(if (state == RestoreUi.Applying) R.string.restore_applying else R.string.restore_checking)) } },
        )
        is RestoreUi.Error -> AlertDialog(
            onDismissRequest = onCancel, text = { Text(stringResource(state.message), modifier = Modifier.testTag("restore_error")) },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_ok)) } },
        )
        is RestoreUi.Pick -> {
            var chosen by remember { mutableStateOf(state.backups.first()) }
            var key by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.restore_choose)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        state.backups.take(10).forEach { f ->
                            Surface(
                                onClick = { chosen = f }, shape = MaterialTheme.shapes.medium,
                                color = if (f == chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().testTag("restore_option"),
                            ) { Text("${Formatters.dateTime(f.appProperties["pkos_created"]?.toLongOrNull() ?: f.createdTime)} · ${Formatters.bytes(context, f.size)}", Modifier.padding(Spacing.m)) }
                        }
                        OutlinedTextField(
                            key, { key = it }, singleLine = true, label = { Text(stringResource(R.string.recovery_label)) },
                            supportingText = { if (keyOnPhone) Text(stringResource(R.string.restore_key_optional)) },
                            modifier = Modifier.fillMaxWidth().testTag("restore_key"),
                        )
                    }
                },
                confirmButton = { Button(onClick = { onPick(chosen, key) }, enabled = keyOnPhone || key.isNotBlank(), modifier = Modifier.testTag("restore_check")) { Text(stringResource(R.string.restore_check)) } },
                dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.capture_dialog_cancel)) } },
            )
        }
        is RestoreUi.Preview -> {
            val p = state.preview
            var agree by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.restore_preview_title)) },
                text = {
                    Column(Modifier.testTag("restore_preview"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.restore_preview_date, Formatters.dateTime(p.manifest.createdAt)))
                        Text(stringResource(R.string.restore_preview_counts, p.manifest.itemCount, p.manifest.attachmentCount))
                        Text(stringResource(R.string.restore_preview_version, p.manifest.appVersion, p.manifest.schemaVersion))
                        Text(stringResource(R.string.restore_preview_verified), color = MaterialTheme.colorScheme.primary)
                        if (p.localItems > 0) Text(stringResource(R.string.restore_preview_local, p.localItems, p.onlyOnPhone, p.changedSinceBackup), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.restore_preview_safety), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(agree, { agree = it }, modifier = Modifier.testTag("restore_agree"))
                            Text(stringResource(R.string.restore_agree), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { Button(onClick = { onConfirm(p) }, enabled = agree, modifier = Modifier.testTag("restore_confirm")) { Text(stringResource(R.string.restore_confirm)) } },
                dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.capture_dialog_cancel)) } },
            )
        }
    }
}
