package dev.probe.textselection.interaction

enum class ResponseStatus {
    SUCCESS,
    ERROR,
}

/**
 * Immutable provider result. Loading is a screen state, not a status.
 * This type is not tied to a screen or a vendor SDK.
 */
data class AIResponse(
    val responseId: String,
    val requestId: String,
    val action: UserAction,
    val content: String,
    val provider: String,
    val model: String,
    val status: ResponseStatus,
)

/**
 * Provider contract. Callers pass a [Prompt] already built for the request.
 */
interface AIProvider {
    val providerName: String
    val modelName: String

    suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse
}
