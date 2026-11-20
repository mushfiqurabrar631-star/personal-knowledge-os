package com.pkos.app.capture

import android.content.Context
import com.pkos.app.R
import com.pkos.core.data.capture.CaptureFailure
import com.pkos.core.data.capture.CaptureResult

/** Plain-language capture outcomes (spec 2.4: confirm briefly; on failure show reason + retry). */
object CaptureMessages {
    fun title(context: Context, result: CaptureResult): String = when (result) {
        is CaptureResult.Saved -> context.getString(R.string.capture_saved_title)
        is CaptureResult.Failed -> context.getString(R.string.capture_failed_title)
    }

    fun body(context: Context, result: CaptureResult): String = when (result) {
        is CaptureResult.Saved -> if (result.duplicateOfId != null) context.getString(R.string.capture_saved_duplicate) else context.getString(R.string.capture_saved_body)
        is CaptureResult.Failed -> context.getString(
            when (result.reason) {
                CaptureFailure.EMPTY -> R.string.capture_fail_empty
                CaptureFailure.UNREADABLE -> R.string.capture_fail_unreadable
                CaptureFailure.TOO_LARGE -> R.string.capture_fail_too_large
                CaptureFailure.LOW_STORAGE -> R.string.capture_fail_storage
                CaptureFailure.DATABASE -> R.string.capture_fail_database
            },
        )
    }

    fun snackbar(context: Context, result: CaptureResult): String = when (result) {
        is CaptureResult.Saved -> body(context, result)
        is CaptureResult.Failed -> title(context, result) + " · " + body(context, result)
    }
}
