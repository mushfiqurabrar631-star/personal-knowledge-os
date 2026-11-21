package com.pkos.core.data.features

import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.model.FieldOrigin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class Granularity { DAY, WEEK, MONTH }

data class TimelineBucket(
    val start: LocalDate,
    val captured: Int,
    val edited: Int,
    val organized: Int,
    /** Topic (or category, when no topic) → number of captures in this period. */
    val topicActivity: List<Pair<String, Int>>,
    val itemIds: List<String>,
)

/** Knowledge timeline (spec 9 #6): captures, owner edits, AI organisation and topic activity per day/week/month. */
class TimelineService(private val db: PkosDatabase, private val zone: ZoneId = ZoneId.systemDefault()) {
    suspend fun build(granularity: Granularity, periods: Int = 12, now: Long = System.currentTimeMillis()): List<TimelineBucket> {
        fun start(d: LocalDate): LocalDate = when (granularity) {
            Granularity.DAY -> d
            Granularity.WEEK -> d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            Granularity.MONTH -> d.with(TemporalAdjusters.firstDayOfMonth())
        }
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = start(day(now))
        val oldest = when (granularity) {
            Granularity.DAY -> today.minusDays(periods - 1L)
            Granularity.WEEK -> today.minusWeeks(periods - 1L)
            Granularity.MONTH -> today.minusMonths(periods - 1L)
        }
        val fromMs = oldest.atStartOfDay(zone).toInstant().toEpochMilli()
        val dao = db.featureDao()
        val items = dao.liveItems().filter { it.createdAt >= fromMs }
        val versions = dao.versionsSince(fromMs)
        val topics = dao.topics().associate { it.id to it.name }
        val cats = db.categoryDao().all().associate { it.id to it.name }
        val byStart = items.groupBy { start(day(it.createdAt)) }
        val editsBy = versions.filter { it.changeSource == FieldOrigin.USER }.groupingBy { start(day(it.changedAt)) }.eachCount()
        val aiBy = versions.filter { it.changeSource == FieldOrigin.AI }.groupingBy { start(day(it.changedAt)) }.eachCount()
        val keys = (byStart.keys + editsBy.keys + aiBy.keys).filter { !it.isBefore(oldest) }.distinct().sortedDescending() // not SortedSet.reversed(): API 35+ only
        return keys.map { k ->
            val its = byStart[k].orEmpty().sortedByDescending { it.createdAt }
            val activity = its.mapNotNull { it.topicId?.let(topics::get) ?: it.categoryId?.let(cats::get) }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value }
            TimelineBucket(k, its.size, editsBy[k] ?: 0, aiBy[k] ?: 0, activity, its.map { it.id })
        }
    }
}
