package dev.probe.textselection.interaction

/**
 * Contract for a future provider. This milestone does not implement it,
 * and the screen does not call it.
 */
interface AIProvider {
    suspend fun generateResponse(request: AIRequest): AIResponse
}

/** No generated text. A real provider would fill this in later. */
data class AIResponse(
    val requestId: String,
)
