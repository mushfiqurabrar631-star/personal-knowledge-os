package com.pkos.core.data.features

import com.pkos.core.data.model.PrivacyScope

/** One source shown with an answer; [n] in the answer text refers to it. */
data class ChatSource(val n: Int, val itemId: String, val title: String, val snippet: String)

data class ChatAnswer(
    val text: String?,
    val sources: List<ChatSource>,
    /** No saved item matched: the app says so instead of answering. */
    val noSources: Boolean = false,
    /** Answer written by cloud AI (else: extracted on the phone from your items). */
    val byCloud: Boolean = false,
    /** Cloud answer had no valid citation, so it was not shown. */
    val ungrounded: Boolean = false,
    /** Matching items kept on this phone only (never sent to cloud AI). */
    val localOnlyWithheld: Int = 0,
)

/** Plain-text generation (cloud). */
fun interface TextGenerator { suspend fun generate(prompt: String): String? }

/**
 * Ask your library (spec 9 #2, 9.2): retrieval over the owner's own items, answer only from them, every claim cited
 * [n], sources openable. Without cloud AI the answer is extracted on the phone (sentences from the items, cited).
 * Local-only items are never put in a cloud prompt.
 */
class KnowledgeChat(private val search: SearchService) {
    suspend fun ask(question: String, generator: TextGenerator?, embedder: EmbeddingProvider? = null, semantic: Boolean = true): ChatAnswer {
        val hits = search.search(question, semantic = semantic, embedder = embedder, limit = 6).filter { it.score > 0 }
        if (hits.isEmpty()) return ChatAnswer(null, emptyList(), noSources = true)
        val tokens = SearchService.tokens(question) + Concepts.expand(SearchService.tokens(question))
        val all = hits.mapIndexed { i, h -> ChatSource(i + 1, h.item.id, h.item.title, snippet(listOfNotNull(h.item.summary, h.item.rawText, h.item.userNote).joinToString("\n"), tokens)) }
        if (generator != null) {
            val shareable = hits.filter { it.item.privacyScope == PrivacyScope.NORMAL }.map { it.item.id }.toSet()
            val sources = all.filter { it.itemId in shareable }.mapIndexed { i, s -> s.copy(n = i + 1) }
            val withheld = all.size - sources.size
            if (sources.isNotEmpty()) {
                val sent = prompt(question, sources)
                val reply = runCatching { generator.generate(sent) }.getOrNull()
                // Spec 10.1 "view recent cloud processing activity": one row per saved item whose text went to the cloud.
                sources.forEach { src -> CloudAudit.record(search.db, src.itemId, CloudAudit.ASK, "gemini", if (reply == null) "ERROR" else "OK", src.snippet.length, System.currentTimeMillis()) }
                if (reply != null && reply.contains("NOT_IN_LIBRARY")) return ChatAnswer(null, emptyList(), noSources = true, byCloud = true, localOnlyWithheld = withheld)
                if (reply != null) {
                    val cited = Regex("\\[(\\d+)]").findAll(reply).map { it.groupValues[1].toInt() }.toSet()
                    val valid = cited.filter { it in 1..sources.size }
                    return if (valid.isEmpty() || cited.any { it !in 1..sources.size }) {
                        ChatAnswer(null, sources, ungrounded = true, localOnlyWithheld = withheld)
                    } else ChatAnswer(reply.trim(), sources.filter { it.n in valid }, byCloud = true, localOnlyWithheld = withheld)
                }
            }
        }
        // On-phone extractive answer: best matching sentence of each source, each cited.
        val lines = all.mapNotNull { s -> s.snippet.takeIf { it.isNotBlank() }?.let { "$it [${s.n}]" } }.take(4)
        return ChatAnswer(lines.joinToString("\n").ifBlank { null }, all, noSources = lines.isEmpty())
    }

    private fun prompt(q: String, sources: List<ChatSource>) = buildString {
        appendLine("Answer the question using ONLY the numbered sources from the user's own saved notes.")
        appendLine("After every sentence that states a fact, add the source number in brackets, e.g. [1] or [2][3].")
        appendLine("If the sources do not contain the answer, reply exactly: NOT_IN_LIBRARY")
        appendLine("Answer in the language of the question. Be brief. Do not invent sources or facts.")
        sources.forEach { appendLine("[${it.n}] ${it.title}: ${it.snippet.take(1_200)}") }
        appendLine("Question: $q")
    }.let { it }

    companion object {
        fun snippet(text: String, tokens: Collection<String>): String {
            val sentences = text.replace(Regex("https?://\\S+"), "").split(Regex("(?<=[.!?।])\\s+|\\s*\\n+\\s*")).map { it.trim() }.filter { it.length > 3 }
            if (sentences.isEmpty()) return ""
            val best = sentences.maxWith(compareBy<String> { s -> tokens.count { t -> s.contains(t, ignoreCase = true) } }.thenBy { it.length.coerceAtMost(200) })
            return best.take(300)
        }
    }
}
