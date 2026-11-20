package com.pkos.core.notion.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces requests out so the app stays under Notion's average limit (about 3 requests/second).
 */
class RateLimiter(
    private val minIntervalMs: Long,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastAtMs: Long? = null

    suspend fun acquire() {
        if (minIntervalMs <= 0) return
        mutex.withLock {
            val last = lastAtMs
            if (last != null) {
                val wait = last + minIntervalMs - clockMs()
                if (wait > 0) sleeper(wait)
            }
            lastAtMs = clockMs()
        }
    }

    companion object {
        const val NOTION_DEFAULT_INTERVAL_MS = 350L
        fun none() = RateLimiter(0)
    }
}
