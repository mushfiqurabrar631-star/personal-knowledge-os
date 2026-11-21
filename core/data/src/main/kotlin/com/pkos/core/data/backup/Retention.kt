package com.pkos.core.data.backup

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Spec 8.1 retention, user-adjustable. Defaults: 7 daily, 4 weekly, 6 monthly. */
data class RetentionPolicy(val daily: Int = 7, val weekly: Int = 4, val monthly: Int = 6) {
    init { require(daily >= 1 && weekly >= 0 && monthly >= 0) { "keep at least one daily backup" } }

    /**
     * Grandfather-father-son selection over verified backups: the newest backup of each of the latest [daily] days,
     * [weekly] ISO weeks and [monthly] months is kept. The newest backup is always kept.
     */
    fun keep(backups: List<Pair<String, Long>>, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
        val sorted = backups.sortedByDescending { it.second }
        if (sorted.isEmpty()) return emptySet()
        val keep = mutableSetOf(sorted.first().first)
        fun bucket(limit: Int, key: (Instant) -> Any) {
            val seen = LinkedHashSet<Any>()
            for ((id, at) in sorted) {
                val k = key(Instant.ofEpochMilli(at))
                if (k in seen) continue
                if (seen.size >= limit) break
                seen += k; keep += id
            }
        }
        bucket(daily) { it.atZone(zone).toLocalDate() }
        bucket(weekly) { val d = it.atZone(zone).toLocalDate(); d.get(IsoFields.WEEK_BASED_YEAR) to d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
        bucket(monthly) { val d = it.atZone(zone).toLocalDate(); d.year to d.monthValue }
        return keep
    }
}
