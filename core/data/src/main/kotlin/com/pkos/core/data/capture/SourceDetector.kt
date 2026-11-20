package com.pkos.core.data.capture

import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.SourcePlatform
import java.net.URI
import java.util.Locale

/** Deterministic, offline detection of where a capture came from and what it probably is. */
object SourceDetector {
    private val packages = mapOf(
        "com.facebook.katana" to SourcePlatform.FACEBOOK, "com.facebook.lite" to SourcePlatform.FACEBOOK,
        "com.facebook.orca" to SourcePlatform.MESSENGER, "com.facebook.mlite" to SourcePlatform.MESSENGER,
        "com.google.android.youtube" to SourcePlatform.YOUTUBE, "app.revanced.android.youtube" to SourcePlatform.YOUTUBE,
        "com.instagram.android" to SourcePlatform.INSTAGRAM,
        "com.android.chrome" to SourcePlatform.CHROME, "com.chrome.beta" to SourcePlatform.CHROME,
        "com.whatsapp" to SourcePlatform.WHATSAPP, "com.whatsapp.w4b" to SourcePlatform.WHATSAPP,
        "org.telegram.messenger" to SourcePlatform.TELEGRAM,
        "com.google.android.apps.photos" to SourcePlatform.GALLERY, "com.sec.android.gallery3d" to SourcePlatform.GALLERY,
        "com.miui.gallery" to SourcePlatform.GALLERY, "com.android.gallery3d" to SourcePlatform.GALLERY,
        "com.google.android.documentsui" to SourcePlatform.FILES, "com.google.android.apps.nbu.files" to SourcePlatform.FILES,
    )

    fun platform(sourcePackage: String?, url: String?, hasImage: Boolean, method: com.pkos.core.data.model.CaptureMethod): SourcePlatform? {
        host(url)?.let { h ->
            when {
                h.endsWith("youtube.com") || h == "youtu.be" -> return SourcePlatform.YOUTUBE
                h.endsWith("facebook.com") || h == "fb.watch" || h.endsWith("fb.com") -> return SourcePlatform.FACEBOOK
                h.endsWith("instagram.com") -> return SourcePlatform.INSTAGRAM
            }
        }
        sourcePackage?.let { pkg -> packages[pkg]?.let { return it } }
        return when {
            url != null -> SourcePlatform.WEB
            method == com.pkos.core.data.model.CaptureMethod.QUICK_NOTE -> SourcePlatform.MANUAL
            method == com.pkos.core.data.model.CaptureMethod.PASTE -> SourcePlatform.CLIPBOARD
            hasImage -> SourcePlatform.GALLERY
            else -> null
        }
    }

    fun contentType(url: String?, mimeTypes: List<String>, fileNames: List<String>, text: String?, method: com.pkos.core.data.model.CaptureMethod): ContentType {
        val h = host(url)
        val path = url?.let { runCatching { URI(it).path }.getOrNull() }.orEmpty().lowercase(Locale.ROOT)
        return when {
            h != null && (h.endsWith("youtube.com") || h == "youtu.be" || h == "fb.watch" || path.contains("/reel") || path.contains("/videos/") || path.contains("/watch")) -> ContentType.VIDEO
            mimeTypes.any { it.startsWith("image/") } ->
                if (fileNames.any { it.contains("screenshot", ignoreCase = true) } || fileNames.any { it.startsWith("Screenshot") }) ContentType.SCREENSHOT else ContentType.IMAGE
            mimeTypes.any { it == "application/pdf" || it.startsWith("application/") || it.startsWith("text/") && it != "text/plain" } -> ContentType.DOCUMENT
            mimeTypes.any { it.startsWith("video/") } -> ContentType.VIDEO
            url != null -> ContentType.WEBSITE
            method == com.pkos.core.data.model.CaptureMethod.QUICK_NOTE -> ContentType.NOTE
            !text.isNullOrBlank() -> ContentType.MESSAGE
            else -> ContentType.OTHER
        }
    }

    fun host(url: String?): String? = url?.let { runCatching { URI(it).host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") }.getOrNull() }
}
