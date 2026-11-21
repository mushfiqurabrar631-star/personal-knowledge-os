package com.pkos.app.backup

import android.content.Context
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.pkos.core.data.backup.DriveApi
import com.pkos.core.data.backup.DriveErrorKind
import com.pkos.core.data.backup.DriveException
import com.pkos.core.data.backup.DriveFile
import com.pkos.core.data.backup.DriveQuota
import com.pkos.core.data.backup.UploadStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Least-privilege scope (spec 8.1): only files PKOS itself creates, e.g. the backup folder and archives. */
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

sealed interface DriveAuthState {
    data class Token(val accessToken: String) : DriveAuthState
    /** Google needs the owner to choose an account / grant access (only possible from the UI). */
    data class NeedsUser(val intentSender: IntentSender) : DriveAuthState
    data class Failed(val reason: String) : DriveAuthState
}

/** Google Identity Services authorization; works in the background once the owner granted access. */
class GoogleDriveAuth(private val context: Context) {
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE))).build()

    suspend fun authorize(): DriveAuthState = try {
        result(Identity.getAuthorizationClient(context).authorize(request).await())
    } catch (e: Exception) {
        DriveAuthState.Failed(e.javaClass.simpleName)
    }

    fun fromIntent(data: android.content.Intent?): DriveAuthState = try {
        result(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    } catch (e: Exception) {
        DriveAuthState.Failed(e.javaClass.simpleName)
    }

    private fun result(r: AuthorizationResult): DriveAuthState = when {
        r.hasResolution() -> DriveAuthState.NeedsUser(r.pendingIntent!!.intentSender)
        r.accessToken != null && DRIVE_FILE_SCOPE in r.grantedScopes -> DriveAuthState.Token(r.accessToken!!)
        else -> DriveAuthState.Failed("SCOPE_NOT_GRANTED")
    }

    /** For background work: a token or AUTH_REQUIRED (never shows UI). */
    suspend fun tokenOrThrow(): String = when (val s = authorize()) {
        is DriveAuthState.Token -> s.accessToken
        is DriveAuthState.NeedsUser -> throw DriveException(DriveErrorKind.AUTH_REQUIRED)
        is DriveAuthState.Failed -> throw DriveException(if (s.reason.contains("ApiException")) DriveErrorKind.AUTH_REQUIRED else DriveErrorKind.NETWORK, s.reason)
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}

/**
 * Google Drive v3 REST over OkHttp. Uses resumable uploads; never logs tokens or bodies.
 * [baseUrl]/[uploadUrl] are overridable for tests (MockWebServer).
 */
class DriveRestClient(
    private val token: suspend () -> String,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val baseUrl: String = "https://www.googleapis.com/drive/v3",
    private val uploadUrl: String = "https://www.googleapis.com/upload/drive/v3",
) : DriveApi {
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(build: Request.Builder.() -> Unit): Response = withContext(Dispatchers.IO) {
        val req = Request.Builder().header("Authorization", "Bearer ${token()}").apply(build).build()
        try { http.newCall(req).execute() } catch (e: IOException) { throw DriveException(DriveErrorKind.NETWORK, e.javaClass.simpleName) }
    }

    private fun fail(r: Response): Nothing {
        val body = runCatching { r.body.string() }.getOrDefault("")
        val retryAfter = r.header("Retry-After")?.toLongOrNull()?.times(1000)
        throw when {
            r.code == 401 -> DriveException(DriveErrorKind.AUTH_REQUIRED)
            r.code == 404 -> DriveException(DriveErrorKind.NOT_FOUND)
            r.code == 403 && body.contains("storageQuotaExceeded") -> DriveException(DriveErrorKind.STORAGE_FULL)
            r.code == 429 || (r.code == 403 && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded"))) ->
                DriveException(DriveErrorKind.RATE_LIMITED, retryAfterMs = retryAfter)
            r.code == 403 -> DriveException(DriveErrorKind.AUTH_REQUIRED, "forbidden")
            r.code >= 500 -> DriveException(DriveErrorKind.SERVER, retryAfterMs = retryAfter)
            else -> DriveException(DriveErrorKind.OTHER, "HTTP ${r.code}")
        }
    }

    private suspend fun jsonCall(build: Request.Builder.() -> Unit): JSONObject = call(build).use { r ->
        if (!r.isSuccessful) fail(r)
        JSONObject(r.body.string().ifBlank { "{}" })
    }

    override suspend fun ensureFolder(name: String): String {
        val q = "name = '${name.replace("'", "\\'")}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = jsonCall { url("$baseUrl/files?q=${enc(q)}&spaces=drive&fields=files(id,name)") }.getJSONArray("files")
        if (found.length() > 0) return found.getJSONObject(0).getString("id")
        val body = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder").toString().toRequestBody(json)
        return jsonCall { url("$baseUrl/files?fields=id"); post(body) }.getString("id")
    }

    override suspend fun startResumableUpload(folderId: String, name: String, size: Long, appProperties: Map<String, String>): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray(listOf(folderId))).put("appProperties", JSONObject(appProperties as Map<*, *>))
            .put("mimeType", "application/octet-stream").toString().toRequestBody(json)
        return call {
            url("$uploadUrl/files?uploadType=resumable&fields=${enc(FIELDS)}")
            header("X-Upload-Content-Type", "application/octet-stream"); header("X-Upload-Content-Length", size.toString())
            post(meta)
        }.use { r -> if (!r.isSuccessful) fail(r); r.header("Location") ?: throw DriveException(DriveErrorKind.OTHER, "no session") }
    }

    override suspend fun uploadChunk(sessionUri: String, file: File, offset: Long, length: Long, total: Long): UploadStep {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(64 * 1024); var left = length
                    while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; sink.write(buf, 0, n); left -= n }
                }
            }
        }
        return call {
            url(sessionUri)
            header("Content-Range", "bytes $offset-${offset + length - 1}/$total")
            put(body)
        }.use(::uploadStep)
    }

    override suspend fun queryUpload(sessionUri: String, total: Long): UploadStep = call {
        url(sessionUri); header("Content-Range", "bytes */$total"); put(ByteArray(0).toRequestBody(null))
    }.use(::uploadStep)

    private fun uploadStep(r: Response): UploadStep = when {
        r.code == 200 || r.code == 201 -> UploadStep.Done(file(JSONObject(r.body.string())))
        r.code == 308 -> UploadStep.Partial(r.header("Range")?.substringAfter("-")?.toLongOrNull()?.plus(1) ?: 0)
        r.code == 404 || r.code == 410 -> throw DriveException(DriveErrorKind.SESSION_EXPIRED)
        else -> fail(r)
    }

    override suspend fun getFile(id: String) = file(jsonCall { url("$baseUrl/files/$id?fields=${enc(FIELDS)}") })

    override suspend fun listFolder(folderId: String): List<DriveFile> {
        val q = "'$folderId' in parents and trashed = false"
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val res = jsonCall { url("$baseUrl/files?q=${enc(q)}&spaces=drive&pageSize=100&fields=${enc("nextPageToken,files($FIELDS)")}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")) }
            val arr = res.getJSONArray("files")
            for (i in 0 until arr.length()) out += file(arr.getJSONObject(i))
            page = res.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    override suspend fun download(id: String, to: File) {
        call { url("$baseUrl/files/$id?alt=media") }.use { r ->
            if (!r.isSuccessful) fail(r)
            try { to.outputStream().use { o -> r.body.byteStream().copyTo(o, 64 * 1024) } } catch (e: IOException) { to.delete(); throw DriveException(DriveErrorKind.NETWORK) }
        }
    }

    override suspend fun delete(id: String) {
        call { url("$baseUrl/files/$id"); delete() }.use { r -> if (!r.isSuccessful && r.code != 204) fail(r) }
    }

    override suspend fun quota(): DriveQuota {
        val q = jsonCall { url("$baseUrl/about?fields=storageQuota") }.getJSONObject("storageQuota")
        return DriveQuota(q.optString("limit").toLongOrNull(), q.optString("usage").toLongOrNull() ?: 0)
    }

    private fun file(o: JSONObject): DriveFile {
        val props = o.optJSONObject("appProperties")
        return DriveFile(
            o.getString("id"), o.optString("name"), o.optString("size").toLongOrNull() ?: 0, o.optString("md5Checksum").ifBlank { null },
            runCatching { Instant.parse(o.optString("createdTime")).toEpochMilli() }.getOrDefault(0),
            props?.keys()?.asSequence()?.associateWith { props.getString(it) } ?: emptyMap(),
        )
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object { const val FIELDS = "id,name,size,md5Checksum,createdTime,appProperties" }
}
