package com.pkos.app.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pkos.app.R
import com.pkos.app.ui.components.IconBadge
import com.pkos.app.ui.components.NoticeCard
import com.pkos.app.ui.components.StagedPill
import com.pkos.app.ui.theme.Spacing

private data class CaptureOption(val icon: ImageVector, val label: Int, val tag: String)

private val options = listOf(
    CaptureOption(Icons.Outlined.Link, R.string.capture_link, "link"),
    CaptureOption(Icons.Outlined.TextFields, R.string.capture_text, "text"),
    CaptureOption(Icons.Outlined.Image, R.string.capture_screenshot, "image"),
    CaptureOption(Icons.Outlined.UploadFile, R.string.capture_file, "file"),
    CaptureOption(Icons.Outlined.EditNote, R.string.capture_quick_note, "note"),
    CaptureOption(Icons.Outlined.ContentPaste, R.string.capture_paste, "paste"),
)

/**
 * Capture sheet with every required source (spec 2.4 / 2.7). Saving is implemented in M2, so in M1 the
 * options are visibly disabled and labelled — nothing pretends to save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CaptureSheetContent()
    }
}

@Composable
fun CaptureSheetContent() {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("capture_sheet"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.capture_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StagedPill("M2")
        }
        Text(stringResource(R.string.capture_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                row.forEach { o -> OptionTile(o, Modifier.weight(1f)) }
            }
        }
        NoticeCard(Icons.Outlined.IosShare, stringResource(R.string.capture_share_tip), stringResource(R.string.capture_staged_note))
        Spacer(Modifier.height(Spacing.s))
    }
}

@Composable
private fun OptionTile(option: CaptureOption, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = 72.dp).semantics { disabled() }.testTag("capture_option_${option.tag}"),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                option.icon,
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(Spacing.m))
            Text(
                stringResource(option.label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
