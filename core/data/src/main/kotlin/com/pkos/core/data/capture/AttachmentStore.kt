package com.pkos.core.data.capture

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * App-private, content-addressed file storage (spec 3.1, 5): files are streamed (never fully loaded in RAM),
 * hashed with SHA-256 while copying, and stored as attachments/<aa>/<sha256>[.ext]. Identical files share one copy.
 */
class AttachmentStore(private val root: File, private val maxFileBytes: Long = MAX_FILE_BYTES) {
    data class Stored(val file: File, val sha256: String, val sizeBytes: Long, val createdNew: Boolean)

    class TooLargeException(val limit: Long) : IOException("File exceeds $limit bytes")

    private val tmpDir get() = File(root, ".tmp")

    @Throws(IOException::class)
    fun store(input: InputStream, extension: String?): Stored {
        tmpDir.mkdirs()
        val tmp = File(tmpDir, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            input.use { src ->
                tmp.outputStream().buffered(BUFFER).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > maxFileBytes) throw TooLargeException(maxFileBytes)
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val ext = extension?.lowercase()?.filter { it.isLetterOrDigit() }?.take(8)?.takeIf { it.isNotEmpty() }
            val dir = File(root, sha.take(2)).apply { mkdirs() }
            val target = File(dir, if (ext != null) "$sha.$ext" else sha)
            if (target.exists() && target.length() == size) {
                tmp.delete()
                return Stored(target, sha, size, createdNew = false)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
            return Stored(target, sha, size, createdNew = true)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** Relative path stored in the database (portable across restore). */
    fun relativePath(file: File): String = file.relativeTo(root).path

    fun resolve(relativePath: String): File = File(root, relativePath)

    fun clearTemp() { tmpDir.listFiles()?.forEach { it.delete() } }

    companion object {
        const val BUFFER = 64 * 1024
        const val MAX_FILE_BYTES = 200L * 1024 * 1024
    }
}
