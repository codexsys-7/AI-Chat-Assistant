package dev.probe.textselection.interaction

const val EMBEDDING_PREVIEW_COUNT = 8
const val EMBEDDING_DEBUG_UNAVAILABLE = "unavailable"

/**
 * Debug-screen text for one request's memory.
 * [preview] is set only for [EmbeddingStatus.COMPLETED] and never includes more
 * than [EMBEDDING_PREVIEW_COUNT] numbers. [dimensions] is set only when a
 * non-empty vector is stored.
 */
data class EmbeddingDebug(
    val status: String,
    val preview: String?,
    val dimensions: String?,
)

/**
 * Memory for [requestId] inside [sessionId] only.
 * A blank id, a missing session, or another session's record returns null.
 */
fun memoryForRequest(
    store: InteractionMemoryStore,
    sessionId: String?,
    requestId: String,
): InteractionMemory? {
    if (sessionId.isNullOrBlank() || requestId.isBlank()) return null
    return store.list(sessionId).lastOrNull { it.requestId == requestId }
}

fun embeddingDebug(memory: InteractionMemory?): EmbeddingDebug {
    val vector = memory?.embedding?.takeIf { it.isNotEmpty() }
    if (memory == null || memory.embeddingStatus == null) {
        return EmbeddingDebug(
            status = EMBEDDING_DEBUG_UNAVAILABLE,
            preview = null,
            dimensions = vector?.size?.toString(),
        )
    }
    val preview = if (memory.embeddingStatus == EmbeddingStatus.COMPLETED && vector != null) {
        embeddingPreview(vector)
    } else {
        null
    }
    return EmbeddingDebug(
        status = memory.embeddingStatus.name,
        preview = preview,
        dimensions = vector?.size?.toString(),
    )
}

fun embeddingPreview(vector: List<Double>): String {
    val shown = vector.take(EMBEDDING_PREVIEW_COUNT)
    val numbers = shown.joinToString(", ") { formatEmbeddingPreviewNumber(it) }
    return "First ${shown.size} of ${vector.size} values (preview, not the full vector): $numbers"
}

internal fun formatEmbeddingPreviewNumber(value: Double): String {
    if (!value.isFinite()) return "non-finite"
    return value.toString()
}
