package com.pkos.core.data.processing

import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.SourcePlatform
import java.util.Locale

/**
 * Deterministic, offline classifier (spec 4.6 "local engine"). Keyword rules in Bengali and English map to the
 * starter categories. It is deliberately modest: its confidence is capped at 0.80 so it can never mark an item
 * as high-confidence on its own, and "Health & Personal Reference" is never inferred (no sensitive inference).
 */
object LocalClassifier {
    const val PROVIDER = "local-rules"
    const val MODEL = "rules-v1"
    private const val MAX_CONFIDENCE = 0.80

    private val rules: Map<String, List<String>> = mapOf(
        "sys-programming" to listOf(
            "python", "kotlin", "java", "javascript", "typescript", "sql", "github", "api", "code", "coding", "programming",
            "developer", "database", "excel", "power bi", "fabric", "data analysis", "dax", "regex", "android studio",
            "প্রোগ্রামিং", "কোড", "ডাটাবেস", "ডেটা",
        ),
        "sys-technology" to listOf(
            "android", "iphone", "app", "software", "ai", "chatgpt", "gpt", "claude", "gemini", "laptop", "gadget", "tech",
            "update", "feature", "প্রযুক্তি", "অ্যাপ", "মোবাইল", "সফটওয়্যার",
        ),
        "sys-education" to listOf(
            "course", "tutorial", "learn", "learning", "lecture", "class", "exam", "textbook", "chapter", "lesson", "study",
            "university", "mba", "শিখ", "শেখা", "কোর্স", "ক্লাস", "পরীক্ষা", "বই", "অধ্যায়", "পড়া", "লেকচার",
        ),
        "sys-business" to listOf(
            "business", "marketing", "sales", "finance", "invest", "investment", "startup", "revenue", "profit", "price",
            "market", "customer", "brand", "ব্যবসা", "মার্কেটিং", "বিক্রি", "বিক্রয়", "টাকা", "বিনিয়োগ", "লাভ", "দাম",
        ),
        "sys-projects" to listOf(
            "meeting", "report", "deadline", "project", "task", "okr", "kpi", "target", "client", "presentation", "plan",
            "মিটিং", "রিপোর্ট", "প্রজেক্ট", "টার্গেট", "কাজ", "পরিকল্পনা",
        ),
        "sys-ideas" to listOf(
            "idea", "ideas", "inspiration", "inspire", "design", "quote", "creative", "concept",
            "আইডিয়া", "ধারণা", "অনুপ্রেরণা", "ডিজাইন",
        ),
        "sys-websites" to listOf(
            "tool", "tools", "website", "extension", "free tool", "generator", "converter", "template", "plugin",
            "টুল", "ওয়েবসাইট",
        ),
    )

    private val purposeRules = mapOf(
        "learn" to listOf("tutorial", "course", "learn", "how to", "guide", "lesson", "কিভাবে", "শিখ", "কোর্স"),
        "reference" to listOf("documentation", "docs", "reference", "cheat sheet", "list of", "রেফারেন্স"),
        "build" to listOf("build", "project", "code", "template", "বানা", "তৈরি"),
        "buy" to listOf("buy", "price", "discount", "offer", "order", "কিনুন", "কিনতে", "দাম", "অফার"),
        "inspiration" to listOf("inspiration", "idea", "quote", "আইডিয়া", "অনুপ্রেরণা"),
    )

    fun classify(input: AiInput, platform: SourcePlatform?, captureType: ContentType?): AiResult {
        val hay = (input.title + "\n" + input.text + "\n" + (input.url ?: "")).lowercase(Locale.ROOT)
        val scores = rules.mapValues { (_, words) -> words.count { hit(hay, it) } }
        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked[0]
        val second = ranked.getOrNull(1)?.value ?: 0
        val matched = rules[top.key].orEmpty().filter { hit(hay, it) }

        var confidence = when {
            top.value == 0 -> 0.30
            else -> (0.50 + 0.08 * top.value + 0.06 * (top.value - second)).coerceAtMost(MAX_CONFIDENCE)
        }
        if (top.value > 0 && top.value == second) confidence = minOf(confidence, 0.55) // ambiguous tie
        val categoryId = if (confidence >= ConfidencePolicy.MEDIUM) top.key else null
        val type = refineType(hay, captureType)
        val purpose = purposeRules.filter { (_, words) -> words.any { hit(hay, it) } }.keys.toList().take(2)
        val tags = buildList {
            platform?.takeIf { it != SourcePlatform.MANUAL && it != SourcePlatform.OTHER }?.let { add(it.name.lowercase().replaceFirstChar { c -> c.titlecase() }) }
            matched.filter { it.length > 2 }.take(4).forEach { add(it) }
        }.distinctBy { it.lowercase() }
        val reason = when {
            top.value == 0 -> ReviewReason.NO_TOPIC
            categoryId == null -> ReviewReason.AMBIGUOUS
            else -> null
        }
        return AiResult(
            title = null, summary = extractiveSummary(input.text), categoryId = categoryId, topic = null, contentType = type,
            purpose = purpose, tags = tags, priority = ItemPriority.NORMAL, status = ItemStatus.INBOX,
            confidence = confidence, needsReview = categoryId == null, reviewReason = reason,
        )
    }

    private fun hit(hay: String, word: String): Boolean {
        val w = word.lowercase(Locale.ROOT)
        // Latin words must match on word boundaries ("ai" ≠ "said"); Bengali stems match as substrings.
        return if (w.first().code < 0x0980) Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])").containsMatchIn(hay) else hay.contains(w)
    }

    private fun refineType(hay: String, captured: ContentType?): ContentType? = when {
        captured in setOf(ContentType.VIDEO, ContentType.SCREENSHOT, ContentType.IMAGE, ContentType.DOCUMENT) -> captured
        listOf("tutorial", "how to", "কিভাবে", "step by step").any { hit(hay, it) } -> ContentType.TUTORIAL
        captured != null -> captured
        else -> ContentType.NOTE
    }

    /** First one or two sentences of the user's own text — never generated wording. */
    fun extractiveSummary(text: String): String? {
        val clean = text.replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.length < 20) return null
        val sentences = Regex("(?<=[.!?।])\\s+").split(clean)
        val out = StringBuilder()
        for (s in sentences) { if (out.length + s.length > 220 && out.isNotEmpty()) break; if (out.isNotEmpty()) out.append(' '); out.append(s) }
        return out.toString().take(240).trim()
    }
}

/** Spec 4.5 thresholds. */
object ConfidencePolicy {
    const val HIGH = 0.85
    const val MEDIUM = 0.60

    enum class Band { HIGH, MEDIUM, LOW }

    fun band(confidence: Double): Band = when {
        confidence >= HIGH -> Band.HIGH
        confidence >= MEDIUM -> Band.MEDIUM
        else -> Band.LOW
    }
}
