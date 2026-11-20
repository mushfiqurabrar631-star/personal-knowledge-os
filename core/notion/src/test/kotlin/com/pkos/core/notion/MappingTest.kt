package com.pkos.core.notion

import com.pkos.core.notion.mapping.MasterDatabaseMapper
import com.pkos.core.notion.mapping.PageBodyBuilder
import com.pkos.core.notion.model.ContentType
import com.pkos.core.notion.model.ItemPriority
import com.pkos.core.notion.model.ItemStatus
import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.model.NotionFileRef
import com.pkos.core.notion.model.SourcePlatform
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.schema.DataSourceSchemaParser
import com.pkos.core.notion.schema.SchemaGuard
import com.pkos.core.notion.url.UrlCanonicalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MappingTest {
    private val check = SchemaGuard.check(DataSourceSchemaParser.parse(MasterSchemaFixture.json()))
    private val mapper = MasterDatabaseMapper()

    private fun base() = SyncItem(
        captureId = UUID.randomUUID().toString(),
        title = "ফ্রি বইয়ের ওয়েবসাইট বাংলায়",
        capturedAt = Instant.parse("2026-11-19T20:30:00Z"), // 02:30 on 20 Nov in Dhaka
    )

    private fun selectName(props: JsonObject, name: String) =
        props[name]?.jsonObject?.get("select")?.jsonObject?.get("name")?.jsonPrimitive?.content

    private fun multiNames(props: JsonObject, name: String) =
        props[name]?.jsonObject?.get("multi_select")?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun schemaParserKeepsTrailingSpacesAndOptions() {
        val schema = DataSourceSchemaParser.parse(MasterSchemaFixture.json())
        assertEquals("multi_select", schema.property("category ")!!.type)
        assertNull(schema.property("category"))
        assertEquals(listOf("Youtube", "Facebook"), schema.property("platform ")!!.options)
        assertFalse(check.blocked)
        assertTrue(check.problems.isEmpty())
    }

    @Test
    fun neverWritesUnmanagedProperties() {
        val props = mapper.map(base().copy(pinned = true, tags = listOf("Education")), check).properties
        for (forbidden in listOf("Notes", "Parent item", "Sub-item")) assertFalse(forbidden in props)
        assertTrue(props.keys.all { it in setOf("Topic name", "Capture ID", "Link/URL", "Date Added", "Type", "Status",
            "Priority", "Pinned", "Tags", "category ", "platform ", "Files & media") })
    }

    @Test
    fun usesDhakaDateAndExactOptionNames() {
        val r = mapper.map(base().copy(
            contentType = ContentType.WEBSITE, status = ItemStatus.TO_LEARN, priority = ItemPriority.NORMAL,
            mainCategory = "education & learning", tags = listOf("education", "website"), platform = SourcePlatform.FACEBOOK,
        ), check)
        val p = r.properties
        assertTrue(p["Date Added"].toString().contains("2026-11-20"))
        assertEquals("Website", selectName(p, "Type"))
        assertEquals("To Learn", selectName(p, "Status"))
        assertEquals("Normal", selectName(p, "Priority"))
        assertEquals("Facebook", selectName(p, "platform "))
        assertEquals(listOf("Education"), multiNames(p, "category "))
        assertEquals(listOf("Education", "Website"), multiNames(p, "Tags"))
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun categoryWithoutOptionFallsBackToTagOrIsReported() {
        val business = mapper.map(base().copy(mainCategory = "Business & Finance"), check)
        assertNull(business.properties["category "])
        assertEquals(listOf("Business"), multiNames(business.properties, "Tags"))

        val farming = mapper.map(base().copy(mainCategory = "Agriculture & Farming"), check)
        assertNull(farming.properties["category "])
        assertNull(farming.properties["Tags"])
        assertEquals("Agriculture & Farming", farming.skipped.single().value)
    }

    @Test
    fun everyEmittedOptionExistsInLiveSchema() {
        val schema = check.schema
        for (type in ContentType.values()) for (status in ItemStatus.values()) for (prio in ItemPriority.values())
            for (platform in SourcePlatform.values()) {
                val p = mapper.map(base().copy(contentType = type, status = status, priority = prio, platform = platform,
                    mainCategory = "Ideas & Inspiration", tags = listOf("IELTS", "Unknown")), check).properties
                for (name in listOf("Type", "Status", "Priority", "platform ")) {
                    selectName(p, name)?.let { assertTrue("$name=$it", it in schema.property(name)!!.options) }
                }
                for (name in listOf("Tags", "category ")) {
                    multiNames(p, name)?.forEach { assertTrue("$name=$it", it in schema.property(name)!!.options) }
                }
            }
    }

    @Test
    fun longTitleTruncatedAndLongUrlMovedToBody() {
        val longUrl = "https://example.com/?q=" + "a".repeat(2100)
        val r = mapper.map(base().copy(title = "ক".repeat(2500), sourceUrl = longUrl), check)
        val title = r.properties["Topic name"]!!.jsonObject["title"]!!.jsonArray[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(2000, title.length)
        assertNull(r.properties["Link/URL"])
        assertEquals(longUrl, r.urlForBody)
    }

    @Test
    fun filesUseUploadIdOrExternalUrl() {
        val r = mapper.map(base().copy(attachments = listOf(
            NotionFileRef("screenshot.png", fileUploadId = "fu_1"),
            NotionFileRef("doc.pdf", externalUrl = "https://drive.google.com/x"),
            NotionFileRef("pending.jpg"),
        )), check)
        val files = r.properties["Files & media"]!!.jsonObject["files"]!!.jsonArray
        assertEquals(2, files.size)
        assertEquals("file_upload", files[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("external", files[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun bodyChunksRespectNotionLimits() {
        val text = ("বাংলা লেখা ".repeat(800)) // ~8800 chars
        val blocks = PageBodyBuilder.build(base().copy(body = KnowledgeBody(
            summary = "সারাংশ", keyPoints = listOf("• এক", "দুই"), topic = "AI", confidence = 0.823, ocrText = text,
        )))
        assertTrue(blocks.size <= PageBodyBuilder.MAX_BLOCKS)
        for (b in blocks) {
            val type = b["type"]!!.jsonPrimitive.content
            b[type]!!.jsonObject["rich_text"]?.jsonArray?.forEach {
                assertTrue(it.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content.length <= 2000)
            }
        }
        val all = blocks.toString()
        assertTrue(all.contains("AI Confidence: 82%"))
        assertTrue(all.contains("\"এক\""))
        assertTrue(PageBodyBuilder.build(base()).isEmpty())
    }

    @Test
    fun chunkTextNeverSplitsSurrogatePairs() {
        val text = "😀".repeat(1500)
        val parts = PageBodyBuilder.chunkText(text, 2001)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { assertFalse(Character.isHighSurrogate(it.last())) }
    }

    @Test
    fun urlCanonicalizerRemovesOnlyTracking() {
        assertEquals("https://bn.welib.org/", UrlCanonicalizer.canonicalize(
            "https://bn.welib.org/?fbclid=Iwb21leAURAuJjbGNrBRECpHBkb2YFZXh0bgNhZW0CMTEAc3J0YwZhcHBfaWQMMzUwNjg1NTMxNzI4AAEeEWpM0bv"))
        assertEquals("https://youtu.be/ynZp8-FjYUo", UrlCanonicalizer.canonicalize("https://youtu.be/ynZp8-FjYUo?si=wFYeGdw0Jweb12Gd"))
        assertEquals("https://www.youtube.com/watch?v=abc&t=30", UrlCanonicalizer.canonicalize("https://WWW.YouTube.com/watch?v=abc&feature=shared&t=30"))
        assertEquals("https://site.com/a?id=5#top", UrlCanonicalizer.canonicalize("https://site.com:443/a?utm_source=fb&id=5&utm_medium=x#top"))
        assertEquals("https://site.com/?si=keep", UrlCanonicalizer.canonicalize("https://site.com/?si=keep"))
        assertEquals("not a url", UrlCanonicalizer.canonicalize(" not a url "))
        assertEquals("https://share.google/OAbikdN3eIrzvNRVu",
            UrlCanonicalizer.extractFirstUrl("Source: OpenStax https://share.google/OAbikdN3eIrzvNRVu."))
    }
}
