package com.pkos.app.capture

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.model.CaptureMethod
import java.io.FileNotFoundException

/**
 * Turns ACTION_SEND / ACTION_SEND_MULTIPLE payloads into a [CaptureRequest] (spec 3.1).
 * Only what the user explicitly shared is read; content URIs are opened lazily during capture.
 */
object ShareIntentParser {
    fun parse(intent: Intent, resolver: ContentResolver, sourceApp: String?): CaptureRequest? {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val uris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            else -> streamListExtra(intent)
        } + clipUris(intent)
        val distinct = uris.distinct()
        // text/uri-list without EXTRA_TEXT: use the clip text
        val clipText = if (text == null) intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).text?.toString() }.joinToString("\n") }?.takeIf { it.isNotBlank() } else null
        val files = distinct.map { uri -> fileFor(uri, resolver, intent.type) }
        val method = if (action == Intent.ACTION_SEND_MULTIPLE) CaptureMethod.SHARE_MULTIPLE else CaptureMethod.SHARE
        if (text.isNullOrBlank() && clipText.isNullOrBlank() && files.isEmpty()) return null
        return CaptureRequest(method = method, text = text ?: clipText, subject = subject, files = files, sourceApp = sourceApp)
    }

    fun fileFor(uri: Uri, resolver: ContentResolver, fallbackMime: String?, method: CaptureMethod? = null): CaptureFile {
        var name: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        val mime = resolver.getType(uri) ?: fallbackMime?.takeIf { !it.contains('*') }
        return CaptureFile(
            displayName = name ?: uri.lastPathSegment,
            mimeType = mime,
            sourceUri = uri.toString(),
            open = { resolver.openInputStream(uri) ?: throw FileNotFoundException("No stream for shared item") },
        )
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    private fun clipUris(intent: Intent): List<Uri> = intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
}
