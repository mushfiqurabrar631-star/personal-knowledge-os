package com.pkos.core.notion.sync

import kotlin.random.Random

/** Exponential backoff with "equal jitter", capped. attempt is 0-based. */
class RetryPolicy(
    val baseDelayMs: Long = 2_000,
    val maxDelayMs: Long = 30 * 60 * 1_000L,
    val maxAttempts: Int = 8,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 30)
        val exp = (baseDelayMs shl shift).coerceAtMost(maxDelayMs).coerceAtLeast(baseDelayMs)
        val half = exp / 2
        return half + random.nextLong(half + 1)
    }

    fun clampServerDelay(ms: Long): Long = ms.coerceIn(1_000, maxDelayMs)

    fun exhausted(attempt: Int): Boolean = attempt + 1 >= maxAttempts
}
