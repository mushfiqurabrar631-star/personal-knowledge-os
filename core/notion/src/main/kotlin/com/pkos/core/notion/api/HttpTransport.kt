package com.pkos.core.notion.api

import java.io.IOException

/** Minimal HTTP abstraction so the Notion logic stays pure Kotlin and testable. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * Implemented in the Android app with OkHttp (HttpURLConnection cannot send PATCH).
 * Implementations must throw [IOException] for network failures and never log bodies or headers.
 */
interface HttpTransport {
    @Throws(IOException::class)
    suspend fun execute(request: HttpRequest): HttpResponse

    /**
     * multipart/form-data POST with one file part (Notion file upload "send" step).
     * Default: unsupported, so pure-JSON transports keep working.
     */
    @Throws(IOException::class)
    suspend fun executeMultipart(
        url: String,
        headers: Map<String, String>,
        partName: String,
        fileName: String,
        contentType: String,
        file: java.io.File,
    ): HttpResponse = throw IOException("multipart not supported by this transport")
}
