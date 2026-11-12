package dev.probe.textselection.interaction

import java.time.Instant

/**
 * Outcome of one embedding attempt.
 * There is no fourth value for "not started". That case is a null status.
 */
enum class EmbeddingStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

/**
 * One successful interaction, copied from the request and the response.
 * Process-local only. No provider, model, or screen state.
 *
 * [embedding] is null for older memories, mock memories, and failed attempts.
 * [embeddingStatus] is null when no embedding call was started. Mock memories
 * stay null: [EmbeddingStatus.FAILED] would mean the call failed, and
 * [EmbeddingStatus.PENDING] would mean a call is in flight.
 */
data class InteractionMemory(
    val memoryId: String,
    val sessionId: String,
    val requestId: String,
    val responseId: String,
    val userAction: UserAction,
    val selectedText: String,
    val precedingContext: String?,
    val followingContext: String?,
    val generatedResponse: String,
    val sourcePackage: String?,
    val createdAt: Instant,
    val embedding: List<Double>? = null,
    val embeddingStatus: EmbeddingStatus? = null,
)

/**
 * Text sent to the embedding model for this memory.
 * Each stored field is included once. Ids, the timestamp, the action, and the
 * source package are not included. A null side is omitted.
 */
fun memoryPassage(memory: InteractionMemory): String {
    val lines = ArrayList<String>(4)
    lines += "Selected text: ${memory.selectedText}"
    memory.precedingContext?.let { lines += "Preceding context: $it" }
    memory.followingContext?.let { lines += "Following context: $it" }
    lines += "Generated response: ${memory.generatedResponse}"
    return lines.joinToString("\n")
}
