package com.pkos.core.notion.sync

import java.time.Instant

/**
 * Write-ahead record of the last Notion write PKOS attempted for an item.
 * Stored locally (Room, same transaction scope as sync_jobs) BEFORE the request is sent, so after a crash,
 * a lost response, or a failed local mapping save, the engine can recognise its own earlier write
 * instead of creating a duplicate or reporting a false conflict.
 */
data class WriteIntent(
    val captureId: String,
    val kind: Kind,
    /** PropertySnapshot hash of the properties sent (or about to be sent). */
    val propertiesHash: String,
    /** Known page id; null for a create whose response has not been seen yet. */
    val pageId: String?,
    val sentAt: Instant,
) {
    enum class Kind { CREATE, UPDATE, APPEND_BODY }
}

interface SyncJournal {
    suspend fun lastIntent(captureId: String): WriteIntent?

    /** Must be durable when it returns. Throwing aborts the write (nothing is sent). */
    suspend fun recordIntent(intent: WriteIntent)
}
