package com.pkos.core.notion

import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.RateLimiter
import com.pkos.core.notion.model.NotionFileRef
import com.pkos.core.notion.model.NotionPageLink
import com.pkos.core.notion.sync.ConflictKind
import com.pkos.core.notion.sync.ConflictResolution
import com.pkos.core.notion.sync.NotionFileUploader
import com.pkos.core.notion.sync.SyncOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** M4 additions: partial PATCH, owner-kept properties, file uploads. */
class M4EngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = TestEnv()

    private suspend fun first(item: com.pkos.core.notion.model.SyncItem): NotionPageLink =
        (env.engine.sync(item, null) as SyncOutcome.Synced).link

    @Test fun updateSendsOnlyChangedProperties() = runBlocking {
        val item = env.item()
        val link = first(item)
        val before = env.fake.calls.size
        val out = env.engine.sync(item.copy(pinned = true), link) as SyncOutcome.Synced
        assertTrue(out.propertiesWritten)
        val patch = env.fake.calls.drop(before).single { it.method == "PATCH" && it.path.startsWith("/pages/") }
        assertEquals(setOf("Pinned"), patch.body!!["properties"]!!.jsonObject.keys)
        env.assertLegacyUntouched()
    }

    @Test fun keepNotionMakesPropertyOwnerOwnedForever() = runBlocking {
        val item = env.item()
        val link = first(item)
        env.fake.userEdit(link.pageId, "Status", buildJsonObject { put("select", buildJsonObject { put("name", "Completed") }) })
        val conflict = env.engine.sync(item.copy(pinned = true), link) as SyncOutcome.Conflict
        assertEquals(ConflictKind.REMOTE_EDITED, conflict.kind)
        assertEquals(listOf("Pinned", "Status"), conflict.differingProperties)
        // "Keep Notion": every differing property stays as it is in Notion and PKOS stops managing it for this item.
        val owned = conflict.differingProperties.toSet()
        val kept = env.engine.sync(item.copy(pinned = true, remoteOwnedProperties = owned), link) as SyncOutcome.Synced
        assertFalse(kept.propertiesWritten)
        fun prop(name: String) = env.fake.pages.getValue(link.pageId)["properties"]!!.jsonObject[name]!!.jsonObject
        assertEquals("Completed", prop("Status")["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("false", prop("Pinned")["checkbox"]!!.jsonPrimitive.content)
        // Later local changes never touch kept properties and raise no new conflict; other fields still sync.
        val before = env.fake.calls.size
        val again = env.engine.sync(item.copy(pinned = true, title = "নতুন শিরোনাম", remoteOwnedProperties = owned), kept.link) as SyncOutcome.Synced
        assertTrue(again.propertiesWritten)
        val patch = env.fake.calls.drop(before).single { it.method == "PATCH" && it.path.startsWith("/pages/") }
        assertEquals(setOf("Topic name"), patch.body!!["properties"]!!.jsonObject.keys)
        assertEquals("Completed", prop("Status")["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun captureIdCanNeverBeOwnerOwned() = runBlocking {
        val out = env.engine.sync(env.item().copy(remoteOwnedProperties = setOf("Capture ID", "Topic name")), null) as SyncOutcome.Synced
        val props = env.fake.pages.getValue(out.link.pageId)["properties"]!!.jsonObject
        assertTrue(props["Capture ID"].toString().contains(out.link.captureId))
    }

    @Test fun uploadedFileIsAttachedOnceAndNeverResent() = runBlocking {
        val client = NotionClient(env.fake, { "t" }, RateLimiter.none())
        val f = tmp.newFile("shot.png").apply { writeBytes(ByteArray(2048) { 7 }) }
        val up = NotionFileUploader(client).upload(f, "shot.png", "image/png") as NotionFileUploader.Result.Uploaded
        val item = env.item().copy(attachments = listOf(NotionFileRef("shot.png", fileUploadId = up.fileUploadId)))
        val link = first(item)
        val files = env.fake.pages.getValue(link.pageId)["properties"]!!.jsonObject["Files & media"]!!.jsonObject["files"]!!.jsonArray
        assertEquals("file", files.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("attached", env.fake.fileUploads[up.fileUploadId])
        // A later update must not resend the (already attached) upload id — Notion would reject it.
        val out = env.engine.sync(item.copy(pinned = true), link)
        assertTrue(out.toString(), out is SyncOutcome.Synced)
        // And an unchanged resync is still a no-op (file name hash matches the Notion-hosted file).
        val before = env.fake.calls.size
        env.engine.sync(item.copy(pinned = true), (out as SyncOutcome.Synced).link)
        assertFalse(env.fake.calls.drop(before).any { it.method == "PATCH" })
    }

    @Test fun uploaderRespectsFreePlanLimitAndReportsFailures() = runBlocking {
        val client = NotionClient(env.fake, { "t" }, RateLimiter.none())
        val big = tmp.newFile("big.pdf").apply { writeBytes(ByteArray(10) { 1 }) }
        assertEquals(NotionFileUploader.Result.TooLarge, NotionFileUploader(client, maxBytes = 5).upload(big, "big.pdf", "application/pdf"))
        env.fake.failNextSend = true
        assertTrue(NotionFileUploader(client).upload(big, "big.pdf", "application/pdf") is NotionFileUploader.Result.Failed)
        assertTrue(env.fake.calls.none { it.method == "PATCH" })
    }

    @Test fun overwriteAfterConflictPatchesOnlyDifferences() = runBlocking {
        val item = env.item()
        val link = first(item)
        env.fake.userEdit(link.pageId, "Status", buildJsonObject { put("select", buildJsonObject { put("name", "Completed") }) })
        val before = env.fake.calls.size
        env.engine.sync(item, link, resolution = ConflictResolution.OVERWRITE_REMOTE) as SyncOutcome.Synced
        val patch = env.fake.calls.drop(before).single { it.method == "PATCH" && it.path.startsWith("/pages/") }
        assertEquals(setOf("Status"), patch.body!!["properties"]!!.jsonObject.keys)
    }
}
