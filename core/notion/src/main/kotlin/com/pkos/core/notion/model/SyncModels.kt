package com.pkos.core.notion.model

import java.time.Instant

enum class ContentType { ARTICLE, VIDEO, WEBSITE, TUTORIAL, IDEA, NOTE, DOCUMENT, IMAGE, MESSAGE, TOOL, OTHER }
enum class ItemStatus { INBOX, TO_REVIEW, TO_LEARN, IN_PROGRESS, DONE, ARCHIVED }
enum class ItemPriority { LOW, NORMAL, HIGH, CRITICAL }
enum class SourcePlatform { FACEBOOK, YOUTUBE, INSTAGRAM, CHROME, WEB, WHATSAPP, MESSENGER, TELEGRAM, GALLERY, FILES, OTHER }
enum class PrivacyScope { NORMAL, LOCAL_ONLY }

/** An attachment already uploaded to Notion (file_upload id) or hosted elsewhere (external url). */
data class NotionFileRef(
    val name: String,
    val fileUploadId: String? = null,
    val externalUrl: String? = null,
)

/** Everything that goes into the page body (content blocks), never into new properties. */
data class KnowledgeBody(
    val summary: String? = null,
    val keyPoints: List<String> = emptyList(),
    val topic: String? = null,
    val subtopic: String? = null,
    val purpose: List<String> = emptyList(),
    val confidence: Double? = null,
    val reviewReason: String? = null,
    val originalText: String? = null,
    val ocrText: String? = null,
    val sourceApp: String? = null,
) {
    val isEmpty: Boolean
        get() = summary.isNullOrBlank() && keyPoints.isEmpty() && topic.isNullOrBlank() &&
            subtopic.isNullOrBlank() && purpose.isEmpty() && confidence == null && reviewReason.isNullOrBlank() &&
            originalText.isNullOrBlank() && ocrText.isNullOrBlank() && sourceApp.isNullOrBlank()
}

/** The view of a local knowledge item that the Notion sync needs. */
data class SyncItem(
    val captureId: String,
    val title: String,
    val sourceUrl: String? = null,
    val capturedAt: Instant,
    val contentType: ContentType? = null,
    val status: ItemStatus = ItemStatus.INBOX,
    val priority: ItemPriority = ItemPriority.NORMAL,
    val pinned: Boolean = false,
    val mainCategory: String? = null,
    val tags: List<String> = emptyList(),
    val platform: SourcePlatform? = null,
    val privacyScope: PrivacyScope = PrivacyScope.NORMAL,
    val attachments: List<NotionFileRef> = emptyList(),
    val body: KnowledgeBody = KnowledgeBody(),
)

/** Stored locally (notion_mappings table) after a verified sync. */
data class NotionPageLink(
    val captureId: String,
    val pageId: String,
    val pageUrl: String?,
    val dataSourceId: String,
    /** SHA-256 of the PKOS-managed property values as read back from Notion. */
    val remoteHash: String,
    val bodyWritten: Boolean,
    val lastSyncedAt: Instant,
)
