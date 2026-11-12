package dev.probe.textselection.ai

import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.InteractionMemory
import dev.probe.textselection.interaction.InteractionMemoryStore
import dev.probe.textselection.interaction.ResponseStatus
import java.time.Instant
import java.util.UUID

/**
 * Builds a prompt, then asks whichever [AIProvider] it was given.
 * It does not choose a vendor and it does not retry.
 * After a successful response it copies the interaction into [memoryStore].
 * That copy does not change the [AIResponse] returned to the caller.
 */
class AIService(
    private val provider: AIProvider,
    private val promptEngine: PromptEngine = PromptEngine,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val memoryStore: InteractionMemoryStore = InteractionMemoryStore(),
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun generate(request: AIRequest): AIResponse {
        val response = try {
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
        remember(request, response)
        return response
    }

    private fun remember(request: AIRequest, response: AIResponse) {
        if (!shouldRemember(request, response)) return
        val memory = InteractionMemory(
            memoryId = newId(),
            sessionId = request.sessionId,
            requestId = request.requestId,
            responseId = response.responseId,
            userAction = request.action,
            selectedText = request.selectedText,
            precedingContext = request.precedingContext,
            followingContext = request.followingContext,
            generatedResponse = response.content,
            sourcePackage = request.sourcePackage,
            createdAt = now(),
        )
        try {
            memoryStore.save(memory)
        } catch (_: Exception) {
            // A full or duplicate store must not change the response the caller receives.
        }
    }

    private fun shouldRemember(request: AIRequest, response: AIResponse): Boolean {
        if (response.status != ResponseStatus.SUCCESS) return false
        if (response.content.isBlank()) return false
        if (request.selectedText.isBlank()) return false
        if (request.sessionId.isBlank()) return false
        if (response.sessionId.isBlank()) return false
        return true
    }

    companion object {
        const val ERROR_MESSAGE = "Could not prepare a response."
    }
}
