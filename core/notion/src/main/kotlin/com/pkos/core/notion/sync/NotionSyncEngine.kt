package com.pkos.core.notion.sync

import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.NotionError
import com.pkos.core.notion.api.NotionErrorKind
import com.pkos.core.notion.api.getOrElse
import com.pkos.core.notion.mapping.MasterDatabaseMapper
import com.pkos.core.notion.mapping.PageBodyBuilder
import com.pkos.core.notion.mapping.PropertySnapshot
import com.pkos.core.notion.mapping.SkippedValue
import com.pkos.core.notion.model.NotionPageLink
import com.pkos.core.notion.model.PrivacyScope
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.schema.DataSourceSchemaParser
import com.pkos.core.notion.schema.FieldKey
import com.pkos.core.notion.schema.SchemaCheck
import com.pkos.core.notion.schema.SchemaGuard
import com.pkos.core.notion.schema.SchemaProblem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.UUID

enum class ActionReason {
    NO_TOKEN, UNAUTHORIZED, NO_ACCESS, DATA_SOURCE_NOT_FOUND, SCHEMA_INCOMPATIBLE,
    PAGE_MISSING, PAGE_IN_TRASH, PAGE_NOT_OWNED, DUPLICATE_REMOTE_PAGES,
    VALIDATION_REJECTED, VERIFY_FAILED, INVALID_CAPTURE_ID, RETRIES_EXHAUSTED,
}

sealed interface SyncOutcome {
    /** Verified by reading the page back from Notion. */
    data class Synced(
        val link: NotionPageLink,
        val created: Boolean,
        val skipped: List<SkippedValue>,
        val schemaWarnings: List<SchemaProblem>,
    ) : SyncOutcome

    data class Retry(val reason: NotionErrorKind, val delayMs: Long) : SyncOutcome
    data class ActionRequired(val reason: ActionReason, val detail: String = "") : SyncOutcome

    /** The page was edited in Notion since PKOS last wrote it. Nothing was overwritten. */
    data class Conflict(val link: NotionPageLink, val remoteHash: String) : SyncOutcome
    data class NotEligible(val reason: String) : SyncOutcome
}

/**
 * Idempotent, non-destructive sync of one item into the Master Database.
 *
 * Guarantees:
 * - Reads the live schema before every write; aborts if Title/Capture ID are unusable.
 * - Never creates a second page for the same Capture ID (looks it up before creating).
 * - Only touches pages whose Capture ID equals the item's id (old entries have none).
 * - Never deletes, archives, renames or changes schema. Failures never touch local data
 *   (the caller keeps its local record and schedules a retry from [SyncOutcome.Retry]).
 */
