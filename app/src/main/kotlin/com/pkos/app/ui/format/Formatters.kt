package com.pkos.app.ui.format

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.ui.graphics.vector.ImageVector
import com.pkos.core.data.model.ContentType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Formatters {
    fun dateTime(epochMillis: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun bytes(context: android.content.Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun host(url: String?): String? = url?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }

    fun icon(type: ContentType?): ImageVector = when (type) {
        ContentType.ARTICLE -> Icons.AutoMirrored.Outlined.Article
        ContentType.VIDEO -> Icons.Outlined.OndemandVideo
        ContentType.WEBSITE -> Icons.Outlined.Language
        ContentType.TUTORIAL -> Icons.Outlined.School
        ContentType.IDEA -> Icons.Outlined.Lightbulb
        ContentType.NOTE -> Icons.Outlined.EditNote
        ContentType.DOCUMENT -> Icons.Outlined.Description
        ContentType.SCREENSHOT, ContentType.IMAGE -> Icons.Outlined.Image
        ContentType.MESSAGE -> Icons.Outlined.ChatBubbleOutline
        ContentType.TOOL -> Icons.Outlined.Handyman
        ContentType.OTHER, null -> Icons.AutoMirrored.Outlined.StickyNote2
    }
}
