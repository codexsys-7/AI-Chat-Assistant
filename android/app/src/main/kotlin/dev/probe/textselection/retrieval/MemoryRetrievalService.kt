package dev.probe.textselection.retrieval

import dev.probe.textselection.ai.EMBEDDING_DIMENSIONS
import dev.probe.textselection.ai.EmbeddingClient
import dev.probe.textselection.ai.EmbeddingResult
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.interaction.EmbeddingStatus
import dev.probe.textselection.interaction.InteractionMemory
import dev.probe.textselection.interaction.InteractionMemoryStore
import dev.probe.textselection.interaction.queryPassage
import kotlinx.coroutines.CancellationException

/**
 * Ranks the current session's stored memories against the current selection.
 * Callable on its own. It does not read another session, write the store,
 * retry a failed embedding, or call chat completion.
 */
class MemoryRetrievalService(
    private val store: InteractionMemoryStore,
    private val embeddings: EmbeddingClient,
    private val config: RetrievalConfig = RetrievalConfig(),
) {
    suspend fun retrieve(
        context: ContextPackage,
        sessionId: String,
        excludedResponseId: String? = null,
    ): RetrievedMemoryContext {
        val passage = queryPassageFor(context) ?: return RetrievedMemoryContext.EMPTY
        val embedded = try {
            embeddings.embed(passage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RetrievedMemoryContext.EMPTY
        }
        val query = queryVector(embedded) ?: return RetrievedMemoryContext.EMPTY
        val kept = LinkedHashMap<String, ScoredMemory>()
        for (memory in store.list(sessionId)) {
            val vector = storedVector(memory) ?: continue
            if (excludedResponseId != null && memory.responseId == excludedResponseId) continue
            val score = cosineSimilarity(query, vector) ?: continue
            if (score < config.threshold) continue
            val key = dedupeKey(memory.selectedText, memory.generatedResponse)
            val current = kept[key]
            if (current == null || score > current.score) {
                kept[key] = ScoredMemory(memory, score)
            }
        }
        val ranked = kept.values.sortedByDescending { it.score }
        return RetrievedMemoryContext.from(ranked.take(config.topK))
    }

    private fun queryPassageFor(context: ContextPackage): String? {
        val selected = context.selectedText ?: return null
        return queryPassage(
            selectedText = selected,
            precedingContext = context.precedingContext,
            followingContext = context.followingContext,
        )
    }

    private fun queryVector(result: EmbeddingResult): FloatArray? {
        if (result !is EmbeddingResult.Success) return null
        if (result.dimensions != config.dimensions) return null
        return finiteVector(result.embedding)
    }

    private fun storedVector(memory: InteractionMemory): FloatArray? {
        if (memory.embeddingStatus != EmbeddingStatus.COMPLETED) return null
        val embedding = memory.embedding ?: return null
        return finiteVector(embedding)
    }

    private fun finiteVector(values: List<Double>): FloatArray? {
        if (values.size != config.dimensions) return null
        val out = FloatArray(values.size)
        var square = 0.0
        for (index in values.indices) {
            val value = values[index]
            if (!value.isFinite()) return null
            val asFloat = value.toFloat()
            if (!asFloat.isFinite()) return null
            out[index] = asFloat
            square += asFloat.toDouble() * asFloat.toDouble()
        }
        if (!square.isFinite() || square == 0.0) return null
        return out
    }
}

/**
 * One place for retrieval limits. [dimensions] follows the embedding contract.
 */
data class RetrievalConfig(
    val topK: Int = DEFAULT_TOP_K,
    val threshold: Double = DEFAULT_THRESHOLD,
    val dimensions: Int = EMBEDDING_DIMENSIONS,
) {
    init {
        require(topK >= 1) { "topK must be at least 1." }
        require(dimensions >= 1) { "dimensions must be at least 1." }
        require(threshold.isFinite()) { "threshold must be finite." }
    }

    companion object {
        const val DEFAULT_TOP_K = 3
        const val DEFAULT_THRESHOLD = 0.75
    }
}

internal fun dedupeKey(selectedText: String, generatedResponse: String): String {
    return normalizeRetrievalText(selectedText) + "\n" + normalizeRetrievalText(generatedResponse)
}

internal fun normalizeRetrievalText(text: String): String {
    return text.trim().replace(WHITESPACE, " ")
}

private val WHITESPACE = Regex("\\s+")
