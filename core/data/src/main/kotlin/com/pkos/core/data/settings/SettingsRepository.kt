package com.pkos.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Off by default: nothing is sent to a cloud AI provider unless the owner turns it on (spec 10.1, R-09). */
    val cloudAiEnabled: Boolean = false,
    /** When on, new captures are created with privacy_scope = LOCAL_ONLY (never sent to cloud AI or Notion). */
    val localOnlyByDefault: Boolean = false,
    /** Floating capture bubble (needs "display over other apps"; off until the owner turns it on). */
    val floatingCaptureEnabled: Boolean = false,
    /** Gemini model used when cloud AI is on (owner-editable; the key itself is kept in the Keystore, not here). */
    val aiModel: String = com.pkos.core.data.processing.GeminiProvider.DEFAULT_MODEL,
    /** Automatic Notion sync; can only be switched on after a successful live connection check (M4). */
    val notionSyncEnabled: Boolean = false,
    // M5 backup (spec 8, 10.1): all off until the owner connects Drive and sets a recovery key.
    val driveConnected: Boolean = false,
    val backupAutoEnabled: Boolean = false,
    val backupPaused: Boolean = false,
    val backupOnlyWhileCharging: Boolean = false,
    val retentionDaily: Int = 7,
    val retentionWeekly: Int = 4,
    val retentionMonthly: Int = 6,
    // M6
    /** Meaning-aware search (local synonyms + fuzzy; plus cloud embeddings only if cloud AI is on). Can be turned off. */
    val semanticSearchEnabled: Boolean = true,
    /** Forgotten-knowledge notifications are opt-in (spec 9 #8). */
    val remindersEnabled: Boolean = false,
    val reminderIntervalDays: Int = 7,
    /** Spec 3: fetch public page title/description for shared links. Off by default (the site sees the request). */
    val linkPreviewsEnabled: Boolean = false,
    /** Spec 7.4: Notion files over the free limit go to the owner's Drive as a link. Off by default. */
    val largeFilesViaDrive: Boolean = false,
    /** Spec 4.6 provider switch: "gemini" or "openai_compat" (owner's own key and endpoint). */
    val aiProvider: String = "gemini",
    val compatBaseUrl: String = "",
    val compatModel: String = "",
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setOnboardingCompleted(done: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setCloudAiEnabled(enabled: Boolean)
    suspend fun setLocalOnlyByDefault(enabled: Boolean)
    suspend fun setFloatingCaptureEnabled(enabled: Boolean)
    suspend fun setAiModel(model: String)
    suspend fun setNotionSyncEnabled(enabled: Boolean)
    suspend fun updateBackup(transform: (AppSettings) -> AppSettings)
    suspend fun updateFeatures(semantic: Boolean? = null, reminders: Boolean? = null, reminderDays: Int? = null)
    suspend fun setLinkPreviews(enabled: Boolean)
    suspend fun setLargeFilesViaDrive(enabled: Boolean)
    suspend fun setAiProvider(provider: String, baseUrl: String, model: String)
    /** Spec 10.1 "delete all data": back to first-run defaults (onboarding shows again). */
    suspend fun clearAll()
}

