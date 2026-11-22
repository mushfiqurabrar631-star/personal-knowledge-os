package com.pkos.app.feature.knowledge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.pkosViewModel
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.features.ExportOptions
import com.pkos.core.data.features.ExportResult
import com.pkos.core.data.features.IntegrityReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface ExportState {
    data object Idle : ExportState
    data object Running : ExportState
    data class Done(val r: ExportResult) : ExportState
    data object Failed : ExportState
}

class ExportViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow<ExportState>(ExportState.Idle)
    val integrity = MutableStateFlow<IntegrityReport?>(null)

    /** Writes to the file the owner picked. A failure leaves the library untouched and says so. */
    fun export(uri: android.net.Uri, options: ExportOptions) {
        state.value = ExportState.Running
        c.appScope.launch {
            state.value = runCatching {
                c.context.contentResolver.openOutputStream(uri, "wt")!!.use { c.exporter.export(it, options) }
            }.fold({ ExportState.Done(it) }, {
                runCatching { android.provider.DocumentsContract.deleteDocument(c.context.contentResolver, uri) } // no half-written export left behind
                ExportState.Failed
            })
        }
    }

    fun check() { c.appScope.launch { integrity.value = c.exporter.integrity(c.context.getDatabasePath(PkosDatabase.FILE_NAME)) } }
}

@Composable
fun ExportRoute(onBack: () -> Unit) {
    val vm = pkosViewModel { ExportViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val integrity by vm.integrity.collectAsStateWithLifecycle()
    var includeLocal by rememberSaveable { mutableStateOf(false) }
    var includeFiles by rememberSaveable { mutableStateOf(true) }
    var protect by rememberSaveable { mutableStateOf(false) }
    // Passwords are kept only in memory (not saved state) and never stored.
    var pw by androidx.compose.runtime.remember { mutableStateOf("") }
    var pw2 by androidx.compose.runtime.remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export(uri, ExportOptions(includeLocal, includeFiles, if (protect || includeLocal) pw.toCharArray() else null))
    }
    ExportScreen(st, integrity, includeLocal, { includeLocal = it }, includeFiles, { includeFiles = it },
        onExport = { pick.launch("pkos-export-${java.time.LocalDate.now()}.zip") }, onCheck = vm::check, onBack = onBack,
        protect = protect || includeLocal, onProtect = { protect = it }, password = pw, onPassword = { pw = it }, password2 = pw2, onPassword2 = { pw2 = it })
}

@Composable
fun ExportScreen(
    st: ExportState, integrity: IntegrityReport?, includeLocal: Boolean, onLocal: (Boolean) -> Unit, includeFiles: Boolean, onFiles: (Boolean) -> Unit,
    onExport: () -> Unit, onCheck: () -> Unit, onBack: () -> Unit,
    protect: Boolean = false, onProtect: (Boolean) -> Unit = {}, password: String = "", onPassword: (String) -> Unit = {},
    password2: String = "", onPassword2: (String) -> Unit = {},
) {
    val pwOk = !protect || (password.length >= ExportOptions.MIN_PASSWORD && password == password2)
    ToolScaffold(stringResource(R.string.nav_export), onBack, "export_screen") {
        item { Text(stringResource(R.string.export_intro), style = MaterialTheme.typography.bodyMedium) }
        item {
            Panel {
                ToggleRow(stringResource(R.string.export_include_local), stringResource(R.string.export_include_local_body), includeLocal, onLocal, "export_local")
                if (includeLocal) Text(stringResource(R.string.export_private_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export_private_warning"))
                ToggleRow(stringResource(R.string.export_include_files), null, includeFiles, onFiles, "export_files")
                ToggleRow(stringResource(R.string.export_protect), stringResource(R.string.export_protect_body), protect, { if (!includeLocal) onProtect(it) }, "export_protect")
                if (protect) {
                    val hidden = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
                    androidx.compose.material3.OutlinedTextField(password, onPassword, label = { Text(stringResource(R.string.export_password)) }, singleLine = true, visualTransformation = hidden, keyboardOptions = kb, modifier = Modifier.fillMaxWidth().testTag("export_password"))
                    androidx.compose.material3.OutlinedTextField(password2, onPassword2, label = { Text(stringResource(R.string.export_password_again)) }, singleLine = true, visualTransformation = hidden, keyboardOptions = kb,
                        isError = password2.isNotEmpty() && password2 != password,
                        supportingText = { if (password2.isNotEmpty() && password2 != password) Text(stringResource(R.string.export_password_mismatch)) },
                        modifier = Modifier.fillMaxWidth().testTag("export_password2"))
                }
                Button(onClick = onExport, enabled = st != ExportState.Running && pwOk, modifier = Modifier.testTag("export_run")) { Text(stringResource(R.string.export_run)) }
                when (st) {
                    ExportState.Running -> { Text(stringResource(R.string.export_running)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is ExportState.Done -> Text(stringResource(R.string.export_done, st.r.items, st.r.attachments, st.r.skippedLocalOnly), modifier = Modifier.testTag("export_done"))
                    ExportState.Failed -> Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export_failed"))
                    ExportState.Idle -> {}
                }
            }
        }
        item {
            Panel(stringResource(R.string.integrity_title)) {
                OutlinedButton(onClick = onCheck, modifier = Modifier.testTag("integrity_run")) { Text(stringResource(R.string.integrity_run)) }
                integrity?.let { r ->
                    if (r.healthy) Text(stringResource(R.string.integrity_ok, r.items, r.attachments), modifier = Modifier.testTag("integrity_ok"))
                    else Text(stringResource(R.string.integrity_problem, r.databaseMessage, r.foreignKeyProblems, r.missingFiles, r.corruptFiles), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("integrity_problem"))
                }
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, body: String?, checked: Boolean, on: (Boolean) -> Unit, tag: String) {
    // One toggleable row: TalkBack reads the title + on/off, and the whole row (≥48dp) is the touch target.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = on).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null)
    }
}
