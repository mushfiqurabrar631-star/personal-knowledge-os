package com.pkos.app.ui.status

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.ui.graphics.vector.ImageVector
import com.pkos.app.R
import com.pkos.core.data.db.dao.KnowledgeCounts
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.model.SyncState

enum class ToneKind { NEUTRAL, INFO, SUCCESS, WARNING, DANGER }

/**
 * Plain-language item status shown everywhere (spec "UX PRIORITY"): Saved on device, Backing up, Backed up,
 * Sending to Notion, Synced, Needs review, Action required — plus Organizing / In Notion / Will retry.
 */
enum class ItemStatusUi(@StringRes val label: Int, val icon: ImageVector, val tone: ToneKind) {
    SAVED_ON_DEVICE(R.string.status_saved_on_device, Icons.Outlined.PhoneAndroid, ToneKind.NEUTRAL),
    ORGANIZING(R.string.status_organizing, Icons.Outlined.AutoAwesome, ToneKind.INFO),
    NEEDS_REVIEW(R.string.status_needs_review, Icons.Outlined.RateReview, ToneKind.WARNING),
    BACKING_UP(R.string.status_backing_up, Icons.Outlined.CloudUpload, ToneKind.INFO),
    BACKED_UP(R.string.status_backed_up, Icons.Outlined.CloudDone, ToneKind.SUCCESS),
    SENDING_TO_NOTION(R.string.status_sending_to_notion, Icons.AutoMirrored.Outlined.Send, ToneKind.INFO),
    IN_NOTION(R.string.status_in_notion, Icons.Outlined.DoneAll, ToneKind.SUCCESS),
    SYNCED(R.string.status_synced, Icons.Outlined.DoneAll, ToneKind.SUCCESS),
    RETRYING(R.string.status_retrying, Icons.Outlined.Refresh, ToneKind.WARNING),
    ACTION_REQUIRED(R.string.status_action_required, Icons.Outlined.ErrorOutline, ToneKind.DANGER),
    ;

    companion object {
        /** Most important state wins: problems → review → organizing → where the data currently is. */
        fun of(processing: ProcessingState, sync: SyncState): ItemStatusUi = when {
            processing == ProcessingState.FAILED || sync.needsAction -> ACTION_REQUIRED
            processing == ProcessingState.NEEDS_REVIEW -> NEEDS_REVIEW
            processing.isInProgress -> ORGANIZING
            else -> when (sync) {
                SyncState.LOCAL_ONLY -> SAVED_ON_DEVICE
                SyncState.BACKUP_QUEUED -> BACKING_UP
                SyncState.BACKED_UP -> BACKED_UP
                SyncState.NOTION_QUEUED -> SENDING_TO_NOTION
                SyncState.NOTION_SYNCED -> IN_NOTION
                SyncState.SYNCED -> SYNCED
                SyncState.ERROR_RETRYABLE -> RETRYING
                SyncState.CONFLICT, SyncState.ERROR_ACTION_REQUIRED -> ACTION_REQUIRED
            }
        }
    }
}

/** Whole-app health for the top-bar indicator. Never claims "up to date" when no destination is configured. */
enum class AppHealthUi(@StringRes val label: Int, val icon: ImageVector, val tone: ToneKind) {
    LOCAL_ONLY(R.string.health_local_only, Icons.Outlined.CloudOff, ToneKind.NEUTRAL),
    SYNCING(R.string.health_syncing, Icons.Outlined.CloudSync, ToneKind.INFO),
    ACTION_REQUIRED(R.string.health_action_required, Icons.Outlined.ErrorOutline, ToneKind.DANGER),
    UP_TO_DATE(R.string.health_up_to_date, Icons.Outlined.CloudDone, ToneKind.SUCCESS),
    ;

    companion object {
        fun of(counts: KnowledgeCounts, queuedActionRequired: Int, anyDestinationConnected: Boolean): AppHealthUi = when {
            counts.actionRequired > 0 || queuedActionRequired > 0 -> ACTION_REQUIRED
            counts.pendingSync > 0 -> SYNCING
            !anyDestinationConnected -> LOCAL_ONLY
            else -> UP_TO_DATE
        }
    }
}
