package com.pkos.core.data.backup

import java.io.File

data class DriveFile(
    val id: String,
    val name: String,
    val size: Long,
    val md5: String?,
    val createdTime: Long,
    val appProperties: Map<String, String> = emptyMap(),
)

data class DriveQuota(val limitBytes: Long?, val usageBytes: Long)

enum class DriveErrorKind { AUTH_REQUIRED, NETWORK, RATE_LIMITED, SERVER, NOT_FOUND, SESSION_EXPIRED, STORAGE_FULL, OTHER }

class DriveException(val kind: DriveErrorKind, message: String = kind.name, val retryAfterMs: Long? = null) : Exception(message)

sealed interface UploadStep {
    data class Done(val file: DriveFile) : UploadStep
    data class Partial(val nextOffset: Long) : UploadStep
}

/**
 * The Drive operations PKOS needs, with the `drive.file` scope only (files PKOS created). Implemented over REST in the
 * app; tests use an in-memory Drive. All methods throw [DriveException].
 */
interface DriveApi {
    /** Finds or creates the visible folder "Personal Knowledge OS Backups". */
    suspend fun ensureFolder(name: String): String
    suspend fun startResumableUpload(folderId: String, name: String, size: Long, appProperties: Map<String, String>): String
    /** Sends bytes [offset, offset+len) of [file]; a Drive session is resumable for about a week. */
    suspend fun uploadChunk(sessionUri: String, file: File, offset: Long, length: Long, total: Long): UploadStep
    /** Asks Drive how many bytes it has for this session (after an interruption). */
    suspend fun queryUpload(sessionUri: String, total: Long): UploadStep
    suspend fun getFile(id: String): DriveFile
    suspend fun listFolder(folderId: String): List<DriveFile>
    suspend fun download(id: String, to: File)
    suspend fun delete(id: String)
    suspend fun quota(): DriveQuota
}

const val BACKUP_FOLDER_NAME = "Personal Knowledge OS Backups"
