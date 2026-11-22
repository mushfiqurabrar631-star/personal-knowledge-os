package com.pkos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.feature.knowledge.Panel
import com.pkos.app.feature.knowledge.ToolScaffold
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.features.CloudActivity
import com.pkos.core.data.features.CloudAudit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface PrivacyMsg {
    data class AiCleared(val n: Int) : PrivacyMsg
    data class CacheCleared(val bytes: Long) : PrivacyMsg
    data class Wiped(val items: Int, val files: Int) : PrivacyMsg
}

class PrivacyViewModel(private val c: AppContainer) : ViewModel() {
    val activity = MutableStateFlow<List<CloudActivity>?>(null)
    val msg = MutableStateFlow<PrivacyMsg?>(null)
    init { refresh() }
    private fun refresh() { viewModelScope.launch { activity.value = c.privacy.recentCloudActivity(100) } }
    fun clearAi() { viewModelScope.launch { msg.value = PrivacyMsg.AiCleared(c.privacy.clearAiHistory()); refresh() } }
    fun clearCache() { viewModelScope.launch { msg.value = PrivacyMsg.CacheCleared(c.privacy.clearCache(c.cacheDirs())) } }
    /** Runs in the app scope so leaving the screen cannot interrupt a half-done wipe. */
    fun deleteAll(done: () -> Unit) {
        c.appScope.launch {
            val r = c.deleteAllData()
            msg.value = PrivacyMsg.Wiped(r.items, r.files)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { done() }
        }
    }
}

@Composable
fun PrivacyRoute(onBack: () -> Unit, openNotion: () -> Unit, openBackup: () -> Unit, afterWipe: () -> Unit) {
    val vm = pkosViewModel { PrivacyViewModel(it) }
    val act by vm.activity.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    PrivacyScreen(act, msg, vm::clearAi, vm::clearCache, { vm.deleteAll(afterWipe) }, openNotion, openBackup, onBack)
}

/** Spec 10.1 privacy controls that are not already on other screens, each saying exactly what it affects. */
@Composable
fun PrivacyScreen(
    activity: List<CloudActivity>?, msg: PrivacyMsg?, onClearAi: () -> Unit, onClearCache: () -> Unit, onDeleteAll: () -> Unit,
    openNotion: () -> Unit, openBackup: () -> Unit, onBack: () -> Unit,
) {
    var confirmAi by rememberSaveable { mutableStateOf(false) }
    var confirmWipe by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val word = stringResource(R.string.privacy_wipe_word)
    ToolScaffold(stringResource(R.string.privacy_title), onBack, "privacy_screen") {
        msg?.let { m ->
            item {
                Text(
                    when (m) {
                        is PrivacyMsg.AiCleared -> stringResource(R.string.privacy_ai_cleared, m.n)
                        is PrivacyMsg.CacheCleared -> stringResource(R.string.privacy_cache_cleared, Formatters.bytes(androidx.compose.ui.platform.LocalContext.current, m.bytes))
                        is PrivacyMsg.Wiped -> stringResource(R.string.privacy_wiped, m.items, m.files)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("privacy_msg").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        // 1. Cloud activity
        item {
            Panel(stringResource(R.string.privacy_activity_title)) {
                Text(stringResource(R.string.privacy_activity_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activity != null && activity.isEmpty()) Text(stringResource(R.string.privacy_activity_empty), modifier = Modifier.testTag("activity_empty"))
            }
        }
        items(activity.orEmpty().take(50), key = { it.id }) { a -> ActivityRow(a) }
        // 2. Clear AI history
        item {
            Panel(stringResource(R.string.privacy_ai_title)) {
                Text(stringResource(R.string.privacy_ai_body), style = MaterialTheme.typography.bodySmall)
                if (!confirmAi) OutlinedButton(onClick = { confirmAi = true }, modifier = Modifier.testTag("privacy_clear_ai")) { Text(stringResource(R.string.privacy_ai_button)) }
                else Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Button(onClick = { confirmAi = false; onClearAi() }, modifier = Modifier.testTag("privacy_clear_ai_confirm")) { Text(stringResource(R.string.privacy_ai_button)) }
                    TextButton(onClick = { confirmAi = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
        // 3. Cache vs all data
        item {
            Panel(stringResource(R.string.privacy_cache_title)) {
                Text(stringResource(R.string.privacy_cache_body), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onClearCache, modifier = Modifier.testTag("privacy_clear_cache")) { Text(stringResource(R.string.privacy_cache_button)) }
            }
        }
        item {
            Panel(stringResource(R.string.privacy_wipe_title), Modifier.testTag("privacy_wipe_panel")) {
                Text(stringResource(R.string.privacy_wipe_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.privacy_wipe_remote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!confirmWipe) OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.testTag("privacy_wipe")) { Text(stringResource(R.string.privacy_wipe_button), color = MaterialTheme.colorScheme.error) }
                else {
                    Text(stringResource(R.string.privacy_wipe_confirm, word), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(word) }, modifier = Modifier.fillMaxWidth().testTag("privacy_wipe_field"))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Button(
                            onClick = { confirmWipe = false; typed = ""; onDeleteAll() }, enabled = typed.trim().equals(word, ignoreCase = true),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                            modifier = Modifier.testTag("privacy_wipe_confirm"),
                        ) { Text(stringResource(R.string.privacy_wipe_button)) }
                        TextButton(onClick = { confirmWipe = false; typed = "" }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
        // 4. Disconnect (controls live on their own screens)
        item {
            Panel(stringResource(R.string.privacy_disconnect_title)) {
                Text(stringResource(R.string.privacy_disconnect_body), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = openNotion, modifier = Modifier.testTag("privacy_open_notion")) { Text("Notion") }
                    OutlinedButton(onClick = openBackup, modifier = Modifier.testTag("privacy_open_drive")) { Text("Google Drive") }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(a: CloudActivity) {
    val what = stringResource(when (a.kind) { CloudAudit.ASK -> R.string.privacy_kind_ask; CloudAudit.EMBED -> R.string.privacy_kind_embed; else -> R.string.privacy_kind_classify })
    Row(Modifier.fillMaxWidth().testTag("activity_${a.id}"), verticalAlignment = Alignment.Top) {
        Icon(if (a.outcome == "OK") Icons.Outlined.CloudUpload else Icons.Outlined.ErrorOutline, null)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(a.title ?: stringResource(R.string.privacy_item_deleted), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(stringResource(R.string.privacy_activity_line, what, "${a.provider} · ${a.model}", a.chars, Formatters.dateTime(a.at), a.outcome),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
