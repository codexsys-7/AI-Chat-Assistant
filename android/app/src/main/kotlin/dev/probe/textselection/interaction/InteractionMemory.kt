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
    return textPassage(
        selectedText = memory.selectedText,
        precedingContext = memory.precedingContext,
        followingContext = memory.followingContext,
        generatedResponse = memory.generatedResponse,
    )
}

/**
 * Passage for a query that has no generated response yet.
 * Labels match [memoryPassage]. The generated-response line is omitted.
 * A null side is omitted. Ids and screen fields are not accepted here.
 */
fun queryPassage(
    selectedText: String,
    precedingContext: String?,
    followingContext: String?,
): String {
    return textPassage(
        selectedText = selectedText,
        precedingContext = precedingContext,
        followingContext = followingContext,
        generatedResponse = null,
    )
}

/**
 * Shared labeled passage. A null [generatedResponse] omits that line so a
 * query and a stored memory stay comparable without duplicating the labels.
 */
fun textPassage(
    selectedText: String,
    precedingContext: String?,
    followingContext: String?,
    generatedResponse: String?,
): String {
    val lines = ArrayList<String>(4)
    lines += "Selected text: $selectedText"
    precedingContext?.let { lines += "Preceding context: $it" }
    followingContext?.let { lines += "Following context: $it" }
    generatedResponse?.let { lines += "Generated response: $it" }
    return lines.joinToString("\n")
}