private val Context.pkosSettings: DataStore<Preferences> by preferencesDataStore(name = "pkos_settings")

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override suspend fun clearAll() { store.edit { it.clear() } }

    constructor(context: Context) : this(context.applicationContext.pkosSettings)

    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_completed")
        val theme = stringPreferencesKey("theme_mode")
        val cloudAi = booleanPreferencesKey("cloud_ai_enabled")
        val localOnly = booleanPreferencesKey("local_only_by_default")
        val floating = booleanPreferencesKey("floating_capture_enabled")
        val aiModel = stringPreferencesKey("ai_model")
        val notionSync = booleanPreferencesKey("notion_sync_enabled")
        val driveConnected = booleanPreferencesKey("drive_connected")
        val backupAuto = booleanPreferencesKey("backup_auto")
        val backupPaused = booleanPreferencesKey("backup_paused")
        val backupCharging = booleanPreferencesKey("backup_only_charging")
        val retDaily = androidx.datastore.preferences.core.intPreferencesKey("retention_daily")
        val retWeekly = androidx.datastore.preferences.core.intPreferencesKey("retention_weekly")
        val retMonthly = androidx.datastore.preferences.core.intPreferencesKey("retention_monthly")
        val semantic = booleanPreferencesKey("semantic_search")
        val linkPreviews = booleanPreferencesKey("link_previews")
        val largeFilesDrive = booleanPreferencesKey("large_files_drive")
        val aiProvider = stringPreferencesKey("ai_provider")
        val compatBaseUrl = stringPreferencesKey("compat_base_url")
        val compatModel = stringPreferencesKey("compat_model")
        val reminders = booleanPreferencesKey("reminders_enabled")
        val reminderDays = androidx.datastore.preferences.core.intPreferencesKey("reminder_days")
    }

    override val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            onboardingCompleted = p[Keys.onboarding] ?: false,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            cloudAiEnabled = p[Keys.cloudAi] ?: false,
            localOnlyByDefault = p[Keys.localOnly] ?: false,
            floatingCaptureEnabled = p[Keys.floating] ?: false,
            aiModel = p[Keys.aiModel]?.takeIf { it.isNotBlank() } ?: com.pkos.core.data.processing.GeminiProvider.DEFAULT_MODEL,
            notionSyncEnabled = p[Keys.notionSync] ?: false,
            driveConnected = p[Keys.driveConnected] ?: false,
            backupAutoEnabled = p[Keys.backupAuto] ?: false,
            backupPaused = p[Keys.backupPaused] ?: false,
            backupOnlyWhileCharging = p[Keys.backupCharging] ?: false,
            retentionDaily = p[Keys.retDaily] ?: 7,
            retentionWeekly = p[Keys.retWeekly] ?: 4,
            retentionMonthly = p[Keys.retMonthly] ?: 6,
            semanticSearchEnabled = p[Keys.semantic] ?: true,
            linkPreviewsEnabled = p[Keys.linkPreviews] ?: false,
            largeFilesViaDrive = p[Keys.largeFilesDrive] ?: false,
            aiProvider = p[Keys.aiProvider] ?: "gemini",
            compatBaseUrl = p[Keys.compatBaseUrl] ?: "",
            compatModel = p[Keys.compatModel] ?: "",
            remindersEnabled = p[Keys.reminders] ?: false,
            reminderIntervalDays = p[Keys.reminderDays] ?: 7,
        )
    }

    override suspend fun setOnboardingCompleted(done: Boolean) { store.edit { it[Keys.onboarding] = done } }
    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[Keys.theme] = mode.name } }
    override suspend fun setCloudAiEnabled(enabled: Boolean) { store.edit { it[Keys.cloudAi] = enabled } }
    override suspend fun setLocalOnlyByDefault(enabled: Boolean) { store.edit { it[Keys.localOnly] = enabled } }
    override suspend fun setFloatingCaptureEnabled(enabled: Boolean) { store.edit { it[Keys.floating] = enabled } }
    override suspend fun setAiModel(model: String) { store.edit { it[Keys.aiModel] = model.trim() } }
    override suspend fun setNotionSyncEnabled(enabled: Boolean) { store.edit { it[Keys.notionSync] = enabled } }
    override suspend fun setLinkPreviews(enabled: Boolean) { store.edit { it[Keys.linkPreviews] = enabled } }
    override suspend fun setLargeFilesViaDrive(enabled: Boolean) { store.edit { it[Keys.largeFilesDrive] = enabled } }
    override suspend fun setAiProvider(provider: String, baseUrl: String, model: String) {
        store.edit { it[Keys.aiProvider] = provider; it[Keys.compatBaseUrl] = baseUrl.trim(); it[Keys.compatModel] = model.trim() }
    }

    override suspend fun updateFeatures(semantic: Boolean?, reminders: Boolean?, reminderDays: Int?) {
        store.edit { p ->
            semantic?.let { p[Keys.semantic] = it }
            reminders?.let { p[Keys.reminders] = it }
            reminderDays?.let { p[Keys.reminderDays] = it.coerceIn(1, 90) }
        }
    }

    override suspend fun updateBackup(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val cur = AppSettings(
                driveConnected = p[Keys.driveConnected] ?: false, backupAutoEnabled = p[Keys.backupAuto] ?: false,
                backupPaused = p[Keys.backupPaused] ?: false, backupOnlyWhileCharging = p[Keys.backupCharging] ?: false,
                retentionDaily = p[Keys.retDaily] ?: 7, retentionWeekly = p[Keys.retWeekly] ?: 4, retentionMonthly = p[Keys.retMonthly] ?: 6,
            )
            val n = transform(cur)
            p[Keys.driveConnected] = n.driveConnected; p[Keys.backupAuto] = n.backupAutoEnabled; p[Keys.backupPaused] = n.backupPaused
            p[Keys.backupCharging] = n.backupOnlyWhileCharging
            p[Keys.retDaily] = n.retentionDaily.coerceIn(1, 60); p[Keys.retWeekly] = n.retentionWeekly.coerceIn(0, 52); p[Keys.retMonthly] = n.retentionMonthly.coerceIn(0, 60)
        }
    }
}
