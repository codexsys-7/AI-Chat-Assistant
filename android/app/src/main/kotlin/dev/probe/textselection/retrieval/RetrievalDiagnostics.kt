package dev.probe.textselection.retrieval

/**
 * Immutable record of one retrieval call.
 * Scores, ranks, and exclusion reasons are fixed when the call finishes.
 * The debug screen prints this record. It does not score memories again.
 *
 * [threshold], [topK], and [dimensions] are the config used for the call.
 * They are not a claim that those values have been tuned.
 */
data class RetrievalDiagnostics(
    val attempted: Boolean,
    val status: RetrievalStatus,
    val queryEmbeddingStatus: String,
    val sessionId: String,
    val requestId: String,
    val originatingResponseId: String?,
    val storedCount: Int,
    val evaluatedCount: Int,
    val excludedCount: Int,
    val returnedCount: Int,
    val threshold: Double,
    val topK: Int,
    val dimensions: Int,
    val durationMillis: Long?,
    val queryText: String?,
    val precedingContextPresent: Boolean,
    val followingContextPresent: Boolean,
    val candidates: List<EvaluatedCandidate>,
    val topHits: List<RetrievedMemoryPreview>,
) {
    init {
        require(storedCount >= 0) { "stored count cannot be negative." }
        require(evaluatedCount == candidates.size) { "evaluated count must match candidates." }
        require(excludedCount == candidates.count { it.exclusion != null }) {
            "excluded count must match exclusion reasons."
        }
        require(returnedCount == topHits.size) { "returned count must match top hits." }
        require(returnedCount == candidates.count { it.selectedForTopK }) {
            "returned count must match top-K selections."
        }
        require(candidates.none { it.selectedForTopK && it.exclusion != null }) {
            "a top-K selection has no exclusion reason."
        }
        require(candidates.none { !it.selectedForTopK && it.exclusion == null }) {
            "an omitted candidate needs an exclusion reason."
        }
    }
}

enum class RetrievalStatus {
    SUCCESS,
    NO_MATCHES,
    FAILED,
    SKIPPED,
}

enum class RetrievalExclusion {
    DIFFERENT_SESSION,
    CURRENT_RESPONSE,
    MISSING_EMBEDDING,
    INVALID_VECTOR,
    BELOW_THRESHOLD,
    DUPLICATE,
    OUTSIDE_TOP_K,
}

data class EvaluatedCandidate(
    val memoryId: String,
    val sessionId: String,
    val requestId: String,
    val responseId: String,
    val score: Double?,
    val threshold: Double,
    val rank: Int?,
    val passedThreshold: Boolean?,
    val selectedForTopK: Boolean,
    val exclusion: RetrievalExclusion?,
)

data class RetrievedMemoryPreview(
    val rank: Int,
    val memoryId: String,
    val sessionId: String,
    val requestId: String,
    val responseId: String,
    val score: Double,
    val selectedText: String,
    val previousAnswerPreview: String,
)

data class RetrievalInspection(
    val context: RetrievedMemoryContext,
    val diagnostics: RetrievalDiagnostics,
)

const val QUERY_EMBEDDING_NOT_CALLED = "NOT_CALLED"
const val QUERY_EMBEDDING_COMPLETED = "COMPLETED"
const val QUERY_EMBEDDING_INVALID_VECTOR = "INVALID_VECTOR"
const val QUERY_EMBEDDING_EXCEPTION = "EXCEPTION"

internal const val PREVIOUS_ANSWER_PREVIEW_LIMIT = 160

fun previousAnswerPreview(answer: String): String {
    val collapsed = answer.trim().replace(PREVIEW_WHITESPACE, " ")
    if (collapsed.length <= PREVIOUS_ANSWER_PREVIEW_LIMIT) return collapsed
    return collapsed.take(PREVIOUS_ANSWER_PREVIEW_LIMIT) + "…"
}

private val PREVIEW_WHITESPACE = Regex("\\s+")
