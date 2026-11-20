package com.pkos.app.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.BuildConfig
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.di.PermissionState
import com.pkos.app.ui.components.IconBadge
import com.pkos.app.ui.components.SectionHeader
import com.pkos.app.ui.components.StagedPill
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.repository.StorageUsage
import com.pkos.core.data.settings.AppSettings
import com.pkos.core.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    private val _storage = MutableStateFlow<StorageUsage?>(null)
    val storage: StateFlow<StorageUsage?> = _storage.asStateFlow()

    fun refreshStorage() { viewModelScope.launch { _storage.value = container.storage.usage() } }
    fun setTheme(mode: ThemeMode) { viewModelScope.launch { container.settings.setThemeMode(mode) } }
    fun setCloudAi(on: Boolean) { viewModelScope.launch { container.settings.setCloudAiEnabled(on) } }
    fun setFloating(on: Boolean) { viewModelScope.launch { container.settings.setFloatingCaptureEnabled(on) } }
    fun setLocalOnly(on: Boolean) { viewModelScope.launch { container.settings.setLocalOnlyByDefault(on) } }
    fun replayOnboarding(then: () -> Unit) { viewModelScope.launch { container.settings.setOnboardingCompleted(false); then() } }
}

enum class AppLanguage(val tag: String) { SYSTEM(""), BENGALI("bn"), ENGLISH("en") }

data class SettingsActions(
    val openIntegrations: () -> Unit,
    val openBackupSync: () -> Unit,
    val onReplayOnboarding: () -> Unit,
    val openAiSettings: () -> Unit = {},
)

