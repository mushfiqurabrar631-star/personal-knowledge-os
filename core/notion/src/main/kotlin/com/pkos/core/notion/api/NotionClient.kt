package com.pkos.core.notion.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder

const val NOTION_API_BASE = "https://api.notion.com/v1"

/** Latest version verified against developers.notion.com on 2026-11-20. */
const val NOTION_API_VERSION = "2026-03-11"

enum class NotionErrorKind {
    NO_TOKEN, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED,
    VALIDATION, SERVER, NETWORK, INVALID_RESPONSE,
}

data class NotionError(
    val kind: NotionErrorKind,
    val httpStatus: Int? = null,
    val code: String? = null,
    /** Short, content-free description safe for local logs. */
    val message: String = "",
    val retryAfterSeconds: Long? = null,
)

sealed interface NotionResult<out T> {
    data class Ok<T>(val value: T) : NotionResult<T>
    data class Err(val error: NotionError) : NotionResult<Nothing>
}

inline fun <T> NotionResult<T>.getOrElse(onError: (NotionError) -> T): T = when (this) {
    is NotionResult.Ok -> value
    is NotionResult.Err -> onError(error)
}

/**
 * Thin wrapper over the official Notion REST API. Only the endpoints PKOS needs.
 * There is deliberately no delete, archive, or schema-changing call in this client.
 */
class NotionClient(
    private val transport: HttpTransport,
    private val tokenProvider: suspend () -> String?,
    private val rateLimiter: RateLimiter = RateLimiter(RateLimiter.NOTION_DEFAULT_INTERVAL_MS),
    private val baseUrl: String = NOTION_API_BASE,
    private val apiVersion: String = NOTION_API_VERSION,
) {
    suspend fun retrieveDatabase(databaseId: String) = call("GET", "/databases/$databaseId", null)
    suspend fun retrieveDataSource(dataSourceId: String) = call("GET", "/data_sources/$dataSourceId", null)
    suspend fun queryDataSource(dataSourceId: String, body: JsonObject) =
        call("POST", "/data_sources/$dataSourceId/query", body)
    suspend fun createPage(body: JsonObject) = call("POST", "/pages", body)
    suspend fun retrievePage(pageId: String) = call("GET", "/pages/$pageId", null)
    suspend fun updatePageProperties(pageId: String, properties: JsonObject) =
        call("PATCH", "/pages/$pageId", buildJsonObject { put("properties", properties) })
    suspend fun listBlockChildren(blockId: String, pageSize: Int = 100, startCursor: String? = null): NotionResult<JsonObject> {
        val cursor = startCursor?.let { "&start_cursor=" + URLEncoder.encode(it, Charsets.UTF_8.name()) }.orEmpty()
        return call("GET", "/blocks/$blockId/children?page_size=$pageSize$cursor", null)
    }
    suspend fun appendBlockChildren(blockId: String, children: JsonArray) =
        call("PATCH", "/blocks/$blockId/children", buildJsonObject { put("children", children) })

    /** Step 1 of a single-part upload (files up to the workspace limit; free plan: 5 MB). */
    suspend fun createFileUpload(filename: String, contentType: String) = call(
        "POST", "/file_uploads",
        buildJsonObject {
            put("mode", kotlinx.serialization.json.JsonPrimitive("single_part"))
            put("filename", kotlinx.serialization.json.JsonPrimitive(filename))
            put("content_type", kotlinx.serialization.json.JsonPrimitive(contentType))
        },
    )

    /** Step 2: send the bytes. Returns the file upload object; status must be "uploaded". */
    suspend fun sendFileUpload(fileUploadId: String, file: java.io.File, filename: String, contentType: String): NotionResult<JsonObject> {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return NotionResult.Err(NotionError(NotionErrorKind.NO_TOKEN, message = "Notion token not configured"))
        rateLimiter.acquire()
        val response = try {
            transport.executeMultipart(
                "$baseUrl/file_uploads/$fileUploadId/send",
                mapOf("Authorization" to "Bearer $token", "Notion-Version" to apiVersion),
                "file", filename, contentType, file,
            )
        } catch (e: IOException) {
            return NotionResult.Err(NotionError(NotionErrorKind.NETWORK, message = e.javaClass.simpleName))
        }
        return parse(response)
    }

    private suspend fun call(method: String, path: String, body: JsonObject?): NotionResult<JsonObject> {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return NotionResult.Err(NotionError(NotionErrorKind.NO_TOKEN, message = "Notion token not configured"))
        rateLimiter.acquire()
        val response = try {
            transport.execute(
                HttpRequest(
                    method = method,
                    url = baseUrl + path,
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Notion-Version" to apiVersion,
                        "Content-Type" to "application/json",
                    ),
                    body = body?.toString(),
                ),
            )
        } catch (e: IOException) {
            return NotionResult.Err(NotionError(NotionErrorKind.NETWORK, message = e.javaClass.simpleName))
        }
        return parse(response)
    }

    private fun parse(response: HttpResponse): NotionResult<JsonObject> {
        if (response.status in 200..299) {
            val parsed = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                ?: return NotionResult.Err(
                    NotionError(NotionErrorKind.INVALID_RESPONSE, response.status, message = "Response was not a JSON object"),
                )
            return NotionResult.Ok(parsed)
        }
        return NotionResult.Err(errorFrom(response))
    }

    private fun errorFrom(response: HttpResponse): NotionError {
        val json = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val code = json?.get("code")?.jsonPrimitive?.contentOrNull
        val message = json?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty().take(300)
        val kind = when (response.status) {
            400 -> NotionErrorKind.VALIDATION
            401 -> NotionErrorKind.UNAUTHORIZED
            403 -> NotionErrorKind.FORBIDDEN
            404 -> NotionErrorKind.NOT_FOUND
            409 -> NotionErrorKind.CONFLICT
            429 -> NotionErrorKind.RATE_LIMITED
            in 500..599 -> NotionErrorKind.SERVER
            else -> NotionErrorKind.VALIDATION
        }
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
        return NotionError(kind, response.status, code, message, retryAfter)
    }
}
