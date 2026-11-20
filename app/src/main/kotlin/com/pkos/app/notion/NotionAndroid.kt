package com.pkos.app.notion

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pkos.app.PkosApplication
import com.pkos.core.data.notion.NotionSyncResult
import com.pkos.core.notion.api.HttpRequest
import com.pkos.core.notion.api.HttpResponse
import com.pkos.core.notion.api.HttpTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real Notion transport (OkHttp: supports PATCH and multipart). Never logs headers or bodies. */
class OkHttpNotionTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // retries are decided by the sync engine (idempotency)
        .build(),
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val body = request.body?.toRequestBody(JSON)
            ?: if (request.method == "POST" || request.method == "PATCH") ByteArray(0).toRequestBody(JSON) else null
        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        return run(builder.build())
    }

    override suspend fun executeMultipart(
        url: String, headers: Map<String, String>, partName: String, fileName: String, contentType: String, file: File,
    ): HttpResponse {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(partName, fileName, file.asRequestBody(contentType.toMediaType()))
            .build()
        val builder = Request.Builder().url(url).post(multipart)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return run(builder.build())
    }

    private suspend fun run(request: Request): HttpResponse = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { r -> HttpResponse(r.code, r.headers.toMultimap().mapValues { it.value.joinToString(",") }, r.body.string()) }
                } catch (e: IOException) {
                    cont.resumeWithException(e); return
                }
                cont.resume(result)
            }
        })
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}

/** One unique chain per item; runs only with network; survives app death and reboot. */
class NotionSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ITEM) ?: return Result.failure()
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val container = (applicationContext as PkosApplication).container
        val decision = inputData.getString(KEY_DECISION)
        val result = when (decision) {
            DECISION_KEEP -> container.notionSync.resolveConflict(id, keepNotion = true, attempt)
            DECISION_OVERWRITE -> container.notionSync.resolveConflict(id, keepNotion = false, attempt)
            else -> container.notionSync.sync(id, attempt)
        }
        if (result is NotionSyncResult.Retry) container.notionScheduler.enqueue(id, attempt + 1, result.delayMs, decision)
        return Result.success()
    }

    companion object {
        const val KEY_ITEM = "item_id"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_DECISION = "decision"
        const val DECISION_KEEP = "keep_notion"
        const val DECISION_OVERWRITE = "overwrite_notion"
    }
}

class NotionSyncScheduler(private val context: Context) {
    fun enqueue(itemId: String, attempt: Int = 0, delayMs: Long = 0, decision: String? = null, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val request = OneTimeWorkRequestBuilder<NotionSyncWorker>()
            .setInputData(workDataOf(NotionSyncWorker.KEY_ITEM to itemId, NotionSyncWorker.KEY_ATTEMPT to attempt, NotionSyncWorker.KEY_DECISION to decision))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("notion-$itemId", policy, request)
    }

    companion object { const val TAG = "pkos-notion" }
}

const val NOTION_TOKEN = "notion_token"
