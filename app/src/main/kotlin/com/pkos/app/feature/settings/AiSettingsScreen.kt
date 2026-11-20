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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.processing.GEMINI_KEY
import com.pkos.app.ui.components.NoticeCard
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AiSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    private val _keySaved = MutableStateFlow(container.secrets.get(GEMINI_KEY) != null)
    val keySaved: StateFlow<Boolean> = _keySaved

    fun setCloud(on: Boolean) { viewModelScope.launch { container.settings.setCloudAiEnabled(on) } }
    fun setModel(model: String) { viewModelScope.launch { container.settings.setAiModel(model) } }
    fun saveKey(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        container.secrets.put(GEMINI_KEY, k); _keySaved.value = true
    }
    fun removeKey() { container.secrets.remove(GEMINI_KEY); _keySaved.value = false }
}

@Composable
fun AiSettingsRoute(onBack: () -> Unit) {
    val vm = pkosViewModel { AiSettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keySaved by vm.keySaved.collectAsStateWithLifecycle()
    AiSettingsScreen(settings, keySaved, onBack, vm::setCloud, vm::saveKey, vm::removeKey, vm::setModel)
}

/** Owner-controlled cloud AI (R-04, R-09): off by default, own free key, Keystore-encrypted, clear data rules. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    settings: AppSettings,
    keySaved: Boolean,
    onBack: () -> Unit,
    onCloud: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onModel: (String) -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable(settings.aiModel) { mutableStateOf(settings.aiModel) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("ai_settings"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            NoticeCard(Icons.Outlined.PhoneAndroid, stringResource(R.string.ai_settings_title), stringResource(R.string.ai_local_explainer))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.toggleable(settings.cloudAiEnabled, role = Role.Switch, onValueChange = onCloud).padding(Spacing.l).testTag("switch_ai_cloud"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Psychology, null)
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_cloud_ai), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_cloud_ai_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.cloudAiEnabled, onCheckedChange = null)
                }
            }
            if (settings.cloudAiEnabled && !keySaved) {
                Text(stringResource(R.string.ai_cloud_on_no_key), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai_no_key_warning"))
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, null)
                        Spacer(Modifier.width(Spacing.m))
                        Text(stringResource(R.string.ai_key_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    }
                    if (keySaved) {
                        StatusPill(stringResource(R.string.ai_key_saved), Icons.Outlined.Shield, ToneKind.SUCCESS)
                        OutlinedButton(onClick = onRemoveKey, modifier = Modifier.testTag("ai_key_remove")) { Text(stringResource(R.string.ai_key_remove)) }
                    } else {
                        Text(stringResource(R.string.ai_key_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = key, onValueChange = { key = it }, singleLine = true,
                            label = { Text(stringResource(R.string.ai_key_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().testTag("ai_key_input"),
                        )
                        Button(onClick = { onSaveKey(key); key = "" }, enabled = key.isNotBlank(), modifier = Modifier.testTag("ai_key_save")) { Text(stringResource(R.string.ai_key_save)) }
                    }
                    OutlinedTextField(
                        value = model, onValueChange = { model = it; onModel(it) }, singleLine = true,
                        label = { Text(stringResource(R.string.ai_model_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("ai_model"),
                    )
                    Text(stringResource(R.string.ai_free_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            NoticeCard(Icons.Outlined.Shield, stringResource(R.string.settings_section_privacy), stringResource(R.string.ai_privacy_explainer))
        }
    }
}
