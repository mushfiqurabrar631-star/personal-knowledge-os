package com.pkos.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.pkos.app.di.AppContainer
import com.pkos.app.di.LocalAppContainer
import com.pkos.app.navigation.PkosApp
import com.pkos.app.ui.theme.PkosTheme
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.model.SourcePlatform
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.settings.AppSettings
import com.pkos.core.data.settings.SettingsRepository
import com.pkos.core.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Test double for settings (the real DataStore implementation is covered in :core:data tests). */
class InMemorySettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun setOnboardingCompleted(done: Boolean) { state.value = state.value.copy(onboardingCompleted = done) }
    override suspend fun setThemeMode(mode: ThemeMode) { state.value = state.value.copy(themeMode = mode) }
    override suspend fun setCloudAiEnabled(enabled: Boolean) { state.value = state.value.copy(cloudAiEnabled = enabled) }
    override suspend fun setLocalOnlyByDefault(enabled: Boolean) { state.value = state.value.copy(localOnlyByDefault = enabled) }
}

/** Real repositories over a real (in-memory) Room database. */
fun testContainer(settings: InMemorySettings = InMemorySettings(AppSettings(onboardingCompleted = true))): AppContainer {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val container = AppContainer(context, PkosDatabase.inMemory(context), settings)
    runBlocking { container.categories.ensureStarterTaxonomy() }
    return container
}

@Composable
fun TestApp(container: AppContainer, dark: Boolean = false, onboarding: Boolean = false, onFinished: () -> Unit = {}) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        PkosTheme(darkTheme = dark) { PkosApp(startWithOnboarding = onboarding, onOnboardingFinished = onFinished) }
    }
}

/**
 * Records inserted straight into the test database to show how lists look with content.
 * Capture itself arrives in M2; these rows are test fixtures, never shipped in the app.
 */
object Fixtures {
    private const val NOW = 1_763_620_000_000L // 2025-11-20

    fun item(
        title: String,
        type: ContentType,
        platform: SourcePlatform?,
        processing: ProcessingState,
        sync: SyncState = SyncState.LOCAL_ONLY,
        summary: String? = null,
        url: String? = null,
        minutesAgo: Long = 0,
        reviewReason: String? = null,
        pinned: Boolean = false,
    ) = KnowledgeItemEntity(
        id = UUID.randomUUID().toString(),
        title = title, summary = summary, sourceUrl = url, sourcePlatform = platform, contentType = type,
        processingState = processing, syncState = sync, reviewReason = reviewReason, pinned = pinned,
        status = if (processing == ProcessingState.ORGANIZED) ItemStatus.TO_LEARN else ItemStatus.INBOX,
        categoryId = if (processing == ProcessingState.ORGANIZED) "sys-education" else null,
        createdAt = NOW - minutesAgo * 60_000, updatedAt = NOW - minutesAgo * 60_000,
    )

    val sample: List<KnowledgeItemEntity> get() = listOf(
        item("Ai দিয়ে marketing", ContentType.VIDEO, SourcePlatform.YOUTUBE, ProcessingState.ORGANIZED,
            summary = "AI টুল দিয়ে কন্টেন্ট পরিকল্পনা ও বিজ্ঞাপন বানানোর ধাপগুলো।", url = "https://youtu.be/ynZp8-FjYUo", minutesAgo = 5, pinned = true),
        item("ফ্রি বইয়ের ওয়েবসাইট বাংলায়", ContentType.WEBSITE, SourcePlatform.FACEBOOK, ProcessingState.AI_PENDING,
            url = "https://bn.welib.org/", minutesAgo = 40),
        item("OpenStax free textbooks", ContentType.WEBSITE, SourcePlatform.CHROME, ProcessingState.NEEDS_REVIEW,
            summary = "Free, peer-reviewed college textbooks.", url = "https://openstax.org", minutesAgo = 90,
            reviewReason = "Category confidence 0.52"),
        item("RSO meeting screenshot", ContentType.SCREENSHOT, SourcePlatform.GALLERY, ProcessingState.FAILED, minutesAgo = 180),
    )
}
