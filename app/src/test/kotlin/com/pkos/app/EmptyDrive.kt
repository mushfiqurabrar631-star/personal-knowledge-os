package com.pkos.app

import com.pkos.core.data.backup.DriveApi
import com.pkos.core.data.backup.DriveFile
import com.pkos.core.data.backup.DriveQuota
import com.pkos.core.data.backup.UploadStep
import java.io.File

/** Minimal Drive with an empty backup folder and a nearly full quota (UI flows only; the engine has its own tests). */
class EmptyDrive : DriveApi {
    override suspend fun ensureFolder(name: String) = "F"
    override suspend fun startResumableUpload(folderId: String, name: String, size: Long, appProperties: Map<String, String>): String = error("unused")
    override suspend fun uploadChunk(sessionUri: String, file: File, offset: Long, length: Long, total: Long): UploadStep = error("unused")
    override suspend fun queryUpload(sessionUri: String, total: Long): UploadStep = error("unused")
    override suspend fun getFile(id: String): DriveFile = error("unused")
    override suspend fun listFolder(folderId: String) = emptyList<DriveFile>()
    override suspend fun download(id: String, to: File) = error("unused")
    override suspend fun delete(id: String) = Unit
    override suspend fun quota() = DriveQuota(15L shl 30, 14L shl 30)
}
