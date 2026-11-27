package com.pkos.core.data.notion

import com.pkos.core.data.backup.DriveApi
import com.pkos.core.data.backup.DriveErrorKind
import com.pkos.core.data.backup.DriveException
import com.pkos.core.data.backup.UploadStep
import java.io.File
import java.security.MessageDigest

/**
 * Hosts a file that is too large for Notion's free-plan upload limit and returns a link Notion can show (spec 7.4).
 * Only used when the owner turned on "Large files via Google Drive"; off by default.
 */
fun interface LargeFileHost {
    /** Returns the link, or throws [DriveException]. */
    suspend fun host(file: File, name: String, mimeType: String, sha256: String): String
}

/**
 * Uploads the file (not encrypted: the owner asked for a readable copy) into the owner's visible Drive folder with the
 * `drive.file` scope. Sharing is never changed, so the link opens only for the owner's Google account. The same file
 * (by SHA-256) is uploaded once and reused.
 */
class DriveLargeFileHost(
    private val drive: DriveApi,
    private val folderName: String = FOLDER_NAME,
    private val chunk: Long = 8L * 1024 * 1024,
) : LargeFileHost {
    override suspend fun host(file: File, name: String, mimeType: String, sha256: String): String {
        if (!file.exists()) throw DriveException(DriveErrorKind.OTHER, "file missing")
        val folder = drive.ensureFolder(folderName)
        drive.listFolder(folder).firstOrNull { it.appProperties[PROP_SHA] == sha256 && it.size == file.length() }?.let { return link(it.id) }
        val size = file.length()
        val session = drive.startResumableUpload(folder, name, size, mapOf(PROP_SHA to sha256))
        var step: UploadStep = UploadStep.Partial(0)
        while (step is UploadStep.Partial) {
            val off = step.nextOffset
            step = drive.uploadChunk(session, file, off, minOf(chunk, size - off), size)
        }
        val remote = drive.getFile((step as UploadStep.Done).file.id)
        if (remote.size != size || !remote.md5.equals(md5(file), ignoreCase = true)) {
            runCatching { drive.delete(remote.id) }
            throw DriveException(DriveErrorKind.OTHER, "verify mismatch")
        }
        return link(remote.id)
    }

    private fun link(id: String) = "https://drive.google.com/file/d/$id/view"

    private fun md5(f: File): String {
        val d = MessageDigest.getInstance("MD5")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FOLDER_NAME = "Personal Knowledge OS Files"
        const val PROP_SHA = "pkos_file_sha"
    }
}
