package com.pkos.core.data.processing

import androidx.room.withTransaction
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.AiRunEntity
import com.pkos.core.data.db.entity.AttachmentEntity
import com.pkos.core.data.db.entity.ItemVersionEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.ProcessingJobEntity
import com.pkos.core.data.db.entity.TagEntity
import com.pkos.core.data.model.ExtractionStatus
import com.pkos.core.data.model.FieldOrigin
import com.pkos.core.data.model.JobState
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.model.ProcessingJobType
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.repository.StarterTaxonomy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

sealed interface ProcessOutcome {
    data class Done(val state: ProcessingState) : ProcessOutcome
    /** A transient problem (rate limit, network, read error). Nothing is lost; try again later. */
    data class Retry(val delayMs: Long, val reason: String) : ProcessOutcome
    data object NothingToDo : ProcessOutcome
}

/**
 * Background processing for one item (spec 4.1 / 5.2): extract → OCR → classify → validate → persist.
 * The original capture (text, files) is never changed; fields the owner edited (origin USER) are never overwritten.
 */
class ProcessingEngine(
    private val db: PkosDatabase,
    private val resolveFile: (String) -> File,
    private val extractor: TextExtractor,
    private val ocr: OcrEngine?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun process(itemId: String, cloud: AiProvider?): ProcessOutcome = withContext(Dispatchers.IO) {
        val item = db.knowledgeDao().get(itemId) ?: return@withContext ProcessOutcome.NothingToDo
        val jobs = db.processingJobDao().forItem(itemId).filter { it.state in OPEN }
        if (jobs.isEmpty()) return@withContext ProcessOutcome.NothingToDo
        if (item.deletedAt != null) {
            jobs.forEach { finish(it, JobState.CANCELLED, null) }
            return@withContext ProcessOutcome.NothingToDo
        }

        // 1. Extraction / OCR (local only).
        for (job in jobs.filter { it.jobType == ProcessingJobType.EXTRACT || it.jobType == ProcessingJobType.OCR }) {
            setState(item.id, ProcessingState.EXTRACT_PENDING)
            val attachments = db.attachmentDao().forItem(item.id).filter { it.extractionStatus == ExtractionStatus.PENDING }
            val targets = attachments.filter { isImage(it) == (job.jobType == ProcessingJobType.OCR) }
            try {
                targets.forEach { a -> extractOne(a, job.jobType) }
                finish(job, JobState.SUCCEEDED, null)
            } catch (e: Exception) {
                val attempts = job.attempts + 1
                if (attempts < MAX_LOCAL_ATTEMPTS) {
                    reschedule(job, attempts, backoff(attempts), e.javaClass.simpleName)
                    return@withContext ProcessOutcome.Retry(backoff(attempts), "extract:${e.javaClass.simpleName}")
                }
                // Give up on this file only; the item is still classified from what we have.
                targets.forEach { db.attachmentDao().setExtracted(it.id, ExtractionStatus.FAILED, null) }
                finish(job, JobState.ACTION_REQUIRED, e.javaClass.simpleName)
            }
        }
        setState(item.id, ProcessingState.EXTRACTED)

        // 2. Classification.
        val classify = jobs.firstOrNull { it.jobType == ProcessingJobType.AI_CLASSIFY } ?: return@withContext ProcessOutcome.Done(ProcessingState.EXTRACTED)
        setState(item.id, ProcessingState.AI_PENDING)
        val fresh = db.knowledgeDao().get(item.id) ?: return@withContext ProcessOutcome.NothingToDo
        val categories = db.categoryDao().all().associate { it.id to it.name }
        val allowed = categories.filterKeys { it != StarterTaxonomy.UNSORTED && it != StarterTaxonomy.HEALTH }
        val texts = db.attachmentDao().forItem(item.id).mapNotNull { it.extractedText }
        val input = AiInput(
            title = fresh.title,
            text = listOfNotNull(fresh.rawText, *texts.toTypedArray()).joinToString("\n\n").take(MAX_INPUT_CHARS),
            url = fresh.sourceUrl,
            platform = fresh.sourcePlatform?.name?.lowercase(),
            contentTypeHint = fresh.contentType?.name?.lowercase(),
            allowedCategories = allowed.values.toList(),
        )
        val local = LocalClassifier.classify(input, fresh.sourcePlatform, fresh.contentType)

        var chosen: AiResult = local
        var provider = LocalClassifier.PROVIDER
        var model = LocalClassifier.MODEL
        var fallbackReason: String? = null
        if (cloud != null && fresh.privacyScope == PrivacyScope.NORMAL) {
            when (val r = runCloud(fresh.id, cloud, input, allowed)) {
                is CloudResult.Ok -> { chosen = r.result; provider = cloud.name; model = cloud.model }
                is CloudResult.Transient -> {
                    val attempts = classify.attempts + 1
                    if (attempts < MAX_CLOUD_ATTEMPTS) {
                        reschedule(classify, attempts, r.delayMs, r.code)
                        return@withContext ProcessOutcome.Retry(r.delayMs, r.code)
                    }
                    fallbackReason = ReviewReason.CLOUD_UNAVAILABLE
                }
                is CloudResult.Permanent -> fallbackReason = r.reason
            }
        }
        db.aiRunDao().takeIf { provider == LocalClassifier.PROVIDER }?.insert(
            AiRunEntity(UUID.randomUUID().toString(), item.id, provider, model, PROMPT_VERSION, AiResultParser.toJson(local, categories), local.confidence, "OK", fallbackReason, input.text.length, clock()),
        )
        val state = apply(fresh, chosen, provider, fallbackReason)
        // Spec 9 #7: proposals only (owner confirms); never fails the processing.
        runCatching { com.pkos.core.data.features.ActionService(db, clock).propose(item.id, chosen.actionItems) }
        finish(classify, JobState.SUCCEEDED, null)
        ProcessOutcome.Done(state)
    }

    private sealed interface CloudResult {
        data class Ok(val result: AiResult) : CloudResult
        data class Transient(val delayMs: Long, val code: String) : CloudResult
        data class Permanent(val reason: String) : CloudResult
    }

    /** One call plus at most one schema-repair retry (spec 11: malformed AI output). Every attempt is audited. */
    private suspend fun runCloud(itemId: String, cloud: AiProvider, input: AiInput, allowed: Map<String, String>): CloudResult {
        var hint: String? = null
        repeat(2) { attempt ->
            val response = runCatching { cloud.classify(input, hint) }.getOrElse { AiResponse.NetworkError(it.javaClass.simpleName) }
            fun audit(outcome: String, json: String?, conf: Double?, detail: String?) = AiRunEntity(
                UUID.randomUUID().toString(), itemId, cloud.name, cloud.model, PROMPT_VERSION, json?.take(20_000), conf, outcome, detail, input.text.length, clock(),
            )
            when (response) {
                is AiResponse.Raw -> when (val parsed = AiResultParser.parse(response.json, allowed)) {
                    is ParsedAi.Valid -> { db.aiRunDao().insert(audit("OK", response.json, parsed.result.confidence, null)); return CloudResult.Ok(parsed.result) }
                    is ParsedAi.Invalid -> {
                        db.aiRunDao().insert(audit("INVALID_OUTPUT", response.json, null, parsed.reason))
                        if (attempt == 1) return CloudResult.Permanent(ReviewReason.CLOUD_INVALID)
                        hint = parsed.reason
                    }
                }
                is AiResponse.RateLimited -> { db.aiRunDao().insert(audit("RATE_LIMITED", null, null, null)); return CloudResult.Transient(response.retryAfterMs.coerceAtLeast(30_000), "rate_limited") }
                is AiResponse.QuotaExceeded -> { db.aiRunDao().insert(audit("QUOTA", null, null, response.detail)); return CloudResult.Permanent(ReviewReason.CLOUD_QUOTA) }
                is AiResponse.AuthFailed -> { db.aiRunDao().insert(audit("AUTH", null, null, response.detail)); return CloudResult.Permanent(ReviewReason.CLOUD_AUTH) }
                is AiResponse.NetworkError -> { db.aiRunDao().insert(audit("NETWORK", null, null, response.detail)); return CloudResult.Transient(60_000, "network") }
                is AiResponse.Failed -> { db.aiRunDao().insert(audit("ERROR", null, null, response.detail)); return CloudResult.Transient(120_000, "server") }
            }
        }
        return CloudResult.Permanent(ReviewReason.CLOUD_INVALID)
    }

    /** Applies the confidence policy (spec 4.5) without touching fields the owner set. */
    private suspend fun apply(item: KnowledgeItemEntity, r: AiResult, provider: String, fallbackReason: String?): ProcessingState {
        val origins = runCatching { JSONObject(item.fieldOrigins) }.getOrElse { JSONObject() }
        fun mine(field: String) = origins.optString(field) == FieldOrigin.USER.name
        val band = ConfidencePolicy.band(r.confidence)
        val low = band == ConfidencePolicy.Band.LOW || r.categoryId == null
        val state = if (low) ProcessingState.NEEDS_REVIEW else ProcessingState.ORGANIZED
        val aiTag = FieldOrigin.AI.name
        var updated = item.copy(
            processingState = state,
            confidence = r.confidence,
            reviewReason = when {
                low -> r.reviewReason ?: fallbackReason ?: ReviewReason.LOW_CONFIDENCE
                r.needsReview -> r.reviewReason
                else -> fallbackReason?.takeIf { provider == LocalClassifier.PROVIDER }
            },
            updatedAt = clock(),
        )
        if (!mine("category")) {
            updated = updated.copy(categoryId = if (low) StarterTaxonomy.UNSORTED else r.categoryId); origins.put("category", aiTag)
        }
        if (!mine("summary") && r.summary != null) { updated = updated.copy(summary = r.summary); origins.put("summary", aiTag) }
        if (!mine("content_type") && r.contentType != null) { updated = updated.copy(contentType = r.contentType); origins.put("content_type", aiTag) }
        if (!mine("purpose") && r.purpose.isNotEmpty()) { updated = updated.copy(purpose = r.purpose.joinToString(",")); origins.put("purpose", aiTag) }
        if (!mine("priority")) { updated = updated.copy(priority = r.priority); origins.put("priority", aiTag) }
        // Only a system-made title may be improved; anything the owner typed stays.
        if (r.title != null && origins.optString("title") == FieldOrigin.SYSTEM.name && !low) { updated = updated.copy(title = r.title); origins.put("title", aiTag) }
        val topicCategory = updated.categoryId?.takeIf { it != StarterTaxonomy.UNSORTED }
        val setTopic = !mine("topic") && !low && r.topic != null && topicCategory != null
        if (setTopic) origins.put("topic", aiTag)
        updated = updated.copy(fieldOrigins = origins.toString())
        var final = updated
        db.withTransaction {
            // Topic / subtopic (spec R-05): reuse an existing topic of that name in the category, else create it.
            if (setTopic) {
                val topic = topicFor(topicCategory!!, null, r.topic!!)
                val sub = topic?.let { t -> r.subtopic?.let { topicFor(topicCategory, t, it) } }
                if (topic != null) final = final.copy(topicId = topic, subtopicId = sub)
            }
            db.knowledgeDao().update(final)
            if (!mine("tags")) r.tags.forEach { name ->
                val normalized = name.lowercase(Locale.ROOT).trim()
                db.tagDao().insertIgnore(TagEntity(UUID.nameUUIDFromBytes(normalized.toByteArray()).toString(), name, normalized))
                db.tagDao().linkByName(final.id, normalized)
            }
            val version = db.itemVersionDao().latestVersion(final.id) + 1
            db.itemVersionDao().insert(
                ItemVersionEntity(UUID.randomUUID().toString(), final.id, version, snapshot(final, provider), clock(), FieldOrigin.AI),
            )
        }
        return state
    }

    /** Reuses the same or a nearly identical topic (spec 9 #4); creates one only below the per-category cap. */
    private suspend fun topicFor(categoryId: String, parentId: String?, name: String): String? {
        val clean = name.trim().take(80)
        db.topicDao().find(categoryId, parentId, clean)?.let { return it.id }
        val topics = com.pkos.core.data.features.TopicService(db, clock)
        topics.findSimilar(categoryId, parentId, clean)?.let { return it.id }
        if (parentId == null && !topics.canCreateAuto(categoryId)) return null
        val id = UUID.randomUUID().toString()
        db.topicDao().insert(com.pkos.core.data.db.entity.TopicEntity(id, categoryId, parentId, clean, isUserCreated = false, createdAt = clock()))
        return id
    }

    private fun snapshot(i: KnowledgeItemEntity, provider: String) = JSONObject().apply {
        put("provider", provider); put("title", i.title); put("category_id", i.categoryId ?: JSONObject.NULL)
        put("content_type", i.contentType?.name ?: JSONObject.NULL); put("purpose", i.purpose ?: JSONObject.NULL)
        put("confidence", i.confidence ?: JSONObject.NULL); put("state", i.processingState.name)
    }.toString()

    private suspend fun extractOne(a: AttachmentEntity, type: ProcessingJobType) {
        val file = resolveFile(a.localPath)
        if (!file.exists()) { db.attachmentDao().setExtracted(a.id, ExtractionStatus.FAILED, null); return }
        val text = if (type == ProcessingJobType.OCR) {
            val engine = ocr ?: run { db.attachmentDao().setExtracted(a.id, ExtractionStatus.UNSUPPORTED, null); return }
            engine.recognize(file)
        } else {
            extractor.extract(file, a.mimeType) ?: run { db.attachmentDao().setExtracted(a.id, ExtractionStatus.UNSUPPORTED, null); return }
        }
        db.attachmentDao().setExtracted(a.id, ExtractionStatus.DONE, text.trim().take(MAX_EXTRACT_CHARS).ifBlank { null })
    }

    private suspend fun setState(id: String, state: ProcessingState) = db.knowledgeDao().setProcessingState(id, state, clock())
    private suspend fun finish(job: ProcessingJobEntity, state: JobState, error: String?) =
        db.processingJobDao().update(job.id, state, job.attempts + if (state == JobState.SUCCEEDED) 1 else 0, null, error, clock())
    private suspend fun reschedule(job: ProcessingJobEntity, attempts: Int, delay: Long, error: String) =
        db.processingJobDao().update(job.id, JobState.RETRY_SCHEDULED, attempts, clock() + delay, error, clock())

    private fun isImage(a: AttachmentEntity) = a.mimeType?.startsWith("image/") == true
    private fun backoff(attempt: Int) = 30_000L shl (attempt - 1).coerceAtMost(6)

    companion object {
        val OPEN = setOf(JobState.QUEUED, JobState.RUNNING, JobState.RETRY_SCHEDULED)
        const val MAX_LOCAL_ATTEMPTS = 3
        const val MAX_CLOUD_ATTEMPTS = 5
        const val MAX_INPUT_CHARS = 8_000
        const val MAX_EXTRACT_CHARS = 200_000
    }
}
