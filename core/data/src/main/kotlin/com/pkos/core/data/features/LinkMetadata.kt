package com.pkos.core.data.features

import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.DismissalEntity
import com.pkos.core.data.model.FieldOrigin
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.processing.HttpGet
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

data class LinkMeta(val title: String?, val description: String?, val site: String?)

/**
 * Spec 3 (Chrome "optional page metadata", YouTube "public metadata only via permitted source"). Runs only when the
 * owner switched it on; never for local-only items; HTTPS only; at most once per item. YouTube uses its public oEmbed
 * endpoint; other sites: the page's own <title>/OpenGraph tags from the first 512 KB. Nothing is logged.
 */
class LinkMetadataService(private val db: PkosDatabase, private val http: HttpGet, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun fetchFor(itemId: String): Boolean {
        val item = db.knowledgeDao().get(itemId) ?: return false
        val url = item.sourceUrl ?: return false
        if (item.privacyScope != PrivacyScope.NORMAL || item.deletedAt != null || item.linkTitle != null) return false
        val key = "linkmeta:$itemId"
        if (db.featureDao().dismissedAt(key) != null) return false // the site already answered once
        val meta = try { fetch(url) } catch (_: java.io.IOException) { return false } // offline: try again on a later run
            catch (_: Exception) { null }
        db.featureDao().dismiss(DismissalEntity(key, clock()))
        if (meta == null) return false
        if (meta.title == null && meta.description == null) return false
        val fresh = db.knowledgeDao().get(itemId) ?: return false
        val origins = runCatching { JSONObject(fresh.fieldOrigins) }.getOrElse { JSONObject() }
        // A page title may replace only a title PKOS generated itself (never one the owner or AI set).
        val newTitle = meta.title?.takeIf { origins.optString("title") == FieldOrigin.SYSTEM.name }
        db.knowledgeDao().update(
            fresh.copy(
                linkTitle = meta.title?.take(300), linkDescription = meta.description?.take(1_000), linkSite = meta.site?.take(100),
                title = newTitle?.take(200) ?: fresh.title, updatedAt = clock(),
            ),
        )
        return true
    }

    suspend fun fetch(url: String): LinkMeta? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (!u.scheme.equals("https", true) || u.host.isNullOrBlank()) return null
        val host = u.host.lowercase().removePrefix("www.").removePrefix("m.")
        return if (host == "youtube.com" || host == "youtu.be" || host == "music.youtube.com") {
            val r = http.get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"), mapOf("Accept" to "application/json"), 64 * 1024)
            if (r.status != 200) return null
            val j = JSONObject(r.body)
            LinkMeta(j.optString("title").ifBlank { null }, j.optString("author_name").ifBlank { null }?.let { "YouTube · $it" }, "YouTube")
        } else {
            val r = http.get(url, mapOf("Accept" to "text/html"), MAX_BYTES)
            if (r.status !in 200..299) return null
            parseHtml(r.body, host)
        }
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
        private fun meta(html: String, name: String): String? =
            Regex("<meta[^>]+(?:property|name)\\s*=\\s*[\"']$name[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value
                ?.let { Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?.let(::clean)?.ifBlank { null }

        fun parseHtml(html: String, host: String): LinkMeta {
            val head = html.take(MAX_BYTES)
            val title = meta(head, "og:title") ?: meta(head, "twitter:title")
                ?: Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.let(::clean)?.ifBlank { null }
            val desc = meta(head, "og:description") ?: meta(head, "description")
            val site = meta(head, "og:site_name") ?: host
            return LinkMeta(title, desc, site)
        }

        fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim()
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    }
}
