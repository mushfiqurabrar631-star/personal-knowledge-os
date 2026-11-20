package com.pkos.core.notion.mapping

import com.pkos.core.notion.model.SyncItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Builds the page content blocks (summary, key points, topic, OCR text …).
 * Approved decision: this information lives in the page body, not in new properties.
 * Limits respected: ≤2000 characters per text object, ≤100 blocks per request.
 */
object PageBodyBuilder {
    const val MAX_TEXT = 2000
    const val MAX_BLOCKS = 100
    const val MAX_LONG_TEXT_PARAGRAPHS = 10
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun build(item: SyncItem, urlForBody: String? = null, zone: ZoneId = ZoneId.of("Asia/Dhaka")): List<JsonObject> {
        val body = item.body
        val blocks = mutableListOf<JsonObject>()

        body.summary?.takeIf { it.isNotBlank() }?.let {
            blocks += heading("সারসংক্ষেপ (Summary)")
            chunkText(it, MAX_TEXT).take(3).forEach { part -> blocks += paragraph(part) }
        }

        val points = body.keyPoints.map { it.trim().removePrefix("•").trim() }.filter { it.isNotEmpty() }
        if (points.isNotEmpty()) {
            blocks += heading("মূল পয়েন্ট (Key points)")
            points.take(20).forEach { blocks += bullet(it.take(MAX_TEXT)) }
        }

        val classification = buildList {
            body.topic?.takeIf { it.isNotBlank() }?.let { add("Topic: $it") }
            body.subtopic?.takeIf { it.isNotBlank() }?.let { add("Subtopic: $it") }
            if (body.purpose.isNotEmpty()) add("Purpose: ${body.purpose.joinToString(", ")}")
            body.confidence?.let { add("AI Confidence: ${(it.coerceIn(0.0, 1.0) * 100).roundToInt()}%") }
            body.reviewReason?.takeIf { it.isNotBlank() }?.let { add("Needs review: $it") }
        }
        if (classification.isNotEmpty()) {
            blocks += heading("শ্রেণিবিন্যাস (Classification)")
            classification.forEach { blocks += bullet(it.take(MAX_TEXT)) }
        }

        addLongText(blocks, "মূল লেখা (Original text)", body.originalText)
        addLongText(blocks, "স্ক্রিনশটের লেখা (OCR text)", body.ocrText)

        val source = buildList {
            body.sourceApp?.takeIf { it.isNotBlank() }?.let { add("Source app: $it") }
            urlForBody?.let { add("Full link: ${it.take(MAX_TEXT - 11)}") }
        }
        if (source.isNotEmpty()) {
            blocks += heading("উৎস (Source)")
            source.forEach { blocks += bullet(it) }
        }

        if (blocks.isEmpty()) return emptyList()
        // Room for divider + footer is reserved so the marker is always the last block.
        val content = if (blocks.size > MAX_BLOCKS - 3) blocks.take(MAX_BLOCKS - 3) + paragraph("…", italic = true) else blocks
        return content + divider() + footer(item, zone)
    }

    /** Footer carrying the [BodyMarker] token. Must stay the last block of the PKOS body. */
    private fun footer(item: SyncItem, zone: ZoneId) = paragraph(
        "PKOS · Captured ${timeFormat.format(item.capturedAt.atZone(zone))} · ${BodyMarker.token(item.captureId)}",
        italic = true,
    )

    private fun addLongText(blocks: MutableList<JsonObject>, title: String, text: String?) {
        if (text.isNullOrBlank()) return
        blocks += heading(title)
        val parts = chunkText(text.trim(), MAX_TEXT)
        parts.take(MAX_LONG_TEXT_PARAGRAPHS).forEach { blocks += paragraph(it) }
        if (parts.size > MAX_LONG_TEXT_PARAGRAPHS) {
            blocks += paragraph("সম্পূর্ণ লেখা PKOS অ্যাপ ও ব্যাকআপে সংরক্ষিত আছে।", italic = true)
        }
    }

    /** Splits text into pieces ≤ [size] chars, preferring line/space boundaries and never splitting surrogate pairs. */
    fun chunkText(text: String, size: Int): List<String> {
        require(size > 1)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val breakAt = maxOf(window.lastIndexOf('\n'), window.lastIndexOf(' '))
                if (breakAt > size / 2) end = start + breakAt + 1
                if (Character.isHighSurrogate(text[end - 1])) end -= 1
            }
            result += text.substring(start, end)
            start = end
        }
        return result.map { it.trimEnd() }.filter { it.isNotEmpty() }
    }

    private fun richText(content: String, italic: Boolean = false) = buildJsonObject {
        putJsonArray("rich_text") {
            addJsonObject {
                put("type", "text")
                putJsonObject("text") { put("content", content) }
                if (italic) putJsonObject("annotations") { put("italic", true) }
            }
        }
    }

    private fun block(type: String, content: JsonObject) = buildJsonObject {
        put("object", "block")
        put("type", type)
        put(type, content)
    }

    private fun heading(text: String) = block("heading_3", richText(text))
    private fun paragraph(text: String, italic: Boolean = false) = block("paragraph", richText(text, italic))
    private fun bullet(text: String) = block("bulleted_list_item", richText(text))
    private fun divider() = block("divider", buildJsonObject { })
}
