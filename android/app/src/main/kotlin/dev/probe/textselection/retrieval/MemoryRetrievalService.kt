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
        return inspect(
            selectedText = context.selectedText,
            precedingContext = context.precedingContext,
            followingContext = context.followingContext,
            sessionId = sessionId,
            requestId = "",
            excludedResponseId = excludedResponseId,
        ).context
    }

    /**
     * One retrieval. The returned diagnostics are the scores from this call.
     * [excludedResponseId] drops that response after its vector is usable.
     * A null or blank id excludes nothing. This does not invent an id.
     */
    suspend fun inspect(
        selectedText: String?,
        precedingContext: String?,
        followingContext: String?,
        sessionId: String,
        requestId: String,
        excludedResponseId: String? = null,
    ): RetrievalInspection {
        val started = System.nanoTime()
        val precedingPresent = !precedingContext.isNullOrBlank()
        val followingPresent = !followingContext.isNullOrBlank()
        val excluded = excludedResponseId?.takeIf { it.isNotBlank() }
        val passage = if (selectedText == null) {
            null
        } else {
            queryPassage(
                selectedText = selectedText,
                precedingContext = precedingContext,
                followingContext = followingContext,
            )
        }
        if (passage == null) {
            return inspection(
                started = started,
                context = RetrievedMemoryContext.EMPTY,
                status = RetrievalStatus.SKIPPED,
                queryEmbeddingStatus = QUERY_EMBEDDING_NOT_CALLED,
                sessionId = sessionId,
                requestId = requestId,
                originatingResponseId = excluded,
                storedCount = storedCount(sessionId),
                queryText = null,
                precedingPresent = precedingPresent,
                followingPresent = followingPresent,
                candidates = emptyList(),
            )
        }
        val embedded = try {
            embeddings.embed(passage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(
                started = started,
                queryEmbeddingStatus = QUERY_EMBEDDING_EXCEPTION,
                sessionId = sessionId,
                requestId = requestId,
                originatingResponseId = excluded,
                queryText = passage,
                precedingPresent = precedingPresent,
                followingPresent = followingPresent,
            )
        }
        val query = usableQuery(embedded)
        if (query == null) {
            return failed(
                started = started,
                queryEmbeddingStatus = queryStatus(embedded),
                sessionId = sessionId,
                requestId = requestId,
                originatingResponseId = excluded,
                queryText = passage,
                precedingPresent = precedingPresent,
                followingPresent = followingPresent,
            )
        }
        val stored = store.list(sessionId)
        val rows = ArrayList<WorkingCandidate>(stored.size)
        val kept = LinkedHashMap<String, Int>()
        for (memory in stored) {
            val row = WorkingCandidate(memory)
            rows += row
            val index = rows.lastIndex
            if (memory.sessionId != sessionId) {
                row.exclusion = RetrievalExclusion.DIFFERENT_SESSION
                continue
            }
            if (memory.embeddingStatus != EmbeddingStatus.COMPLETED || memory.embedding == null) {
                row.exclusion = RetrievalExclusion.MISSING_EMBEDDING
                continue
            }
            val vector = finiteVector(memory.embedding)
            val score = if (vector == null) null else cosineSimilarity(query, vector)
            if (vector == null || score == null) {
                row.exclusion = RetrievalExclusion.INVALID_VECTOR
                continue
            }
            row.score = score
            row.passedThreshold = score >= config.threshold
            if (excluded != null && memory.responseId == excluded) {
                row.exclusion = RetrievalExclusion.CURRENT_RESPONSE
                continue
            }
            if (score < config.threshold) {
                row.exclusion = RetrievalExclusion.BELOW_THRESHOLD
                continue
            }
            val key = dedupeKey(memory.selectedText, memory.generatedResponse)
            val previous = kept[key]
            if (previous == null) {
                kept[key] = index
            } else if (score > rows[previous].score!!) {
                rows[previous].exclusion = RetrievalExclusion.DUPLICATE
                kept[key] = index
            } else {
                row.exclusion = RetrievalExclusion.DUPLICATE
            }
        }
        val ranked = kept.values.sortedByDescending { rows[it].score!! }
        ranked.forEachIndexed { position, rowIndex ->
            val row = rows[rowIndex]
            row.rank = position + 1
            if (position < config.topK) {
                row.selectedForTopK = true
            } else {
                row.exclusion = RetrievalExclusion.OUTSIDE_TOP_K
            }
        }
        val hits = ranked.take(config.topK).map { index ->
            ScoredMemory(rows[index].memory, rows[index].score!!)
        }
        val status = if (hits.isEmpty()) RetrievalStatus.NO_MATCHES else RetrievalStatus.SUCCESS
        return inspection(
            started = started,
            context = RetrievedMemoryContext.from(hits),
            status = status,
            queryEmbeddingStatus = QUERY_EMBEDDING_COMPLETED,
            sessionId = sessionId,
            requestId = requestId,
            originatingResponseId = excluded,
            storedCount = stored.size,
            queryText = passage,
            precedingPresent = precedingPresent,
            followingPresent = followingPresent,
            candidates = rows.map { it.toCandidate(config.threshold) },
        )
    }

    private fun failed(
        started: Long,
        queryEmbeddingStatus: String,
        sessionId: String,
        requestId: String,
        originatingResponseId: String?,
        queryText: String,
        precedingPresent: Boolean,
        followingPresent: Boolean,
    ): RetrievalInspection {
        return inspection(
            started = started,
            context = RetrievedMemoryContext.EMPTY,
            status = RetrievalStatus.FAILED,
            queryEmbeddingStatus = queryEmbeddingStatus,
            sessionId = sessionId,
            requestId = requestId,
            originatingResponseId = originatingResponseId,
            storedCount = storedCount(sessionId),
            queryText = queryText,
            precedingPresent = precedingPresent,
            followingPresent = followingPresent,
            candidates = emptyList(),
        )
    }

    private fun inspection(
        started: Long,
        context: RetrievedMemoryContext,
        status: RetrievalStatus,
        queryEmbeddingStatus: String,
        sessionId: String,
        requestId: String,
        originatingResponseId: String?,
        storedCount: Int,
        queryText: String?,
        precedingPresent: Boolean,
        followingPresent: Boolean,
        candidates: List<EvaluatedCandidate>,
    ): RetrievalInspection {
        val copied = java.util.List.copyOf(candidates)
        val topHits = java.util.List.copyOf(
            copied.filter { it.selectedForTopK }.sortedBy { it.rank }.map { candidate ->
                val memory = context.memories.first { it.memoryId == candidate.memoryId }
                RetrievedMemoryPreview(
                    rank = candidate.rank!!,
                    memoryId = candidate.memoryId,
                    sessionId = candidate.sessionId,
                    requestId = candidate.requestId,
                    responseId = candidate.responseId,
                    score = candidate.score!!,
                    selectedText = memory.selectedText,
                    previousAnswerPreview = previousAnswerPreview(memory.generatedResponse),
                )
            },
        )
        return RetrievalInspection(
            context = context,
            diagnostics = RetrievalDiagnostics(
                attempted = true,
                status = status,
                queryEmbeddingStatus = queryEmbeddingStatus,
                sessionId = sessionId,
                requestId = requestId,
                originatingResponseId = originatingResponseId,
                storedCount = storedCount,
                evaluatedCount = copied.size,
                excludedCount = copied.count { it.exclusion != null },
                returnedCount = topHits.size,
                threshold = config.threshold,
                topK = config.topK,
                dimensions = config.dimensions,
                durationMillis = elapsedMillis(started),
                queryText = queryText,
                precedingContextPresent = precedingPresent,
                followingContextPresent = followingPresent,
                candidates = copied,
                topHits = topHits,
            ),
        )
    }

    private fun storedCount(sessionId: String): Int = store.list(sessionId).size

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun queryStatus(result: EmbeddingResult): String {
        return when (result) {
            is EmbeddingResult.Success -> QUERY_EMBEDDING_INVALID_VECTOR
            EmbeddingResult.Invalid -> "INVALID"
            EmbeddingResult.EmptyVector -> "EMPTY_VECTOR"
            EmbeddingResult.WrongDimension -> "WRONG_DIMENSION"
            EmbeddingResult.TimedOut -> "TIMED_OUT"
            EmbeddingResult.NetworkFailure -> "NETWORK_FAILURE"
            EmbeddingResult.BackendError -> "BACKEND_ERROR"
        }
    }

    private fun usableQuery(result: EmbeddingResult): FloatArray? {
        if (result !is EmbeddingResult.Success) return null
        if (result.dimensions != config.dimensions) return null
        return finiteVector(result.embedding)
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

private class WorkingCandidate(
    val memory: InteractionMemory,
) {
    var score: Double? = null
    var exclusion: RetrievalExclusion? = null
    var passedThreshold: Boolean? = null
    var rank: Int? = null
    var selectedForTopK: Boolean = false

    fun toCandidate(threshold: Double): EvaluatedCandidate {
        return EvaluatedCandidate(
            memoryId = memory.memoryId,
            sessionId = memory.sessionId,
            requestId = memory.requestId,
            responseId = memory.responseId,
            score = score,
            threshold = threshold,
            rank = rank,
            passedThreshold = passedThreshold,
            selectedForTopK = selectedForTopK,
            exclusion = exclusion,
        )
    }
}

/**
 * One place for retrieval limits. [dimensions] follows the embedding contract.
 * The defaults are injectable. They are not a claim that the values are tuned.
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
