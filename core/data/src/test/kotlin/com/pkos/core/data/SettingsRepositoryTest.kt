package com.pkos.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pkos.core.data.settings.DataStoreSettingsRepository
import com.pkos.core.data.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsRepositoryTest {
    @Test fun privacySafeDefaultsAndPersistence() = runBlocking {
        val dir = Files.createTempDirectory("pkos-ds").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        val defaults = repo.settings.first()
        assertFalse("cloud AI must default to OFF", defaults.cloudAiEnabled)
        assertFalse(defaults.onboardingCompleted)
        assertFalse(defaults.localOnlyByDefault)
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)

        repo.setOnboardingCompleted(true); repo.setThemeMode(ThemeMode.DARK); repo.setLocalOnlyByDefault(true)
        val s = repo.settings.first()
        assertTrue(s.onboardingCompleted); assertTrue(s.localOnlyByDefault); assertEquals(ThemeMode.DARK, s.themeMode)
        scope.cancel()
    }
}
