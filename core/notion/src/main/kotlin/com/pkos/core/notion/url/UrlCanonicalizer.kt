package com.pkos.core.notion.url

import java.net.URI
import java.util.Locale

/**
 * Removes tracking parameters so the same link always looks the same (for display and duplicate checks).
 * Only parameters known to be tracking-only are removed; everything else is kept as-is.
 */
object UrlCanonicalizer {
    private val alwaysStrip = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "igsh", "igshid", "mibextid", "rdid", "share_url", "_rdr",
    )
    private val youtubeStrip = setOf("si", "feature", "pp")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com")
    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun extractFirstUrl(text: String): String? =
        urlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')

    fun canonicalize(raw: String): String {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return trimmed
        if (scheme != "http" && scheme != "https") return trimmed
        val host = uri.host?.lowercase(Locale.ROOT) ?: return trimmed
        val isYoutube = host in youtubeHosts
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val keptQuery = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.filterNot { pair ->
                val key = pair.substringBefore('=').lowercase(Locale.ROOT)
                key in alwaysStrip || key.startsWith("utm_") || (isYoutube && key in youtubeStrip)
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
        val path = uri.rawPath.orEmpty()
        return buildString {
            append(scheme).append("://")
            uri.rawUserInfo?.let { append(it).append('@') }
            append(host).append(port).append(path)
            if (keptQuery != null) append('?').append(keptQuery)
            uri.rawFragment?.let { append('#').append(it) }
        }
    }
}
