package dev.probe.textselection.ai

import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.ResponseStatus
import java.util.UUID

/**
 * Builds a prompt, then asks whichever [AIProvider] it was given.
 * It does not choose a vendor and it does not retry.
 */
class AIService(
    private val provider: AIProvider,
    private val promptEngine: PromptEngine = PromptEngine,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun generate(request: AIRequest): AIResponse {
        return try {
            val prompt = promptEngine.build(request)
            provider.generateResponse(request, prompt)
        } catch (_: Exception) {
            AIResponse(
                responseId = newId(),
                requestId = request.requestId,
                sessionId = request.sessionId,
                action = request.action,
                content = ERROR_MESSAGE,
                provider = provider.providerName,
                model = provider.modelName,
                status = ResponseStatus.ERROR,
            )
        }
    }

    companion object {
        const val ERROR_MESSAGE = "Could not prepare a response."
    }
}