class NotionSyncEngine(
    private val client: NotionClient,
    private val dataSourceId: String,
    private val mapper: MasterDatabaseMapper = MasterDatabaseMapper(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Instant = Instant::now,
) {

    suspend fun sync(
        item: SyncItem,
        existing: NotionPageLink?,
        attempt: Int = 0,
        overwriteRemoteEdits: Boolean = false,
    ): SyncOutcome {
        if (item.privacyScope == PrivacyScope.LOCAL_ONLY) return SyncOutcome.NotEligible("Item is local-only")
        if (!isCanonicalUuid(item.captureId)) {
            return SyncOutcome.ActionRequired(ActionReason.INVALID_CAPTURE_ID)
        }

        // 1. Live schema, every time.
        val schemaJson = client.retrieveDataSource(dataSourceId)
            .getOrElse { return failure(it, attempt, ActionReason.DATA_SOURCE_NOT_FOUND) }
        val schema = runCatching { DataSourceSchemaParser.parse(schemaJson) }.getOrNull()
            ?: return retryOrGiveUp(NotionErrorKind.INVALID_RESPONSE, attempt, null)
        val check = SchemaGuard.check(schema)
        if (check.blocked) {
            val detail = check.problems.filter { it.blocking }
                .joinToString { "${it.propertyName.trim()}: ${it.kind}" }
            return SyncOutcome.ActionRequired(ActionReason.SCHEMA_INCOMPATIBLE, detail)
        }
        val mapping = mapper.map(item, check)
        val managedNames = check.usable.values.map { it.name }
        val captureProp = check.usable.getValue(FieldKey.CAPTURE_ID).name

        // 2. Already linked locally → update that page only.
        if (existing != null) {
            val page = client.retrievePage(existing.pageId)
                .getOrElse { return failure(it, attempt, ActionReason.PAGE_MISSING) }
            ownershipProblem(page, item.captureId, captureProp)?.let { return it }
            val currentHash = PropertySnapshot.hash(page, managedNames)
            if (!overwriteRemoteEdits && currentHash != existing.remoteHash) {
                return SyncOutcome.Conflict(existing, currentHash)
            }
            return update(item, existing.pageId, existing.bodyWritten, check, mapping.properties, mapping.urlForBody,
                mapping.skipped, managedNames, captureProp, attempt, created = false)
        }

        // 3. Not linked locally → look up by Capture ID before creating anything.
        val query = buildJsonObject {
            putJsonObject("filter") {
                put("property", captureProp)
                putJsonObject("rich_text") { put("equals", item.captureId) }
            }
            put("page_size", 2)
        }
        val found = client.queryDataSource(dataSourceId, query)
            .getOrElse { return failure(it, attempt, ActionReason.DATA_SOURCE_NOT_FOUND) }
        val results = found["results"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { PropertySnapshot.plainText(it["properties"]?.jsonObject?.get(captureProp)) == item.captureId }

        when {
            results.size > 1 -> return SyncOutcome.ActionRequired(
                ActionReason.DUPLICATE_REMOTE_PAGES, "${results.size} pages share this Capture ID",
            )
            results.size == 1 -> {
                val page = results.first()
                ownershipProblem(page, item.captureId, captureProp)?.let { return it }
                val pageId = page.string("id") ?: return retryOrGiveUp(NotionErrorKind.INVALID_RESPONSE, attempt, null)
                val children = client.listBlockChildren(pageId)
                    .getOrElse { return failure(it, attempt, ActionReason.PAGE_MISSING) }
                val hasBody = children["results"]?.jsonArray?.isNotEmpty() == true
                return update(item, pageId, hasBody, check, mapping.properties, mapping.urlForBody,
                    mapping.skipped, managedNames, captureProp, attempt, created = false)
            }
        }

        // 4. Create.
        val blocks = PageBodyBuilder.build(item, mapping.urlForBody)
        val createBody = buildJsonObject {
            putJsonObject("parent") {
                put("type", "data_source_id")
                put("data_source_id", dataSourceId)
            }
            put("properties", mapping.properties)
            if (blocks.isNotEmpty()) put("children", JsonArray(blocks))
        }
        val created = client.createPage(createBody)
            .getOrElse { return failure(it, attempt, ActionReason.DATA_SOURCE_NOT_FOUND) }
        val pageId = created.string("id") ?: return retryOrGiveUp(NotionErrorKind.INVALID_RESPONSE, attempt, null)
        return verify(item, pageId, blocks.isNotEmpty(), managedNames, captureProp, mapping.skipped, check, attempt, created = true)
    }

    private suspend fun update(
        item: SyncItem,
        pageId: String,
        bodyAlreadyWritten: Boolean,
        check: SchemaCheck,
        properties: JsonObject,
        urlForBody: String?,
        skipped: List<SkippedValue>,
        managedNames: List<String>,
        captureProp: String,
        attempt: Int,
        created: Boolean,
    ): SyncOutcome {
        client.updatePageProperties(pageId, properties)
            .getOrElse { return failure(it, attempt, ActionReason.PAGE_MISSING) }
        var bodyWritten = bodyAlreadyWritten
        if (!bodyWritten) {
            val blocks = PageBodyBuilder.build(item, urlForBody)
            if (blocks.isNotEmpty()) {
                client.appendBlockChildren(pageId, JsonArray(blocks))
                    .getOrElse { return failure(it, attempt, ActionReason.PAGE_MISSING) }
                bodyWritten = true
            }
        }
        return verify(item, pageId, bodyWritten, managedNames, captureProp, skipped, check, attempt, created)
    }

    private suspend fun verify(
        item: SyncItem,
        pageId: String,
        bodyWritten: Boolean,
        managedNames: List<String>,
        captureProp: String,
        skipped: List<SkippedValue>,
        check: SchemaCheck,
        attempt: Int,
        created: Boolean,
    ): SyncOutcome {
        val page = client.retrievePage(pageId)
            .getOrElse { return failure(it, attempt, ActionReason.PAGE_MISSING) }
        val storedId = PropertySnapshot.plainText(page["properties"]?.jsonObject?.get(captureProp))
        if (storedId != item.captureId || !belongsToDataSource(page)) {
            return SyncOutcome.ActionRequired(ActionReason.VERIFY_FAILED, "Read-back did not match")
        }
        val link = NotionPageLink(
            captureId = item.captureId,
            pageId = pageId,
            pageUrl = page.string("url"),
            dataSourceId = dataSourceId,
            remoteHash = PropertySnapshot.hash(page, managedNames),
            bodyWritten = bodyWritten,
            lastSyncedAt = clock(),
        )
        return SyncOutcome.Synced(link, created, skipped, check.problems)
    }

    private fun ownershipProblem(page: JsonObject, captureId: String, captureProp: String): SyncOutcome? {
        if (page["in_trash"]?.jsonPrimitive?.booleanOrNull == true || page["archived"]?.jsonPrimitive?.booleanOrNull == true) {
            return SyncOutcome.ActionRequired(ActionReason.PAGE_IN_TRASH)
        }
        val stored = PropertySnapshot.plainText(page["properties"]?.jsonObject?.get(captureProp))
        if (stored != captureId || !belongsToDataSource(page)) {
            return SyncOutcome.ActionRequired(ActionReason.PAGE_NOT_OWNED)
        }
        return null
    }

    private fun belongsToDataSource(page: JsonObject): Boolean {
        val parent = page["parent"]?.jsonObject ?: return false
        val ds = parent["data_source_id"]?.jsonPrimitive?.contentOrNull ?: return false
        return normalizeId(ds) == normalizeId(dataSourceId)
    }

    private fun failure(error: NotionError, attempt: Int, notFound: ActionReason): SyncOutcome = when (error.kind) {
        NotionErrorKind.NO_TOKEN -> SyncOutcome.ActionRequired(ActionReason.NO_TOKEN)
        NotionErrorKind.UNAUTHORIZED -> SyncOutcome.ActionRequired(ActionReason.UNAUTHORIZED, error.code.orEmpty())
        NotionErrorKind.FORBIDDEN -> SyncOutcome.ActionRequired(ActionReason.NO_ACCESS, error.code.orEmpty())
        NotionErrorKind.NOT_FOUND -> SyncOutcome.ActionRequired(notFound, error.code.orEmpty())
        NotionErrorKind.VALIDATION -> SyncOutcome.ActionRequired(ActionReason.VALIDATION_REJECTED, error.message)
        NotionErrorKind.RATE_LIMITED, NotionErrorKind.SERVER, NotionErrorKind.NETWORK,
        NotionErrorKind.CONFLICT, NotionErrorKind.INVALID_RESPONSE ->
            retryOrGiveUp(error.kind, attempt, error.retryAfterSeconds)
    }

    private fun retryOrGiveUp(kind: NotionErrorKind, attempt: Int, retryAfterSeconds: Long?): SyncOutcome {
        if (retryPolicy.exhausted(attempt)) return SyncOutcome.ActionRequired(ActionReason.RETRIES_EXHAUSTED, kind.name)
        val delay = retryAfterSeconds?.let { retryPolicy.clampServerDelay(it * 1_000) } ?: retryPolicy.delayFor(attempt)
        return SyncOutcome.Retry(kind, delay)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        fun normalizeId(id: String) = id.replace("-", "").lowercase()
        fun isCanonicalUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
