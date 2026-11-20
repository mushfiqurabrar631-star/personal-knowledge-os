package com.pkos.app.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.repository.CategoryRepository
import com.pkos.core.data.repository.CollectionRepository
import com.pkos.core.data.repository.KnowledgeRepository
import com.pkos.core.data.repository.StorageRepository
import com.pkos.core.data.repository.SyncOverviewRepository
import com.pkos.core.data.settings.DataStoreSettingsRepository
import com.pkos.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/** Connection state of an external destination. Only a real, verified connection may report CONNECTED. */
enum class ConnectionState { NOT_CONNECTED, CONNECTED, ACTION_REQUIRED }

data class IntegrationStatus(
    val notion: ConnectionState,
    val drive: ConnectionState,
    val aiProvider: ConnectionState,
) {
    val anyDestinationConnected: Boolean
        get() = notion == ConnectionState.CONNECTED || drive == ConnectionState.CONNECTED
}

/**
 * M1: no Notion token, Google account or AI key can be stored yet, so every integration honestly reports
 * NOT_CONNECTED. M3/M4/M5 replace this with checks against encrypted credentials and verified connections.
 */
class IntegrationStatusProvider {
    fun current(): IntegrationStatus = IntegrationStatus(
        notion = ConnectionState.NOT_CONNECTED,
        drive = ConnectionState.NOT_CONNECTED,
        aiProvider = ConnectionState.NOT_CONNECTED,
    )
}

enum class PermissionState { GRANTED, NOT_GRANTED, NOT_REQUIRED }

/** Reads (never requests) permission state; requests happen only when a feature needs them (spec 3.1). */
class PermissionStatusProvider(private val context: Context) {
    fun overlay(): PermissionState =
        if (Settings.canDrawOverlays(context)) PermissionState.GRANTED else PermissionState.NOT_GRANTED

    fun notifications(): PermissionState = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> PermissionState.NOT_REQUIRED
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
            PermissionState.GRANTED
        else -> PermissionState.NOT_GRANTED
    }
}

/** Manual dependency container (no reflection, easy to swap with in-memory fakes in tests). */
class AppContainer(
    val context: Context,
    val database: PkosDatabase,
    val settings: SettingsRepository,
    freeBytes: () -> Long = { android.os.StatFs(context.filesDir.path).availableBytes },
) {
    val knowledge = KnowledgeRepository(database)
    val categories = CategoryRepository(database)
    val syncOverview = SyncOverviewRepository(database)
    val collections = CollectionRepository(database)
    val storage = StorageRepository(context)
    val integrations = IntegrationStatusProvider()
    val attachments = com.pkos.core.data.capture.AttachmentStore(java.io.File(context.filesDir, StorageRepository.ATTACHMENTS_DIR))
    val captureRepository = com.pkos.core.data.capture.CaptureRepository(
        database, attachments, freeBytes = freeBytes,
    )

    /** Called after every successful capture (M3 schedules background processing here). */
    var onCaptured: (String) -> Unit = {}

    /** Single entry point for every capture path (Share, floating button, in-app sheet). */
    suspend fun capture(request: com.pkos.core.data.capture.CaptureRequest): com.pkos.core.data.capture.CaptureResult {
        val localOnly = settings.settings.first().localOnlyByDefault
        val result = captureRepository.capture(
            request,
            if (localOnly) com.pkos.core.data.model.PrivacyScope.LOCAL_ONLY else com.pkos.core.data.model.PrivacyScope.NORMAL,
        )
        if (result is com.pkos.core.data.capture.CaptureResult.Saved) onCaptured(result.itemId)
        return result
    }
    val permissions = PermissionStatusProvider(context)

    companion object {
        fun create(context: Context): AppContainer {
            val app = context.applicationContext
            return AppContainer(app, PkosDatabase.create(app), DataStoreSettingsRepository(app))
        }
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
