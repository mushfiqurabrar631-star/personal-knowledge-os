package com.pkos.core.data.model

/** Processing lifecycle of a capture (spec 6.1). Stored by name. */
enum class ProcessingState {
    CAPTURED, LOCAL_SAVED, EXTRACT_PENDING, EXTRACTED, AI_PENDING, ORGANIZED, NEEDS_REVIEW, FAILED;

    val isInProgress: Boolean get() = this in setOf(CAPTURED, LOCAL_SAVED, EXTRACT_PENDING, EXTRACTED, AI_PENDING)
}

/** Destination sync state (spec 8.5). Stored by name. */
enum class SyncState {
    LOCAL_ONLY, BACKUP_QUEUED, BACKED_UP, NOTION_QUEUED, NOTION_SYNCED, SYNCED, CONFLICT, ERROR_RETRYABLE, ERROR_ACTION_REQUIRED;

    val isPending: Boolean get() = this in setOf(BACKUP_QUEUED, NOTION_QUEUED, ERROR_RETRYABLE)
    val needsAction: Boolean get() = this == CONFLICT || this == ERROR_ACTION_REQUIRED
}

enum class ContentType { ARTICLE, VIDEO, WEBSITE, TUTORIAL, IDEA, NOTE, DOCUMENT, SCREENSHOT, MESSAGE, TOOL, OTHER }
enum class ItemPriority { LOW, NORMAL, HIGH, CRITICAL }
enum class ItemStatus { INBOX, TO_REVIEW, TO_LEARN, IN_PROGRESS, DONE, ARCHIVED }
enum class SourcePlatform { FACEBOOK, YOUTUBE, INSTAGRAM, CHROME, WEB, WHATSAPP, MESSENGER, TELEGRAM, GALLERY, FILES, CLIPBOARD, MANUAL, OTHER }
enum class CaptureMethod { SHARE, SHARE_MULTIPLE, FLOATING, QUICK_NOTE, PASTE, FILE_PICKER, PHOTO_PICKER, IMPORT }
enum class PrivacyScope { NORMAL, LOCAL_ONLY }

/** Who set a field's value (spec 6.2). USER always outranks AI. */
enum class FieldOrigin { USER, AI, IMPORT, SYSTEM }

enum class SyncDestination { DRIVE_BACKUP, NOTION }
enum class JobState { QUEUED, RUNNING, SUCCEEDED, RETRY_SCHEDULED, ACTION_REQUIRED, CANCELLED }
enum class ProcessingJobType { EXTRACT, OCR, AI_CLASSIFY, EMBED }
enum class BackupStatus { CREATING, UPLOADING, VERIFIED, FAILED }
enum class ExtractionStatus { NOT_NEEDED, PENDING, DONE, FAILED, UNSUPPORTED }

/** Inbox filters (spec 2.5 / 2.7). */
enum class InboxFilter { ALL, PROCESSING, NEEDS_REVIEW, FAILED, SYNC_PENDING }
