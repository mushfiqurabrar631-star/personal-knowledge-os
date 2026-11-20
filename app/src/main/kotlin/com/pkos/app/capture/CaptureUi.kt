package com.pkos.app.capture

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pkos.app.R
import com.pkos.app.di.LocalAppContainer
import com.pkos.app.feature.capture.CaptureKind
import com.pkos.app.feature.capture.CaptureSheet
import com.pkos.app.ui.components.toneOf
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import kotlinx.coroutines.launch

/**
 * Shared capture launcher: photo picker | file picker | clipboard | typed text → local save.
 * Photo Picker and the Storage Access Framework are used instead of broad storage permissions (spec 3.1).
 */
class CaptureLauncher(val pick: (CaptureKind) -> Unit, val text: (CaptureKind, String) -> Unit)

@Composable
fun rememberCaptureLauncher(
    floating: Boolean = false,
    onCancel: () -> Unit = {},
    onRequest: (CaptureRequest) -> Unit,
): CaptureLauncher {
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) onCancel()
        else onRequest(CaptureRequest(CaptureMethod.PHOTO_PICKER, files = uris.map { ShareIntentParser.fileFor(it, context.contentResolver, "image/*") }))
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) onCancel()
        else onRequest(CaptureRequest(CaptureMethod.FILE_PICKER, files = uris.map { ShareIntentParser.fileFor(it, context.contentResolver, null) }))
    }
    return CaptureLauncher(
        pick = { kind ->
            when (kind) {
                CaptureKind.IMAGE -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                CaptureKind.FILE -> pickFile.launch(arrayOf("*/*"))
                // User-initiated paste only; the clipboard is never read in the background (spec 3).
                CaptureKind.PASTE -> onRequest(CaptureRequest(if (floating) CaptureMethod.FLOATING else CaptureMethod.PASTE, text = readClipboard(context).orEmpty()))
                else -> Unit
            }
        },
        text = { kind, text ->
            val method = when {
                floating -> CaptureMethod.FLOATING
                kind == CaptureKind.NOTE -> CaptureMethod.QUICK_NOTE
                else -> CaptureMethod.PASTE
            }
            onRequest(CaptureRequest(method, text = text))
        },
    )
}

/** In-app capture from the bottom sheet; results are reported via snackbar by the caller. */
@Composable
fun CaptureFlow(visible: Boolean, onDismiss: () -> Unit, onResult: (CaptureResult) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val launcher = rememberCaptureLauncher { request -> scope.launch { onResult(container.capture(request)) } }
    if (visible) {
        CaptureSheet(
            onDismiss = onDismiss,
            onPick = { kind -> onDismiss(); launcher.pick(kind) },
            onText = { kind, text -> onDismiss(); launcher.text(kind, text) },
        )
    }
}

fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }.joinToString("\n").takeIf { it.isNotBlank() }
}

/** Brief confirmation after a capture with optional note / pin (spec 2.4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureResultCard(
    result: CaptureResult?,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onOpen: (String) -> Unit,
    onPin: (String) -> Unit,
    onNote: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pinned by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = modifier.fillMaxWidth().testTag("capture_result")) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            when (result) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(24.dp)); Spacer(Modifier.width(Spacing.m))
                    Text(stringResource(R.string.capture_saving), style = MaterialTheme.typography.titleMedium)
                }
                else -> {
                    val ok = result is CaptureResult.Saved
                    val tone = toneOf(if (ok) ToneKind.SUCCESS else ToneKind.DANGER)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = tone.content)
                        Spacer(Modifier.width(Spacing.m))
                        Text(CaptureMessages.title(context, result), style = MaterialTheme.typography.titleMedium)
                    }
                    if (result is CaptureResult.Saved) Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    Text(CaptureMessages.body(context, result), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result is CaptureResult.Saved) {
                        if (noteOpen) {
                            OutlinedTextField(note, { note = it }, placeholder = { Text(stringResource(R.string.capture_note_hint)) }, modifier = Modifier.fillMaxWidth().testTag("capture_note"))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            if (noteOpen) {
                                FilledTonalButton(onClick = { onNote(result.itemId, note); noteOpen = false }, enabled = note.isNotBlank()) { Text(stringResource(R.string.capture_save_note)) }
                            } else {
                                OutlinedButton(onClick = { noteOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.capture_add_note)) }
                            }
                            OutlinedButton(onClick = { pinned = true; onPin(result.itemId) }, enabled = !pinned, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.PushPin, null); Text(" " + stringResource(if (pinned) R.string.capture_pinned else R.string.capture_pin))
                            }
                            OutlinedButton(onClick = { onOpen(result.itemId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.capture_open)) }
                            Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("capture_done")) { Text(stringResource(R.string.capture_done)) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("capture_retry")) { Text(stringResource(R.string.action_retry)) }
                            OutlinedButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.capture_done)) }
                        }
                    }
                }
            }
        }
    }
}
