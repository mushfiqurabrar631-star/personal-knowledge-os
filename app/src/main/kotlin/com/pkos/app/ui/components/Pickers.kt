package com.pkos.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.pkos.app.R
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ItemPriority

/** Chip that opens a small menu; "Any" clears the choice. */
@Composable
fun <T> DropdownChip(label: String, selected: T?, options: List<T>, optionLabel: @Composable (T) -> String, onSelect: (T?) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null, onClick = { open = true },
            label = { Text(if (selected == null) label else "$label: ${optionLabel(selected)}", maxLines = 1) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.search_filter_any)) }, onClick = { open = false; onSelect(null) })
            options.forEach { o -> DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) }, modifier = Modifier.testTag("${tag}_${o}")) }
        }
    }
}

@Composable
fun priorityLabel(p: ItemPriority): String = stringResource(
    when (p) { ItemPriority.LOW -> R.string.priority_low; ItemPriority.NORMAL -> R.string.priority_normal; ItemPriority.HIGH -> R.string.priority_high; ItemPriority.CRITICAL -> R.string.priority_critical },
)

@Composable
fun typeLabel(t: ContentType): String = stringResource(
    when (t) {
        ContentType.ARTICLE -> R.string.type_article; ContentType.VIDEO -> R.string.type_video; ContentType.WEBSITE -> R.string.type_website
        ContentType.TUTORIAL -> R.string.type_tutorial; ContentType.IDEA -> R.string.type_idea; ContentType.NOTE -> R.string.type_note
        ContentType.DOCUMENT -> R.string.type_document; ContentType.SCREENSHOT -> R.string.type_screenshot; ContentType.IMAGE -> R.string.type_image
        ContentType.MESSAGE -> R.string.type_message; ContentType.TOOL -> R.string.type_tool; ContentType.OTHER -> R.string.type_other
    },
)
