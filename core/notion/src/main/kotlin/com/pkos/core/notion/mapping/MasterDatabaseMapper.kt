package com.pkos.core.notion.mapping

import com.pkos.core.notion.model.ContentType
import com.pkos.core.notion.model.ItemPriority
import com.pkos.core.notion.model.ItemStatus
import com.pkos.core.notion.model.SourcePlatform
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.schema.FieldKey
import com.pkos.core.notion.schema.NotionPropertyDef
import com.pkos.core.notion.schema.OptionMatcher
import com.pkos.core.notion.schema.SchemaCheck
import com.pkos.core.notion.url.UrlCanonicalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.ZoneId

data class SkippedValue(val field: FieldKey, val value: String, val reason: Reason) {
    enum class Reason { NO_MATCHING_OPTION, PROPERTY_UNAVAILABLE, NO_EQUIVALENT, TOO_LONG }
}

data class MappingResult(
    val properties: JsonObject,
    /** Values that could not be written; shown to the user as option proposals, never auto-created. */
    val skipped: List<SkippedValue>,
    /** Set when the URL exceeded Notion's limit and must go into the page body instead. */
    val urlForBody: String?,
)

/**
 * Maps a PKOS item to the existing Master Database properties.
 *
 * Safety rules (approved 2026-11-20):
 * - Only properties that exist in the live schema with the expected type are written.
 * - Select / multi-select values are written only if the option already exists (exact live name).
 *   Notion would silently create unknown options, which would be a schema change.
 */
class MasterDatabaseMapper(private val zone: ZoneId = ZoneId.of("Asia/Dhaka")) {

    fun map(item: SyncItem, check: SchemaCheck): MappingResult {
        val skipped = mutableListOf<SkippedValue>()
        var urlForBody: String? = null
        val usable = check.usable

        val properties = buildJsonObject {
            usable[FieldKey.TITLE]?.let { def ->
                val title = item.title.trim().ifEmpty { "Untitled capture" }.take(MAX_TEXT)
                put(def.name, textValue("title", title))
            }
            usable[FieldKey.CAPTURE_ID]?.let { def -> put(def.name, textValue("rich_text", item.captureId)) }

            item.sourceUrl?.takeIf { it.isNotBlank() }?.let { raw ->
                val url = UrlCanonicalizer.canonicalize(raw)
                val def = usable[FieldKey.LINK_URL]
                when {
                    def == null -> {
                        skipped += SkippedValue(FieldKey.LINK_URL, url, SkippedValue.Reason.PROPERTY_UNAVAILABLE)
                        urlForBody = url
                    }
                    url.length > MAX_TEXT -> {
                        skipped += SkippedValue(FieldKey.LINK_URL, url.take(80), SkippedValue.Reason.TOO_LONG)
                        urlForBody = url
                    }
                    else -> put(def.name, buildJsonObject { put("url", url) })
                }
            }

            usable[FieldKey.DATE_ADDED]?.let { def ->
                val date = item.capturedAt.atZone(zone).toLocalDate().toString()
                put(def.name, buildJsonObject { putJsonObject("date") { put("start", date) } })
            }

            selectValue(usable[FieldKey.TYPE], FieldKey.TYPE, item.contentType?.let { typeCandidates(it) }, item.contentType?.name, skipped)
                ?.let { (name, value) -> put(name, value) }
            selectValue(usable[FieldKey.STATUS], FieldKey.STATUS, statusCandidates(item.status), item.status.name, skipped)
                ?.let { (name, value) -> put(name, value) }
            selectValue(usable[FieldKey.PRIORITY], FieldKey.PRIORITY, priorityCandidates(item.priority), item.priority.name, skipped)
                ?.let { (name, value) -> put(name, value) }
            selectValue(usable[FieldKey.PLATFORM], FieldKey.PLATFORM, item.platform?.let { platformCandidates(it) }, item.platform?.name, skipped)
                ?.let { (name, value) -> put(name, value) }

            usable[FieldKey.PINNED]?.let { def -> put(def.name, buildJsonObject { put("checkbox", item.pinned) }) }

            // Category: exact/alias match; otherwise try to express it as an existing Tag.
            val categoryDef = usable[FieldKey.CATEGORY]
            val categoryOption = item.mainCategory?.takeIf { it.isNotBlank() }?.let { cat ->
                categoryDef?.let { OptionMatcher.resolve(it, listOf(cat) + CategoryAliases.forCategory(cat)) }
            }
            if (categoryDef != null && categoryOption != null) {
                put(categoryDef.name, multiSelect(listOf(categoryOption)))
            }

            val tagsDef = usable[FieldKey.TAGS]
            val resolvedTags = linkedSetOf<String>()
            if (tagsDef != null) {
                for (tag in item.tags) {
                    val match = OptionMatcher.resolve(tagsDef, listOf(tag))
                    if (match != null) resolvedTags += match
                    else skipped += SkippedValue(FieldKey.TAGS, tag, SkippedValue.Reason.NO_MATCHING_OPTION)
                }
            } else {
                item.tags.forEach { skipped += SkippedValue(FieldKey.TAGS, it, SkippedValue.Reason.PROPERTY_UNAVAILABLE) }
            }
            item.mainCategory?.takeIf { it.isNotBlank() && categoryOption == null }?.let { cat ->
                val viaTag = tagsDef?.let { OptionMatcher.resolve(it, listOf(cat) + CategoryAliases.forTag(cat)) }
                if (viaTag != null) resolvedTags += viaTag
                else skipped += SkippedValue(FieldKey.CATEGORY, cat, SkippedValue.Reason.NO_MATCHING_OPTION)
            }
            if (tagsDef != null && resolvedTags.isNotEmpty()) put(tagsDef.name, multiSelect(resolvedTags.toList()))

            val filesDef = usable[FieldKey.FILES]
            val files = item.attachments.filter { it.fileUploadId != null || it.externalUrl != null }
            if (files.isNotEmpty()) {
                if (filesDef == null) {
                    files.forEach { skipped += SkippedValue(FieldKey.FILES, it.name, SkippedValue.Reason.PROPERTY_UNAVAILABLE) }
                } else {
                    put(filesDef.name, buildJsonObject {
                        putJsonArray("files") {
                            for (f in files) addJsonObject {
                                put("name", f.name.take(100))
                                if (f.fileUploadId != null) {
                                    put("type", "file_upload")
                                    putJsonObject("file_upload") { put("id", f.fileUploadId) }
                                } else {
                                    put("type", "external")
                                    putJsonObject("external") { put("url", f.externalUrl) }
                                }
                            }
                        }
                    })
                }
            }
        }
        return MappingResult(properties, skipped, urlForBody)
    }

