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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.pkos.app.R
import com.pkos.app.ui.components.IconBadge
import com.pkos.app.ui.components.NoticeCard
import com.pkos.app.ui.theme.Spacing

enum class CaptureKind { LINK, TEXT, IMAGE, FILE, NOTE, PASTE }

private data class CaptureOption(val kind: CaptureKind, val icon: ImageVector, val label: Int, val tag: String)

private val options = listOf(
    CaptureOption(CaptureKind.LINK, Icons.Outlined.Link, R.string.capture_link, "link"),
    CaptureOption(CaptureKind.TEXT, Icons.Outlined.TextFields, R.string.capture_text, "text"),
    CaptureOption(CaptureKind.IMAGE, Icons.Outlined.Image, R.string.capture_screenshot, "image"),
    CaptureOption(CaptureKind.FILE, Icons.Outlined.UploadFile, R.string.capture_file, "file"),
    CaptureOption(CaptureKind.NOTE, Icons.Outlined.EditNote, R.string.capture_quick_note, "note"),
    CaptureOption(CaptureKind.PASTE, Icons.Outlined.ContentPaste, R.string.capture_paste, "paste"),
)

/**
 * Capture sheet with every required source (spec 2.4 / 2.7). Every option saves locally first.
 */
// rememberModalBottomSheetState is deprecated in Material3 1.5 in favour of a newer API; kept until that API is stable.
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(onDismiss: () -> Unit, onPick: (CaptureKind) -> Unit, onText: (CaptureKind, String) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        // Hide with the standard animation first, then start the chosen capture (Material guidance).
        CaptureSheetContent(
            onPick = { kind -> scope.launch { state.hide() }.invokeOnCompletion { onPick(kind) } },
            onText = { kind, text -> scope.launch { state.hide() }.invokeOnCompletion { onText(kind, text) } },
        )
    }
}

@Composable
fun CaptureSheetContent(onPick: (CaptureKind) -> Unit = {}, onText: (CaptureKind, String) -> Unit = { _, _ -> }) {
    // Link / text / note are typed right here in the sheet (no second window), files use the system pickers.
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<CaptureKind?>(null) }
    editing?.let { kind ->
        TextCaptureEditor(kind, onCancel = { editing = null }) { text -> onText(kind, text) }
        return
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("capture_sheet"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.capture_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.capture_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                row.forEach { o ->
                    OptionTile(o, Modifier.weight(1f)) {
                        if (o.kind == CaptureKind.LINK || o.kind == CaptureKind.TEXT || o.kind == CaptureKind.NOTE) editing = o.kind else onPick(o.kind)
                    }
                }
            }
        }
        NoticeCard(Icons.Outlined.IosShare, stringResource(R.string.capture_share_tip), stringResource(R.string.capture_staged_note))
        Text(stringResource(R.string.capture_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.s))
    }
}

@Composable
private fun OptionTile(option: CaptureOption, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = 72.dp).testTag("capture_option_${option.tag}"),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(option.icon)
            Spacer(Modifier.width(Spacing.m))
            Text(stringResource(option.label), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
fun TextCaptureEditor(kind: CaptureKind, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val title = when (kind) { CaptureKind.LINK -> R.string.capture_dialog_link; CaptureKind.NOTE -> R.string.capture_dialog_note; else -> R.string.capture_dialog_text }
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).testTag("capture_editor"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(if (kind == CaptureKind.LINK) R.string.capture_dialog_link_hint else R.string.capture_dialog_text_hint)) },
            minLines = if (kind == CaptureKind.LINK) 1 else 4,
            singleLine = kind == CaptureKind.LINK,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (kind == CaptureKind.LINK) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("capture_text_input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.align(Alignment.End)) {
            androidx.compose.material3.TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.capture_dialog_cancel)) }
            androidx.compose.material3.Button(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("capture_text_save")) {
                Text(stringResource(R.string.capture_dialog_save))
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
