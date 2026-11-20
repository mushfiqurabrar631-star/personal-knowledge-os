package com.pkos.core.notion.sync

import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.NotionError
import com.pkos.core.notion.api.NotionErrorKind
import com.pkos.core.notion.api.NotionResult
import com.pkos.core.notion.api.getOrElse
import com.pkos.core.notion.mapping.BodyMarker
import com.pkos.core.notion.mapping.MappingResult
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

enum class ActionReason {
    NO_TOKEN, UNAUTHORIZED, NO_ACCESS, DATA_SOURCE_NOT_FOUND, SCHEMA_INCOMPATIBLE,
    PAGE_MISSING, PAGE_IN_TRASH, PAGE_NOT_OWNED, DUPLICATE_REMOTE_PAGES,
    VALIDATION_REJECTED, VERIFY_FAILED, INVALID_CAPTURE_ID, RETRIES_EXHAUSTED,
}

enum class RetryReason { RATE_LIMITED, SERVER, NETWORK, CONFLICT, INVALID_RESPONSE, LOCAL_JOURNAL, AWAITING_REMOTE_INDEX }

/** WRITTEN: this item's PKOS marker was found (or written now). UNKNOWN: page too long to scan safely. */
enum class BodyState { WRITTEN, ABSENT, UNKNOWN }

enum class ConflictKind {
    /** Linked page changed in Notion since PKOS last wrote it. */
    REMOTE_EDITED,

    /** Page found by Capture ID without a local mapping, and its values differ from both PKOS's last write and the local item. */
    ADOPTION_REVIEW,
}

enum class ConflictResolution {
    /** Write the local values to Notion. */
    OVERWRITE_REMOTE,

    /** Write nothing; link the page. The app must copy [SyncOutcome.Synced.keptRemoteValues] into the local item. */
    KEEP_REMOTE,
}

sealed interface SyncOutcome {
    /** Verified by reading the page back from Notion (or, if nothing was written, by the page just read). */
    data class Synced(
        val link: NotionPageLink,
        val created: Boolean,
        val adopted: Boolean,
        val propertiesWritten: Boolean,
        val bodyState: BodyState,
        val skipped: List<SkippedValue>,
        val schemaWarnings: List<SchemaProblem>,
        val keptRemoteValues: Map<String, String> = emptyMap(),
    ) : SyncOutcome

    data class Retry(val reason: RetryReason, val delayMs: Long) : SyncOutcome
    data class ActionRequired(val reason: ActionReason, val detail: String = "") : SyncOutcome

    /** Nothing was written. Ask the owner, then call sync again with a [ConflictResolution]. */
    data class Conflict(
        val kind: ConflictKind,
        val pageId: String,
        val pageUrl: String?,
        val remoteHash: String,
        val differingProperties: List<String>,
        val remoteValues: Map<String, String>,
        val localValues: Map<String, String>,
    ) : SyncOutcome

    data class NotEligible(val reason: String) : SyncOutcome
}

/**
 * Idempotent, non-destructive, crash-safe sync of one item into the Master Database.
 *
 * - Live schema is read before every write; Title / Capture ID must be usable.
 * - Before every write a [WriteIntent] is stored in the [SyncJournal]. After a crash, lost response or failed
 *   local save, the engine recognises its own earlier write (same properties hash) and continues, instead of
 *   creating a duplicate page or reporting a false conflict.
 * - A page found by Capture ID without a local mapping is adopted silently only if its values equal what PKOS
 *   last wrote or would write now; otherwise [ConflictKind.ADOPTION_REVIEW].
 * - Body blocks are appended only when this item's [BodyMarker] is absent. Existing blocks are never edited or
 *   deleted; there is no delete or archive call anywhere in this module.
 */