    private fun selectValue(
        def: NotionPropertyDef?,
        key: FieldKey,
        candidates: List<String>?,
        rawLabel: String?,
        skipped: MutableList<SkippedValue>,
    ): Pair<String, JsonObject>? {
        if (rawLabel == null) return null
        if (candidates.isNullOrEmpty()) {
            skipped += SkippedValue(key, rawLabel, SkippedValue.Reason.NO_EQUIVALENT)
            return null
        }
        if (def == null) {
            skipped += SkippedValue(key, rawLabel, SkippedValue.Reason.PROPERTY_UNAVAILABLE)
            return null
        }
        val option = OptionMatcher.resolve(def, candidates)
        if (option == null) {
            skipped += SkippedValue(key, rawLabel, SkippedValue.Reason.NO_MATCHING_OPTION)
            return null
        }
        return def.name to buildJsonObject { putJsonObject("select") { put("name", option) } }
    }

    private fun multiSelect(names: List<String>) = buildJsonObject {
        putJsonArray("multi_select") { names.forEach { n -> addJsonObject { put("name", n) } } }
    }

    private fun textValue(type: String, content: String) = buildJsonObject {
        putJsonArray(type) {
            addJsonObject {
                put("type", "text")
                putJsonObject("text") { put("content", content) }
            }
        }
    }

    companion object {
        const val MAX_TEXT = 2000

        fun typeCandidates(type: ContentType): List<String> = when (type) {
            ContentType.VIDEO -> listOf("Video")
            ContentType.ARTICLE, ContentType.WEBSITE, ContentType.TOOL, ContentType.TUTORIAL -> listOf("Website")
            ContentType.NOTE, ContentType.MESSAGE -> listOf("Note")
            ContentType.IDEA -> listOf("Idea")
            ContentType.DOCUMENT, ContentType.IMAGE -> listOf("File")
            ContentType.OTHER -> emptyList()
        }

        fun statusCandidates(status: ItemStatus): List<String> = when (status) {
            ItemStatus.INBOX, ItemStatus.TO_REVIEW -> listOf("Inbox")
            ItemStatus.TO_LEARN -> listOf("To Learn")
            ItemStatus.IN_PROGRESS -> listOf("In Progress")
            ItemStatus.DONE -> listOf("Completed", "Done")
            ItemStatus.ARCHIVED -> emptyList()
        }

        fun priorityCandidates(priority: ItemPriority): List<String> = when (priority) {
            ItemPriority.LOW -> listOf("Low", "Normal")
            ItemPriority.NORMAL -> listOf("Normal")
            ItemPriority.HIGH -> listOf("High")
            ItemPriority.CRITICAL -> listOf("Critical", "High")
        }

        fun platformCandidates(platform: SourcePlatform): List<String> = when (platform) {
            SourcePlatform.FACEBOOK -> listOf("Facebook")
            SourcePlatform.YOUTUBE -> listOf("Youtube", "YouTube")
            SourcePlatform.INSTAGRAM -> listOf("Instagram")
            SourcePlatform.CHROME -> listOf("Chrome")
            SourcePlatform.WEB -> listOf("Website", "Web")
            SourcePlatform.WHATSAPP -> listOf("WhatsApp")
            SourcePlatform.MESSENGER -> listOf("Messenger")
            SourcePlatform.TELEGRAM -> listOf("Telegram")
            SourcePlatform.GALLERY -> listOf("Gallery")
            SourcePlatform.FILES -> listOf("Files")
            SourcePlatform.OTHER -> emptyList()
        }
    }
}

/** Maps PKOS starter taxonomy names onto the user's existing option names. */
object CategoryAliases {
    private val category = mapOf(
        "education & learning" to listOf("Education"),
        "technology & software" to listOf("Technology"),
        "programming & data" to listOf("Technology"),
        "ideas & inspiration" to listOf("idea"),
        "personal notes" to listOf("Personal life"),
        "mobile tricks" to listOf("Mobile trick"),
        "ai & tools" to listOf("AI"),
        "artificial intelligence" to listOf("AI"),
    )
    private val tag = mapOf(
        "business & finance" to listOf("Business"),
        "business & startup" to listOf("Business"),
        "career & jobs" to listOf("Career"),
        "english & ielts" to listOf("English", "IELTS"),
        "websites & tools" to listOf("Website"),
    )

    fun forCategory(name: String): List<String> = category[OptionMatcher.normalize(name)].orEmpty()
    fun forTag(name: String): List<String> = tag[OptionMatcher.normalize(name)].orEmpty()
}
