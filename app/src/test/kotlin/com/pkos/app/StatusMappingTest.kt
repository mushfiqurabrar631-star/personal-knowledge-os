package com.pkos.app

import com.pkos.app.ui.status.AppHealthUi
import com.pkos.app.ui.status.ItemStatusUi
import com.pkos.core.data.db.dao.KnowledgeCounts
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusMappingTest {
    @Test fun everyCombinationMapsToOnePlainLabel() {
        for (p in ProcessingState.entries) for (s in SyncState.entries) ItemStatusUi.of(p, s) // total, no exception
        assertEquals(ItemStatusUi.SAVED_ON_DEVICE, ItemStatusUi.of(ProcessingState.ORGANIZED, SyncState.LOCAL_ONLY))
        assertEquals(ItemStatusUi.BACKING_UP, ItemStatusUi.of(ProcessingState.ORGANIZED, SyncState.BACKUP_QUEUED))
        assertEquals(ItemStatusUi.BACKED_UP, ItemStatusUi.of(ProcessingState.ORGANIZED, SyncState.BACKED_UP))
        assertEquals(ItemStatusUi.SENDING_TO_NOTION, ItemStatusUi.of(ProcessingState.ORGANIZED, SyncState.NOTION_QUEUED))
        assertEquals(ItemStatusUi.SYNCED, ItemStatusUi.of(ProcessingState.ORGANIZED, SyncState.SYNCED))
        assertEquals(ItemStatusUi.RETRYING, ItemStatusUi.of(ProcessingState.ORGANIZED, SyncState.ERROR_RETRYABLE))
    }

    @Test fun problemsOutrankEverythingElse() {
        assertEquals(ItemStatusUi.ACTION_REQUIRED, ItemStatusUi.of(ProcessingState.FAILED, SyncState.SYNCED))
        assertEquals(ItemStatusUi.ACTION_REQUIRED, ItemStatusUi.of(ProcessingState.NEEDS_REVIEW, SyncState.CONFLICT))
        assertEquals(ItemStatusUi.NEEDS_REVIEW, ItemStatusUi.of(ProcessingState.NEEDS_REVIEW, SyncState.BACKUP_QUEUED))
        assertEquals(ItemStatusUi.ORGANIZING, ItemStatusUi.of(ProcessingState.AI_PENDING, SyncState.LOCAL_ONLY))
    }

    @Test fun healthNeverClaimsUpToDateWithoutADestination() {
        val zero = KnowledgeCounts(0, 0, 0, 0, 0, 0)
        assertEquals(AppHealthUi.LOCAL_ONLY, AppHealthUi.of(zero, 0, anyDestinationConnected = false))
        assertEquals(AppHealthUi.UP_TO_DATE, AppHealthUi.of(zero, 0, anyDestinationConnected = true))
        assertEquals(AppHealthUi.SYNCING, AppHealthUi.of(zero.copy(pendingSync = 2), 0, false))
        assertEquals(AppHealthUi.ACTION_REQUIRED, AppHealthUi.of(zero, 1, true))
    }
}
