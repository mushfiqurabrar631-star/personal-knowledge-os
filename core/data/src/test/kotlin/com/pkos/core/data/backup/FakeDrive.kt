package com.pkos.core.data.backup

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** In-memory Google Drive with resumable sessions, failures on demand and quota. */
class FakeDrive : DriveApi {
    class Stored(val file: DriveFile, val bytes: ByteArray, val parent: String)
    private class Session(val name: String, val size: Long, val parent: String, val props: Map<String, String>) { var data = ByteArray(0) }

    val files = linkedMapOf<String, Stored>()
    val folders = linkedMapOf<String, String>()
    private val sessions = mutableMapOf<String, Session>()
    var clock = 1_800_000_000_000L
    var quota = DriveQuota(15L * 1024 * 1024 * 1024, 0)
    var authRequired = false
    /** Network failure after this many successful chunk uploads (then resets). */
    var failChunkAfter: Int? = null
    var expireSessions = false
    var corruptStoredMd5 = false
    var calls = mutableListOf<String>()
    private var chunksSent = 0

    private fun gate(op: String) {
        calls += op
        if (authRequired) throw DriveException(DriveErrorKind.AUTH_REQUIRED)
    }

    override suspend fun ensureFolder(name: String): String {
        gate("folder")
        return folders.entries.firstOrNull { it.value == name }?.key ?: UUID.randomUUID().toString().also { folders[it] = name }
    }

    override suspend fun startResumableUpload(folderId: String, name: String, size: Long, appProperties: Map<String, String>): String {
        gate("start")
        return "session-" + UUID.randomUUID().toString().also { sessions["session-$it"] = Session(name, size, folderId, appProperties) }
    }

    override suspend fun uploadChunk(sessionUri: String, file: File, offset: Long, length: Long, total: Long): UploadStep {
        gate("chunk")
        val s = sessions[sessionUri] ?: throw DriveException(DriveErrorKind.SESSION_EXPIRED)
        if (expireSessions) { sessions.remove(sessionUri); throw DriveException(DriveErrorKind.SESSION_EXPIRED) }
        failChunkAfter?.let { if (chunksSent >= it) { failChunkAfter = null; chunksSent = 0; throw DriveException(DriveErrorKind.NETWORK) } }
        check(offset == s.data.size.toLong()) { "offset $offset but session has ${s.data.size}" }
        val bytes = file.inputStream().use { it.skip(offset); it.readNBytes(length.toInt()) }
        s.data += bytes; chunksSent++
        return if (s.data.size.toLong() == total) finish(sessionUri, s) else UploadStep.Partial(s.data.size.toLong())
    }

    override suspend fun queryUpload(sessionUri: String, total: Long): UploadStep {
        gate("query")
        val s = sessions[sessionUri] ?: throw DriveException(DriveErrorKind.SESSION_EXPIRED)
        return UploadStep.Partial(s.data.size.toLong())
    }

    private fun finish(uri: String, s: Session): UploadStep.Done {
        sessions.remove(uri)
        val id = UUID.randomUUID().toString()
        val md5 = MessageDigest.getInstance("MD5").digest(s.data).joinToString("") { "%02x".format(it) }
        val f = DriveFile(id, s.name, s.data.size.toLong(), md5, clock, s.props)
        files[id] = Stored(f, s.data, s.parent)
        quota = quota.copy(usageBytes = quota.usageBytes + s.data.size)
        return UploadStep.Done(f)
    }

    override suspend fun getFile(id: String): DriveFile {
        gate("get")
        val f = files[id]?.file ?: throw DriveException(DriveErrorKind.NOT_FOUND)
        return if (corruptStoredMd5) f.copy(md5 = "0".repeat(32)) else f
    }

    override suspend fun listFolder(folderId: String) = files.values.filter { it.parent == folderId }.map { it.file }.also { gate("list") }

    override suspend fun download(id: String, to: File) { gate("download"); to.writeBytes(files[id]?.bytes ?: throw DriveException(DriveErrorKind.NOT_FOUND)) }

    override suspend fun delete(id: String) { gate("delete"); files.remove(id) ?: throw DriveException(DriveErrorKind.NOT_FOUND) }

    override suspend fun quota(): DriveQuota { gate("quota"); return quota }

    fun tamper(id: String, at: Int) { val s = files.getValue(id); val b = s.bytes.copyOf(); b[at] = (b[at].toInt() xor 0x40).toByte(); files[id] = Stored(s.file, b, s.parent) }
}
