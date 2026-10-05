package dev.probe.textselection.ai

import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.Prompt
import dev.probe.textselection.interaction.UserAction

/**
 * Turns an [AIRequest] into a [Prompt]. Local only: no network and no new facts.
 */
object PromptEngine {
    fun build(request: AIRequest): Prompt {
        return Prompt(
            systemInstruction = instruction(request.action),
            userContent = userContent(request),
        )
    }

    private fun instruction(action: UserAction): String = when (action) {
        UserAction.EXPLAIN -> "Explain the passage using the available context."
        UserAction.EXAMPLE -> "Give a concrete example of the passage using the available context."
        UserAction.FOLLOW_UP -> "Answer the implied follow-up using the passage and the available context."
    }

    private fun userContent(request: AIRequest): String {
        val lines = mutableListOf<String>()
        lines += "Action: ${request.action.name}"
        lines += "Context quality: ${request.contextQuality.name}"
        if (request.contextQuality == ContextQuality.UNAVAILABLE) {
            lines += "Only the selected text is available."
        } else {
            val preceding = request.precedingContext?.takeIf { it.isNotBlank() }
            val following = request.followingContext?.takeIf { it.isNotBlank() }
            if (preceding != null) lines += "Preceding context: $preceding"
            if (following != null) lines += "Following context: $following"
            if (preceding == null && following == null) {
                lines += "Only the selected text is available."
            }
        }
        lines += "Selected text: ${request.selectedText}"
        return lines.joinToString("\n")
    }
}
