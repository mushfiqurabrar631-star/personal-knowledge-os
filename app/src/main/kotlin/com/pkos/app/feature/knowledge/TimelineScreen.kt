package com.pkos.app.feature.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.ui.components.EmptyState
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.features.Granularity
import com.pkos.core.data.features.TimelineBucket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class TimelineUi(val granularity: Granularity, val buckets: List<TimelineBucket>, val titles: Map<String, String>, val loaded: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(c: AppContainer) : ViewModel() {
    private val gran = MutableStateFlow(Granularity.DAY)
    val ui: StateFlow<TimelineUi> = combine(gran, c.knowledge.counts()) { g, _ -> g }.mapLatest { g ->
        val b = c.timeline.build(g, periods = if (g == Granularity.DAY) 30 else 12)
        val titles = c.database.featureDao().liveItems().associate { it.id to it.title }
        TimelineUi(g, b, titles, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUi(Granularity.DAY, emptyList(), emptyMap(), false))
    fun set(g: Granularity) { gran.value = g }
}

@Composable
fun TimelineRoute(onBack: () -> Unit, openItem: (String) -> Unit) {
    val vm = pkosViewModel { TimelineViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    TimelineScreen(ui, vm::set, onBack, openItem)
}

@Composable
fun TimelineScreen(ui: TimelineUi, onGranularity: (Granularity) -> Unit, onBack: () -> Unit, openItem: (String) -> Unit) {
    ToolScaffold(stringResource(R.string.nav_timeline), onBack, "timeline_list") {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Granularity.entries.forEachIndexed { i, g ->
                    SegmentedButton(selected = ui.granularity == g, onClick = { onGranularity(g) }, shape = SegmentedButtonDefaults.itemShape(i, 3), modifier = Modifier.testTag("timeline_${g.name}")) {
                        Text(stringResource(when (g) { Granularity.DAY -> R.string.timeline_day; Granularity.WEEK -> R.string.timeline_week; Granularity.MONTH -> R.string.timeline_month }))
                    }
                }
            }
        }
        if (ui.loaded && ui.buckets.isEmpty()) item { EmptyState(Icons.Outlined.Timeline, stringResource(R.string.timeline_empty_title), stringResource(R.string.timeline_empty_body), Modifier.padding(top = 32.dp)) }
        items(ui.buckets, key = { it.start.toString() }) { b ->
            val label = when (ui.granularity) {
                Granularity.MONTH -> b.start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                else -> b.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            }
            Panel(label, Modifier.testTag("timeline_bucket")) {
                Text(stringResource(R.string.timeline_counts, b.captured, b.edited, b.organized), style = MaterialTheme.typography.bodyMedium)
                if (b.topicActivity.isNotEmpty()) Text(stringResource(R.string.timeline_topics, b.topicActivity.take(3).joinToString(", ") { "${it.first} (${it.second})" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                b.itemIds.take(5).forEach { id ->
                    Text("• " + (ui.titles[id] ?: "…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { openItem(id) }.padding(vertical = Spacing.xs))
                }
                if (b.itemIds.size > 5) Text("+${b.itemIds.size - 5}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
