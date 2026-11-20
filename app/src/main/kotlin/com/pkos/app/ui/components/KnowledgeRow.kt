package com.pkos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.status.ItemStatusUi
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.entity.KnowledgeItemEntity

/** One knowledge item in a list: type icon, title, snippet/source, and its plain-language status. */
@Composable
fun KnowledgeRow(item: KnowledgeItemEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = ItemStatusUi.of(item.processingState, item.syncState)
    val secondary = item.summary?.takeIf { it.isNotBlank() }
        ?: item.rawText?.takeIf { it.isNotBlank() }
        ?: Formatters.host(item.sourceUrl)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.l).heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            IconBadge(Formatters.icon(item.contentType))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.pinned) {
                        Spacer(Modifier.width(Spacing.xs))
                        Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                secondary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(stringResource(status.label), status.icon, status.tone)
            }
        }
    }
}