@Composable
fun SettingsRoute(actions: SettingsActions) {
    val vm = pkosViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val container = com.pkos.app.di.LocalAppContainer.current
    var permissionsTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { vm.refreshStorage(); permissionsTick++; onPauseOrDispose { } }
    val overlay = remember(permissionsTick) { container.permissions.overlay() }
    val notifications = remember(permissionsTick) { container.permissions.notifications() }
    var language by remember { mutableIntStateOf(currentLanguage().ordinal) }
    val context = LocalContext.current
    var explainOverlay by remember { mutableStateOf(false) }
    var awaitingOverlay by remember { mutableStateOf(false) }
    var deniedNotice by remember { mutableStateOf(false) }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    fun enableFloating() {
        vm.setFloating(true)
        com.pkos.app.capture.FloatingCaptureService.start(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 && notifications != PermissionState.GRANTED) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    // Returning from the system "Display over other apps" page: enable only if the owner actually granted it.
    LaunchedEffect(permissionsTick) {
        if (awaitingOverlay) {
            awaitingOverlay = false
            if (com.pkos.app.capture.FloatingCaptureService.canRun(context)) enableFloating() else deniedNotice = true
        }
    }
    if (explainOverlay) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { explainOverlay = false },
            title = { Text(stringResource(R.string.floating_permission_title)) },
            text = { Text(stringResource(R.string.floating_permission_body)) },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    explainOverlay = false; awaitingOverlay = true
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:" + context.packageName).toUri()))
                }) { Text(stringResource(R.string.floating_permission_open)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { explainOverlay = false }) { Text(stringResource(R.string.capture_dialog_cancel)) } },
        )
    }
    if (deniedNotice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deniedNotice = false },
            text = { Text(stringResource(R.string.floating_permission_denied)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { deniedNotice = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    SettingsScreen(
        settings = settings,
        storage = storage,
        language = AppLanguage.entries[language],
        overlay = overlay,
        notifications = notifications,
        onTheme = vm::setTheme,
        onLanguage = { l -> language = l.ordinal; applyLanguage(l) },
        onCloudAi = vm::setCloudAi,
        onLocalOnly = vm::setLocalOnly,
        onFloating = { on ->
            when {
                !on -> { vm.setFloating(false); com.pkos.app.capture.FloatingCaptureService.stop(context) }
                com.pkos.app.capture.FloatingCaptureService.canRun(context) -> enableFloating()
                else -> explainOverlay = true
            }
        },
        actions = actions.copy(onReplayOnboarding = { vm.replayOnboarding(actions.onReplayOnboarding) }),
    )
}

/** Per-app language via AppCompat (stored by the framework; Android 13+ also shows it in system settings). */
fun currentLanguage(): AppLanguage = when (AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')) {
    "bn" -> AppLanguage.BENGALI
    "en" -> AppLanguage.ENGLISH
    else -> AppLanguage.SYSTEM
}

fun applyLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(
        if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    storage: StorageUsage?,
    language: AppLanguage,
    overlay: PermissionState,
    notifications: PermissionState,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onCloudAi: (Boolean) -> Unit,
    onLocalOnly: (Boolean) -> Unit,
    actions: SettingsActions,
    onFloating: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge) }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).testTag("settings_list"), contentPadding = PaddingValues(bottom = Spacing.xl)) {
            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
            item {
                Group {
                    ChoiceRow(Icons.Outlined.Palette, stringResource(R.string.settings_theme)) {
                        Segmented(
                            options = listOf(R.string.settings_theme_system, R.string.settings_theme_light, R.string.settings_theme_dark),
                            selected = settings.themeMode.ordinal,
                            onSelect = { onTheme(ThemeMode.entries[it]) },
                            tag = "theme",
                        )
                    }
                    ChoiceRow(Icons.Outlined.Translate, stringResource(R.string.settings_language)) {
                        Segmented(
                            options = listOf(R.string.settings_language_system, R.string.settings_language_bn, R.string.settings_language_en),
                            selected = language.ordinal,
                            onSelect = { onLanguage(AppLanguage.entries[it]) },
                            tag = "language",
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_connections)) }
            item {
                Group {
                    LinkRow(Icons.Outlined.Hub, stringResource(R.string.settings_integrations), stringResource(R.string.settings_integrations_sub), actions.openIntegrations)
                    LinkRow(Icons.Outlined.CloudSync, stringResource(R.string.settings_backup_sync), stringResource(R.string.settings_backup_sync_sub), actions.openBackupSync)
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_capture)) }
            item {
                Group {
                    SwitchRow(Icons.Outlined.AddCircleOutline, stringResource(R.string.floating_title), stringResource(R.string.floating_sub), settings.floatingCaptureEnabled, onFloating, "floating")
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_privacy)) }
            item {
                Group {
                    SwitchRow(Icons.Outlined.Psychology, stringResource(R.string.settings_cloud_ai), stringResource(R.string.settings_cloud_ai_sub), settings.cloudAiEnabled, onCloudAi, "cloud_ai")
                    LinkRow(Icons.Outlined.Key, stringResource(R.string.ai_settings_title), stringResource(R.string.ai_settings_sub), actions.openAiSettings)
                    SwitchRow(Icons.Outlined.PhoneAndroid, stringResource(R.string.settings_local_only), stringResource(R.string.settings_local_only_sub), settings.localOnlyByDefault, onLocalOnly, "local_only")
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_permissions)) }
            item {
                Group {
                    InfoRow(Icons.Outlined.Layers, stringResource(R.string.settings_perm_overlay), permissionText(overlay))
                    InfoRow(Icons.Outlined.Notifications, stringResource(R.string.settings_perm_notifications), permissionText(notifications))
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_data)) }
            item {
                Group {
                    InfoRow(
                        Icons.Outlined.Storage, stringResource(R.string.settings_storage),
                        storage?.let { stringResource(R.string.settings_storage_value, Formatters.bytes(context, it.databaseBytes), Formatters.bytes(context, it.attachmentBytes)) }
                            ?: stringResource(R.string.loading),
                    )
                    InfoRow(Icons.Outlined.SaveAlt, stringResource(R.string.settings_export), null) { StagedPill("M6") }
                    InfoRow(Icons.Outlined.Key, stringResource(R.string.settings_recovery_key), null) { StagedPill("M5") }
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_about)) }
            item {
                Group {
                    InfoRow(Icons.Outlined.Info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
                    LinkRow(Icons.Outlined.Replay, stringResource(R.string.settings_replay_onboarding), null, actions.onReplayOnboarding)
                    InfoRow(Icons.Outlined.Badge, stringResource(R.string.settings_licenses), null)
                }
            }
        }
    }
}

@Composable
private fun permissionText(state: PermissionState) = stringResource(
    when (state) {
        PermissionState.GRANTED -> R.string.settings_perm_granted
        PermissionState.NOT_GRANTED -> R.string.settings_perm_not_granted
        PermissionState.NOT_REQUIRED -> R.string.settings_perm_not_needed
    },
)

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = Spacing.screen).fillMaxWidth()) {
        Column(Modifier.padding(vertical = Spacing.xs)) { content() }
    }
}

@Composable
private fun RowShell(icon: ImageVector, title: String, subtitle: String?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 36.dp)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.let { Spacer(Modifier.width(Spacing.s)); it() }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        RowShell(icon, title, subtitle) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String?, trailing: (@Composable () -> Unit)? = null) =
    RowShell(icon, title, subtitle, trailing = trailing)

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    RowShell(
        icon, title, subtitle,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag("switch_$tag"),
    ) { Switch(checked = checked, onCheckedChange = null) }
}

@Composable
private fun ChoiceRow(icon: ImageVector, title: String, control: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 36.dp)
            Spacer(Modifier.width(Spacing.m))
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        control()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Int>, selected: Int, onSelect: (Int) -> Unit, tag: String) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.testTag("${tag}_$i"),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

