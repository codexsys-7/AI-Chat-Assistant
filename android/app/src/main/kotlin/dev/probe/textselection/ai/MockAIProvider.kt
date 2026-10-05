package dev.probe.textselection.ai

import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.Prompt
import dev.probe.textselection.interaction.ResponseStatus
import java.util.UUID

/**
 * Local stand-in. It only speaks from the [Prompt] it is given.
 */
class MockAIProvider(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AIProvider {
    override val providerName: String = PROVIDER_NAME
    override val modelName: String = MODEL_NAME

    override suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse {
        val content = buildString {
            append("[MOCK ")
            append(modelName)
            append("] ")
            append(prompt.systemInstruction)
            append(" Context quality: ")
            append(request.contextQuality.name)
            append(". Selected passage: \"")
            append(request.selectedText)
            append("\"")
            if (request.contextQuality == ContextQuality.UNAVAILABLE) {
                append(" Only the selected text is available.")
            }
        }
        return AIResponse(
            responseId = newId(),
            requestId = request.requestId,
            action = request.action,
            content = content,
            provider = providerName,
            model = modelName,
            status = ResponseStatus.SUCCESS,
        )
    }

    companion object {
        const val PROVIDER_NAME = "MockAIProvider"
        const val MODEL_NAME = "mock-v1"
    }
}
