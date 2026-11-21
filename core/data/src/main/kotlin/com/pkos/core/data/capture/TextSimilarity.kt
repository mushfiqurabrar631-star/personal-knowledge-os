package com.pkos.core.data.capture

/**
 * Fuzzy near-duplicate detection (spec 4.1 step 10), fully offline: Jaccard similarity of character 3-gram sets over
 * normalised text (lower-case, URLs/punctuation removed, whitespace collapsed). Works for Bengali and English alike.
 * Used only to *suggest* a duplicate; nothing is merged or deleted.
 */
object TextSimilarity {
    const val THRESHOLD = 0.85
    const val MIN_LENGTH = 40

    fun normalize(text: String): String = text.lowercase()
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    fun shingles(text: String, n: Int = 3): Set<String> {
        val t = normalize(text)
        if (t.length < n) return if (t.isEmpty()) emptySet() else setOf(t)
        return (0..t.length - n).mapTo(HashSet()) { t.substring(it, it + n) }
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val inter = small.count { it in large }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** Best match at or above [threshold], as (id, score). */
    fun bestMatch(text: String, candidates: List<Pair<String, String>>, threshold: Double = THRESHOLD): Pair<String, Double>? {
        if (normalize(text).length < MIN_LENGTH) return null
        val s = shingles(text)
        return candidates.asSequence()
            .map { (id, other) -> id to jaccard(s, shingles(other)) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
    }
}
