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
class IntegrationStatusProvider(
    private val aiKeySaved: () -> Boolean = { false },
    private val notionReady: () -> Boolean = { false },
    private val driveReady: () -> Boolean = { false },
) {
    fun current(): IntegrationStatus = IntegrationStatus(
        notion = if (notionReady()) ConnectionState.CONNECTED else ConnectionState.NOT_CONNECTED,
        drive = if (driveReady()) ConnectionState.CONNECTED else ConnectionState.NOT_CONNECTED,
        aiProvider = if (aiKeySaved()) ConnectionState.CONNECTED else ConnectionState.NOT_CONNECTED,
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
    val secrets: com.pkos.app.processing.SecretStore = com.pkos.app.processing.KeystoreSecretStore(context),
    ocr: com.pkos.core.data.processing.OcrEngine? = com.pkos.app.processing.MlKitOcrEngine(context),
    private val http: com.pkos.core.data.processing.HttpPost = com.pkos.app.processing.UrlConnectionHttp,
    notionTransport: com.pkos.core.notion.api.HttpTransport = com.pkos.app.notion.OkHttpNotionTransport(),
    driveOverride: com.pkos.core.data.backup.DriveApi? = null,
    private val kdfIterations: Int = com.pkos.core.data.backup.KdfParams.DEFAULT_ITERATIONS,
) {
    val knowledge = KnowledgeRepository(database)
    val categories = CategoryRepository(database)
    val syncOverview = SyncOverviewRepository(database)
    val collections = CollectionRepository(database)
    val storage = StorageRepository(context)
    @Volatile private var notionEnabledCache = false
    val integrations = IntegrationStatusProvider(
        aiKeySaved = { secrets.get(com.pkos.app.processing.GEMINI_KEY) != null },
        notionReady = { notionEnabledCache && secrets.get(com.pkos.app.notion.NOTION_TOKEN) != null },
        driveReady = { kotlinx.coroutines.runBlocking { settings.settings.first().driveConnected } },
    )

    /** One client for the whole app so the 3 requests/second limit is shared by every sync. */
    val notionClient = com.pkos.core.notion.api.NotionClient(notionTransport, { secrets.get(com.pkos.app.notion.NOTION_TOKEN) })
    val notionSync by lazy { com.pkos.core.data.notion.NotionSyncService(database, notionClient, attachments::resolve) }
    val notionScheduler = com.pkos.app.notion.NotionSyncScheduler(context)

    /** Queues a Notion sync for this item if the owner turned sync on and a token is saved. Otherwise nothing. */
    suspend fun requestNotionSync(itemId: String) {
        val enabled = settings.settings.first().notionSyncEnabled
        notionEnabledCache = enabled
        if (!enabled || secrets.get(com.pkos.app.notion.NOTION_TOKEN) == null) return
        notionSync.markQueued(itemId)
        notionScheduler.enqueue(itemId)
    }

    // ---- M5: encrypted Google Drive backup ----
    val driveAuth = com.pkos.app.backup.GoogleDriveAuth(context)
    private val driveApi: com.pkos.core.data.backup.DriveApi = driveOverride ?: com.pkos.app.backup.DriveRestClient({ driveAuth.tokenOrThrow() })
    private val backupWork = java.io.File(context.filesDir, "backup-work")
    val backupScheduler = com.pkos.app.backup.BackupScheduler(context)
    val lastBackupResult = kotlinx.coroutines.flow.MutableStateFlow<com.pkos.core.data.backup.BackupRunResult?>(null)
    val appVersion: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    /** The key-encryption key derived from the owner's recovery key, kept only in the Keystore-encrypted store. */
    fun backupKek(): ByteArray? = secrets.get(BACKUP_KEK)?.let { java.util.Base64.getDecoder().decode(it) }
    fun storeBackupKek(kek: ByteArray) = secrets.put(BACKUP_KEK, java.util.Base64.getEncoder().encodeToString(kek))

    val backupService by lazy {
        com.pkos.core.data.backup.BackupService(
            database, context.getDatabasePath(PkosDatabase.FILE_NAME), java.io.File(context.filesDir, "attachments"), backupWork, driveApi,
            ::backupKek, appVersion,
            retention = { kotlinx.coroutines.runBlocking { settings.settings.first() }.let { com.pkos.core.data.backup.RetentionPolicy(it.retentionDaily, it.retentionWeekly, it.retentionMonthly) } },
            kdfIterations = kdfIterations,
        )
    }
    val restoreService by lazy {
        com.pkos.core.data.backup.RestoreService(context.getDatabasePath(PkosDatabase.FILE_NAME), java.io.File(context.filesDir, "attachments"), backupWork, driveApi)
    }

    /** Queues every item that changed since its last verified sync (app start, "Sync now", sync switched on). */
    suspend fun requestNotionSyncAll(policy: androidx.work.ExistingWorkPolicy = androidx.work.ExistingWorkPolicy.KEEP): Int {
        val enabled = settings.settings.first().notionSyncEnabled
        notionEnabledCache = enabled
        if (!enabled || secrets.get(com.pkos.app.notion.NOTION_TOKEN) == null) return 0
        val ids = notionSync.itemsNeedingSync()
        ids.forEach { notionSync.markQueued(it); notionScheduler.enqueue(it, policy = policy) }
        return ids.size
    }
    val attachments = com.pkos.core.data.capture.AttachmentStore(java.io.File(context.filesDir, StorageRepository.ATTACHMENTS_DIR))
    val captureRepository = com.pkos.core.data.capture.CaptureRepository(
        database, attachments, freeBytes = freeBytes,
    )

    val processing = com.pkos.core.data.processing.ProcessingEngine(
        database, attachments::resolve,
        com.pkos.core.data.processing.DefaultTextExtractor(com.pkos.app.processing.PdfTextExtractor(context)), ocr,
    )
    val scheduler = com.pkos.app.processing.ProcessingScheduler(context)

    /** Cloud AI only when the owner turned it on AND saved a key; otherwise everything stays on the phone (R-09). */
    suspend fun cloudProvider(): com.pkos.core.data.processing.AiProvider? {
        val s = settings.settings.first()
        if (!s.cloudAiEnabled) return null
        val key = secrets.get(com.pkos.app.processing.GEMINI_KEY) ?: return null
        return com.pkos.core.data.processing.GeminiProvider(key, s.aiModel, http)
    }

    /** Called after every successful capture: background processing starts right away (spec 5.2). */
    var onCaptured: (String) -> Unit = { scheduler.enqueue(it) }

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
            finishPendingRestore(app)
            return AppContainer(app, PkosDatabase.create(app), DataStoreSettingsRepository(app))
        }

        /**
         * First start after a restore: the app must be able to open (and migrate) the restored database; otherwise the
         * pre-restore safety snapshot is put back automatically (spec 8.4 #30).
         */
        fun finishPendingRestore(app: Context) {
            val restore = com.pkos.core.data.backup.RestoreService(
                app.getDatabasePath(PkosDatabase.FILE_NAME), java.io.File(app.filesDir, "attachments"), java.io.File(app.filesDir, "backup-work"), null,
            )
            if (!restore.markerFile.exists()) return
            val ok = runCatching {
                val d = PkosDatabase.create(app)
                try { d.openHelper.writableDatabase.query("SELECT COUNT(*) FROM knowledge_items").use { it.moveToFirst() } } finally { d.close() }
            }.isSuccess
            restore.finishPending(ok)
        }
    }
}

const val BACKUP_KEK = "backup_kek"

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
