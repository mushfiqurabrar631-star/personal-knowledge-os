package com.pkos.core.notion.sync

import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.NotionError
import com.pkos.core.notion.api.NotionErrorKind
import com.pkos.core.notion.api.NotionResult
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Uploads one local file with Notion's File Upload API (create → send), single part only.
 * Files above [maxBytes] are not sent (Notion's free plan limit is 5 MB per file; PKOS never needs a paid plan).
 * An upload id must be attached to a page within about an hour, so callers re-upload stale, unattached ids.
 */
class NotionFileUploader(private val client: NotionClient, val maxBytes: Long = FREE_PLAN_MAX_BYTES) {
    sealed interface Result {
        data class Uploaded(val fileUploadId: String) : Result
        data object TooLarge : Result
        data class Failed(val error: NotionError) : Result
    }

    suspend fun upload(file: File, filename: String, contentType: String): Result {
        if (!file.exists()) return Result.Failed(NotionError(NotionErrorKind.VALIDATION, message = "local file missing"))
        if (file.length() > maxBytes) return Result.TooLarge
        val created = when (val r = client.createFileUpload(filename, contentType)) {
            is NotionResult.Ok -> r.value
            is NotionResult.Err -> return Result.Failed(r.error)
        }
        val id = created["id"]?.jsonPrimitive?.contentOrNull
            ?: return Result.Failed(NotionError(NotionErrorKind.INVALID_RESPONSE, message = "no upload id"))
        val sent = when (val r = client.sendFileUpload(id, file, filename, contentType)) {
            is NotionResult.Ok -> r.value
            is NotionResult.Err -> return Result.Failed(r.error)
        }
        val status = sent["status"]?.jsonPrimitive?.contentOrNull
        return if (status == "uploaded") Result.Uploaded(id)
        else Result.Failed(NotionError(NotionErrorKind.INVALID_RESPONSE, message = "upload status $status"))
    }

    companion object {
        const val FREE_PLAN_MAX_BYTES = 5L * 1024 * 1024
        /** Notion expires unattached uploads after one hour; re-upload a little before that. */
        const val UPLOAD_REUSE_WINDOW_MS = 50L * 60 * 1000
    }
}
