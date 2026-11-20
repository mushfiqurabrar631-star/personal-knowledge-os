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
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setOnboardingCompleted(done: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setCloudAiEnabled(enabled: Boolean)
    suspend fun setLocalOnlyByDefault(enabled: Boolean)
    suspend fun setFloatingCaptureEnabled(enabled: Boolean)
}

private val Context.pkosSettings: DataStore<Preferences> by preferencesDataStore(name = "pkos_settings")

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    constructor(context: Context) : this(context.applicationContext.pkosSettings)

    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_completed")
        val theme = stringPreferencesKey("theme_mode")
        val cloudAi = booleanPreferencesKey("cloud_ai_enabled")
        val localOnly = booleanPreferencesKey("local_only_by_default")
        val floating = booleanPreferencesKey("floating_capture_enabled")
    }

    override val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            onboardingCompleted = p[Keys.onboarding] ?: false,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            cloudAiEnabled = p[Keys.cloudAi] ?: false,
            localOnlyByDefault = p[Keys.localOnly] ?: false,
            floatingCaptureEnabled = p[Keys.floating] ?: false,
        )
    }

    override suspend fun setOnboardingCompleted(done: Boolean) { store.edit { it[Keys.onboarding] = done } }
    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[Keys.theme] = mode.name } }
    override suspend fun setCloudAiEnabled(enabled: Boolean) { store.edit { it[Keys.cloudAi] = enabled } }
    override suspend fun setLocalOnlyByDefault(enabled: Boolean) { store.edit { it[Keys.localOnly] = enabled } }
    override suspend fun setFloatingCaptureEnabled(enabled: Boolean) { store.edit { it[Keys.floating] = enabled } }
}