class NotionSyncEngine(
    private val client: NotionClient,
    private val dataSourceId: String,
    private val journal: SyncJournal,
    private val mapper: MasterDatabaseMapper = MasterDatabaseMapper(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Instant = Instant::now,
    /** How long to wait for Notion's query index after a create whose response was lost. */
    private val createLookupGraceMs: Long = 120_000,
    private val maxBodyScanPages: Int = 20,
) {
    private class Ctx(
        val item: SyncItem,
        val existing: NotionPageLink?,
        val intent: WriteIntent?,
        val check: SchemaCheck,
        val mapping: MappingResult,
        val managed: List<String>,
        val captureProp: String,
        val expectedHash: String,
        val attempt: Int,
        val resolution: ConflictResolution?,
    )

    private sealed interface Step<out T> {
        data class Go<T>(val value: T) : Step<T>
        data class Stop(val outcome: SyncOutcome) : Step<Nothing>
    }

    suspend fun sync(
        item: SyncItem,
        existing: NotionPageLink?,
        attempt: Int = 0,
        resolution: ConflictResolution? = null,
    ): SyncOutcome {
        if (item.privacyScope == PrivacyScope.LOCAL_ONLY) return SyncOutcome.NotEligible("Item is local-only")
        if (!isCanonicalUuid(item.captureId)) return SyncOutcome.ActionRequired(ActionReason.INVALID_CAPTURE_ID)

        val schemaJson = client.retrieveDataSource(dataSourceId)
            .getOrElse { return failure(it, attempt, ActionReason.DATA_SOURCE_NOT_FOUND) }
        val schema = runCatching { DataSourceSchemaParser.parse(schemaJson) }.getOrNull()
            ?: return retryOrGiveUp(RetryReason.INVALID_RESPONSE, attempt, null)
        val check = SchemaGuard.check(schema)
        if (check.blocked) {
            val detail = check.problems.filter { it.blocking }.joinToString { "${it.propertyName.trim()}: ${it.kind}" }
            return SyncOutcome.ActionRequired(ActionReason.SCHEMA_INCOMPATIBLE, detail)
        }
        val mapping = mapper.map(item, check)
        val managed = check.usable.values.map { it.name }
        val intent = journalRead(item.captureId) ?: return retryOrGiveUp(RetryReason.LOCAL_JOURNAL, attempt, null)

        val ctx = Ctx(
            item = item,
            existing = existing,
            intent = intent.getOrNull(),
            check = check,
            mapping = mapping,
            managed = managed,
            captureProp = check.usable.getValue(FieldKey.CAPTURE_ID).name,
            expectedHash = PropertySnapshot.hashProperties(mapping.properties, managed),
            attempt = attempt,
            resolution = resolution,
        )

        val page: JsonObject? = if (existing != null) {
            val p = client.retrievePage(existing.pageId).getOrElse { return failure(it, attempt, ActionReason.PAGE_MISSING) }
            ownershipProblem(p, ctx)?.let { return it }
            p
        } else {
            when (val found = findUnlinked(ctx)) {
                is Step.Stop -> return found.outcome
                is Step.Go -> found.value
            }
        }
        return if (page == null) create(ctx) else reconcile(ctx, page)
    }

    /** Looks for this item's page when there is no local mapping. Go(null) means "safe to create". */
    private suspend fun findUnlinked(ctx: Ctx): Step<JsonObject?> {
        val intent = ctx.intent
        intent?.pageId?.let { knownId ->
            when (val r = client.retrievePage(knownId)) {
                is NotionResult.Ok -> {
                    ownershipProblem(r.value, ctx)?.let { return Step.Stop(it) }
                    return Step.Go(r.value)
                }
                is NotionResult.Err ->
                    if (r.error.kind != NotionErrorKind.NOT_FOUND) return Step.Stop(failure(r.error, ctx.attempt, ActionReason.PAGE_MISSING))
            }
        }
        val query = buildJsonObject {
            putJsonObject("filter") {
                put("property", ctx.captureProp)
                putJsonObject("rich_text") { put("equals", ctx.item.captureId) }
            }
            put("page_size", 2)
        }
        val found = client.queryDataSource(dataSourceId, query)
            .getOrElse { return Step.Stop(failure(it, ctx.attempt, ActionReason.DATA_SOURCE_NOT_FOUND)) }
        val results = found["results"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { PropertySnapshot.plainText(it["properties"]?.jsonObject?.get(ctx.captureProp)) == ctx.item.captureId }
        return when {
            results.size > 1 -> Step.Stop(
                SyncOutcome.ActionRequired(ActionReason.DUPLICATE_REMOTE_PAGES, "${results.size} pages share this Capture ID"),
            )
            results.size == 1 -> ownershipProblem(results.first(), ctx)?.let { Step.Stop(it) } ?: Step.Go(results.first())
            intent != null && intent.kind == WriteIntent.Kind.CREATE && intent.pageId == null -> {
                // A create was sent but its response never arrived. Notion's query index can lag behind a fresh
                // page, so wait before concluding the create failed — creating now could duplicate the page.
                val ageMs = Duration.between(intent.sentAt, clock()).toMillis()
                if (ageMs < createLookupGraceMs) {
                    Step.Stop(waitOrGiveUp(RetryReason.AWAITING_REMOTE_INDEX, ctx.attempt, (createLookupGraceMs - ageMs).coerceAtLeast(1_000)))
                } else {
                    Step.Go(null)
                }
            }
            else -> Step.Go(null)
        }
    }

    private suspend fun create(ctx: Ctx): SyncOutcome {
        val blocks = PageBodyBuilder.build(ctx.item, ctx.mapping.urlForBody)
        if (!journalWrite(WriteIntent(ctx.item.captureId, WriteIntent.Kind.CREATE, ctx.expectedHash, null, clock()))) {
            return retryOrGiveUp(RetryReason.LOCAL_JOURNAL, ctx.attempt, null)
        }
        val body = buildJsonObject {
            putJsonObject("parent") {
                put("type", "data_source_id")
                put("data_source_id", dataSourceId)
            }
            put("properties", ctx.mapping.properties)
            if (blocks.isNotEmpty()) put("children", JsonArray(blocks))
        }
        val created = client.createPage(body)
            .getOrElse { return failure(it, ctx.attempt, ActionReason.DATA_SOURCE_NOT_FOUND) }
        val pageId = created.string("id") ?: return retryOrGiveUp(RetryReason.INVALID_RESPONSE, ctx.attempt, null)
        // Best effort: lets a later retry fetch the page directly even if the query index lags.
        journalWrite(WriteIntent(ctx.item.captureId, WriteIntent.Kind.CREATE, ctx.expectedHash, pageId, clock()))

        val readBack = client.retrievePage(pageId).getOrElse { return failure(it, ctx.attempt, ActionReason.PAGE_MISSING) }
        if (ownershipProblem(readBack, ctx) != null) return SyncOutcome.ActionRequired(ActionReason.VERIFY_FAILED)
        val bodyState = if (blocks.isNotEmpty()) BodyState.WRITTEN else BodyState.ABSENT
        return SyncOutcome.Synced(
            link = link(ctx, readBack, pageId, bodyState),
            created = true, adopted = false, propertiesWritten = true, bodyState = bodyState,
            skipped = ctx.mapping.skipped, schemaWarnings = ctx.check.problems,
        )
    }

    private suspend fun reconcile(ctx: Ctx, page: JsonObject): SyncOutcome {
        val pageId = page.string("id") ?: return retryOrGiveUp(RetryReason.INVALID_RESPONSE, ctx.attempt, null)
        val remoteProps = page["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val currentHash = PropertySnapshot.hash(page, ctx.managed)
        val ownHashes = buildSet {
            ctx.existing?.remoteHash?.let(::add)
            ctx.intent?.takeIf { it.pageId == null || it.pageId == pageId }?.propertiesHash?.let(::add)
        }

        var propertiesWritten = false
        var kept = emptyMap<String, String>()
        when {
            currentHash == ctx.expectedHash -> Unit // already up to date
            currentHash in ownHashes || ctx.resolution == ConflictResolution.OVERWRITE_REMOTE -> {
                if (!journalWrite(WriteIntent(ctx.item.captureId, WriteIntent.Kind.UPDATE, ctx.expectedHash, pageId, clock()))) {
                    return retryOrGiveUp(RetryReason.LOCAL_JOURNAL, ctx.attempt, null)
                }
                client.updatePageProperties(pageId, ctx.mapping.properties)
                    .getOrElse { return failure(it, ctx.attempt, ActionReason.PAGE_MISSING) }
                propertiesWritten = true
            }
            ctx.resolution == ConflictResolution.KEEP_REMOTE -> {
                val diff = PropertySnapshot.diff(remoteProps, ctx.mapping.properties, ctx.managed)
                kept = PropertySnapshot.normalizedValues(remoteProps, diff)
            }
            else -> {
                val diff = PropertySnapshot.diff(remoteProps, ctx.mapping.properties, ctx.managed)
                return SyncOutcome.Conflict(
                    kind = if (ctx.existing != null) ConflictKind.REMOTE_EDITED else ConflictKind.ADOPTION_REVIEW,
                    pageId = pageId,
                    pageUrl = page.string("url"),
                    remoteHash = currentHash,
                    differingProperties = diff,
                    remoteValues = PropertySnapshot.normalizedValues(remoteProps, diff),
                    localValues = PropertySnapshot.normalizedValues(ctx.mapping.properties, diff),
                )
            }
        }
        val believedHash = if (propertiesWritten) ctx.expectedHash else currentHash

        var bodyState = if (ctx.existing?.bodyWritten == true) {
            BodyState.WRITTEN
        } else {
            when (val scan = scanBody(pageId, ctx)) {
                is Step.Stop -> return scan.outcome
                is Step.Go -> scan.value
            }
        }
        var appended = false
        if (bodyState == BodyState.ABSENT) {
            val blocks = PageBodyBuilder.build(ctx.item, ctx.mapping.urlForBody)
            if (blocks.isNotEmpty()) {
                if (!journalWrite(WriteIntent(ctx.item.captureId, WriteIntent.Kind.APPEND_BODY, believedHash, pageId, clock()))) {
                    return retryOrGiveUp(RetryReason.LOCAL_JOURNAL, ctx.attempt, null)
                }
                client.appendBlockChildren(pageId, JsonArray(blocks))
                    .getOrElse { return failure(it, ctx.attempt, ActionReason.PAGE_MISSING) }
                appended = true
                bodyState = BodyState.WRITTEN
            }
        }

        val finalPage = if (propertiesWritten || appended) {
            client.retrievePage(pageId).getOrElse { return failure(it, ctx.attempt, ActionReason.PAGE_MISSING) }
        } else {
            page
        }
        if (ownershipProblem(finalPage, ctx) != null) return SyncOutcome.ActionRequired(ActionReason.VERIFY_FAILED)
        return SyncOutcome.Synced(
            link = link(ctx, finalPage, pageId, bodyState),
            created = false, adopted = ctx.existing == null, propertiesWritten = propertiesWritten, bodyState = bodyState,
            skipped = ctx.mapping.skipped, schemaWarnings = ctx.check.problems, keptRemoteValues = kept,
        )
    }

    /** Pages through the page's top-level blocks looking for this item's marker. Never modifies anything. */
    private suspend fun scanBody(pageId: String, ctx: Ctx): Step<BodyState> {
        var cursor: String? = null
        repeat(maxBodyScanPages) {
            val res = client.listBlockChildren(pageId, 100, cursor)
                .getOrElse { return Step.Stop(failure(it, ctx.attempt, ActionReason.PAGE_MISSING)) }
            val blocks = res["results"] as? JsonArray ?: JsonArray(emptyList())
            if (BodyMarker.containsMarker(blocks, ctx.item.captureId)) return Step.Go(BodyState.WRITTEN)
            val more = res["has_more"]?.jsonPrimitive?.booleanOrNull == true
            cursor = res.string("next_cursor")
            if (!more || cursor == null) return Step.Go(BodyState.ABSENT)
        }
        return Step.Go(BodyState.UNKNOWN)
    }

    private fun link(ctx: Ctx, page: JsonObject, pageId: String, bodyState: BodyState) = NotionPageLink(
        captureId = ctx.item.captureId,
        pageId = pageId,
        pageUrl = page.string("url"),
        dataSourceId = dataSourceId,
        remoteHash = PropertySnapshot.hash(page, ctx.managed),
        bodyWritten = bodyState == BodyState.WRITTEN,
        lastSyncedAt = clock(),
    )

    private fun ownershipProblem(page: JsonObject, ctx: Ctx): SyncOutcome? {
        if (page["in_trash"]?.jsonPrimitive?.booleanOrNull == true || page["archived"]?.jsonPrimitive?.booleanOrNull == true) {
            return SyncOutcome.ActionRequired(ActionReason.PAGE_IN_TRASH)
        }
        val stored = PropertySnapshot.plainText(page["properties"]?.jsonObject?.get(ctx.captureProp))
        if (stored != ctx.item.captureId || !belongsToDataSource(page)) {
            return SyncOutcome.ActionRequired(ActionReason.PAGE_NOT_OWNED)
        }
        return null
    }

    private fun belongsToDataSource(page: JsonObject): Boolean {
        val parent = page["parent"] as? JsonObject ?: return false
        val ds = parent["data_source_id"]?.jsonPrimitive?.contentOrNull ?: return false
        return normalizeId(ds) == normalizeId(dataSourceId)
    }

    /** Result wrapper: null = journal failed; Result(null) = no intent recorded. */
    private suspend fun journalRead(captureId: String): Result<WriteIntent?>? = try {
        Result.success(journal.lastIntent(captureId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun journalWrite(intent: WriteIntent): Boolean = try {
        journal.recordIntent(intent)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun failure(error: NotionError, attempt: Int, notFound: ActionReason): SyncOutcome = when (error.kind) {
        NotionErrorKind.NO_TOKEN -> SyncOutcome.ActionRequired(ActionReason.NO_TOKEN)
        NotionErrorKind.UNAUTHORIZED -> SyncOutcome.ActionRequired(ActionReason.UNAUTHORIZED, error.code.orEmpty())
        NotionErrorKind.FORBIDDEN -> SyncOutcome.ActionRequired(ActionReason.NO_ACCESS, error.code.orEmpty())
        NotionErrorKind.NOT_FOUND -> SyncOutcome.ActionRequired(notFound, error.code.orEmpty())
        NotionErrorKind.VALIDATION -> SyncOutcome.ActionRequired(ActionReason.VALIDATION_REJECTED, error.message)
        NotionErrorKind.RATE_LIMITED -> retryOrGiveUp(RetryReason.RATE_LIMITED, attempt, error.retryAfterSeconds)
        NotionErrorKind.SERVER -> retryOrGiveUp(RetryReason.SERVER, attempt, error.retryAfterSeconds)
        NotionErrorKind.NETWORK -> retryOrGiveUp(RetryReason.NETWORK, attempt, null)
        NotionErrorKind.CONFLICT -> retryOrGiveUp(RetryReason.CONFLICT, attempt, null)
        NotionErrorKind.INVALID_RESPONSE -> retryOrGiveUp(RetryReason.INVALID_RESPONSE, attempt, null)
    }

    private fun retryOrGiveUp(reason: RetryReason, attempt: Int, retryAfterSeconds: Long?): SyncOutcome {
        if (retryPolicy.exhausted(attempt)) return SyncOutcome.ActionRequired(ActionReason.RETRIES_EXHAUSTED, reason.name)
        val delay = retryAfterSeconds?.let { retryPolicy.clampServerDelay(it * 1_000) } ?: retryPolicy.delayFor(attempt)
        return SyncOutcome.Retry(reason, delay)
    }

    private fun waitOrGiveUp(reason: RetryReason, attempt: Int, delayMs: Long): SyncOutcome =
        if (retryPolicy.exhausted(attempt)) SyncOutcome.ActionRequired(ActionReason.RETRIES_EXHAUSTED, reason.name)
        else SyncOutcome.Retry(reason, retryPolicy.clampServerDelay(delayMs))

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        fun normalizeId(id: String) = id.replace("-", "").lowercase()
        fun isCanonicalUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
